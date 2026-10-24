package ai.xybrid.mediator

import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.xybrid.XybridModel
import ai.xybrid.defaultStreamingConfig
import ai.xybrid.partials
import ai.xybrid.stream
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit latency probes, not part of the device gate. Run one method at a time with
 * `-e class ai.xybrid.mediator.LatencyProbeTest#<method>`. Reports contain synthetic fixtures only.
 */
@RunWith(AndroidJUnit4::class)
class LatencyProbeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val gson = GsonBuilder().serializeSpecialFloatingPointValues().setPrettyPrinting().create()

    /**
     * Each variant gets a fresh engine: in LiteRT-LM 0.18 one speculative conversation leaves the drafter
     * active for later conversations on the same engine, so variants cannot share one. Speculative
     * variants run last for the same reason.
     */
    /** Constrained GPU decoding round-trips full-vocabulary logits through the CPU every token. */
    @Test fun gemmaCpu() = runBlocking {
        val off = Decoding(speculative = false)
        val rows = mutableListOf<Map<String, Any?>>()
        for ((name, threads, decoding) in listOf(Triple("cpu4-constrained", 4, off),
            Triple("cpu4-unconstrained", 4, off.copy(constrained = false)), Triple("cpu6-constrained", 6, off))) {
            val runtime = LocalRuntime(context, decoding = decoding, cpuThreads = threads)
            try {
                val start = SystemClock.elapsedRealtime()
                runtime.initialize { }
                rows += mapOf("variant" to name, "id" to "initialize", "totalMs" to SystemClock.elapsedRealtime() - start,
                    "backend" to runtime.backendLabel)
                rows += attempt(runtime, 8192, name, decoding, "warm-up", dialogues().first().second)
                for ((id, turns) in dialogues()) rows += attempt(runtime, 8192, name, decoding, id, turns)
                write("latency-probe-gemma-cpu.json", rows)
            } finally { runtime.close() }
        }
    }

    @Test fun gemmaVariants() = runBlocking {
        val off = Decoding(speculative = false)
        val variants = listOf(
            Triple("constrained", 8192, off),
            Triple("constrained", 4096, off),
            Triple("unconstrained", 4096, off.copy(constrained = false)),
            Triple("unconstrained-preface", 4096, off.copy(constrained = false, prefillPreface = true)),
            Triple("unconstrained-speculative", 4096, Decoding(speculative = true, constrained = false)),
            Triple("constrained-speculative", 4096, Decoding(speculative = true)))
        val dialogues = dialogues()
        val rows = mutableListOf<Map<String, Any?>>()
        for ((name, tokens, decoding) in variants) {
            val runtime = LocalRuntime(context, maxNumTokens = tokens, decoding = decoding)
            try {
                runtime.initialize { }
                rows += attempt(runtime, tokens, name, decoding, "warm-up", dialogues.first().second)
                for ((id, turns) in dialogues) rows += attempt(runtime, tokens, name, decoding, id, turns)
                write("latency-probe-gemma.json", rows)
            } finally { runtime.close() }
        }
    }

    private suspend fun attempt(runtime: LocalRuntime, tokens: Int, name: String, decoding: Decoding,
        id: String, turns: List<Turn>): Map<String, Any?> {
        val row = mutableMapOf<String, Any?>("maxNumTokens" to tokens, "variant" to name, "id" to id,
            "thermalStatus" to power().currentThermalStatus, "thermalHeadroom" to power().getThermalHeadroom(0))
        runtime.prepareNext() // As during a live turn, background conversation setup finishes first.
        val start = SystemClock.elapsedRealtime()
        try {
            val generated = runtime.generate(Graph(), turns, decoding)
            row["totalMs"] = SystemClock.elapsedRealtime() - start
            row["gemma"] = generated.gemma
            row["raw"] = generated.raw // Synthetic fixture output, kept to diagnose invalid variants.
            row["analysis"] = runCatching { AnalysisCodec.decode(generated.raw, Graph(), turns) }
                .onFailure { row["invalid"] = it.cause?.javaClass?.simpleName ?: it.javaClass.simpleName }.getOrNull()
        } catch (error: Exception) {
            row["error"] = "${error.javaClass.simpleName}: ${error.message?.take(600)}"
        }
        return row
    }

    /**
     * Streams 16 kHz mono PCM16 WAV clips from files/asr-probe at microphone pace, comparing Whisper
     * encoder contexts. An optional sidecar .txt holds the spoken text for a word error rate.
     */
    @Test fun asrAudioContext() = runBlocking {
        val clips = File(context.getExternalFilesDir(null), "asr-probe").listFiles { file -> file.extension == "wav" }
            ?.sortedBy { it.name }.orEmpty()
        check(clips.isNotEmpty()) { "Push 16 kHz mono PCM16 WAV clips to files/asr-probe first." }
        val runtime = LocalRuntime(context)
        val rows = mutableListOf<Map<String, Any?>>()
        try {
            runtime.initialize { }
            val model = requireNotNull(runtime.speechModel)
            val contexts = listOf(1500u, 500u)
            for (frames in contexts) warm(model, frames)
            clips.forEachIndexed { index, clip ->
                val samples = readWav(clip)
                val expected = File(clip.parentFile, clip.nameWithoutExtension + ".txt").takeIf { it.isFile }?.readText()?.trim()
                for (frames in if (index % 2 == 0) contexts else contexts.reversed()) {
                    rows += paced(model, frames, samples).withClip(clip, samples, expected)
                    rows += unpaced(model, frames, samples).withClip(clip, samples, expected)
                    write("latency-probe-asr.json", rows)
                }
            }
        } finally { runtime.close() }
        write("latency-probe-asr.json", rows)
    }

    private fun warm(model: XybridModel, frames: UInt) {
        model.stream(defaultStreamingConfig().copy(audioCtx = frames)).use { it.feed(FloatArray(16_000)); it.flush() }
    }

    /** Feeds 128 ms reads no faster than real time, like Microphone, then measures Done → final text. */
    private suspend fun paced(model: XybridModel, frames: UInt, samples: FloatArray) = withContext(Dispatchers.IO) {
        coroutineScope {
            val session = model.stream(defaultStreamingConfig().copy(audioCtx = frames))
            val partials = mutableListOf<Map<String, Any>>()
            val start = SystemClock.elapsedRealtime()
            val listener = launch {
                session.partials().collect {
                    synchronized(partials) {
                        partials += mapOf("atMs" to SystemClock.elapsedRealtime() - start,
                            "audioMs" to it.audioDurationMs.toLong(), "chunk" to it.chunkSequence.toLong(), "text" to it.text)
                    }
                }
            }
            try {
                val audio = samples + FloatArray(4_800) // A short pause before someone taps Done.
                var fed = 0
                while (fed < audio.size) {
                    val end = minOf(fed + 2048, audio.size)
                    val wait = start + end * 1000L / 16_000 - SystemClock.elapsedRealtime()
                    if (wait > 0) delay(wait)
                    session.feed(audio.copyOfRange(fed, end))
                    fed = end
                }
                val stoppedAt = SystemClock.elapsedRealtime()
                val text = session.flush()
                val finished = SystemClock.elapsedRealtime()
                listener.cancelAndJoin()
                mapOf("mode" to "paced", "audioCtx" to frames.toInt(), "stopToFinalMs" to finished - stoppedAt,
                    "captureMs" to stoppedAt - start, "text" to text,
                    "partials" to synchronized(partials) { partials.toList() })
            } finally {
                runCatching { session.cancel() }
                listener.cancel()
                session.close()
            }
        }
    }

    /** Total inference cost with no capture pacing: feed the whole clip, then flush. */
    private suspend fun unpaced(model: XybridModel, frames: UInt, samples: FloatArray) = withContext(Dispatchers.IO) {
        model.stream(defaultStreamingConfig().copy(audioCtx = frames)).use { session ->
            val start = SystemClock.elapsedRealtime()
            val audio = samples + FloatArray(4_800)
            for (offset in audio.indices step 2048) session.feed(audio.copyOfRange(offset, minOf(offset + 2048, audio.size)))
            val text = session.flush()
            mapOf("mode" to "unpaced", "audioCtx" to frames.toInt(), "computeMs" to SystemClock.elapsedRealtime() - start, "text" to text)
        }
    }

    private fun Map<String, Any?>.withClip(clip: File, samples: FloatArray, expected: String?) = this + mapOf(
        "clip" to clip.name, "audioMs" to samples.size * 1000L / 16_000, "expected" to expected,
        "wordErrorRate" to expected?.let { wordErrorRate(it, this["text"] as String) })

    private fun dialogues() = context.assets.open("evaluation-dialogues.json").bufferedReader().use { JsonParser.parseReader(it).asJsonArray }
        .map { element ->
            val example = element.asJsonObject
            example["id"].asString to example.getAsJsonArray("turns").map {
                val turn = it.asJsonObject
                Turn(turn["id"].asInt, Speaker.valueOf(turn["speaker"].asString), turn["text"].asString, typed = true)
            }
        }

    private fun power() = context.getSystemService(PowerManager::class.java)

    private fun write(name: String, rows: List<Map<String, Any?>>) {
        File(context.getExternalFilesDir(null), name).writeText(gson.toJson(rows))
    }
}

/** Reads the data chunk of a 16 kHz mono PCM16 RIFF file, skipping any other chunks. */
internal fun readWav(file: File): FloatArray {
    val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    check(bytes.getInt(0) == 0x46464952 && bytes.getInt(8) == 0x45564157) { "${file.name} is not a WAV file." }
    var offset = 12
    var format: List<Int>? = null
    while (offset + 8 <= bytes.limit()) {
        val id = bytes.getInt(offset)
        val size = bytes.getInt(offset + 4)
        val body = offset + 8
        // PCM tag, channels, sample rate, bits per sample.
        if (id == 0x20746d66) format = listOf(bytes.getShort(body).toInt(), bytes.getShort(body + 2).toInt(),
            bytes.getInt(body + 4), bytes.getShort(body + 14).toInt())
        if (id == 0x61746164) {
            check(format == listOf(1, 1, 16_000, 16)) { "${file.name} must be 16 kHz mono PCM16." }
            val count = minOf(size, bytes.limit() - body) / 2
            return FloatArray(count) { bytes.getShort(body + it * 2) / 32768f }
        }
        offset = body + size + (size and 1)
    }
    error("${file.name} has no audio data.")
}

internal fun wordErrorRate(expected: String, actual: String): Double {
    fun words(text: String) = text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9' ]"), " ").split(' ').filter { it.isNotBlank() }
    val reference = words(expected)
    val hypothesis = words(actual)
    var previous = IntArray(hypothesis.size + 1) { it }
    for (i in 1..reference.size) {
        val current = IntArray(hypothesis.size + 1)
        current[0] = i
        for (j in 1..hypothesis.size) {
            val substitution = previous[j - 1] + if (reference[i - 1] == hypothesis[j - 1]) 0 else 1
            current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
        }
        previous = current
    }
    return if (reference.isEmpty()) 0.0 else previous[hypothesis.size].toDouble() / reference.size
}

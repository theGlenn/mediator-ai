package ai.xybrid.mediator

import android.content.Context
import android.os.SystemClock
import ai.xybrid.Xybrid
import ai.xybrid.XybridModel
import ai.xybrid.XybridModelLoader
import ai.xybrid.defaultStreamingConfig
import ai.xybrid.stream
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class TimedAnalysis(val analysis: Analysis, val gemma: GemmaMetrics)
internal data class Generation(val raw: String, val gemma: GemmaMetrics)

/**
 * Owns the two native runtimes. All initialization/inference/teardown runs off the UI thread.
 * [maxNumTokens] sizes Gemma's KV cache; [decoding] applies to every analysis unless a probe overrides it.
 * [cpuThreads] forces Gemma onto CPU with that many threads instead of trying GPU first.
 */
internal class LocalRuntime(private val context: Context, private val maxNumTokens: Int = 8192,
    private val decoding: Decoding = Decoding(), private val cpuThreads: Int? = null) {
    private val mutex = Mutex()
    private var engine: Engine? = null
    var speechModel: XybridModel? = null
        private set
    var backendLabel: String = "Not loaded"
        private set
    @Volatile private var generation: com.google.ai.edge.litertlm.Conversation? = null
    private val generationLock = Any()
    // Conversation setup takes 1.3-2.5 s on the phone and does not depend on the turns, so the next
    // one is created while people talk. Guarded by mutex; at most one conversation exists at a time.
    private var spare: com.google.ai.edge.litertlm.Conversation? = null
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun initialize(status: (String) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (engine != null && speechModel != null) return@withLock
            try {
                val directory = File(context.getExternalFilesDir(null), "models")
                status("Checking model files and SHA-256 checksums…")
                verifyModels(directory)
                currentCoroutineContext().ensureActive()
                Xybrid.init(context)
                val whisper = File(directory, "whisper")
                File(whisper, "model_metadata.json").writeText(context.assets.open("whisper-model_metadata.json").bufferedReader().use { it.readText() })
                status("Loading and warming local speech recognition…")
                speechModel = XybridModelLoader.fromDirectory(whisper.absolutePath).loadBlocking()
                speechModel!!.stream(defaultStreamingConfig()).use { session ->
                    session.feed(FloatArray(16_000))
                    session.flush()
                }
                currentCoroutineContext().ensureActive()
                val model = File(directory, "gemma-4-E2B-it.litertlm")
                status("Loading Gemma on GPU and checking structured output…")
                if (cpuThreads != null) {
                    engine = openEngine(model, Backend.CPU(threadCount = cpuThreads))
                    backendLabel = "Gemma 4 E2B · CPU · $cpuThreads threads"
                } else try {
                    engine = openEngine(model, Backend.GPU())
                    backendLabel = "Gemma 4 E2B · GPU"
                } catch (gpuFailure: Exception) {
                    currentCoroutineContext().ensureActive()
                    status("GPU unavailable. Checking Gemma on CPU…")
                    engine = openEngine(model, Backend.CPU())
                    backendLabel = "Gemma 4 E2B · CPU (GPU unavailable)"
                }
                spare = engine!!.createConversation(GemmaAnalysis.configuration(decoding))
                currentCoroutineContext().ensureActive()
            } catch (failure: Throwable) {
                release()
                throw failure
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun openEngine(model: File, backend: Backend): Engine {
        // Records per-turn prefill/decode counters; prompts and outputs are unchanged.
        ExperimentalFlags.enableBenchmark = true
        val candidate = Engine(EngineConfig(model.absolutePath, backend,
            maxNumTokens = maxNumTokens, cacheDir = context.cacheDir.absolutePath))
        try {
            candidate.initialize()
            candidate.createConversation(ConversationConfig(enableResponseFormat = true,
                channels = emptyList(), extraContext = mapOf("enable_thinking" to false))).use {
                val output = it.sendMessage("Return a JSON object with ready set to true.",
                    maxOutputToken = 32, responseFormat = ResponseFormat.json(
                        """{"type":"object","properties":{"ready":{"type":"boolean"}},"required":["ready"],"additionalProperties":false}"""))
                check(JsonParser.parseString(output.toString()).asJsonObject["ready"].asBoolean)
            }
            return candidate
        } catch (failure: Throwable) {
            candidate.close()
            throw failure
        }
    }

    suspend fun analyze(turns: List<Turn>): TimedAnalysis {
        val generated = generate(turns)
        return TimedAnalysis(AnalysisCodec.decode(generated.raw, turns), generated.gemma)
    }

    /** Unvalidated output for [analyze] and device probes; never display it without decoding. */
    suspend fun generate(turns: List<Turn>, decoding: Decoding = this.decoding): Generation = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            require(turns.isNotEmpty() && turns.size <= 8)
            val setupStart = SystemClock.elapsedRealtime()
            val prepared = spare.takeIf { decoding == this@LocalRuntime.decoding }?.also { spare = null }
            val active = prepared ?: requireNotNull(engine) { "Load models before starting." }
                .createConversation(GemmaAnalysis.configuration(decoding))
            synchronized(generationLock) { generation = active }
            try {
                currentCoroutineContext().ensureActive()
                val generateStart = SystemClock.elapsedRealtime()
                val raw = GemmaAnalysis.generate(active, turns, decoding.constrained)
                val metrics = metrics(active, generateStart - setupStart, SystemClock.elapsedRealtime() - generateStart)
                currentCoroutineContext().ensureActive()
                Generation(raw, metrics)
            } finally {
                synchronized(generationLock) { generation = null; active.close() }
                if (decoding == this@LocalRuntime.decoding) background.launch { prepareNext() }
            }
        }
    }

    /** Waits until the next analysis conversation exists; analyses also schedule this themselves. */
    suspend fun prepareNext() = withContext(Dispatchers.IO) {
        mutex.withLock {
            // A failed attempt leaves setup to the next analysis, which reports any error.
            if (spare == null) spare = engine?.let { runCatching { it.createConversation(GemmaAnalysis.configuration(decoding)) }.getOrNull() }
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun metrics(active: com.google.ai.edge.litertlm.Conversation, setup: Long, generate: Long): GemmaMetrics {
        // Counters are diagnostic only; a runtime that cannot report them must not fail the analysis.
        val info = runCatching { active.getBenchmarkInfo() }.getOrNull() ?: return GemmaMetrics(setup, generate)
        return GemmaMetrics(setup, generate, info.lastPrefillTokenCount, info.lastDecodeTokenCount,
            (info.timeToFirstTokenInSecond * 1000).toLong(), info.lastPrefillTokensPerSecond, info.lastDecodeTokensPerSecond)
    }

    fun cancelGeneration() {
        // The runtime exposes cancellation as a thread-safe native operation.
        synchronized(generationLock) { runCatching { generation?.cancelProcess() } }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        background.cancel()
        mutex.withLock { release() }
    }

    private fun release() {
        runCatching { spare?.close() }
        spare = null
        runCatching { engine?.close() }
        runCatching { speechModel?.close() }
        engine = null
        speechModel = null
        backendLabel = "Not loaded"
    }

    private fun verifyModels(directory: File) {
        val lock = context.assets.open("models.lock.json").bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        for (entry in lock.entrySet()) {
            val spec = entry.value.asJsonObject
            val file = File(directory, spec["file"].asString)
            require(file.isFile && file.length() == spec["size"].asLong) {
                "Model files are missing or incomplete. Run scripts/provision.py over USB first."
            }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(1024 * 1024)
                var size = input.read(buffer)
                while (size != -1) { digest.update(buffer, 0, size); size = input.read(buffer) }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(actual == spec["sha256"].asString) { "Model checksum mismatch. Re-provision the models over USB." }
        }
    }

}

package ai.xybrid.mediator

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import ai.xybrid.XybridModel
import ai.xybrid.XybridStreamingSession
import ai.xybrid.defaultStreamingConfig
import ai.xybrid.partials
import ai.xybrid.stream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

internal class Microphone {
    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    private val sessionLock = Any()
    private var activeSession: XybridStreamingSession? = null

    fun prepare() { running.set(true) }

    @SuppressLint("MissingPermission") // Permission is requested before this method is invoked.
    suspend fun record(model: XybridModel, onPartial: (String) -> Unit): String = withContext(Dispatchers.IO) {
        coroutineScope {
            if (!running.get()) return@coroutineScope ""
            val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "This microphone does not support 16 kHz mono capture." }
            val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 8192))
            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                audio.release()
                error("Microphone initialization failed.")
            }
            recorder = audio
            val session = try { model.stream(defaultStreamingConfig()) } catch (error: Exception) {
                audio.release(); recorder = null; throw error
            }
            synchronized(sessionLock) { activeSession = session }
            val partials = launch { session.partials().collect { onPartial(it.text) } }
            var speechSamples = 0
            try {
                currentCoroutineContext().ensureActive()
                if (running.get()) audio.startRecording()
                val buffer = ShortArray(2048)
                while (running.get()) {
                    currentCoroutineContext().ensureActive()
                    val count = audio.read(buffer, 0, buffer.size)
                    if (!running.get()) break
                    check(count >= 0) { "Microphone disconnected. Try recording again." }
                    if (count == 0) continue
                    val samples = FloatArray(count) { buffer[it] / 32768f }
                    val rms = sqrt(samples.sumOf { (it * it).toDouble() } / count)
                    if (rms > 0.005) speechSamples += count
                    session.feed(samples)
                }
                currentCoroutineContext().ensureActive()
                if (speechSamples < 8000) { session.cancel(); return@coroutineScope "" }
                session.flush()
            } finally {
                withContext(NonCancellable) {
                    running.set(false)
                    runCatching { audio.stop() }
                    audio.release()
                    recorder = null
                    runCatching { session.cancel() }
                    partials.cancelAndJoin()
                    synchronized(sessionLock) {
                        activeSession = null
                        session.close()
                    }
                }
            }
        }
    }

    fun abort() {
        stop()
        synchronized(sessionLock) { runCatching { activeSession?.cancel() } }
    }

    fun stop() {
        running.set(false)
        runCatching { recorder?.stop() }
    }
}

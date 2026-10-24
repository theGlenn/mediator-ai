package ai.xybrid.mediator

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

internal data class ScreenState(
    val conversation: Conversation = Conversation(),
    val ready: Boolean = false,
    val busy: Boolean = false,
    val status: String = "Models stay on this phone. Load them to begin.",
    val backend: String = "",
    val partial: String = "",
    val seconds: Int = 30,
    val error: String? = null,
    val analysisMillis: Long? = null,
    val endToCardMillis: Long? = null,
)

internal class MediatorViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = LocalRuntime(application)
    private val microphone = Microphone()
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    private val work = SessionWork(viewModelScope)
    private var timer: Job? = null
    private var chime: Job? = null
    private var chimeTone: ToneGenerator? = null
    private var audioEndedAt: Long? = null

    fun loadModels() {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        val revision = mutable.value.conversation.revision
        work.launch {
            try {
                runtime.initialize { message -> mutable.update { it.copy(status = message) } }
                mutable.update { it.copy(ready = true, status = "Both models loaded and warmed. Ready to listen.", backend = runtime.backendLabel) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(error) }
            catch (error: LinkageError) { fail(IllegalStateException("Native runtime could not load on this device. Reinstall the arm64 APK.", error)) }
            finally { if (mutable.value.conversation.revision == revision) mutable.update { it.copy(busy = false) } }
        }
    }

    fun start(a: String, b: String) {
        if (!mutable.value.ready || mutable.value.busy) return
        mutable.update { it.copy(conversation = it.conversation.start(a.take(30), b.take(30)),
            status = "Choose who speaks first.", error = null, analysisMillis = null, endToCardMillis = null) }
    }

    fun record(speaker: Speaker) {
        if (!canTakeTurn()) return
        stopChime()
        mutable.update { it.copy(conversation = it.conversation.beginTurn(speaker),
            partial = "", seconds = 30, status = "Listening…", error = null) }
        val revision = mutable.value.conversation.revision
        audioEndedAt = null
        microphone.prepare()
        var captureTimer: Job? = null
        work.launch {
            try {
                val text = microphone.record(requireNotNull(runtime.speechModel)) { partial ->
                    mutable.update { if (it.conversation.revision == revision) it.copy(partial = partial) else it }
                }
                captureTimer?.cancel()
                if (mutable.value.conversation.revision != revision) return@launch
                mutable.update { it.copy(conversation = it.conversation.stopRecording(), partial = "") }
                if (text.isBlank()) {
                    mutable.update { it.copy(status = "No speech detected. Try again or use typed entry.") }
                } else {
                    mutable.update { it.copy(conversation = it.conversation.commit(speaker, text)) }
                    analyze()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (mutable.value.conversation.revision == revision) fail(error)
            } finally {
                captureTimer?.cancel()
                if (mutable.value.conversation.revision == revision) {
                    mutable.update { it.copy(busy = false, conversation = it.conversation.stopRecording()) }
                }
            }
        }
        captureTimer = viewModelScope.launch {
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (mutable.value.conversation.revision == revision && mutable.value.conversation.recording != null) {
                delay(200)
                val remaining = max(0, ((deadline - SystemClock.elapsedRealtime() + 999) / 1000).toInt())
                mutable.update { it.copy(seconds = remaining) }
                if (remaining == 0) { done(); break }
            }
        }
        timer = captureTimer
    }

    fun done() {
        if (mutable.value.conversation.recording == null || mutable.value.busy) return
        audioEndedAt = SystemClock.elapsedRealtime()
        mutable.update { it.copy(busy = true, status = "Finishing the transcript…") }
        timer?.cancel()
        microphone.stop()
    }

    fun typed(speaker: Speaker, text: String) {
        if (!canTakeTurn() || text.isBlank()) return
        stopChime()
        audioEndedAt = null
        mutable.update { it.copy(conversation = it.conversation.commit(speaker, text.take(1000), typed = true), error = null) }
        work.launch { analyze() }
    }

    fun edit(id: Int, text: String) {
        if (mutable.value.busy || mutable.value.conversation.recording != null || text.isBlank()) return
        stopChime()
        audioEndedAt = null
        mutable.update { it.copy(conversation = it.conversation.edit(id, text.take(1000)), error = null) }
        work.launch { analyze() }
    }

    fun retry() {
        if (mutable.value.busy || mutable.value.conversation.recording != null || mutable.value.conversation.turns.isEmpty()) return
        work.launch { analyze() }
    }

    private suspend fun analyze() {
        val snapshot = mutable.value.conversation
        mutable.update { it.copy(busy = true, status = "Finding the requests and common ground…", error = null) }
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val result = runtime.analyze(snapshot.turns)
            val now = SystemClock.elapsedRealtime()
            mutable.update {
                if (it.conversation.revision != snapshot.revision) it else it.copy(
                    conversation = it.conversation.apply(snapshot.revision, result, now),
                    analysisMillis = now - startedAt, endToCardMillis = audioEndedAt?.let { end -> now - end },
                    status = if (result.intervention != null && snapshot.turns.map { t -> t.speaker }.distinct().size == 2)
                        "A question is ready when you are." else "Map updated. Choose the next speaker.")
            }
            scheduleChime(snapshot.revision)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (mutable.value.conversation.revision == snapshot.revision) fail(error) }
        finally { if (mutable.value.conversation.revision == snapshot.revision) mutable.update { it.copy(busy = false) } }
    }

    private fun scheduleChime(revision: Long) {
        chime?.cancel()
        chime = viewModelScope.launch {
            val last = mutable.value.conversation.lastChimeAt
            if (last != null) delay(max(0, last + 60_000 - SystemClock.elapsedRealtime()))
            val now = SystemClock.elapsedRealtime()
            val session = mutable.value.conversation
            if (session.revision != revision || !session.chimeDue(now)) return@launch
            mutable.update { it.copy(conversation = it.conversation.markChimed(now)) }
            runCatching {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 25)
                chimeTone = tone
                try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180); delay(250) }
                finally { tone.release(); if (chimeTone == tone) chimeTone = null }
            }
        }
    }

    fun openSuggestion() { stopChime(); mutable.update { it.copy(conversation = it.conversation.openSuggestion()) } }
    fun dismissSuggestion() { stopChime(); mutable.update { it.copy(conversation = it.conversation.dismissSuggestion()) } }
    fun confirm(speaker: Speaker) { mutable.update { it.copy(conversation = it.conversation.confirm(speaker)) } }

    fun background() {
        if (mutable.value.busy || mutable.value.conversation.recording != null || mutable.value.conversation.pending != null) {
            cancelWork(end = false)
        }
    }

    fun end() = cancelWork(end = true)

    private fun cancelWork(end: Boolean) {
        timer?.cancel(); stopChime()
        microphone.abort()
        runtime.cancelGeneration()
        mutable.update {
            val session = if (end) it.conversation.end() else it.conversation.copy(
                revision = it.conversation.revision + 1, recording = null, pending = null,
                suggestionOpen = false, agreement = it.conversation.agreement?.takeIf { agreement -> agreement.complete })
            it.copy(conversation = session, busy = true, partial = "", error = null,
                status = if (end) "Session cleared. Models remain warm for the next conversation." else "Paused. Choose a speaker to continue.")
        }
        val revision = mutable.value.conversation.revision
        work.cancelAndDrain {
            if (mutable.value.conversation.revision == revision) mutable.update { it.copy(busy = false) }
        }
    }

    private fun canTakeTurn() = mutable.value.let {
        it.ready && !it.busy && it.conversation.active && it.conversation.recording == null && it.conversation.turns.size < 8
    }

    private fun stopChime() {
        chime?.cancel()
        runCatching { chimeTone?.stopTone() }
    }

    private fun fail(error: Exception) {
        mutable.update { it.copy(error = error.message ?: "The local model could not finish. Try again.", status = "Needs attention.") }
    }

    override fun onCleared() {
        stopChime()
        microphone.abort(); runtime.cancelGeneration(); work.cancel()
        CoroutineScope(Dispatchers.IO).launch { work.join(); runtime.close() }
        super.onCleared()
    }
}

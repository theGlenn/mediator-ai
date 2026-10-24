package ai.xybrid.mediator

/** Timing metadata only; never retains session content. All timestamps use the same monotonic clock. */
internal data class AudioTiming(
    val firstPartialMillis: Long?,
    val captureMillis: Long,
    val finalTranscriptMillis: Long,
    val audioEndedAtMillis: Long,
)

/** Shared by capture, ASR partial callbacks, and the main-thread stop request. */
internal class AudioTimingTracker {
    private var startedAt: Long? = null
    private var stoppedAt: Long? = null
    private var firstPartialAt: Long? = null

    @Synchronized fun start(now: Long) { if (startedAt == null) startedAt = now }

    @Synchronized fun partial(nonblank: Boolean, now: Long) {
        if (nonblank && startedAt != null && firstPartialAt == null) firstPartialAt = now
    }

    @Synchronized fun stop(now: Long) {
        if (startedAt != null && stoppedAt == null) stoppedAt = now
    }

    @Synchronized fun finish(now: Long): AudioTiming? {
        val start = startedAt ?: return null
        val stop = stoppedAt ?: return null
        return AudioTiming(firstPartialAt?.let { it - start }, stop - start, now - stop, stop)
    }
}

/**
 * Phase split for one Gemma analysis. Setup and generation are wall-clock; token counts and rates come
 * from LiteRT-LM's benchmark counters and are null when the runtime does not report them.
 */
internal data class GemmaMetrics(
    val setupMillis: Long,
    val generateMillis: Long,
    val prefillTokens: Int? = null,
    val decodeTokens: Int? = null,
    val timeToFirstTokenMillis: Long? = null,
    val prefillTokensPerSecond: Double? = null,
    val decodeTokensPerSecond: Double? = null,
) {
    fun logMetadata() = "setupMs=$setupMillis generateMs=$generateMillis " +
        "prefillTokens=${prefillTokens ?: "na"} decodeTokens=${decodeTokens ?: "na"} " +
        "ttftMs=${timeToFirstTokenMillis ?: "na"} prefillTps=${prefillTokensPerSecond.rate()} " +
        "decodeTps=${decodeTokensPerSecond.rate()}"

    private fun Double?.rate() = this?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "na"
}

internal enum class AnalysisSource { RECORDED, TYPED, EDIT, RETRY }

internal data class AnalysisTiming(
    val source: AnalysisSource,
    val turnCount: Int,
    val firstPartialMillis: Long?,
    val captureMillis: Long?,
    val finalTranscriptMillis: Long?,
    val analysisMillis: Long,
    val endToMapMillis: Long?,
    val gemma: GemmaMetrics? = null,
) {
    fun appendTo(history: List<AnalysisTiming>) = (history + this).takeLast(20)

    fun logMetadata() = "source=${source.name.lowercase()} turns=$turnCount " +
        "firstPartialMs=${firstPartialMillis ?: "na"} captureMs=${captureMillis ?: "na"} " +
        "finalTranscriptMs=${finalTranscriptMillis ?: "na"} analysisMs=$analysisMillis " +
        "endToMapMs=${endToMapMillis ?: "na"}" + (gemma?.let { " " + it.logMetadata() } ?: "")

    companion object {
        fun complete(source: AnalysisSource, turnCount: Int, startedAt: Long, completedAt: Long,
            audio: AudioTiming? = null, gemma: GemmaMetrics? = null): AnalysisTiming {
            val recorded = audio.takeIf { source == AnalysisSource.RECORDED }
            return AnalysisTiming(source, turnCount, recorded?.firstPartialMillis, recorded?.captureMillis,
                recorded?.finalTranscriptMillis, completedAt - startedAt,
                recorded?.let { completedAt - it.audioEndedAtMillis }, gemma)
        }
    }
}

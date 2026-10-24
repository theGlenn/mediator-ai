package ai.xybrid.mediator

import org.junit.Assert.*
import org.junit.Test

class LatencyDiagnosticsTest {
    @Test fun firstNonblankPartialAndFirstStopDefineRecordingDurations() {
        val capture = AudioTimingTracker()
        capture.partial(nonblank = true, now = 50) // Ignore callbacks before capture starts.
        capture.start(100)
        capture.partial(nonblank = false, now = 120)
        capture.partial(nonblank = true, now = 180)
        capture.partial(nonblank = true, now = 240)
        capture.stop(500)
        capture.stop(700) // Repeated stop/cleanup must not move the audio boundary.

        assertEquals(AudioTiming(80, 400, 250, 500), capture.finish(750))
    }

    @Test fun recordingWithoutAPartialHasNoFirstPartialMeasurement() {
        val capture = AudioTimingTracker()
        capture.start(0)
        capture.stop(500)
        assertEquals(AudioTiming(null, 500, 100, 500), capture.finish(600))
        assertNull(AudioTimingTracker().finish(600))
    }

    @Test fun recordedCompletionMeasuresFromAudioEndRatherThanCaptureStart() {
        val audio = AudioTiming(80, 400, 250, 500)
        val timing = AnalysisTiming.complete(AnalysisSource.RECORDED, 2, 800, 1800, audio)
        assertEquals(80L, timing.firstPartialMillis)
        assertEquals(400L, timing.captureMillis)
        assertEquals(250L, timing.finalTranscriptMillis)
        assertEquals(1000L, timing.analysisMillis)
        assertEquals(1300L, timing.endToMapMillis)
    }

    @Test fun typedEditAndRetryNeverInheritAudioMeasurements() {
        val priorAudio = AudioTiming(80, 400, 250, 500)
        for (source in listOf(AnalysisSource.TYPED, AnalysisSource.EDIT, AnalysisSource.RETRY)) {
            val timing = AnalysisTiming.complete(source, 2, 2000, 3000, priorAudio)
            assertNull(timing.firstPartialMillis)
            assertNull(timing.captureMillis)
            assertNull(timing.finalTranscriptMillis)
            assertNull(timing.endToMapMillis)
            assertEquals(1000L, timing.analysisMillis)
        }
    }

    @Test fun historyRetainsOnlyTwentyMostRecentCompletionsInOrder() {
        var history = emptyList<AnalysisTiming>()
        repeat(25) { index ->
            history = AnalysisTiming.complete(AnalysisSource.RETRY, 2, 0, index.toLong()).appendTo(history)
        }
        assertEquals(20, history.size)
        assertEquals((5L..24L).toList(), history.map { it.analysisMillis })
    }

    @Test fun logLineContainsOnlyFixedLabelsAndNumericMetadata() {
        val timing = AnalysisTiming.complete(AnalysisSource.RECORDED, 2, 800, 1800,
            AudioTiming(80, 400, 250, 500))
        assertEquals("source=recorded turns=2 firstPartialMs=80 captureMs=400 finalTranscriptMs=250 " +
            "analysisMs=1000 endToMapMs=1300", timing.logMetadata())
        assertEquals("source=typed turns=1 firstPartialMs=na captureMs=na finalTranscriptMs=na " +
            "analysisMs=200 endToMapMs=na",
            AnalysisTiming.complete(AnalysisSource.TYPED, 1, 10, 210).logMetadata())
    }

    @Test fun gemmaPhaseSplitAppendsCountsAndRatesOnly() {
        val counted = GemmaMetrics(40, 900, prefillTokens = 1500, decodeTokens = 120,
            timeToFirstTokenMillis = 450, prefillTokensPerSecond = 3333.33, decodeTokensPerSecond = 26.66)
        assertEquals("source=typed turns=2 firstPartialMs=na captureMs=na finalTranscriptMs=na " +
            "analysisMs=1000 endToMapMs=na setupMs=40 generateMs=900 prefillTokens=1500 decodeTokens=120 " +
            "ttftMs=450 prefillTps=3333.3 decodeTps=26.7",
            AnalysisTiming.complete(AnalysisSource.TYPED, 2, 0, 1000, gemma = counted).logMetadata())
        assertEquals("setupMs=40 generateMs=900 prefillTokens=na decodeTokens=na ttftMs=na prefillTps=na decodeTps=na",
            GemmaMetrics(40, 900).logMetadata())
    }
}

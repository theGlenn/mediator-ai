package ai.xybrid.mediator

import org.junit.Assert.*
import org.junit.Test

class AnalysisCodecTest {
    private val turns = listOf(Turn(1, Speaker.A, "I need updates"), Turn(2, Speaker.B, "I need focus"))
    private val valid = """{"A":{"request":{"t":"Updates","e":[1]},"concern":{"t":"","e":[1]}},"B":{"request":{"t":"Focus","e":[2]},"concern":{"t":"","e":[2]}},"common":{"t":"","e":{"A":1,"B":2}},"question":{"t":"Would one daily update work?","e":{"A":1,"B":2}},"proposal":{"t":"","e":{"A":1,"B":2}}}"""

    @Test fun parsesGroundedCompactOutput() {
        val result = AnalysisCodec.decode(valid, turns)
        assertEquals("Updates", result.views.first().request!!.text)
        assertNull(result.views.first().concern)
        assertNull(result.commonGround)
        assertEquals(listOf(1, 2), result.intervention!!.question.evidence)
    }

    @Test fun unknownPlaceholderLabelsAreAbsentInsteadOfDisplayedAsConcerns() {
        listOf("N/A", "unknown", "not stated").forEach { label ->
            val result = AnalysisCodec.decode(valid.replace("\"t\":\"Updates\"", "\"t\":\"$label\""), turns)
            assertNull(result.views.first().request)
        }
    }

    @Test fun rejectsInventedEvidence() {
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisCodec.decode(valid.replace("[1]", "[99]"), turns)
        }
    }

    @Test fun rejectsWrongSpeakerEvidence() {
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisCodec.decode(valid.replace("[1]", "[2]"), turns)
        }
    }

    @Test fun rejectsMalformedTruncatedAndUnknownSpeakerOutput() {
        listOf("{", "{}", valid.dropLast(10), valid.replace("\"B\":", "\"C\":"))
            .forEach { raw -> assertThrows(IllegalArgumentException::class.java) { AnalysisCodec.decode(raw, turns) } }
    }

    @Test fun rejectsUnsupportedExtraFieldsAndMissingSpeaker() {
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisCodec.decode(valid.replaceFirst("{", "{\"agreement\":true,"), turns)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisCodec.decode(valid.replace("\"B\":", "\"A\":"), turns)
        }
    }

    @Test fun populatedClaimsRequireEvidenceFromBothParticipantsForInterventions() {
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisCodec.decode(valid.replace("\"A\":1,\"B\":2", "\"A\":1,\"B\":1"), turns)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisCodec.decode(valid.replace("\"t\":\"Updates\",\"e\":[1]", "\"t\":\"Updates\",\"e\":[]"), turns)
        }
    }

    @Test fun firstSpeakerCannotInventSecondSpeakerOrIntervention() {
        val raw = """{"A":{"request":{"t":"Updates","e":[1]},"concern":{"t":"","e":[1]}},"B":{"request":{"t":"","e":[]},"concern":{"t":"","e":[]}},"common":{"t":"","e":{}},"question":{"t":"","e":{}},"proposal":{"t":"","e":{}}}"""
        val analysis = AnalysisCodec.decode(raw, turns.take(1))
        assertNull(analysis.views.last().request)
        assertNull(analysis.intervention)
    }
}

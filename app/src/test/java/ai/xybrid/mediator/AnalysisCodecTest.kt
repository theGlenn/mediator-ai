package ai.xybrid.mediator

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class AnalysisCodecTest {
    private val turns = listOf(Turn(1, Speaker.A, "I need updates"), Turn(2, Speaker.B, "I need focus"))
    private val valid = """{"A":{"request":"Updates","concern":""},"B":{"request":"Focus","concern":""},"common":"","question":"Would one daily update work?","proposal":""}"""
    private val corrected = turns + Turn(3, Speaker.A, "Actually four works")
    private val multiTurn = """{"A":{"request":{"t":"Update at four","e":3},"concern":{"t":"","e":1}},"B":{"request":"Focus","concern":""},"common":"","question":"Would four work?","proposal":"Try four"}"""

    @Test fun parsesCompactSingleTurnOutputAndInfersEvidence() {
        val result = AnalysisCodec.decode(valid, turns)
        assertEquals(Claim("Updates", listOf(1)), result.views.first().request)
        assertNull(result.views.first().concern)
        assertNull(result.commonGround)
        assertEquals(listOf(1, 2), result.intervention!!.question.evidence)
    }

    @Test fun multiTurnSpeakerCitesOneOwnTurnAndSharedClaimsFollowIt() {
        val result = AnalysisCodec.decode(multiTurn, corrected)
        assertEquals(Claim("Update at four", listOf(3)), result.views.first().request)
        assertEquals(listOf(3, 2), result.intervention!!.question.evidence)
        assertEquals(listOf(3, 2), result.intervention!!.proposal!!.evidence)
    }

    @Test fun sharedEvidenceFallsBackToConcernThenLatestTurn() {
        val raw = """{"A":{"request":{"t":"","e":1},"concern":{"t":"Planning","e":1}},"B":{"request":"","concern":""},"common":"","question":"What would help?","proposal":""}"""
        assertEquals(listOf(1, 2), AnalysisCodec.decode(raw, corrected).intervention!!.question.evidence)
    }

    @Test fun unknownPlaceholderLabelsAreAbsentInsteadOfDisplayedAsConcerns() {
        listOf("N/A", "unknown", "not stated", "No concern").forEach { label ->
            val result = AnalysisCodec.decode(valid.replace("\"Updates\"", "\"$label\""), turns)
            assertNull(result.views.first().request)
        }
    }

    @Test fun rejectsInventedOrOtherSpeakerEvidence() {
        listOf("\"e\":99", "\"e\":2").forEach { evidence ->
            assertThrows(IllegalArgumentException::class.java) {
                AnalysisCodec.decode(multiTurn.replace("\"e\":3", evidence), corrected)
            }
        }
    }

    @Test fun rejectsAFormThatDoesNotMatchTheSpeakersTurnCount() {
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisCodec.decode(valid.replace("\"Updates\"", """{"t":"Updates","e":1}"""), turns)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisCodec.decode(multiTurn.replace("""{"t":"Update at four","e":3}""", "\"Update at four\""), corrected)
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

    @Test fun firstSpeakerCannotInventSecondSpeakerOrIntervention() {
        val raw = """{"A":{"request":"Updates","concern":""},"B":{"request":"","concern":""},"common":"","question":"","proposal":""}"""
        val analysis = AnalysisCodec.decode(raw, turns.take(1))
        assertNull(analysis.views.last().request)
        assertNull(analysis.intervention)
        listOf(raw.replace("\"B\":{\"request\":\"\"", "\"B\":{\"request\":\"Focus\""),
            raw.replace("\"question\":\"\"", "\"question\":\"Why?\"")).forEach { invented ->
            assertThrows(IllegalArgumentException::class.java) { AnalysisCodec.decode(invented, turns.take(1)) }
        }
    }

    @Test fun schemaOffersObjectsAndOwnTurnIdsOnlyToSpeakersWithSeveralTurns() {
        val schema = JsonParser.parseString(AnalysisCodec.schemaFor(corrected)).asJsonObject["properties"].asJsonObject
        val a = schema["A"].asJsonObject["properties"].asJsonObject["request"].asJsonObject
        assertEquals("[1,3]", a["properties"].asJsonObject["e"].asJsonObject["enum"].toString())
        assertEquals("string", schema["B"].asJsonObject["properties"].asJsonObject["request"].asJsonObject["type"].asString)
        val first = JsonParser.parseString(AnalysisCodec.schemaFor(turns.take(1))).asJsonObject["properties"].asJsonObject
        assertEquals("", first["question"].asJsonObject["const"].asString)
        assertEquals("", first["B"].asJsonObject["properties"].asJsonObject["concern"].asJsonObject["const"].asString)
    }
}

package ai.xybrid.mediator

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class AnalysisCodecTest {
    private val turns = listOf(Turn(1, Speaker.A, "I need updates to plan"), Turn(2, Speaker.B, "I need focus"))
    private val firstRaw = """{"A":{"request":"Updates","concern":"Planning"},"B":{},"common":"","intervene":{"why":"none"}}"""
    private val first = Graph().apply(AnalysisCodec.decode(firstRaw, Graph(), turns.take(1)))
    private val secondRaw = """{"A":{},"B":{"request":"Focus","concern":"","link":{"rel":"conflicts","to":1}},"common":"","intervene":{"why":"impasse","question":"Would one daily update work?","proposal":"Try one daily update"}}"""

    @Test fun firstTurnAddsClaimsCitingItsOnlyTurnAndCannotIntervene() {
        val update = AnalysisCodec.decode(firstRaw, Graph(), turns.take(1))
        assertEquals(listOf(Claim("Updates", listOf(1)), Claim("Planning", listOf(1))), update.nodes.map { it.claim })
        assertEquals(setOf(1), update.turns)
        assertNull(update.intervention)
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisCodec.decode(firstRaw.replace("""{"why":"none"}""", """{"why":"impasse","question":"Why?","proposal":""}"""), Graph(), turns.take(1))
        }
    }

    @Test fun laterTurnLinksToTheMapAndInterventionCitesBothSpeakers() {
        val update = AnalysisCodec.decode(secondRaw, first, turns)
        assertEquals(NewNode(NodeKind.REQUEST, Speaker.B, Claim("Focus", listOf(2)), LinkKind.CONFLICTS to 1), update.nodes.single())
        assertEquals(listOf(1, 2), update.intervention!!.question.evidence)
        assertEquals("impasse", update.reason)
        assertEquals(setOf(2), update.turns)
    }

    @Test fun onlySpeakersWithNewTurnsAddClaims() {
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisCodec.decode(secondRaw.replace("\"A\":{}", """"A":{"request":"Invented","concern":""}"""), first, turns)
        }
    }

    @Test fun severalNewTurnsFromOneSpeakerRequireTheirOwnCitation() {
        val three = turns + Turn(3, Speaker.A, "Actually four works")
        val raw = """{"A":{"request":"Update at four","concern":"","e":3},"B":{"request":"Focus","concern":""},"common":"","intervene":{"why":"none"}}"""
        assertEquals(listOf(3), AnalysisCodec.decode(raw, Graph(), three).nodes.first().claim.evidence)
        listOf(raw.replace(",\"e\":3", ""), raw.replace("\"e\":3", "\"e\":2")).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { AnalysisCodec.decode(invalid, Graph(), three) }
        }
    }

    @Test fun implausibleLinksAreDroppedButTheClaimStays() {
        listOf("""{"rel":"replaces","to":1}""", """{"rel":"conflicts","to":9}""").forEach { link ->
            val raw = secondRaw.replace("""{"rel":"conflicts","to":1}""", link)
            assertNull(AnalysisCodec.decode(raw, first, turns).nodes.single().link)
        }
    }

    @Test fun repeatedClaimsPlaceholdersAndRepeatedCommonGroundAddNothing() {
        val three = turns + Turn(3, Speaker.A, "Still updates")
        val withB = first.apply(AnalysisCodec.decode(secondRaw.replace("\"common\":\"\"", "\"common\":\"Delivery\""), first, turns))
        val raw = """{"A":{"request":"updates","concern":"N/A"},"B":{},"common":"Delivery","intervene":{"why":"none"}}"""
        assertTrue(AnalysisCodec.decode(raw, withB, three).nodes.isEmpty())
    }

    @Test fun rejectsMalformedTruncatedAndLeakedWireSyntax() {
        listOf("{", "{}", secondRaw.dropLast(10), secondRaw.replace("\"Focus\"", "\"t\\\":\\\"Focus\\\"}\""))
            .forEach { raw -> assertThrows(IllegalArgumentException::class.java) { AnalysisCodec.decode(raw, first, turns) } }
    }

    @Test fun schemaOffersOnlyWhatTheNewTurnsAllow() {
        val opening = JsonParser.parseString(AnalysisCodec.schemaFor(Graph(), turns.take(1))).asJsonObject["properties"].asJsonObject
        assertEquals(0, opening["B"].asJsonObject["properties"].asJsonObject.size())
        assertEquals("", opening["common"].asJsonObject["const"].asString)
        assertEquals("none", opening["intervene"].asJsonObject["properties"].asJsonObject["why"].asJsonObject["const"].asString)
        val later = JsonParser.parseString(AnalysisCodec.schemaFor(first, turns)).asJsonObject["properties"].asJsonObject
        val item = later["B"].asJsonObject["properties"].asJsonObject
        assertEquals("[1,2]", item["link"].asJsonObject["properties"].asJsonObject["to"].asJsonObject["enum"].toString())
        assertFalse(item.has("e"))
        assertEquals(2, later["intervene"].asJsonObject["anyOf"].asJsonArray.size())
    }
}

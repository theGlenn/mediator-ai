package ai.xybrid.mediator

import org.junit.Assert.*
import org.junit.Test

class ConversationGraphTest {
    private val names = mapOf(Speaker.A to "Alex", Speaker.B to "Sam")
    private fun node(kind: NodeKind, owner: Speaker?, text: String, vararg turns: Int, link: Pair<LinkKind, Int>? = null) =
        NewNode(kind, owner, Claim(text, turns.toList()), listOfNotNull(link))

    private val first = Graph().apply(GraphUpdate(listOf(node(NodeKind.REQUEST, Speaker.A, "Morning update", 1),
        node(NodeKind.CONCERN, Speaker.A, "Plan delivery", 1)), setOf(1)))

    @Test fun newNodesGetIdsAndAConcernExplainsTheLatestRequest() {
        assertEquals(listOf(1, 2), first.nodes.map { it.id })
        assertEquals(listOf(Link(2, LinkKind.EXPLAINS, 1)), first.links)
        assertEquals(setOf(1), first.mapped)
    }

    @Test fun replacingSupersedesOnlyThatNodeAndTheSummaryShowsTheLatest() {
        val revised = first.apply(GraphUpdate(listOf(node(NodeKind.REQUEST, Speaker.A, "Update at four", 3,
            link = LinkKind.REPLACES to 1)), setOf(3)))
        assertEquals(3, revised.node(1).replacedBy)
        assertEquals(listOf(2, 3), revised.active.map { it.id })
        assertEquals("Update at four", revised.projection(null).views.first().request!!.text)
        assertEquals(listOf("Revises earlier: Morning update"), revised.describe(revised.node(3), names))
    }

    @Test fun commonGroundRestsOnEachSpeakersClaimAndReplacesTheLastOne() {
        val both = first.apply(GraphUpdate(listOf(node(NodeKind.REQUEST, Speaker.B, "Afternoon update", 2,
            link = LinkKind.CONFLICTS to 1), node(NodeKind.COMMON, null, "Regular updates", 1, 2)), setOf(2)))
        assertTrue(Link(1, LinkKind.SUPPORTS, 4) in both.links && Link(3, LinkKind.SUPPORTS, 4) in both.links)
        assertEquals(listOf("Rests on Alex: Morning update", "Rests on Sam: Afternoon update"), both.describe(both.node(4), names))
        assertEquals(listOf("Conflicts with Sam: Afternoon update"), both.describe(both.node(1), names))
        val again = both.apply(GraphUpdate(listOf(node(NodeKind.COMMON, null, "One reliable update", 1, 2)), setOf(5)))
        assertEquals("One reliable update", again.latest(null, NodeKind.COMMON)!!.claim.text)
        assertEquals(1, again.active.count { it.kind == NodeKind.COMMON })
    }

    @Test fun forgettingACorrectedTurnRemovesItsNodesAndRestoresWhatTheyReplaced() {
        val revised = first.apply(GraphUpdate(listOf(node(NodeKind.REQUEST, Speaker.A, "Update at four", 3,
            link = LinkKind.REPLACES to 1)), setOf(3)))
        val forgotten = revised.forget(3)
        assertEquals(first.nodes, forgotten.nodes)
        assertEquals(first.links, forgotten.links)
        assertEquals(setOf(1), forgotten.mapped)
        assertTrue(first.forget(1).nodes.isEmpty())
    }
}

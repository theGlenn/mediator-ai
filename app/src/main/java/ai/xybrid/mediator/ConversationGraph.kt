package ai.xybrid.mediator

internal enum class NodeKind { REQUEST, CONCERN, COMMON }

/**
 * REPLACES: a speaker revised their own claim. EXPLAINS: a concern gives the reason for a request.
 * CONFLICTS / SUPPORTS: one speaker's claim opposes or fits the other's. COMMON nodes are SUPPORTED by
 * one claim per speaker.
 */
internal enum class LinkKind { REPLACES, EXPLAINS, CONFLICTS, SUPPORTS }

/** A claim on the map. Owned nodes cite one turn of their speaker; common ground cites one turn per speaker. */
internal data class Node(val id: Int, val kind: NodeKind, val owner: Speaker?, val claim: Claim, val replacedBy: Int? = null) {
    val active get() = replacedBy == null
}

internal data class Link(val from: Int, val kind: LinkKind, val to: Int)

/** A node proposed by one update; [links] point at existing nodes, never at another new node. */
internal data class NewNode(val kind: NodeKind, val owner: Speaker?, val claim: Claim, val links: List<Pair<LinkKind, Int>> = emptyList())

/** What one analysis adds for the [turns] it mapped, and whether the model wants to step in. */
internal data class GraphUpdate(val nodes: List<NewNode>, val turns: Set<Int>, val intervention: Intervention? = null,
    val reason: String? = null)

/**
 * The conversation map. Each analysis sends only active nodes plus unmapped turns, so the prompt does not
 * grow with the transcript; nodes are never rewritten, only replaced, and [mapped] records the turns they cover.
 */
internal data class Graph(val nodes: List<Node> = emptyList(), val links: List<Link> = emptyList(),
    val mapped: Set<Int> = emptySet()) {
    val active get() = nodes.filter { it.active }

    fun node(id: Int) = nodes.first { it.id == id }

    /** The latest active claim of [kind] for [owner], which the tabletop and the gate summarize. */
    fun latest(owner: Speaker?, kind: NodeKind) = active.lastOrNull { it.owner == owner && it.kind == kind }

    fun apply(update: GraphUpdate): Graph {
        var nodes = nodes
        var links = links
        var next = (nodes.maxOfOrNull { it.id } ?: 0) + 1
        for (proposed in update.nodes) {
            val id = next++
            fun replace(old: Int) {
                nodes = nodes.map { if (it.id == old && it.active) it.copy(replacedBy = id) else it }
                links = links + Link(id, LinkKind.REPLACES, old)
            }
            val view = Graph(nodes, links)
            when {
                proposed.kind == NodeKind.COMMON -> {
                    // One active shared outcome at a time; it rests on each speaker's current claim.
                    view.latest(null, NodeKind.COMMON)?.let { replace(it.id) }
                    links = links + Speaker.entries.mapNotNull { view.anchor(it) }.map { Link(it.id, LinkKind.SUPPORTS, id) }
                }
                proposed.links.isNotEmpty() -> proposed.links.forEach { (kind, to) ->
                    if (kind == LinkKind.REPLACES) replace(to) else links = links + Link(id, kind, to)
                }
                proposed.kind == NodeKind.CONCERN ->
                    view.latest(proposed.owner, NodeKind.REQUEST)?.let { links = links + Link(id, LinkKind.EXPLAINS, it.id) }
            }
            nodes = nodes + Node(id, proposed.kind, proposed.owner, proposed.claim)
        }
        return Graph(nodes, links, mapped + update.turns)
    }

    /** A speaker's claim that shared nodes rest on: their latest request, else their latest concern. */
    fun anchor(speaker: Speaker) = latest(speaker, NodeKind.REQUEST) ?: latest(speaker, NodeKind.CONCERN)

    /** Removes everything a corrected turn produced and restores what it had replaced, so the turn can be remapped. */
    fun forget(turn: Int): Graph {
        val removed = nodes.filter { turn in it.claim.evidence }.map { it.id }.toSet()
        return Graph(nodes.filter { it.id !in removed }.map { if (it.replacedBy in removed) it.copy(replacedBy = null) else it },
            links.filter { it.from !in removed && it.to !in removed }, mapped - turn)
    }

    /** Plain-language links for one node, as the Debug map lists them under it. */
    fun describe(node: Node, names: Map<Speaker, String>): List<String> = links.mapNotNull { link ->
        val other = when (node.id) { link.from -> node(link.to); link.to -> node(link.from); else -> return@mapNotNull null }
        val name = other.owner?.let { names.getValue(it) } ?: "Both"
        when {
            link.kind == LinkKind.REPLACES -> "Revises earlier: ${other.claim.text}".takeIf { link.from == node.id }
            link.kind == LinkKind.EXPLAINS -> "Reason for: ${other.claim.text}".takeIf { link.from == node.id }
            link.kind == LinkKind.CONFLICTS -> "Conflicts with $name: ${other.claim.text}"
            node.kind == NodeKind.COMMON -> "Rests on $name: ${other.claim.text}"
            other.kind == NodeKind.COMMON -> null
            else -> "Fits $name: ${other.claim.text}"
        }
    }

    fun projection(intervention: Intervention?) = Analysis(
        Speaker.entries.map { SpeakerView(it, latest(it, NodeKind.REQUEST)?.claim, latest(it, NodeKind.CONCERN)?.claim) },
        latest(null, NodeKind.COMMON)?.claim, intervention)
}

package ai.xybrid.mediator

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Wire format for one incremental map update. Gemma sees the active graph and the unmapped turns, and
 * returns only additions: for each speaker with new turns a request and concern slot (empty when absent),
 * and whether to intervene. Common ground comes from a separate focused follow-up ([commonCandidate]),
 * because E2B almost never filled an optional common field inside the main update. Two relations are required decisions rather than
 * optional links, which E2B mostly skipped: `revises` (0 or the speaker's own earlier request it replaces)
 * and `vs` (how the request relates to the other speaker's current request). Fixed slots keep E2B filling
 * explicit requests, which an open claim list did not. Every
 * generated token costs a full constrained decode step on the phone, so citations the app can infer are
 * omitted: claims cite their speaker's only new turn unless they have several, and shared claims cite each
 * speaker's anchor claim.
 */
internal object AnalysisCodec {
    val reasons = listOf("impasse", "misunderstanding", "common_ground", "next_step")

    /** Turns the next update must map, oldest first. */
    fun pending(graph: Graph, turns: List<Turn>) = turns.filter { it.id !in graph.mapped }

    /** [mustAsk]: no question has been suggested yet, so the first update after both speak has to offer one. */
    fun schemaFor(graph: Graph, turns: List<Turn>, mustAsk: Boolean = false): String {
        val fresh = pending(graph, turns)
        val both = turns.map { it.speaker }.toSet().size == 2
        val owned = Speaker.entries.joinToString(",") { speaker ->
            """"${speaker.name}":${ownedSchema(fresh.filter { it.speaker == speaker }.map { it.id }, graph, speaker)}"""
        }
        val ask = """{"type":"object","additionalProperties":false,"properties":{
            "why":{"type":"string","enum":[${reasons.joinToString(",") { "\"$it\"" }}]},"question":$TEXT,"proposal":$TEXT},
            "required":["why","question","proposal"]}"""
        val intervene = when { !both -> NONE; mustAsk -> ask; else -> """{"anyOf":[$NONE,$ask]}""" }
        // llguidance otherwise permits newlines between keys, and each one costs a decode step.
        return """{"type":"object","additionalProperties":false,"x-guidance":{"whitespace_flexible":false},"properties":{
          $owned,"intervene":$intervene},"required":["A","B","intervene"]}"""
    }

    private fun ownedSchema(ids: List<Int>, graph: Graph, speaker: Speaker): String {
        if (ids.isEmpty()) return """{"type":"object","additionalProperties":false,"properties":{},"required":[]}"""
        val evidence = if (ids.size > 1) ""","e":{"type":"integer","enum":[${ids.joinToString(",")}]}""" else ""
        val own = ownRequests(graph, speaker)
        val revises = if (own.isEmpty()) "" else ""","revises":{"type":"integer","enum":[0,${own.joinToString(",")}]}"""
        val vs = if (opposite(graph, speaker) == null) "" else ""","vs":{"type":"string","enum":["conflicts","supports","unrelated"]}"""
        val required = listOf("request", "concern") + listOfNotNull("e".takeIf { ids.size > 1 },
            "revises".takeIf { own.isNotEmpty() }, "vs".takeIf { opposite(graph, speaker) != null })
        return """{"type":"object","additionalProperties":false,"properties":{"request":$TEXT,"concern":$TEXT$evidence$revises$vs},
            "required":[${required.joinToString(",") { "\"$it\"" }}]}"""
    }

    private fun ownRequests(graph: Graph, speaker: Speaker) = graph.active.filter { it.owner == speaker && it.kind == NodeKind.REQUEST }.map { it.id }

    /** The other speaker's current request, which `vs` compares against. */
    private fun opposite(graph: Graph, speaker: Speaker) = graph.latest(Speaker.entries.first { it != speaker }, NodeKind.REQUEST)

    fun decode(raw: String, graph: Graph, turns: List<Turn>): GraphUpdate = try {
        val fresh = pending(graph, turns)
        require(fresh.isNotEmpty())
        val root = JsonParser.parseString(raw).asJsonObject.exact("A", "B", "intervene")
        val owned = Speaker.entries.flatMap { speaker ->
            val ids = fresh.filter { it.speaker == speaker }.map { it.id }
            owned(root[speaker.name].asJsonObject, graph, speaker, ids)
        }
        val mapped = fresh.map { it.id }.toSet()
        val trial = graph.apply(GraphUpdate(owned, mapped))
        val sources = Speaker.entries.map { speaker ->
            trial.anchor(speaker)?.claim?.evidence?.first() ?: turns.lastOrNull { it.speaker == speaker }?.id
        }
        val intervene = root["intervene"].asJsonObject
        val why = intervene["why"].asString
        val intervention = if (why == "none") null.also { intervene.exact("why") } else {
            require(why in reasons)
            intervene.exact("why", "question", "proposal")
            val evidence = sources.map { requireNotNull(it) }
            val question = label(intervene["question"])
            val proposal = label(intervene["proposal"])
            require(question != null || proposal == null)
            question?.let { Intervention(Claim(it, evidence), proposal?.let { text -> Claim(text, evidence) }) }
        }
        GraphUpdate(owned, mapped, intervention, why.takeIf { intervention != null })
    } catch (error: Exception) {
        throw IllegalArgumentException("The model response did not pass grounding checks. Retry or shorten the turn.", error)
    }

    /**
     * The two current requests a common-ground follow-up compares: after a new request when the map has no
     * common ground yet, or when a new request supports the other person's. Null when no check is due.
     */
    fun commonCandidate(graph: Graph, update: GraphUpdate): Pair<Node, Node>? {
        val added = update.nodes.filter { it.kind == NodeKind.REQUEST }
        if (added.isEmpty()) return null
        val trial = graph.apply(update)
        val a = trial.latest(Speaker.A, NodeKind.REQUEST) ?: return null
        val b = trial.latest(Speaker.B, NodeKind.REQUEST) ?: return null
        val compatible = added.any { node -> node.links.any { it.first == LinkKind.SUPPORTS } }
        return (a to b).takeIf { trial.latest(null, NodeKind.COMMON) == null || compatible }
    }

    /** Adds the follow-up's common ground to [update]. Common ground is optional, so a bad answer adds nothing. */
    fun withCommon(raw: String, graph: Graph, update: GraphUpdate): GraphUpdate {
        val text = runCatching { label(JsonParser.parseString(raw).asJsonObject.exact("common")["common"]) }.getOrNull() ?: return update
        val trial = graph.apply(update)
        if (trial.latest(null, NodeKind.COMMON)?.claim?.text.equals(text, ignoreCase = true)) return update
        val evidence = Speaker.entries.map { trial.anchor(it)?.claim?.evidence?.first() ?: return update }
        return update.copy(nodes = update.nodes + NewNode(NodeKind.COMMON, null, Claim(text, evidence)))
    }

    private fun JsonObject.exact(vararg keys: String): JsonObject {
        require(keySet() == keys.toSet())
        return this
    }

    /** A speaker's request and concern slots. `revises` and `vs` describe the request; without one they are ignored. */
    private fun owned(obj: JsonObject, graph: Graph, speaker: Speaker, ids: List<Int>): List<NewNode> {
        if (ids.isEmpty()) return emptyList<NewNode>().also { obj.exact() }
        val own = ownRequests(graph, speaker)
        val opposite = opposite(graph, speaker)
        obj.exact(*listOfNotNull("request", "concern", "e".takeIf { ids.size > 1 }, "revises".takeIf { own.isNotEmpty() },
            "vs".takeIf { opposite != null }).toTypedArray())
        val turn = if (ids.size == 1) ids.single() else obj["e"].asInt.also { require(it in ids) }
        val revises = obj["revises"]?.asInt?.takeIf { it != 0 }?.also { require(it in own) }
        val relation = when (obj["vs"]?.asString) {
            null, "unrelated" -> null
            "conflicts" -> LinkKind.CONFLICTS
            "supports" -> LinkKind.SUPPORTS
            else -> error("vs")
        }
        val request = label(obj["request"])?.takeUnless { duplicate(graph, speaker, NodeKind.REQUEST, it) }
        val concern = label(obj["concern"])?.takeUnless { duplicate(graph, speaker, NodeKind.CONCERN, it) }
        val links = listOfNotNull(revises?.let { LinkKind.REPLACES to it }, relation?.let { it to requireNotNull(opposite).id })
        return listOfNotNull(request?.let { NewNode(NodeKind.REQUEST, speaker, Claim(it, listOf(turn)), links) },
            concern?.let { NewNode(NodeKind.CONCERN, speaker, Claim(it, listOf(turn))) })
    }

    private fun duplicate(graph: Graph, speaker: Speaker, kind: NodeKind, text: String) =
        graph.active.any { it.owner == speaker && it.kind == kind && it.claim.text.equals(text, ignoreCase = true) }

    /** Null for absent or placeholder labels, which are never displayed as interpretations. */
    private fun label(value: JsonElement): String? {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        val text = value.asString.trim()
        if (text.lowercase(java.util.Locale.ROOT) in setOf("", "n/a", "none", "no concern", "unknown", "not stated", "not specified")) return null
        require(text.length <= 180 && text.none { it in "\"{}\\" })
        return text
    }

    // Labels exclude quotes, braces and backslashes so wire syntax cannot leak into displayed text.
    private const val TEXT = """{"type":"string","pattern":"^[^\"{}\\\\]{0,180}${'$'}"}"""
    val COMMON_SCHEMA = """{"type":"object","additionalProperties":false,"x-guidance":{"whitespace_flexible":false},
        "properties":{"common":$TEXT},"required":["common"]}"""
    private const val NONE = """{"type":"object","additionalProperties":false,"properties":{"why":{"type":"string","const":"none"}},"required":["why"]}"""
}

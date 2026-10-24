package ai.xybrid.mediator

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Wire format for one incremental map update. Gemma sees the active graph and the unmapped turns, and
 * returns only additions: for each speaker with new turns a request and concern slot (empty when absent)
 * plus an optional link from that request to an existing node, an optional common ground, and whether to
 * intervene. Fixed slots keep E2B filling explicit requests, which an open claim list did not. Every
 * generated token costs a full constrained decode step on the phone, so citations the app can infer are
 * omitted: claims cite their speaker's only new turn unless they have several, and shared claims cite each
 * speaker's anchor claim.
 */
internal object AnalysisCodec {
    val reasons = listOf("impasse", "misunderstanding", "common_ground", "next_step")

    /** Turns the next update must map, oldest first. */
    fun pending(graph: Graph, turns: List<Turn>) = turns.filter { it.id !in graph.mapped }

    fun schemaFor(graph: Graph, turns: List<Turn>): String {
        val fresh = pending(graph, turns)
        val both = turns.map { it.speaker }.toSet().size == 2
        val targets = graph.active.filter { it.owner != null }.map { it.id }
        val owned = Speaker.entries.joinToString(",") { speaker ->
            """"${speaker.name}":${ownedSchema(fresh.filter { it.speaker == speaker }.map { it.id }, targets)}"""
        }
        val intervene = if (!both) NONE else """{"anyOf":[$NONE,{"type":"object","additionalProperties":false,"properties":{
            "why":{"type":"string","enum":[${reasons.joinToString(",") { "\"$it\"" }}]},"question":$TEXT,"proposal":$TEXT},
            "required":["why","question","proposal"]}]}"""
        // llguidance otherwise permits newlines between keys, and each one costs a decode step.
        return """{"type":"object","additionalProperties":false,"x-guidance":{"whitespace_flexible":false},"properties":{
          $owned,"common":${if (both) TEXT else EMPTY},"intervene":$intervene},"required":["A","B","common","intervene"]}"""
    }

    private fun ownedSchema(ids: List<Int>, targets: List<Int>): String {
        if (ids.isEmpty()) return """{"type":"object","additionalProperties":false,"properties":{},"required":[]}"""
        val evidence = if (ids.size > 1) ""","e":{"type":"integer","enum":[${ids.joinToString(",")}]}""" else ""
        val link = if (targets.isEmpty()) "" else ""","link":{"type":"object","additionalProperties":false,"properties":{
            "rel":{"type":"string","enum":["replaces","conflicts","supports"]},"to":{"type":"integer","enum":[${targets.joinToString(",")}]}},
            "required":["rel","to"]}"""
        val required = if (ids.size > 1) """["request","concern","e"]""" else """["request","concern"]"""
        return """{"type":"object","additionalProperties":false,"properties":{"request":$TEXT,"concern":$TEXT$evidence$link},"required":$required}"""
    }

    fun decode(raw: String, graph: Graph, turns: List<Turn>): GraphUpdate = try {
        val fresh = pending(graph, turns)
        require(fresh.isNotEmpty())
        val root = JsonParser.parseString(raw).asJsonObject.exact("A", "B", "common", "intervene")
        val owned = Speaker.entries.flatMap { speaker ->
            val ids = fresh.filter { it.speaker == speaker }.map { it.id }
            owned(root[speaker.name].asJsonObject, graph, speaker, ids)
        }
        val mapped = fresh.map { it.id }.toSet()
        val trial = graph.apply(GraphUpdate(owned, mapped))
        val sources = Speaker.entries.map { speaker ->
            trial.anchor(speaker)?.claim?.evidence?.first() ?: turns.lastOrNull { it.speaker == speaker }?.id
        }
        val common = label(root["common"])
            ?.takeIf { text -> graph.latest(null, NodeKind.COMMON)?.claim?.text?.equals(text, ignoreCase = true) != true }
            ?.let { NewNode(NodeKind.COMMON, null, Claim(it, sources.map { id -> requireNotNull(id) })) }
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
        GraphUpdate(owned + listOfNotNull(common), mapped, intervention, why.takeIf { intervention != null })
    } catch (error: Exception) {
        throw IllegalArgumentException("The model response did not pass grounding checks. Retry or shorten the turn.", error)
    }

    private fun JsonObject.exact(vararg keys: String): JsonObject {
        require(keySet() == keys.toSet())
        return this
    }

    /** A speaker's request and concern slots; the link belongs to the request, or to the concern without one. */
    private fun owned(obj: JsonObject, graph: Graph, speaker: Speaker, ids: List<Int>): List<NewNode> {
        if (ids.isEmpty()) return emptyList<NewNode>().also { obj.exact() }
        require(obj.keySet().containsAll(listOf("request", "concern")) && obj.keySet().all { it in setOf("request", "concern", "e", "link") })
        require(("e" in obj.keySet()) == (ids.size > 1))
        val turn = if (ids.size == 1) ids.single() else obj["e"].asInt.also { require(it in ids) }
        val claims = listOf(NodeKind.REQUEST to label(obj["request"]), NodeKind.CONCERN to label(obj["concern"]))
            .mapNotNull { (kind, text) -> text?.let { kind to it } }
            .filterNot { (kind, text) -> graph.active.any { it.owner == speaker && it.kind == kind && it.claim.text.equals(text, ignoreCase = true) } }
        return claims.mapIndexed { index, (kind, text) ->
            NewNode(kind, speaker, Claim(text, listOf(turn)), obj["link"]?.takeIf { index == 0 }?.let { link(it.asJsonObject, graph, speaker, kind) })
        }
    }

    /** Links are structure, not claims: an implausible one is dropped rather than failing the update. */
    private fun link(obj: JsonObject, graph: Graph, speaker: Speaker, kind: NodeKind): Pair<LinkKind, Int>? {
        obj.exact("rel", "to")
        val target = graph.active.find { it.id == obj["to"].asInt } ?: return null
        return when (obj["rel"].asString) {
            "replaces" -> (LinkKind.REPLACES to target.id).takeIf { target.owner == speaker && target.kind == kind }
            "conflicts" -> (LinkKind.CONFLICTS to target.id).takeIf { target.owner != null && target.owner != speaker }
            "supports" -> (LinkKind.SUPPORTS to target.id).takeIf { target.owner != null && target.owner != speaker }
            else -> error("rel")
        }
    }

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
    private const val EMPTY = """{"type":"string","const":""}"""
    private const val NONE = """{"type":"object","additionalProperties":false,"properties":{"why":{"type":"string","const":"none"}},"required":["why"]}"""
}

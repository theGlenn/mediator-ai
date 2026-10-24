package ai.xybrid.mediator

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Every generated token costs a full constrained decode step on the phone, so the wire format carries
 * only citations the app cannot infer. While each speaker has at most one turn, claims are plain text
 * citing that turn implicitly. Once anyone has several turns, every A/B claim is {"t":text,"e":turn_id};
 * one form per output keeps E2B from writing JSON inside a string. Shared claims are plain text, cited by
 * each speaker's request turn (else concern turn, else latest turn).
 */
internal object AnalysisCodec {
    fun schemaFor(turns: List<Turn>): String {
        val shared = if (turns.map { it.speaker }.toSet().size == 2) TEXT else EMPTY
        val cited = cited(turns)
        val views = Speaker.entries.joinToString(",") { speaker ->
            val owned = ownedSchema(turns.filter { it.speaker == speaker }.map { it.id }, cited)
            """"${speaker.name}":{"type":"object","additionalProperties":false,"properties":{"request":$owned,"concern":$owned},"required":["request","concern"]}"""
        }
        // llguidance otherwise permits newlines between keys, and each one costs a decode step.
        return """{"type":"object","additionalProperties":false,"x-guidance":{"whitespace_flexible":false},"properties":{
          $views,"common":$shared,"question":$shared,"proposal":$shared},
          "required":["A","B","common","question","proposal"]}"""
    }

    private fun cited(turns: List<Turn>) = turns.groupingBy { it.speaker }.eachCount().values.any { it > 1 }

    private fun ownedSchema(ids: List<Int>, cited: Boolean) = when {
        ids.isEmpty() -> EMPTY
        !cited -> TEXT
        else -> """{"type":"object","additionalProperties":false,"properties":{"t":$TEXT,"e":{"type":"integer","enum":[${ids.joinToString(",")}]}},"required":["t","e"]}"""
    }

    fun decode(raw: String, turns: List<Turn>): Analysis = try {
        val root = JsonParser.parseString(raw).asJsonObject.exact("A", "B", "common", "question", "proposal")
        val views = Speaker.entries.map { speaker ->
            val view = root[speaker.name].asJsonObject.exact("request", "concern")
            SpeakerView(speaker, owned(view["request"], turns, speaker), owned(view["concern"], turns, speaker))
        }
        val sources = views.map { view ->
            (view.request ?: view.concern)?.evidence?.single() ?: turns.lastOrNull { it.speaker == view.speaker }?.id
        }
        val ground = shared(root["common"], sources)
        val question = shared(root["question"], sources)
        val proposal = shared(root["proposal"], sources)
        require(question != null || proposal == null)
        Analysis(views, ground, question?.let { Intervention(it, proposal) })
    } catch (error: Exception) {
        throw IllegalArgumentException("The model response did not pass grounding checks. Retry or shorten the turn.", error)
    }

    private fun JsonObject.exact(vararg keys: String): JsonObject {
        require(keySet() == keys.toSet())
        return this
    }

    private fun owned(value: JsonElement, turns: List<Turn>, owner: Speaker): Claim? {
        val ids = turns.filter { it.speaker == owner }.map { it.id }
        if (ids.isEmpty()) return null.also { require(label(value) == null) }
        if (!cited(turns)) return label(value)?.let { text -> Claim(text, ids) }
        val obj = value.asJsonObject.exact("t", "e")
        val evidence = obj["e"]
        require(evidence.isJsonPrimitive && evidence.asJsonPrimitive.isNumber && evidence.asString.matches(Regex("[1-8]")))
        require(evidence.asInt in ids)
        return label(obj["t"])?.let { text -> Claim(text, listOf(evidence.asInt)) }
    }

    /** Shared claims require both participants; their sources come from each speaker's own claims. */
    private fun shared(value: JsonElement, sources: List<Int?>): Claim? {
        val text = label(value) ?: return null
        return Claim(text, sources.map { requireNotNull(it) })
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
}

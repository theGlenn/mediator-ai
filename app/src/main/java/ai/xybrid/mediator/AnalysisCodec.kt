package ai.xybrid.mediator

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Short wire keys leave room for actual content within the 256-token generation budget. */
internal object AnalysisCodec {
    fun schemaFor(turns: List<Turn>): String {
        val shared = sharedSchema(turns)
        val views = Speaker.entries.joinToString(",") { speaker ->
            val owned = claimSchema(turns.filter { it.speaker == speaker }.map { it.id })
            """"${speaker.name}":{"type":"object","additionalProperties":false,"properties":{"request":$owned,"concern":$owned},"required":["request","concern"]}"""
        }
        return """{"type":"object","additionalProperties":false,"properties":{
          $views,"common":$shared,"question":$shared,"proposal":$shared},
          "required":["A","B","common","question","proposal"]}"""
    }

    private fun claimSchema(ids: List<Int>): String {
        val text = if (ids.isEmpty()) """{"type":"string","const":""}""" else """{"type":"string","maxLength":180}"""
        val item = if (ids.isEmpty()) """{"type":"integer"}""" else """{"type":"integer","enum":[${ids.joinToString(",")}]}"""
        // Empty text means absent, even when the schema requires a source placeholder.
        // This avoids nullable unions and missing citations observed with E2B decoding.
        val minimum = if (ids.isEmpty()) 0 else 1
        return """{"type":"object","additionalProperties":false,"properties":{"t":$text,"e":{"type":"array","items":$item,"minItems":$minimum,"maxItems":${ids.size}}},"required":["t","e"]}"""
    }

    private fun sharedSchema(turns: List<Turn>): String {
        val both = turns.map { it.speaker }.toSet().size == 2
        val text = if (both) """{"type":"string","maxLength":180}""" else """{"type":"string","const":""}"""
        val properties = if (!both) "" else Speaker.entries.joinToString(",") { speaker ->
            val ids = turns.filter { it.speaker == speaker }.joinToString(",") { it.id.toString() }
            """"${speaker.name}":{"type":"integer","enum":[$ids]}"""
        }
        val required = if (both) """["A","B"]""" else "[]"
        val evidence = """{"type":"object","additionalProperties":false,"properties":{$properties},"required":$required}"""
        return """{"type":"object","additionalProperties":false,"properties":{"t":$text,"e":$evidence},"required":["t","e"]}"""
    }

    fun decode(raw: String, turns: List<Turn>): Analysis = try {
        val root = JsonParser.parseString(raw).asJsonObject.exact("A", "B", "common", "question", "proposal")
        val views = Speaker.entries.map { speaker ->
            val view = root[speaker.name].asJsonObject.exact("request", "concern")
            SpeakerView(speaker, claim(view["request"], turns, speaker), claim(view["concern"], turns, speaker))
        }
        val ground = claim(root["common"], turns)
        val question = claim(root["question"], turns)
        val proposal = claim(root["proposal"], turns)
        require(question != null || proposal == null)
        Analysis(views, ground, question?.let { Intervention(it, proposal) })
    } catch (error: Exception) {
        throw IllegalArgumentException("The model response did not pass grounding checks. Retry or shorten the turn.", error)
    }

    private fun JsonObject.exact(vararg keys: String): JsonObject {
        require(keySet() == keys.toSet())
        return this
    }

    private fun sourceId(value: JsonElement): Int {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asString.matches(Regex("[1-8]")))
        return value.asInt
    }

    private fun claim(value: JsonElement, turns: List<Turn>, owner: Speaker? = null): Claim? {
        val obj = value.asJsonObject.exact("t", "e")
        require(obj["t"].isJsonPrimitive && obj["t"].asJsonPrimitive.isString)
        val text = obj["t"].asString.trim()
        val ids = if (owner != null) obj.getAsJsonArray("e").map { sourceId(it) }
        else {
            val evidence = obj["e"].asJsonObject
            require(evidence.keySet().isEmpty() || evidence.keySet() == setOf("A", "B"))
            Speaker.entries.mapNotNull { speaker -> evidence[speaker.name]?.let { value ->
                sourceId(value).also { id -> require(turns.any { it.id == id && it.speaker == speaker }) }
            } }
        }
        require(ids.distinct().size == ids.size)
        val sources = ids.map { id -> requireNotNull(turns.find { it.id == id }) }
        if (owner != null) require(sources.all { it.speaker == owner })
        if (text.lowercase(java.util.Locale.ROOT) in setOf("", "n/a", "none", "unknown", "not stated", "not specified")) return null
        require(text.length <= 180 && ids.size in 1..8)
        if (owner == null) require(sources.map { it.speaker }.toSet().size == 2)
        return Claim(text, ids)
    }
}

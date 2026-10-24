package ai.xybrid.mediator

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File

/** Host CPU quality diagnostic. Its timings never count as phone performance evidence. */
fun main(args: Array<String>) {
    val model = File(args.firstOrNull { !it.startsWith("--") } ?: "models/gemma-4-E2B-it.litertlm")
    require(model.isFile) { "Run python3 scripts/provision.py --download-only first." }
    val cases = JsonParser.parseString(File("app/src/main/assets/evaluation-dialogues.json").readText()).asJsonArray
    val reports = mutableListOf<Map<String, Any?>>()
    Engine(EngineConfig(model.absolutePath, Backend.CPU(), maxNumTokens = 8192)).use { engine ->
        engine.initialize()
        val firstOnly = listOf(Turn(1, Speaker.A, "I need a daily update because I am responsible for delivery."))
        val firstAnalysis = engine.createConversation(GemmaAnalysis.configuration()).use {
            GemmaAnalysis.run(it, firstOnly) { text -> println("Single-speaker synthetic output: $text") }
        }
        check(firstAnalysis.views.single { it.speaker == Speaker.A }.request != null)
        check(firstAnalysis.views.single { it.speaker == Speaker.B }.request == null)
        check(firstAnalysis.commonGround == null && firstAnalysis.intervention == null)
        println("Single-speaker control passed: no invented second speaker or intervention.")
        if (args.contains("--smoke-only")) {
            engine.createConversation(ConversationConfig(maxOutputToken = 128,
                extraContext = mapOf("enable_thinking" to false))).use {
                println("Plain extraction check: " + it.sendMessage("Alex says: I need a daily update because I am responsible for delivery. Sam says: Constant check-ins break my focus. In one sentence describe what each person wants."))
            }
        }
        for (item in if (args.contains("--smoke-only")) cases.take(1) else cases) {
            val example = item.asJsonObject
            val turns = example.getAsJsonArray("turns").map {
                val t = it.asJsonObject
                Turn(t["id"].asInt, Speaker.valueOf(t["speaker"].asString), t["text"].asString, typed = true)
            }
            val started = System.nanoTime()
            var raw = ""
            try {
                val analysis = engine.createConversation(GemmaAnalysis.configuration()).use { GemmaAnalysis.run(it, turns) { text -> raw = text } }
                check(analysis.views.all { it.request != null }) { "Explicit requests were left empty in a test dialogue." }
                check(analysis.intervention != null) { "An unresolved test dialogue did not produce a question." }
                if (example["id"].asString == "correction") {
                    check(analysis.views.first().request!!.evidence.contains(3)) { "The corrected request must cite the correction." }
                }
                if (example["id"].asString == "unknown-concern") {
                    check(analysis.views.all { it.concern == null } && analysis.commonGround == null) { "Unstated reasons and shared goals must stay absent." }
                }
                reports += mapOf("id" to example["id"].asString, "analysis" to analysis,
                    "hostCpuMs" to (System.nanoTime() - started) / 1_000_000,
                    "review" to example["review"].asString, "humanUseful" to null)
                println("${example["id"].asString}: structurally grounded")
            } catch (failure: Exception) {
                reports += mapOf("id" to example["id"].asString, "rawSyntheticOutput" to raw, "error" to failure.toString(),
                    "cause" to failure.cause?.toString())
                println("${example["id"].asString}: FAILED (${failure.javaClass.simpleName})")
            }
            File("probe/build").mkdirs()
            File("probe/build/evaluation-report.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(reports))
        }
    }
    check(reports.none { it.containsKey("error") }) { "See probe/build/evaluation-report.json for failed cases." }
    println("All ${reports.size} outputs contained requests and a question, and passed syntax, source ID and speaker-ownership checks. Human quality review is still required.")
}

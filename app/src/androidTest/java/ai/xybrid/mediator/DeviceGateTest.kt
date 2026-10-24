package ai.xybrid.mediator

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Requires USB-provisioned model files. No mock model, network, or silently skipped cases. Each dialogue is
 * replayed one turn at a time, as in a live session, so every check runs on the incrementally built map.
 */
@RunWith(AndroidJUnit4::class)
class DeviceGateTest {
    @Test fun bothRuntimesAndGroundedDialogues() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = LocalRuntime(context)
        val reports = mutableListOf<Map<String, Any?>>()
        try {
            runtime.initialize { }
            assertNotNull(runtime.speechModel)
            val opening = runtime.analyze(Graph(), listOf(Turn(1, Speaker.A, "I need updates to plan delivery.")))
            assertNull(opening.update.intervention)
            val first = Graph().apply(opening.update).projection(opening.update.intervention)
            assertNotNull(first.views.single { it.speaker == Speaker.A }.request)
            assertNull(first.views.single { it.speaker == Speaker.B }.request)
            assertNull(first.intervention)
            assertNull(first.commonGround)
            val cases = context.assets.open("evaluation-dialogues.json").bufferedReader().use { JsonParser.parseReader(it).asJsonArray }
            for (element in cases) {
                val example = element.asJsonObject
                val turns = example.getAsJsonArray("turns").map {
                    val turn = it.asJsonObject
                    Turn(turn["id"].asInt, Speaker.valueOf(turn["speaker"].asString), turn["text"].asString, typed = true)
                }
                var graph = Graph()
                var decoded: Analysis? = null
                val updates = mutableListOf<Map<String, Any?>>()
                try {
                    var result: Analysis? = null
                    for (count in 1..turns.size) {
                        runtime.prepareNext() // Live turns give the background conversation setup time to finish.
                        val start = SystemClock.elapsedRealtime()
                        val (update, gemma) = runtime.analyze(graph, turns.take(count))
                        graph = graph.apply(update)
                        result = graph.projection(update.intervention)
                        updates += mapOf("turn" to count, "analysisMs" to SystemClock.elapsedRealtime() - start, "gemma" to gemma,
                            "reason" to update.reason)
                    }
                    decoded = result
                    checkNotNull(result)
                    check(result.views.all { it.request != null }) { "Explicit requests were left empty in a test dialogue." }
                    check(result.intervention != null) { "An unresolved test dialogue did not produce a question." }
                    if (example["id"].asString == "correction") {
                        check(result.views.first().request!!.evidence.contains(3)) { "The corrected request must cite the correction." }
                    }
                    if (example["id"].asString == "unknown-concern") {
                        check(result.views.all { it.concern == null } && result.commonGround == null) { "Unstated reasons and shared goals must stay absent." }
                    }
                    reports += mapOf("id" to example["id"].asString, "backend" to runtime.backendLabel,
                        "analysisMs" to updates.last()["analysisMs"], "updates" to updates,
                        "analysis" to result, "graph" to graph, "review" to example["review"].asString,
                        "humanUseful" to null)
                } catch (error: Exception) {
                    // Messages are fixed gate/codec strings; the synthetic fixture text is already public.
                    reports += mapOf("id" to example["id"].asString, "error" to error.javaClass.simpleName,
                        "message" to error.message, "cause" to error.cause?.toString(), "analysis" to decoded,
                        "graph" to graph, "updates" to updates)
                }
            }
            // Synthetic fixtures only. Live session content is never written to this report.
            File(context.getExternalFilesDir(null), "evaluation-report.json").writeText(Gson().toJson(reports))
            assertEquals("Every case must report; inspect evaluation-report.json", cases.size(), reports.size)
            assertTrue("One or more real model outputs failed. See evaluation-report.json", reports.none { it.containsKey("error") })
        } finally { runtime.close() }
    }
}

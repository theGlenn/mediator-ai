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

/** Requires USB-provisioned model files. No mock model, network, or silently skipped cases. */
@RunWith(AndroidJUnit4::class)
class DeviceGateTest {
    @Test fun bothRuntimesAndTenGroundedDialogues() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = LocalRuntime(context)
        val reports = mutableListOf<Map<String, Any?>>()
        try {
            runtime.initialize { }
            assertNotNull(runtime.speechModel)
            val first = runtime.analyze(listOf(Turn(1, Speaker.A, "I need updates to plan delivery."))).analysis
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
                runtime.prepareNext() // Live turns give the background conversation setup time to finish.
                val start = SystemClock.elapsedRealtime()
                try {
                    val (result, gemma) = runtime.analyze(turns)
                    check(result.views.all { it.request != null }) { "Explicit requests were left empty in a test dialogue." }
                    check(result.intervention != null) { "An unresolved test dialogue did not produce a question." }
                    if (example["id"].asString == "correction") {
                        check(result.views.first().request!!.evidence.contains(3)) { "The corrected request must cite the correction." }
                    }
                    if (example["id"].asString == "unknown-concern") {
                        check(result.views.all { it.concern == null } && result.commonGround == null) { "Unstated reasons and shared goals must stay absent." }
                    }
                    reports += mapOf("id" to example["id"].asString, "backend" to runtime.backendLabel,
                        "analysisMs" to SystemClock.elapsedRealtime() - start, "gemma" to gemma,
                        "analysis" to result, "review" to example["review"].asString,
                        "humanUseful" to null)
                } catch (error: Exception) {
                    reports += mapOf("id" to example["id"].asString, "error" to error.javaClass.simpleName)
                }
            }
            // Synthetic fixtures only. Live session content is never written to this report.
            File(context.getExternalFilesDir(null), "evaluation-report.json").writeText(Gson().toJson(reports))
            assertEquals("All ten cases must pass structural and evidence validation; inspect evaluation-report.json", 10, reports.size)
            assertTrue("One or more real model outputs failed. See evaluation-report.json", reports.none { it.containsKey("error") })
        } finally { runtime.close() }
    }
}

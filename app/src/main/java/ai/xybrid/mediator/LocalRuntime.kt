package ai.xybrid.mediator

import android.content.Context
import ai.xybrid.Xybrid
import ai.xybrid.XybridModel
import ai.xybrid.XybridModelLoader
import ai.xybrid.defaultStreamingConfig
import ai.xybrid.stream
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns the two native runtimes. All initialization/inference/teardown runs off the UI thread. */
internal class LocalRuntime(private val context: Context) {
    private val mutex = Mutex()
    private var engine: Engine? = null
    var speechModel: XybridModel? = null
        private set
    var backendLabel: String = "Not loaded"
        private set
    @Volatile private var generation: com.google.ai.edge.litertlm.Conversation? = null
    private val generationLock = Any()

    suspend fun initialize(status: (String) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (engine != null && speechModel != null) return@withLock
            try {
                val directory = File(context.getExternalFilesDir(null), "models")
                status("Checking model files and SHA-256 checksums…")
                verifyModels(directory)
                currentCoroutineContext().ensureActive()
                Xybrid.init(context)
                val whisper = File(directory, "whisper")
                File(whisper, "model_metadata.json").writeText(context.assets.open("whisper-model_metadata.json").bufferedReader().use { it.readText() })
                status("Loading and warming local speech recognition…")
                speechModel = XybridModelLoader.fromDirectory(whisper.absolutePath).loadBlocking()
                speechModel!!.stream(defaultStreamingConfig()).use { session ->
                    session.feed(FloatArray(16_000))
                    session.flush()
                }
                currentCoroutineContext().ensureActive()
                val model = File(directory, "gemma-4-E2B-it.litertlm")
                status("Loading Gemma on GPU and checking structured output…")
                try {
                    engine = openEngine(model, Backend.GPU())
                    backendLabel = "Gemma 4 E2B · GPU"
                } catch (gpuFailure: Exception) {
                    currentCoroutineContext().ensureActive()
                    status("GPU unavailable. Checking Gemma on CPU…")
                    engine = openEngine(model, Backend.CPU())
                    backendLabel = "Gemma 4 E2B · CPU (GPU unavailable)"
                }
                currentCoroutineContext().ensureActive()
            } catch (failure: Throwable) {
                release()
                throw failure
            }
        }
    }

    private fun openEngine(model: File, backend: Backend): Engine {
        val candidate = Engine(EngineConfig(model.absolutePath, backend,
            maxNumTokens = 8192, cacheDir = context.cacheDir.absolutePath))
        try {
            candidate.initialize()
            candidate.createConversation(ConversationConfig(enableResponseFormat = true,
                channels = emptyList(), extraContext = mapOf("enable_thinking" to false))).use {
                val output = it.sendMessage("Return a JSON object with ready set to true.",
                    maxOutputToken = 32, responseFormat = ResponseFormat.json(
                        """{"type":"object","properties":{"ready":{"type":"boolean"}},"required":["ready"],"additionalProperties":false}"""))
                check(JsonParser.parseString(output.toString()).asJsonObject["ready"].asBoolean)
            }
            return candidate
        } catch (failure: Throwable) {
            candidate.close()
            throw failure
        }
    }

    suspend fun analyze(turns: List<Turn>): Analysis = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            require(turns.isNotEmpty() && turns.size <= 8)
            val active = requireNotNull(engine) { "Load models before starting." }.createConversation(GemmaAnalysis.configuration())
            synchronized(generationLock) { generation = active }
            try {
                currentCoroutineContext().ensureActive()
                val result = GemmaAnalysis.run(active, turns)
                currentCoroutineContext().ensureActive()
                result
            } finally {
                synchronized(generationLock) { generation = null; active.close() }
            }
        }
    }

    fun cancelGeneration() {
        // The runtime exposes cancellation as a thread-safe native operation.
        synchronized(generationLock) { runCatching { generation?.cancelProcess() } }
    }

    suspend fun close() = withContext(Dispatchers.IO) { mutex.withLock { release() } }

    private fun release() {
        runCatching { engine?.close() }
        runCatching { speechModel?.close() }
        engine = null
        speechModel = null
        backendLabel = "Not loaded"
    }

    private fun verifyModels(directory: File) {
        val lock = context.assets.open("models.lock.json").bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        for (entry in lock.entrySet()) {
            val spec = entry.value.asJsonObject
            val file = File(directory, spec["file"].asString)
            require(file.isFile && file.length() == spec["size"].asLong) {
                "Model files are missing or incomplete. Run scripts/provision.py over USB first."
            }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(1024 * 1024)
                var size = input.read(buffer)
                while (size != -1) { digest.update(buffer, 0, size); size = input.read(buffer) }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(actual == spec["sha256"].asString) { "Model checksum mismatch. Re-provision the models over USB." }
        }
    }

}

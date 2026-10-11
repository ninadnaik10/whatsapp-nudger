package com.hackday.notifrouter.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Process-wide Gemma 4 engine on LiteRT-LM.
 *
 * Loading takes seconds and ~1–2 GB of RAM, so we load once and keep it for the lifetime of the
 * process (the NotificationListenerService keeps the process alive). A fresh Conversation is
 * created per message so the KV cache never grows across notifications.
 */
object GemmaEngine {
    const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
    private const val TAG = "GemmaEngine"

    enum class State { NO_MODEL, IDLE, LOADING, READY, ERROR }
    data class Status(val state: State, val detail: String = "")

    /** Knobs that require an engine reload to change. */
    data class Options(
        /**
         * Load the vision encoder so notification images can be passed to Gemma. Its first-ever
         * GPU compile takes ~10 s (cached in cacheDir afterwards); per-message cost is negligible.
         */
        val vision: Boolean = true,
        /** Multi-token prediction; Google recommends it for all GPU workloads. */
        val speculativeDecoding: Boolean = true,
    )

    /** Per-call knobs. */
    data class GenOptions(
        val maxOutputTokens: Int = 64,
        /** Stop as soon as one complete `a|b|c|d|e` line has been produced. */
        val stopAfterLine: Boolean = true,
    )

    data class GenResult(
        val text: String, val setupMs: Long, val firstTokenMs: Long, val decodeMs: Long, val chunks: Int,
        val usedImage: Boolean = false,
    ) {
        val totalMs get() = setupMs + firstTokenMs + decodeMs
        override fun toString() =
            "total=${totalMs}ms setup=${setupMs}ms firstToken=${firstTokenMs}ms decode=${decodeMs}ms chunks=$chunks chars=${text.length}"
    }

    private val _status = MutableStateFlow(Status(State.IDLE))
    val status: StateFlow<Status> = _status.asStateFlow()

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var visionEnabled = false
    var options = Options()
        private set

    /** Push the model with: adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/<pkg>/files/ */
    fun modelFile(context: Context): File = File(context.getExternalFilesDir(null), MODEL_FILE_NAME)

    val isReady: Boolean get() = engine != null

    /** Closes the current engine (if any) and loads with [newOptions]. */
    suspend fun reload(context: Context, newOptions: Options): Boolean {
        mutex.withLock {
            engine?.close()
            engine = null
            options = newOptions
        }
        return ensureLoaded(context)
    }

    @OptIn(ExperimentalApi::class)
    suspend fun ensureLoaded(context: Context): Boolean = mutex.withLock {
        if (engine != null) return true
        val model = modelFile(context)
        if (!model.exists()) {
            _status.value = Status(State.NO_MODEL, "Model not found at ${model.absolutePath}")
            return false
        }
        _status.value = Status(State.LOADING, "Loading ${model.name} (${model.length() / 1_000_000} MB)…")
        withContext(Dispatchers.IO) {
            // Try fastest first; fall back to plainer configs if the device lacks GPU/vision support.
            val attempts = buildList {
                if (options.vision) add(Triple("GPU + vision", Backend.GPU(), Backend.GPU()))
                add(Triple("GPU", Backend.GPU(), null))
                add(Triple("CPU", Backend.CPU(), null))
            }
            for ((label, backend, vision) in attempts) {
                try {
                    // MTP is a process-wide flag read at initialize(); only worth it on GPU.
                    ExperimentalFlags.enableSpeculativeDecoding = options.speculativeDecoding && backend is Backend.GPU
                    val e = Engine(
                        EngineConfig(
                            modelPath = model.absolutePath,
                            backend = backend,
                            visionBackend = vision,
                            maxNumTokens = 4096,
                            cacheDir = context.cacheDir.path,
                        )
                    )
                    val started = System.currentTimeMillis()
                    e.initialize()
                    val initMs = System.currentTimeMillis() - started
                    engine = e
                    visionEnabled = vision != null
                    // The first generation compiles GPU kernels (and the vision encoder, if loaded).
                    // Pay that here instead of on the first real notification.
                    _status.value = Status(State.LOADING, "$label · warming up…")
                    val warm = runCatching { generateLocked(e, "Reply with OK.", "OK?", emptyList(), null, GenOptions(maxOutputTokens = 4, stopAfterLine = false)) }
                    val label2 = label + if (ExperimentalFlags.enableSpeculativeDecoding == true) " + MTP" else ""
                    _status.value = Status(State.READY, "$label2 · init ${initMs} ms · warm-up ${warm.getOrNull()?.totalMs ?: -1} ms")
                    Log.i(TAG, "Engine ready ($label2) init=${initMs}ms warmup=${warm.getOrNull()}")
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "Engine init failed with $label", t)
                    _status.value = Status(State.ERROR, "$label failed: ${t.message}")
                }
            }
        }
        engine != null
    }

    /**
     * Runs one stateless generation. [fewShot] pairs are sent as earlier user/model turns.
     * Calls are serialized because a phone can only afford one inference at a time.
     */
    suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        fewShot: List<Pair<String, String>> = emptyList(),
        imageJpeg: ByteArray? = null,
        gen: GenOptions = GenOptions(),
    ): GenResult = mutex.withLock {
        val e = checkNotNull(engine) { "Engine not loaded" }
        generateLocked(e, systemPrompt, userPrompt, fewShot, imageJpeg, gen)
            .also { Log.i(TAG, "gen image=${it.usedImage} $it") }
    }

    private object StopGeneration : RuntimeException() {
        private fun readResolve(): Any = StopGeneration
    }

    private suspend fun generateLocked(
        e: Engine,
        systemPrompt: String,
        userPrompt: String,
        fewShot: List<Pair<String, String>>,
        imageJpeg: ByteArray?,
        gen: GenOptions,
    ): GenResult = withContext(Dispatchers.Default) {
        val t0 = System.nanoTime()
        val config = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            initialMessages = fewShot.flatMap { (user, model) -> listOf(Message.user(user), Message.model(model)) },
            // Greedy decoding: routing should be deterministic.
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.1),
            maxOutputToken = gen.maxOutputTokens,
        )
        e.createConversation(config).use { conversation ->
            val contents = if (imageJpeg != null && visionEnabled) {
                Contents.of(Content.ImageBytes(imageJpeg), Content.Text(userPrompt))
            } else {
                Contents.of(userPrompt)
            }
            val t1 = System.nanoTime()
            var tFirst = 0L
            var chunks = 0
            val out = StringBuilder()
            try {
                conversation.sendMessageAsync(contents).collect { chunk ->
                    if (chunks == 0) tFirst = System.nanoTime()
                    chunks++
                    out.append(chunk.toString())
                    if (gen.stopAfterLine && hasCompleteLine(out)) throw StopGeneration
                }
            } catch (_: StopGeneration) {
                conversation.cancelProcess()
            }
            val t2 = System.nanoTime()
            if (chunks == 0) tFirst = t2
            GenResult(
                text = out.toString(),
                setupMs = (t1 - t0) / 1_000_000,
                firstTokenMs = (tFirst - t1) / 1_000_000,
                decodeMs = (t2 - tFirst) / 1_000_000,
                chunks = chunks,
                usedImage = imageJpeg != null && visionEnabled,
            )
        }
    }

    /** True once a line with 4+ pipes is followed by a newline (the model tends to keep talking). */
    private fun hasCompleteLine(out: CharSequence): Boolean {
        val nl = out.lastIndexOf('\n')
        if (nl < 0) return false
        return out.substring(0, nl).lineSequence().any { line -> line.count { it == '|' } >= 4 }
    }

    fun close() {
        engine?.close()
        engine = null
        _status.value = Status(State.IDLE)
    }
}

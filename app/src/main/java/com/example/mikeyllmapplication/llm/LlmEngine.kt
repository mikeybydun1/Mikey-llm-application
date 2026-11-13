package com.example.mikeyllmapplication.llm

import android.content.Context
import android.util.Log
import com.example.mikeyllmapplication.device.SystemCollectors
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class BackendChoice(val label: String) {
    AUTO("Auto"), CPU("CPU"), GPU("GPU");
}

sealed interface EngineState {
    data object NotLoaded : EngineState
    data class Loading(val model: ModelSpec, val backend: String) : EngineState
    data class Ready(val model: ModelSpec, val backend: String, val loadMs: Long) : EngineState
    data class Error(val model: ModelSpec?, val message: String) : EngineState
}

/**
 * Thin, coroutine-friendly wrapper around the LiteRT-LM [Engine] (Google AI Edge on-device inference).
 * One engine and one conversation are alive at a time.
 */
class LlmEngine(context: Context) {

    private val app = context.applicationContext
    private val lock = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val inference = Dispatchers.IO.limitedParallelism(1)

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var spec: ModelSpec? = null

    private val _state = MutableStateFlow<EngineState>(EngineState.NotLoaded)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    val currentModel: ModelSpec? get() = spec

    init {
        runCatching { Engine.setNativeMinLogSeverity(LogSeverity.ERROR) }
    }

    suspend fun load(model: ModelSpec, file: File, choice: BackendChoice) = lock.withLock {
        cancel() // let an in-flight answer finish quickly so the single inference thread frees up
        withContext(inference) {
            closeLocked()
            val candidates = when (choice) {
                BackendChoice.CPU -> listOf("CPU")
                BackendChoice.GPU -> listOf("GPU", "CPU")
                // The emulator has no OpenCL, so GPU would just fail slowly there.
                BackendChoice.AUTO -> if (SystemCollectors.isEmulator()) listOf("CPU") else listOf("GPU", "CPU")
            }
            var lastError: Throwable? = null
            for (backendName in candidates) {
                _state.value = EngineState.Loading(model, backendName)
                val t0 = System.currentTimeMillis()
                try {
                    val backend = if (backendName == "GPU") Backend.GPU() else Backend.CPU()
                    val cacheDir = File(app.cacheDir, "litertlm").apply { mkdirs() }
                    val e = Engine(
                        EngineConfig(
                            modelPath = file.absolutePath,
                            backend = backend,
                            maxNumTokens = model.maxTokens,
                            cacheDir = cacheDir.absolutePath,
                        )
                    )
                    e.initialize()
                    engine = e
                    spec = model
                    conversation = e.createConversation(conversationConfig())
                    _state.value = EngineState.Ready(model, backendName, System.currentTimeMillis() - t0)
                    Log.i(TAG, "Loaded ${model.id} on $backendName in ${System.currentTimeMillis() - t0} ms")
                    return@withContext
                } catch (t: Throwable) {
                    Log.w(TAG, "Loading ${model.id} on $backendName failed", t)
                    lastError = t
                    closeLocked()
                }
            }
            _state.value = EngineState.Error(model, friendlyError(lastError))
        }
    }

    private fun conversationConfig() = ConversationConfig(
        systemInstruction = Contents.of(Prompts.SYSTEM),
        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.6),
    )

    /**
     * Sends a user turn and streams back the answer text. Each emitted value is the newly generated
     * text chunk (delta). The flow completes when generation finishes.
     */
    fun send(prompt: String): Flow<String> {
        val conv = conversation ?: throw IllegalStateException("Model not loaded")
        return conv.sendMessageAsync(Message.user(prompt))
            .map { msg -> msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text } }
            .flowOn(inference)
    }

    fun cancel() {
        runCatching { conversation?.cancelProcess() }
    }

    /** Tokens currently held in the conversation's KV cache (used to decide when to reset). */
    suspend fun tokenCount(): Int = withContext(inference) {
        runCatching { conversation?.getTokenCount() ?: 0 }.getOrDefault(0)
    }

    /** Drops chat history inside the model (the UI keeps its own copy). */
    suspend fun resetConversation() = lock.withLock {
        withContext(inference) {
            val e = engine ?: return@withContext
            runCatching { conversation?.close() }
            conversation = e.createConversation(conversationConfig())
        }
    }

    suspend fun unload() = lock.withLock {
        withContext(inference) {
            closeLocked()
            _state.value = EngineState.NotLoaded
        }
    }

    private fun closeLocked() {
        // Stop any in-flight generation before freeing native memory.
        runCatching { conversation?.cancelProcess() }
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
        spec = null
    }

    private fun friendlyError(t: Throwable?): String {
        val raw = t?.message ?: t?.javaClass?.simpleName ?: "unknown error"
        return when {
            t is OutOfMemoryError || raw.contains("memory", ignoreCase = true) ->
                "Not enough memory to load this model. Try a smaller model or give the emulator more RAM. ($raw)"
            else -> raw
        }
    }

    companion object {
        private const val TAG = "LlmEngine"
    }
}

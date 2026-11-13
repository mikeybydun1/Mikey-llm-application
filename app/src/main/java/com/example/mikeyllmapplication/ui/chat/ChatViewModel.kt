package com.example.mikeyllmapplication.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mikeyllmapplication.AppContainer
import com.example.mikeyllmapplication.device.SectionKey
import com.example.mikeyllmapplication.llm.EngineState
import com.example.mikeyllmapplication.llm.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

enum class Role { USER, ASSISTANT, NOTICE }

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val status: String? = null,
    val streaming: Boolean = false,
    val isError: Boolean = false,
    val sectionsUsed: List<SectionKey> = emptyList(),
    val deviceData: String? = null,
    val stats: String? = null,
)

class ChatViewModel(private val c: AppContainer) : ViewModel() {

    private val ids = AtomicLong()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _includeDeviceData = MutableStateFlow(c.settings.includeDeviceData)
    val includeDeviceData: StateFlow<Boolean> = _includeDeviceData.asStateFlow()

    val engineState: StateFlow<EngineState> = c.llm.state

    private var job: Job? = null

    fun setIncludeDeviceData(v: Boolean) {
        c.settings.includeDeviceData = v
        _includeDeviceData.value = v
    }

    fun send(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _generating.value) return
        append(ChatMessage(ids.incrementAndGet(), Role.USER, q))

        val ready = engineState.value as? EngineState.Ready
        if (ready == null) {
            append(
                ChatMessage(
                    ids.incrementAndGet(), Role.NOTICE,
                    when (val s = engineState.value) {
                        is EngineState.Loading -> "The model is still loading — try again in a moment."
                        is EngineState.Error -> "The model failed to load: ${s.message}. Open the Models tab."
                        else -> "No model loaded yet. Open the Models tab to download or load one."
                    },
                    isError = true,
                )
            )
            return
        }

        val replyId = ids.incrementAndGet()
        append(ChatMessage(replyId, Role.ASSISTANT, "", status = "Reading device data…", streaming = true))
        _generating.value = true

        job = viewModelScope.launch {
            try {
                val ctx = if (_includeDeviceData.value) {
                    c.device.buildContext(q, ready.model.contextCharBudget)
                } else null
                edit(replyId) { it.copy(sectionsUsed = ctx?.sections.orEmpty(), deviceData = ctx?.text) }

                val prompt = Prompts.userTurn(ctx?.text, q)
                // Keep the KV cache from overflowing: start a fresh model-side conversation when needed.
                val used = c.llm.tokenCount()
                val needed = prompt.length / 3 + 1024
                if (used > 0 && used + needed > ready.model.maxTokens) {
                    c.llm.resetConversation()
                    insertBefore(
                        replyId,
                        ChatMessage(
                            ids.incrementAndGet(), Role.NOTICE,
                            "Context window full ($used tokens) — the model's memory of earlier messages was reset.",
                        ),
                    )
                }

                edit(replyId) { it.copy(status = "Thinking…") }
                val start = System.currentTimeMillis()
                var firstTokenAt = 0L
                val sb = StringBuilder()
                c.llm.send(prompt).collect { delta ->
                    if (delta.isEmpty()) return@collect
                    if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                    sb.append(delta)
                    val text = sb.toString()
                    edit(replyId) { it.copy(text = text, status = null) }
                }
                val end = System.currentTimeMillis()
                val genSec = (end - (if (firstTokenAt > 0) firstTokenAt else start)) / 1000.0
                val approxTokens = sb.length / 4.0
                val stats = buildString {
                    append("${ready.model.displayName.substringBefore(" (")} · ${ready.backend}")
                    if (firstTokenAt > 0) append(" · first token ${"%.1f".format((firstTokenAt - start) / 1000.0)} s")
                    if (genSec > 0.2) append(" · ~${"%.1f".format(approxTokens / genSec)} tok/s")
                }
                edit(replyId) {
                    it.copy(
                        text = sb.toString().trim().ifEmpty { "(no answer generated)" },
                        streaming = false, status = null, stats = stats,
                    )
                }
            } catch (e: CancellationException) {
                edit(replyId) { it.copy(streaming = false, status = null, text = it.text.ifEmpty { "(stopped)" }) }
                throw e
            } catch (t: Throwable) {
                edit(replyId) {
                    it.copy(
                        streaming = false, status = null, isError = true,
                        text = (it.text + "\n\n⚠️ Generation failed: ${t.message ?: t.javaClass.simpleName}").trim(),
                    )
                }
            } finally {
                _generating.value = false
            }
        }
    }

    fun stop() {
        c.llm.cancel()
        job?.cancel()
    }

    fun newChat() {
        stop()
        _messages.value = emptyList()
        viewModelScope.launch { c.llm.resetConversation() }
    }

    private fun append(m: ChatMessage) = _messages.update { it + m }

    private fun insertBefore(id: Long, m: ChatMessage) = _messages.update { list ->
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) list + m else list.toMutableList().apply { add(i, m) }
    }

    private fun edit(id: Long, f: (ChatMessage) -> ChatMessage) = _messages.update { list ->
        list.map { if (it.id == id) f(it) else it }
    }

    override fun onCleared() {
        c.llm.cancel()
    }
}

package com.example.mikeyllmapplication.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mikeyllmapplication.llm.EngineState
import com.example.mikeyllmapplication.ui.common.MarkdownText

private val suggestions = listOf(
    "Summarize everything about my phone",
    "How good is my Wi-Fi signal and what channel am I on?",
    "Scan for nearby BLE devices",
    "Which cellular network and band am I on?",
    "What Bluetooth LE features does my phone support?",
    "What are my IP address and DNS servers?",
    "Explain my battery status",
    "What's the difference between 5G NSA and SA?",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenModels: () -> Unit, modifier: Modifier = Modifier) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val includeData by vm.includeDeviceData.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var shownData by remember { mutableStateOf<ChatMessage?>(null) }
    val listState = rememberLazyListState()

    val lastText = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(messages.size, lastText) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE / 2)
    }

    fun submit(text: String) {
        vm.send(text)
        input = ""
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Mikey LLM")
                    Text(
                        engineLabel(engine), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            actions = {
                IconToggleButton(checked = includeData, onCheckedChange = vm::setIncludeDeviceData) {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = if (includeData) "Device data on" else "Device data off",
                        tint = if (includeData) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
                IconButton(onClick = vm::newChat) { Icon(Icons.Default.AddComment, "New chat") }
            },
        )

        Box(Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                EmptyState(engine, includeData, onOpenModels, onSuggestion = ::submit)
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(messages, key = { it.id }) { m -> MessageBubble(m, onShowData = { shownData = m }) }
                }
            }
        }

        if (messages.isNotEmpty() && !generating) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                suggestions.take(5).forEach { s -> SuggestionChip(onClick = { submit(s) }, label = { Text(s, maxLines = 1) }) }
            }
        }

        Row(
            Modifier.fillMaxWidth().imePadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).heightIn(max = 140.dp),
                placeholder = { Text("Ask about BLE, Wi-Fi, cellular, battery…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (!generating) submit(input) }),
                shape = RoundedCornerShape(24.dp),
            )
            Spacer(Modifier.size(8.dp))
            if (generating) {
                FilledIconButton(onClick = vm::stop) { Icon(Icons.Default.Stop, "Stop") }
            } else {
                FilledIconButton(onClick = { submit(input) }, enabled = input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }

    shownData?.let { m ->
        AlertDialog(
            onDismissRequest = { shownData = null },
            confirmButton = { TextButton(onClick = { shownData = null }) { Text("Close") } },
            title = { Text("Device data sent to the model") },
            text = {
                SelectionContainer {
                    Text(
                        m.deviceData ?: "", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            },
        )
    }
}

private fun engineLabel(s: EngineState) = when (s) {
    is EngineState.NotLoaded -> "No model loaded"
    is EngineState.Loading -> "Loading ${s.model.displayName} on ${s.backend}…"
    is EngineState.Ready -> "${s.model.displayName} · ${s.backend} · on-device"
    is EngineState.Error -> "Model error — see Models tab"
}

@Composable
private fun EmptyState(
    engine: EngineState,
    includeData: Boolean,
    onOpenModels: () -> Unit,
    onSuggestion: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ask me anything about this device", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Answers are generated on this phone by a Google AI Edge (LiteRT-LM) model. " +
                if (includeData) "Live readings from Wi-Fi, Bluetooth/BLE, cellular, battery, sensors and more are " +
                    "attached to each question automatically." else "Device data is currently OFF (phone icon above).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (engine) {
            is EngineState.Ready -> Unit
            is EngineState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Loading ${engine.model.displayName}… (first load can take a minute)")
            }
            else -> Surface(
                color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        if (engine is EngineState.Error) "Model failed to load: ${engine.message}"
                        else "First, download an on-device model (one time).",
                    )
                    TextButton(onClick = onOpenModels) { Text("Open Models") }
                }
            }
        }
        Text("Try:", style = MaterialTheme.typography.titleSmall)
        suggestions.forEach { s ->
            AssistChip(onClick = { onSuggestion(s) }, label = { Text(s) })
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage, onShowData: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    when (m.role) {
        Role.NOTICE -> Text(
            m.text,
            style = MaterialTheme.typography.labelMedium,
            color = if (m.isError) colors.error else colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )

        Role.USER -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                m.text,
                color = colors.onPrimaryContainer,
                modifier = Modifier.widthIn(max = 320.dp)
                    .background(colors.primaryContainer, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        Role.ASSISTANT -> Column(
            Modifier.fillMaxWidth()
                .background(colors.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (m.status != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(m.status, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
            if (m.text.isNotEmpty()) {
                SelectionContainer {
                    MarkdownText(m.text + if (m.streaming) " ▍" else "", color = if (m.isError) colors.error else colors.onSurface)
                }
            }
            if (m.sectionsUsed.isNotEmpty() || m.stats != null) {
                Spacer(Modifier.size(6.dp))
                if (m.sectionsUsed.isNotEmpty()) {
                    TextButton(onClick = onShowData, contentPadding = PaddingValues(0.dp)) {
                        Text(
                            "📱 Used: " + m.sectionsUsed.joinToString { it.title.substringBefore(" (") },
                            style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                m.stats?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

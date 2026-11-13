package com.example.mikeyllmapplication.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mikeyllmapplication.device.Format
import com.example.mikeyllmapplication.llm.BackendChoice
import com.example.mikeyllmapplication.llm.DownloadState
import com.example.mikeyllmapplication.llm.EngineState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: ModelsViewModel, modifier: Modifier = Modifier) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val backend by vm.backend.collectAsStateWithLifecycle()
    val token by vm.hfToken.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("On-device models") },
            actions = { IconButton(onClick = vm::rescan) { Icon(Icons.Default.Refresh, "Rescan for model files") } },
        )
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { EngineCard(engine) }
            item {
                Column {
                    Text("Inference backend", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.size(6.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        BackendChoice.entries.forEachIndexed { i, b ->
                            SegmentedButton(
                                selected = b == backend,
                                onClick = { vm.setBackend(b) },
                                shape = SegmentedButtonDefaults.itemShape(i, BackendChoice.entries.size),
                            ) { Text(b.label) }
                        }
                    }
                    Text(
                        "Auto = CPU on the emulator, GPU (with CPU fallback) on real phones.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(rows, key = { it.spec.id }) { r ->
                ModelCard(r, onDownload = { vm.download(r.spec) }, onPause = { vm.pause(r.spec) },
                    onLoad = { vm.load(r.spec) }, onDelete = { vm.delete(r.spec) })
            }
            item {
                OutlinedTextField(
                    value = token, onValueChange = vm::setHfToken, singleLine = true,
                    label = { Text("Hugging Face token (only for gated models)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Install a model with adb instead", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Copy a .litertlm file from your computer, then tap refresh above:",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        SelectionContainer {
                            Text(
                                "adb push gemma-4-E2B-it.litertlm \\\n  ${vm.downloadDir}/",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineCard(engine: EngineState) {
    Card {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            when (engine) {
                is EngineState.Loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                is EngineState.Ready -> Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                else -> Unit
            }
            Spacer(Modifier.size(12.dp))
            Column {
                Text(
                    when (engine) {
                        is EngineState.NotLoaded -> "No model loaded"
                        is EngineState.Loading -> "Loading ${engine.model.displayName} (${engine.backend})…"
                        is EngineState.Ready -> "${engine.model.displayName} ready"
                        is EngineState.Error -> "Load failed"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    when (engine) {
                        is EngineState.Ready -> "Backend ${engine.backend} · loaded in ${"%.1f".format(engine.loadMs / 1000.0)} s"
                        is EngineState.Error -> engine.message
                        is EngineState.Loading -> "Large models can take up to a minute the first time."
                        is EngineState.NotLoaded -> "Download a model below. It is stored on the device; inference never leaves it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (engine is EngineState.Error) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    r: ModelRow,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.spec.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (r.active) Text("ACTIVE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "${Format.bytes(r.spec.sizeBytes)} · ${r.spec.maxTokens} token context · ${r.spec.minRamGb}+ GB RAM",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(r.spec.description, style = MaterialTheme.typography.bodySmall)

            val dl = r.download
            if (dl is DownloadState.Running) {
                if (dl.status != null && dl.downloaded == 0L) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { dl.fraction }, modifier = Modifier.fillMaxWidth())
                }
                Text(
                    "${Format.bytes(dl.downloaded)} / ${Format.bytes(dl.total)}" +
                        if (dl.bytesPerSec > 0) " · ${Format.bytes(dl.bytesPerSec)}/s" else "",
                    style = MaterialTheme.typography.labelSmall,
                )
                dl.status?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (dl is DownloadState.Failed) {
                Text(dl.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            r.installedPath?.let {
                Text("Installed: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    r.installedPath != null -> {
                        Button(onClick = onLoad, enabled = !r.active) { Text(if (r.active) "Loaded" else "Load") }
                        if (r.deletable) TextButton(onClick = onDelete) { Text("Delete") }
                    }
                    r.downloading -> OutlinedButton(onClick = onPause) { Text("Pause") }
                    else -> Button(onClick = onDownload) {
                        Text(if (r.partialBytes > 0) "Resume (${Format.bytes(r.partialBytes)} done)" else "Download")
                    }
                }
            }
        }
    }
}

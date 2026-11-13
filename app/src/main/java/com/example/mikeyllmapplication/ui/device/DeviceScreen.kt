package com.example.mikeyllmapplication.ui.device

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkCell
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mikeyllmapplication.device.DeviceSection
import com.example.mikeyllmapplication.device.Entry
import com.example.mikeyllmapplication.device.SectionKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    vm: DeviceViewModel,
    missingPermissions: List<String>,
    onGrantPermissions: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections by vm.sections.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    LaunchedEffect(message) {
        message?.let { onMessage(it); vm.consumeMessage() }
    }
    // Re-read after the user grants permissions.
    LaunchedEffect(missingPermissions.size) { vm.refresh() }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Device data") },
            actions = {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                IconButton(onClick = { vm.refresh(triggerWifiScan = true) }) { Icon(Icons.Default.Refresh, "Refresh") }
            },
        )
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (missingPermissions.isNotEmpty()) {
                item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text("Some data is hidden", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Not granted: " + missingPermissions.joinToString { it.substringAfterLast('.') },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.size(8.dp))
                            Button(onClick = onGrantPermissions) { Text("Grant permissions") }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = vm::scanBle, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
                    if (scanning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("Scanning for BLE devices…")
                    } else {
                        Icon(Icons.Default.BluetoothSearching, null)
                        Spacer(Modifier.size(8.dp))
                        Text("Run a 6-second BLE scan")
                    }
                }
            }
            items(sections, key = { it.key }) { s -> SectionCard(s) }
        }
    }
}

@Composable
private fun SectionCard(s: DeviceSection) {
    var expanded by rememberSaveable(s.key) {
        mutableStateOf(s.key in setOf(SectionKey.WIFI, SectionKey.BLUETOOTH, SectionKey.CELLULAR))
    }
    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(iconFor(s.key), null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(12.dp))
            Text(s.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        if (expanded) {
            SelectionContainer {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    s.entries.forEach { EntryRow(it, 0) }
                    s.notes.forEach {
                        Text(
                            "ⓘ $it", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(e: Entry, depth: Int) {
    Column(Modifier.padding(start = (depth * 12).dp, top = 4.dp)) {
        Text(e.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (e.value.isNotEmpty()) Text(e.value, style = MaterialTheme.typography.bodyMedium)
        e.children.forEach { EntryRow(it, depth + 1) }
    }
}

@Suppress("DEPRECATION")
private fun iconFor(k: SectionKey): ImageVector = when (k) {
    SectionKey.SYSTEM -> Icons.Default.Smartphone
    SectionKey.HARDWARE -> Icons.Default.Memory
    SectionKey.DISPLAY -> Icons.Default.Info
    SectionKey.BATTERY -> Icons.Default.BatteryFull
    SectionKey.NETWORK -> Icons.Default.Public
    SectionKey.WIFI -> Icons.Default.Wifi
    SectionKey.BLUETOOTH -> Icons.Default.Bluetooth
    SectionKey.CELLULAR -> Icons.Default.NetworkCell
    SectionKey.LOCATION -> Icons.Default.LocationOn
    SectionKey.SENSORS -> Icons.Default.Sensors
    SectionKey.AUDIO -> Icons.Default.VolumeUp
    SectionKey.CAMERA -> Icons.Default.CameraAlt
    SectionKey.FEATURES -> Icons.Default.Nfc
    SectionKey.SECURITY -> Icons.Default.Security
    SectionKey.APPS -> Icons.Default.Apps
    SectionKey.PERMISSIONS -> Icons.Default.VerifiedUser
}

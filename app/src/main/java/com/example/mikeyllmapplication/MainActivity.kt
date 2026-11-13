package com.example.mikeyllmapplication

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mikeyllmapplication.device.Permissions
import com.example.mikeyllmapplication.ui.chat.ChatScreen
import com.example.mikeyllmapplication.ui.chat.ChatViewModel
import com.example.mikeyllmapplication.ui.device.DeviceScreen
import com.example.mikeyllmapplication.ui.device.DeviceViewModel
import com.example.mikeyllmapplication.ui.models.ModelsScreen
import com.example.mikeyllmapplication.ui.models.ModelsViewModel
import com.example.mikeyllmapplication.ui.theme.MikeyLLMApplicationTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MikeyApp).container
        setContent {
            MikeyLLMApplicationTheme {
                MikeyAppUi(container)
            }
        }
    }
}

private enum class Tab(val label: String) { CHAT("Chat"), DEVICE("Device"), MODELS("Models") }

@Composable
@Suppress("DEPRECATION")
private fun MikeyAppUi(container: AppContainer) {
    val context = LocalContext.current
    val chatVm: ChatViewModel = viewModel { ChatViewModel(container) }
    val deviceVm: DeviceViewModel = viewModel { DeviceViewModel(container) }
    val modelsVm: ModelsViewModel = viewModel { ModelsViewModel(container) }

    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var permissionsTick by remember { mutableIntStateOf(0) }
    val missing = remember(permissionsTick) { Permissions.missing(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsTick++
        if (Permissions.missing(context).isNotEmpty()) {
            scope.launch {
                val r = snackbar.showSnackbar(
                    "Some permissions were denied; related data will be hidden.",
                    actionLabel = "Settings", duration = SnackbarDuration.Long,
                )
                if (r == SnackbarResult.ActionPerformed) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    )
                }
            }
        }
    }
    // Ask once on first launch; afterwards the Device tab offers a button.
    LaunchedEffect(Unit) {
        if (!container.settings.askedPermissions && missing.isNotEmpty()) {
            container.settings.askedPermissions = true
            launcher.launch(Permissions.runtimeNames)
        }
    }
    // Pick up permissions changed from system Settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionsTick++ }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.CHAT -> Icons.Default.Chat
                                    Tab.DEVICE -> Icons.Default.PhoneAndroid
                                    Tab.MODELS -> Icons.Default.Download
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding).consumeWindowInsets(padding)
        when (tab) {
            Tab.CHAT -> ChatScreen(chatVm, onOpenModels = { tab = Tab.MODELS }, modifier = m)
            Tab.DEVICE -> DeviceScreen(
                deviceVm, missing,
                onGrantPermissions = { launcher.launch(Permissions.runtimeNames) },
                onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                modifier = m,
            )
            Tab.MODELS -> ModelsScreen(modelsVm, modifier = m)
        }
    }
}

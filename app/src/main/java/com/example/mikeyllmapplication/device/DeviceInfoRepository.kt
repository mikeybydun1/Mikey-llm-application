package com.example.mikeyllmapplication.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Single entry point for reading anything about the device. All methods are main-safe. */
class DeviceInfoRepository(context: Context) {

    private val app = context.applicationContext
    private val system = SystemCollectors(app)
    private val net = NetworkCollectors(app)
    private val cellular = CellularCollector(app)
    val bluetooth = BluetoothCollector(app)

    suspend fun collect(key: SectionKey): DeviceSection = withContext(Dispatchers.IO) {
        try {
            when (key) {
                SectionKey.SYSTEM -> system.system()
                SectionKey.HARDWARE -> system.hardware()
                SectionKey.DISPLAY -> system.display()
                SectionKey.BATTERY -> system.battery()
                SectionKey.NETWORK -> net.network()
                SectionKey.WIFI -> net.wifi()
                SectionKey.BLUETOOTH -> bluetooth.bluetooth()
                SectionKey.CELLULAR -> cellular.cellular()
                SectionKey.LOCATION -> system.location()
                SectionKey.SENSORS -> system.sensors()
                SectionKey.AUDIO -> system.audio()
                SectionKey.CAMERA -> system.cameras()
                SectionKey.FEATURES -> system.features()
                SectionKey.SECURITY -> system.security()
                SectionKey.APPS -> system.apps()
                SectionKey.PERMISSIONS -> system.permissions()
            }
        } catch (t: Throwable) {
            DeviceSection(key, emptyList(), listOf("Failed to read: ${t.javaClass.simpleName}: ${t.message}"))
        }
    }

    suspend fun collect(keys: Collection<SectionKey>): List<DeviceSection> = coroutineScope {
        keys.sortedBy { it.ordinal }.map { async { collect(it) } }.awaitAll()
    }

    suspend fun collectAll(): List<DeviceSection> = collect(SectionKey.entries)

    fun requestWifiScan() = net.requestWifiScan()

    suspend fun scanBle(durationMs: Long = 5_000) = bluetooth.scanBle(durationMs)

    /** One line that is always included so the model knows what it is running on. */
    fun headline(): String {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val transport = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val battery = app.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
            "${if (SystemCollectors.isEmulator()) " [emulator]" else ""}, connected via $transport, " +
            "battery $battery%, local time $now"
    }

    /** Builds the device-data block that is prepended to the user's question. */
    suspend fun buildContext(question: String, maxChars: Int): ContextResult {
        val route = TopicRouter.route(question)
        if (route.wantsWifiScan) requestWifiScan()
        if (route.wantsBleScan) scanBle(4_000)
        val sections = collect(route.sections)
        val header = "DEVICE: ${headline()}\n"
        // If we are over budget, shrink lists inside sections before dropping whole sections.
        var text = header + sections.joinToString("\n") { it.toPromptText(maxChildren = 12) }
        if (text.length > maxChars) {
            text = header + sections.joinToString("\n") { it.toPromptText(maxChildren = 4) }
        }
        if (text.length > maxChars) text = text.take(maxChars) + "\n…(truncated)"
        return ContextResult(text, sections.map { it.key })
    }

    data class ContextResult(val text: String, val sections: List<SectionKey>)
}

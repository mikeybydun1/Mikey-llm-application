package com.example.mikeyllmapplication.device

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.nfc.NfcManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Collectors for everything except radios (see NetworkCollectors, BluetoothCollector, CellularCollector). */
class SystemCollectors(private val context: Context) {

    fun system() = section(SectionKey.SYSTEM) {
        put("Manufacturer / Brand") { "${Build.MANUFACTURER} / ${Build.BRAND}" }
        put("Model") { Build.MODEL }
        put("Device / Product") { "${Build.DEVICE} / ${Build.PRODUCT}" }
        put("Hardware / Board") { "${Build.HARDWARE} / ${Build.BOARD}" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            put("SoC") { "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" }
        }
        put("Android version") { "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" }
        put("Security patch") { Build.VERSION.SECURITY_PATCH }
        put("Build ID / type") { "${Build.ID} / ${Build.TYPE}" }
        put("Build fingerprint") { Build.FINGERPRINT }
        put("Kernel") { System.getProperty("os.version") }
        put("Supported ABIs") { Build.SUPPORTED_ABIS.joinToString() }
        put("Is emulator") { Format.bool(isEmulator()) }
        put("Uptime (since boot)") { Format.duration(SystemClock.elapsedRealtime()) }
        put("Local time") {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
        }
        put("Time zone") { TimeZone.getDefault().id }
        put("Locale") { Locale.getDefault().toLanguageTag() }
    }

    fun hardware() = section(SectionKey.HARDWARE) {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        put("RAM total") { Format.bytes(mi.totalMem) }
        put("RAM available") { "${Format.bytes(mi.availMem)} (${Format.percent(mi.availMem, mi.totalMem)} free)" }
        put("Low-memory state") { Format.bool(mi.lowMemory) }
        put("Low-RAM device") { Format.bool(am.isLowRamDevice) }
        put("App memory class") { "${am.memoryClass} MB (large heap ${am.largeMemoryClass} MB)" }
        put("CPU cores") { Runtime.getRuntime().availableProcessors() }
        put("CPU max frequencies") {
            (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }
                    ?.readText()?.trim()?.toLongOrNull()?.let { "${it / 1000} MHz" }
            }.distinct().joinToString().ifEmpty { null }
        }
        put("CPU hardware") {
            File("/proc/cpuinfo").takeIf { it.canRead() }?.readLines()
                ?.firstOrNull { it.startsWith("Hardware") || it.startsWith("model name") }
                ?.substringAfter(':')?.trim()
        }
        val data = StatFs(Environment.getDataDirectory().path)
        put("Internal storage") {
            "${Format.bytes(data.availableBytes)} free of ${Format.bytes(data.totalBytes)}"
        }
        context.getExternalFilesDir(null)?.let { ext ->
            val s = StatFs(ext.path)
            put("Shared storage") { "${Format.bytes(s.availableBytes)} free of ${Format.bytes(s.totalBytes)}" }
        }
        put("Thermal status") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                when (context.getSystemService(PowerManager::class.java).currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> "none (cool)"
                    PowerManager.THERMAL_STATUS_LIGHT -> "light"
                    PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                    PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                    PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                    else -> "unknown"
                }
            } else null
        }
    }

    @Suppress("DEPRECATION")
    fun display() = section(SectionKey.DISPLAY) {
        val dm = context.resources.displayMetrics
        put("Resolution") { "${dm.widthPixels} x ${dm.heightPixels} px" }
        put("Density") { "${dm.densityDpi} dpi (scale ${dm.density})" }
        put("Font scale") { context.resources.configuration.fontScale }
        val display = context.getSystemService(WindowManager::class.java).defaultDisplay
        put("Refresh rate") { "${display.refreshRate.toInt()} Hz" }
        put("Supported refresh rates") {
            display.supportedModes.map { it.refreshRate.toInt() }.distinct().sorted().joinToString { "$it Hz" }
        }
        put("HDR capable") {
            display.hdrCapabilities?.supportedHdrTypes?.isNotEmpty()?.let { Format.bool(it) }
        }
        put("Dark mode") {
            val night = context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            Format.bool(night == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        }
        put("Screen brightness (0-255)") {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }
    }

    fun battery() = section(SectionKey.BATTERY) {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = context.getSystemService(BatteryManager::class.java)
        if (intent != null) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            put("Level") { if (level >= 0) "${level * 100 / scale}%" else null }
            put("Status") {
                when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                    BatteryManager.BATTERY_STATUS_FULL -> "full"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
                    else -> "unknown"
                }
            }
            put("Power source") {
                when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                    0 -> "battery (unplugged)"
                    else -> "other"
                }
            }
            put("Health") {
                when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
                    BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
                    else -> "unknown"
                }
            }
            put("Temperature") {
                intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                    .takeIf { it != Int.MIN_VALUE }?.let { "${it / 10.0} °C" }
            }
            put("Voltage") {
                intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 }?.let { "$it mV" }
            }
            put("Technology") { intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                put("Charge cycles") {
                    intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it >= 0 }
                }
            }
        }
        put("Current now") {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                .takeIf { it != Int.MIN_VALUE && it != 0 }?.let { "${it / 1000} mA" }
        }
        put("Remaining charge") {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                .takeIf { it > 0 }?.let { "${it / 1000} mAh" }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            put("Time to full charge") {
                bm.computeChargeTimeRemaining().takeIf { it > 0 }?.let { Format.duration(it) }
            }
        }
        val pm = context.getSystemService(PowerManager::class.java)
        put("Battery saver") { Format.bool(pm.isPowerSaveMode) }
        put("Ignoring battery optimizations (this app)") {
            Format.bool(pm.isIgnoringBatteryOptimizations(context.packageName))
        }
        put("Device idle (Doze)") { Format.bool(pm.isDeviceIdleMode) }
        put("Screen interactive") { Format.bool(pm.isInteractive) }
    }

    fun sensors() = section(SectionKey.SENSORS) {
        val sm = context.getSystemService(SensorManager::class.java)
        val all = sm.getSensorList(Sensor.TYPE_ALL)
        put("Sensor count") { all.size }
        group("Sensors", children = all.map { s ->
            Entry(
                s.name,
                "${s.stringType.substringAfterLast('.')} · vendor ${s.vendor} · " +
                    "range ${s.maximumRange} · ${s.power} mA" + if (s.isWakeUpSensor) " · wake-up" else ""
            )
        })
    }

    fun audio() = section(SectionKey.AUDIO) {
        val am = context.getSystemService(AudioManager::class.java)
        put("Ringer mode") {
            when (am.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> "normal"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                AudioManager.RINGER_MODE_SILENT -> "silent"
                else -> "unknown"
            }
        }
        put("Audio mode") {
            when (am.mode) {
                AudioManager.MODE_NORMAL -> "normal"
                AudioManager.MODE_IN_CALL -> "in call"
                AudioManager.MODE_IN_COMMUNICATION -> "in VoIP/communication"
                AudioManager.MODE_RINGTONE -> "ringing"
                else -> "other (${am.mode})"
            }
        }
        put("Music playing") { Format.bool(am.isMusicActive) }
        put("Microphone muted") { Format.bool(am.isMicrophoneMute) }
        listOf(
            "Media" to AudioManager.STREAM_MUSIC,
            "Ring" to AudioManager.STREAM_RING,
            "Notification" to AudioManager.STREAM_NOTIFICATION,
            "Alarm" to AudioManager.STREAM_ALARM,
            "Call" to AudioManager.STREAM_VOICE_CALL,
        ).forEach { (name, stream) ->
            put("$name volume") { "${am.getStreamVolume(stream)}/${am.getStreamMaxVolume(stream)}" }
        }
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val ins = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        group("Output devices", outs.size.toString(), outs.map { Entry(audioType(it.type), it.productName.toString()) })
        group("Input devices", ins.size.toString(), ins.map { Entry(audioType(it.type), it.productName.toString()) })
    }

    private fun audioType(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "built-in speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in mic"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO (headset)"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Audio headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE Audio speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB audio"
        AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> "type $t"
    }

    fun cameras() = section(SectionKey.CAMERA) {
        val cm = context.getSystemService(CameraManager::class.java)
        val ids = cm.cameraIdList
        put("Camera count") { ids.size }
        ids.forEach { id ->
            val c = cm.getCameraCharacteristics(id)
            group("Camera $id", children = entries {
                put("Facing") {
                    when (c.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "front"
                        CameraCharacteristics.LENS_FACING_BACK -> "back"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                        else -> "unknown"
                    }
                }
                put("Sensor") {
                    c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let {
                        "${it.width}x${it.height} (${"%.1f".format(Locale.US, it.width * it.height / 1e6)} MP)"
                    }
                }
                put("Flash") { c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)?.let { Format.bool(it) } }
                put("Focal lengths") {
                    c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString { "$it mm" }
                }
            })
        }
    }

    fun location() = section(SectionKey.LOCATION) {
        val lm = context.getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            put("Location services enabled") { Format.bool(lm.isLocationEnabled) }
            put("GNSS hardware") { lm.gnssHardwareModelName }
            put("GNSS hardware year") { lm.gnssYearOfHardware.takeIf { it > 0 } }
        }
        put("Providers") {
            lm.allProviders.joinToString { "$it (${if (lm.isProviderEnabled(it)) "on" else "off"})" }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            put("GNSS capabilities") {
                val caps = lm.gnssCapabilities
                buildList {
                    if (caps.hasMeasurements()) add("raw measurements")
                    if (caps.hasNavigationMessages()) add("navigation messages")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && caps.hasAntennaInfo()) add("antenna info")
                }.joinToString().ifEmpty { "basic" }
            }
        }
        if (Permissions.hasLocation(context)) {
            @SuppressLint("MissingPermission")
            val last = lm.allProviders.mapNotNull { p ->
                try {
                    lm.getLastKnownLocation(p)
                } catch (e: Exception) {
                    null
                }
            }.maxByOrNull { it.time }
            if (last != null) {
                put("Last known location") {
                    "%.5f, %.5f (±%.0f m, via %s, %s ago)".format(
                        Locale.US, last.latitude, last.longitude, last.accuracy, last.provider,
                        Format.duration(System.currentTimeMillis() - last.time)
                    )
                }
                if (last.hasAltitude()) put("Altitude") { "%.0f m".format(Locale.US, last.altitude) }
                if (last.hasSpeed()) put("Speed") { "%.1f m/s".format(Locale.US, last.speed) }
            } else {
                note("No cached location fix yet (open Maps once or set a location in the emulator's Extended Controls).")
            }
        } else {
            note("Location permission not granted, so no coordinates are available.")
        }
    }

    fun features() = section(SectionKey.FEATURES) {
        val pm = context.packageManager
        val nfc = context.getSystemService(NfcManager::class.java)?.defaultAdapter
        put("NFC") { if (nfc == null) "not present" else if (nfc.isEnabled) "present, enabled" else "present, disabled" }
        val features = listOf(
            PackageManager.FEATURE_BLUETOOTH to "Bluetooth Classic",
            PackageManager.FEATURE_BLUETOOTH_LE to "Bluetooth Low Energy",
            PackageManager.FEATURE_WIFI to "Wi-Fi",
            PackageManager.FEATURE_WIFI_DIRECT to "Wi-Fi Direct (P2P)",
            PackageManager.FEATURE_WIFI_AWARE to "Wi-Fi Aware (NAN)",
            PackageManager.FEATURE_WIFI_RTT to "Wi-Fi RTT (802.11mc ranging)",
            PackageManager.FEATURE_TELEPHONY to "Telephony",
            "android.hardware.telephony.radio.access" to "Cellular radio access",
            "android.hardware.telephony.euicc" to "eSIM (eUICC)",
            "android.hardware.uwb" to "Ultra-Wideband (UWB)",
            PackageManager.FEATURE_NFC to "NFC",
            PackageManager.FEATURE_NFC_HOST_CARD_EMULATION to "NFC host card emulation",
            PackageManager.FEATURE_LOCATION_GPS to "GPS",
            PackageManager.FEATURE_FINGERPRINT to "Fingerprint",
            PackageManager.FEATURE_FACE to "Face unlock",
            PackageManager.FEATURE_CAMERA_ANY to "Camera",
            PackageManager.FEATURE_USB_HOST to "USB host (OTG)",
            PackageManager.FEATURE_CONSUMER_IR to "IR blaster",
            PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL to "Vulkan",
            PackageManager.FEATURE_STRONGBOX_KEYSTORE to "StrongBox keystore",
        )
        val (yes, no) = features.partition { pm.hasSystemFeature(it.first) }
        put("Supported") { yes.joinToString { it.second } }
        put("Not supported") { no.joinToString { it.second } }
    }

    fun security() = section(SectionKey.SECURITY) {
        val km = context.getSystemService(KeyguardManager::class.java)
        put("Screen lock set") { Format.bool(km.isDeviceSecure) }
        put("Device currently locked") { Format.bool(km.isKeyguardLocked) }
        val cr = context.contentResolver
        put("Developer options") {
            Format.bool(Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1)
        }
        put("USB debugging (ADB)") { Format.bool(Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1) }
        put("Airplane mode") { Format.bool(Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) }
        put("Automatic time") { Format.bool(Settings.Global.getInt(cr, Settings.Global.AUTO_TIME, 0) == 1) }
        put("Device name") { Settings.Global.getString(cr, Settings.Global.DEVICE_NAME) }
        put("Install from unknown sources (this app)") {
            Format.bool(context.packageManager.canRequestPackageInstalls())
        }
        put("Encryption") {
            @Suppress("DEPRECATION")
            when (context.getSystemService(android.app.admin.DevicePolicyManager::class.java).storageEncryptionStatus) {
                android.app.admin.DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER -> "file-based (per user)"
                android.app.admin.DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE -> "active"
                android.app.admin.DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE -> "inactive"
                else -> "unsupported/unknown"
            }
        }
    }

    fun apps() = section(SectionKey.APPS) {
        val pm = context.packageManager
        val pkgs: List<PackageInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.getInstalledPackages(0)
        }
        val user = pkgs.filter { (it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM == 0 }
        put("Total packages") { pkgs.size }
        put("User-installed apps") { user.size }
        put("System packages") { pkgs.size - user.size }
        group("User apps (most recently updated first)", children = user.sortedByDescending { it.lastUpdateTime }
            .map { p ->
                val label = p.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: p.packageName
                Entry(label, "${p.packageName} v${p.versionName ?: "?"}")
            })
    }

    fun permissions() = section(SectionKey.PERMISSIONS) {
        Permissions.runtime.forEach { (perm, why) ->
            put(Permissions.shortName(perm)) {
                (if (Permissions.has(context, perm)) "GRANTED" else "DENIED") + " — $why"
            }
        }
    }

    companion object {
        fun isEmulator(): Boolean = Build.FINGERPRINT.contains("generic") ||
            Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone") ||
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
            Build.PRODUCT.contains("sdk")
    }
}

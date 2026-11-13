package com.example.mikeyllmapplication.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** One device seen during a BLE scan. */
data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val txPower: Int?,
    val connectable: Boolean?,
    val serviceUuids: List<String>,
    val manufacturerIds: List<Int>,
    val lastSeenMs: Long,
)

@SuppressLint("MissingPermission") // every call site checks Permissions.* first
class BluetoothCollector(private val context: Context) {

    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val scanMutex = Mutex()

    @Volatile
    var lastScan: List<BleDevice> = emptyList()
        private set

    @Volatile
    var lastScanAtMs: Long = 0
        private set

    @Volatile
    var lastScanError: String? = null
        private set

    fun bluetooth() = section(SectionKey.BLUETOOTH) {
        val a = adapter
        if (a == null) {
            put("Bluetooth") { "not supported on this device" }
            return@section
        }
        val canConnect = Permissions.hasBtConnect(context)
        put("Adapter state") {
            when (a.state) {
                BluetoothAdapter.STATE_ON -> "on"
                BluetoothAdapter.STATE_OFF -> "off"
                BluetoothAdapter.STATE_TURNING_ON -> "turning on"
                BluetoothAdapter.STATE_TURNING_OFF -> "turning off"
                else -> "unknown"
            }
        }
        if (canConnect) {
            put("Adapter name") { a.name }
            put("Scan mode") {
                when (a.scanMode) {
                    BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> "discoverable"
                    BluetoothAdapter.SCAN_MODE_CONNECTABLE -> "connectable, not discoverable"
                    else -> "not connectable"
                }
            }
            put("Classic discovery running") { Format.bool(a.isDiscovering) }
        } else {
            note("BLUETOOTH_CONNECT permission not granted: adapter name and paired devices are hidden.")
        }
        put("BLE supported") {
            Format.bool(context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE))
        }
        group("BLE controller capabilities", children = entries {
            put("LE 2M PHY (high speed)") { Format.bool(a.isLe2MPhySupported) }
            put("LE Coded PHY (long range)") { Format.bool(a.isLeCodedPhySupported) }
            put("Extended advertising (BT 5)") { Format.bool(a.isLeExtendedAdvertisingSupported) }
            put("Periodic advertising") { Format.bool(a.isLePeriodicAdvertisingSupported) }
            put("Max advertising data length") { "${a.leMaximumAdvertisingDataLength} bytes" }
            put("Multiple advertisement") { Format.bool(a.isMultipleAdvertisementSupported) }
            put("Offloaded scan filtering") { Format.bool(a.isOffloadedFilteringSupported) }
            put("Offloaded scan batching") { Format.bool(a.isOffloadedScanBatchingSupported) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                put("LE Audio") { featureStatus(a.isLeAudioSupported) }
                put("LE Audio broadcast source") { featureStatus(a.isLeAudioBroadcastSourceSupported) }
            }
        })
        if (canConnect && a.isEnabled) {
            val bonded = runCatching { a.bondedDevices.toList() }.getOrDefault(emptyList())
            group("Paired (bonded) devices", bonded.size.toString(), bonded.map { d -> deviceEntry(d) })
            val gatt = runCatching { manager!!.getConnectedDevices(BluetoothProfile.GATT) }.getOrDefault(emptyList())
            group("Connected BLE (GATT) devices", gatt.size.toString(), gatt.map { d -> deviceEntry(d) })
            group("Profile connections", children = entries {
                listOf(
                    "A2DP (audio)" to BluetoothProfile.A2DP,
                    "Headset (HFP)" to BluetoothProfile.HEADSET,
                ).forEach { (name, p) ->
                    put(name) {
                        when (a.getProfileConnectionState(p)) {
                            BluetoothProfile.STATE_CONNECTED -> "connected"
                            BluetoothProfile.STATE_CONNECTING -> "connecting"
                            else -> "disconnected"
                        }
                    }
                }
            })
        }
        if (lastScanAtMs > 0) {
            val ageS = (System.currentTimeMillis() - lastScanAtMs) / 1000
            val summary = "active BLE scan completed ${if (ageS < 5) "just now" else "$ageS s ago"}; " +
                "found ${lastScan.size} advertising device(s) in range"
            group("Latest BLE scan", summary, lastScan.sortedByDescending { it.rssi }.map { bleEntry(it) })
        } else {
            note("No BLE scan has been run yet. Ask about nearby BLE devices or tap 'BLE scan' on the Device tab.")
        }
        lastScanError?.let { note("Last BLE scan problem: $it") }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun featureStatus(code: Int) = when (code) {
        android.bluetooth.BluetoothStatusCodes.FEATURE_SUPPORTED -> "supported"
        android.bluetooth.BluetoothStatusCodes.FEATURE_NOT_SUPPORTED -> "not supported"
        else -> "unknown ($code)"
    }

    private fun deviceEntry(d: BluetoothDevice): Entry {
        val details = entries {
            put("Address") { d.address }
            put("Type") {
                when (d.type) {
                    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic (BR/EDR)"
                    BluetoothDevice.DEVICE_TYPE_LE -> "Low Energy"
                    BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual mode"
                    else -> "unknown"
                }
            }
            put("Class") { d.bluetoothClass?.let { majorClass(it.majorDeviceClass) } }
            put("Bond state") {
                when (d.bondState) {
                    BluetoothDevice.BOND_BONDED -> "bonded"
                    BluetoothDevice.BOND_BONDING -> "bonding"
                    else -> "not bonded"
                }
            }
        }
        return Entry(d.name ?: d.address, details.joinToString(" · ") { "${it.label}: ${it.value}" })
    }

    private fun bleEntry(b: BleDevice): Entry {
        val details = buildList {
            add("${b.rssi} dBm (${Format.bleRssiProximity(b.rssi)})")
            add(b.address)
            b.txPower?.let { add("Tx power $it dBm") }
            b.connectable?.let { add(if (it) "connectable" else "non-connectable (beacon)") }
            if (b.serviceUuids.isNotEmpty()) add("services ${b.serviceUuids.joinToString()}")
            if (b.manufacturerIds.isNotEmpty()) {
                add("manufacturer ${b.manufacturerIds.joinToString { companyName(it) }}")
            }
        }.joinToString(" · ")
        return Entry(b.name ?: "(unnamed)", details)
    }

    /**
     * Runs a low-latency BLE scan for [durationMs] and caches the results.
     * Returns null on success or a human-readable error.
     */
    suspend fun scanBle(durationMs: Long = 5_000): String? = scanMutex.withLock {
        val a = adapter ?: return fail("Bluetooth not supported on this device")
        if (!Permissions.hasBtScan(context)) {
            return fail(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "BLUETOOTH_SCAN permission not granted"
                else "Location permission not granted (needed for BLE scans before Android 12)"
            )
        }
        if (!a.isEnabled) return fail("Bluetooth is turned off")
        val scanner = a.bluetoothLeScanner ?: return fail("BLE scanner unavailable")
        val found = ConcurrentHashMap<String, BleDevice>()
        var error: Int? = null
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { record(it) }
            override fun onScanFailed(errorCode: Int) {
                error = errorCode
            }

            private fun record(r: ScanResult) {
                val rec = r.scanRecord
                val name = runCatching { r.device.name }.getOrNull() ?: rec?.deviceName
                val mfr = rec?.manufacturerSpecificData
                found[r.device.address] = BleDevice(
                    address = r.device.address,
                    name = name,
                    rssi = r.rssi,
                    txPower = r.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT }
                        ?: rec?.txPowerLevel?.takeIf { it != Int.MIN_VALUE },
                    connectable = r.isConnectable,
                    serviceUuids = rec?.serviceUuids?.map { shortUuid(it.uuid.toString()) } ?: emptyList(),
                    manufacturerIds = mfr?.let { (0 until it.size()).map { i -> it.keyAt(i) } } ?: emptyList(),
                    lastSeenMs = System.currentTimeMillis(),
                )
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, cb)
            delay(durationMs)
        } catch (e: SecurityException) {
            return fail("permission denied: ${e.message}")
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
        lastScan = found.values.toList()
        lastScanAtMs = System.currentTimeMillis()
        lastScanError = error?.let { "scan failed with error code $it" }
        lastScanError
    }

    private fun fail(msg: String): String {
        lastScanError = msg
        return msg
    }

    companion object {
        /** 0000180d-0000-1000-8000-00805f9b34fb -> 0x180D (Heart Rate) */
        fun shortUuid(uuid: String): String {
            val base = "-0000-1000-8000-00805f9b34fb"
            if (!uuid.endsWith(base, ignoreCase = true) || !uuid.startsWith("0000")) return uuid
            val short = uuid.substring(4, 8).uppercase()
            return wellKnownServices[short]?.let { "0x$short ($it)" } ?: "0x$short"
        }

        private val wellKnownServices = mapOf(
            "1800" to "Generic Access", "1801" to "Generic Attribute", "180A" to "Device Information",
            "180D" to "Heart Rate", "180F" to "Battery", "1812" to "HID", "1816" to "Cycling Speed",
            "1818" to "Cycling Power", "181A" to "Environmental Sensing", "1809" to "Health Thermometer",
            "1810" to "Blood Pressure", "1808" to "Glucose", "FE9F" to "Google", "FEAA" to "Eddystone",
            "FD6F" to "Exposure Notification", "FE2C" to "Google Fast Pair", "184E" to "Audio Stream Control",
            "1844" to "Volume Control", "FEF3" to "Google", "FDF0" to "Google",
        )

        fun companyName(id: Int) = when (id) {
            0x004C -> "Apple (0x004C)"
            0x0006 -> "Microsoft (0x0006)"
            0x00E0 -> "Google (0x00E0)"
            0x0075 -> "Samsung (0x0075)"
            0x0059 -> "Nordic Semiconductor (0x0059)"
            0x0157 -> "Huami/Amazfit (0x0157)"
            0x038F -> "Xiaomi (0x038F)"
            0x0087 -> "Garmin (0x0087)"
            0x000F -> "Broadcom (0x000F)"
            0x000D -> "Texas Instruments (0x000D)"
            else -> "0x%04X".format(id)
        }

        fun majorClass(c: Int) = when (c) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> "audio/video"
            BluetoothClass.Device.Major.COMPUTER -> "computer"
            BluetoothClass.Device.Major.PHONE -> "phone"
            BluetoothClass.Device.Major.WEARABLE -> "wearable"
            BluetoothClass.Device.Major.HEALTH -> "health"
            BluetoothClass.Device.Major.PERIPHERAL -> "peripheral (keyboard/mouse)"
            BluetoothClass.Device.Major.IMAGING -> "imaging"
            BluetoothClass.Device.Major.TOY -> "toy"
            BluetoothClass.Device.Major.NETWORKING -> "networking"
            else -> "other"
        }
    }
}


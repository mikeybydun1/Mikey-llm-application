package com.example.mikeyllmapplication.device

import java.util.Locale

/** Pure formatting / radio helpers. Kept free of Android types so they are unit-testable. */
object Format {

    fun bytes(b: Long): String {
        if (b < 0) return "unknown"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024; i++
        }
        return if (i == 0) "$b B" else String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        val d = s / 86400
        val h = (s % 86400) / 3600
        val m = (s % 3600) / 60
        return buildString {
            if (d > 0) append(d).append("d ")
            if (d > 0 || h > 0) append(h).append("h ")
            append(m).append("m")
        }
    }

    fun percent(part: Long, total: Long): String =
        if (total <= 0) "?" else String.format(Locale.US, "%.0f%%", part * 100.0 / total)

    /** Wi-Fi band name for a centre frequency in MHz. */
    fun wifiBand(freqMhz: Int): String = when (freqMhz) {
        in 2400..2500 -> "2.4 GHz"
        in 4900..5899 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        in 58320..70200 -> "60 GHz"
        else -> "unknown band"
    }

    /** Wi-Fi channel number for a centre frequency in MHz (802.11 channelisation). */
    fun wifiChannel(freqMhz: Int): Int? = when {
        freqMhz == 2484 -> 14
        freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
        freqMhz in 5160..5885 -> (freqMhz - 5000) / 5
        freqMhz == 5935 -> 2
        freqMhz in 5955..7115 -> (freqMhz - 5950) / 5
        else -> null
    }

    /** Human description of Wi-Fi RSSI. */
    fun wifiRssiQuality(rssi: Int): String = when {
        rssi >= -50 -> "excellent"
        rssi >= -60 -> "very good"
        rssi >= -67 -> "good"
        rssi >= -75 -> "fair"
        rssi >= -85 -> "weak"
        else -> "very weak / unusable"
    }

    /** LTE RSRP quality buckets (3GPP-ish field engineering thresholds). */
    fun lteRsrpQuality(rsrp: Int): String = when {
        rsrp >= -80 -> "excellent"
        rsrp >= -90 -> "good"
        rsrp >= -100 -> "fair"
        rsrp >= -110 -> "poor"
        else -> "very poor / edge of coverage"
    }

    fun bleRssiProximity(rssi: Int): String = when {
        rssi >= -55 -> "very close (<1 m)"
        rssi >= -70 -> "near (1–5 m)"
        rssi >= -85 -> "far (5–15 m)"
        else -> "very far / weak"
    }

    fun hex(bytes: ByteArray, max: Int = 24): String {
        val s = bytes.take(max).joinToString("") { String.format(Locale.US, "%02X", it) }
        return if (bytes.size > max) "$s…" else s
    }

    /** Converts a little-endian int IPv4 (as returned by legacy WifiManager APIs) to dotted form. */
    fun ipv4FromLittleEndianInt(ip: Int): String =
        "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"

    fun bool(b: Boolean) = if (b) "yes" else "no"

    /** Telephony / CellInfo "unavailable" sentinel is Int.MAX_VALUE. */
    fun cellInt(v: Int, unit: String = ""): String? =
        if (v == Int.MAX_VALUE || v == Int.MIN_VALUE) null else if (unit.isEmpty()) "$v" else "$v $unit"

    fun cellLong(v: Long): String? = if (v == Long.MAX_VALUE) null else "$v"
}

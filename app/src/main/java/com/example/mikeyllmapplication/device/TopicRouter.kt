package com.example.mikeyllmapplication.device

/**
 * Maps a free-form question to the device-data sections that are relevant for answering it.
 *
 * Small on-device models have limited context windows, so instead of dumping everything into
 * every prompt we pick the sections that matter (and fall back to a compact overview).
 *
 * Keyword syntax: plain words must match as whole words ("sim" does not match "simple");
 * a trailing `*` makes it a prefix ("advertis*" matches "advertising", "advertisement").
 */
object TopicRouter {

    data class Route(
        val sections: Set<SectionKey>,
        /** True when the user is asking about nearby BLE devices, so a fresh scan is worth running. */
        val wantsBleScan: Boolean,
        /** True when the user is asking about nearby Wi-Fi networks, so a fresh scan is worth requesting. */
        val wantsWifiScan: Boolean,
    )

    private val keywords: Map<SectionKey, List<String>> = mapOf(
        SectionKey.BLUETOOTH to listOf(
            "bluetooth", "ble", "bt", "gatt", "beacon*", "pair*", "bond*", "le audio", "a2dp", "hfp",
            "earbud*", "headphone*", "headset*", "airpod*", "wearable*", "smartwatch*", "advertis*", "eddystone",
            "ibeacon*", "uuid*", "2m phy", "coded phy", "long range", "peripheral*",
        ),
        SectionKey.WIFI to listOf(
            "wifi", "wi-fi", "wi fi", "wlan", "ssid", "bssid", "router*", "access point*", "hotspot*", "802.11*",
            "2.4 ghz", "2.4ghz", "5ghz", "5 ghz", "6ghz", "6 ghz", "wpa*", "mesh", "dhcp", "link speed",
        ),
        SectionKey.CELLULAR to listOf(
            "cell", "cells", "cellular", "mobile data", "mobile network*", "lte", "4g", "5g", "3g", "2g", "nr",
            "nsa", "sa", "sim", "sims", "esim", "carrier*", "operator*", "tower*", "rsrp", "rsrq", "sinr",
            "earfcn", "arfcn", "roaming", "apn", "telephony", "mcc", "mnc", "pci", "volte", "imei", "gsm",
            "wcdma", "umts", "hspa*",
        ),
        SectionKey.NETWORK to listOf(
            "network*", "internet", "online", "offline", "connect*", "ip", "ip address*", "ipv4", "ipv6",
            "dns", "gateway", "vpn", "proxy", "metered", "bandwidth", "speed", "latency", "data saver", "mtu",
            "private dns", "tether*",
        ),
        SectionKey.BATTERY to listOf(
            "battery", "batteries", "charg*", "power", "mah", "voltage", "drain*", "doze", "temperature",
            "hot", "overheat*",
        ),
        SectionKey.HARDWARE to listOf(
            "cpu", "processor", "chip*", "soc", "core", "cores", "ram", "memory", "storage", "space", "disk",
            "thermal", "slow", "performance", "spec", "specs", "specification*",
        ),
        SectionKey.SYSTEM to listOf(
            "android version", "version", "os", "build", "kernel", "patch", "update*", "model", "manufacturer",
            "what phone", "which phone", "uptime", "emulator", "abi", "time zone", "timezone", "locale",
        ),
        SectionKey.DISPLAY to listOf("display", "screen", "resolution", "refresh rate", "hz", "dpi", "brightness", "hdr"),
        SectionKey.LOCATION to listOf(
            "location", "gps", "gnss", "where am i", "coordinates", "latitude", "longitude", "satellite*",
        ),
        SectionKey.SENSORS to listOf(
            "sensor*", "accelerometer", "gyro*", "magnetometer", "compass", "proximity", "barometer", "step*",
        ),
        SectionKey.AUDIO to listOf("audio", "volume", "speaker*", "microphone", "mic", "sound", "ringer", "silent"),
        SectionKey.CAMERA to listOf("camera*", "megapixel*", "lens", "lenses", "flash", "photo*"),
        SectionKey.FEATURES to listOf(
            "nfc", "uwb", "ultra-wideband", "ultra wideband", "feature*", "support*", "capab*", "fingerprint", "ir",
        ),
        SectionKey.SECURITY to listOf(
            "security", "secure", "lock*", "encrypt*", "developer", "adb", "debug*", "airplane", "settings",
        ),
        SectionKey.APPS to listOf("app", "apps", "installed", "package*", "application", "applications"),
        SectionKey.PERMISSIONS to listOf("permission*", "allowed", "denied", "access"),
    )

    /**
     * Ambiguous words that only count when the question does not already name a specific radio —
     * "how is my Wi-Fi signal" should not drag in cellular data, but "how is my signal" should.
     */
    private val weakKeywords: Map<String, Set<SectionKey>> = mapOf(
        "signal*" to setOf(SectionKey.WIFI, SectionKey.CELLULAR),
        "bars" to setOf(SectionKey.WIFI, SectionKey.CELLULAR),
        "reception" to setOf(SectionKey.WIFI, SectionKey.CELLULAR),
        "coverage" to setOf(SectionKey.WIFI, SectionKey.CELLULAR),
        "band*" to setOf(SectionKey.WIFI, SectionKey.CELLULAR),
        "channel*" to setOf(SectionKey.WIFI),
        "rssi" to setOf(SectionKey.WIFI, SectionKey.BLUETOOTH),
        "wireless" to setOf(SectionKey.WIFI, SectionKey.BLUETOOTH),
        "radio*" to setOf(SectionKey.WIFI, SectionKey.BLUETOOTH, SectionKey.CELLULAR),
        "call*" to setOf(SectionKey.CELLULAR),
    )

    private val everything = listOf(
        "everything", "all info*", "all the info*", "full report", "overview", "summary", "summari*",
        "about my phone", "about this phone", "about my device", "about this device", "health check",
        "diagnos*", "what can you see", "what do you know",
    )

    private val scanWords = listOf(
        "nearby", "around", "scan*", "discover*", "find", "detect*", "beacon*", "advertis*", "close to",
        "in range", "visible", "available",
    )

    private val radioSections = setOf(SectionKey.WIFI, SectionKey.BLUETOOTH, SectionKey.CELLULAR, SectionKey.LOCATION)

    private val regexCache = HashMap<String, Regex>()

    private fun matches(text: String, keyword: String): Boolean {
        val re = synchronized(regexCache) {
            regexCache.getOrPut(keyword) {
                val prefix = keyword.endsWith("*")
                val body = Regex.escape(keyword.removeSuffix("*"))
                Regex("(?<![a-z0-9])$body" + if (prefix) "" else "(?![a-z0-9])")
            }
        }
        return re.containsMatchIn(text)
    }

    private fun List<String>.anyIn(text: String) = any { matches(text, it) }

    fun route(question: String): Route {
        val q = question.lowercase()
        if (everything.anyIn(q)) {
            return Route(SectionKey.entries.toSet() - SectionKey.APPS, wantsBleScan = false, wantsWifiScan = false)
        }
        val matched = keywords.filter { (_, words) -> words.anyIn(q) }.keys.toMutableSet()
        if (matched.none { it in radioSections }) {
            weakKeywords.forEach { (word, keys) -> if (matches(q, word)) matched += keys }
        }

        // Radio questions almost always benefit from the generic connectivity picture as well.
        if (SectionKey.WIFI in matched || SectionKey.CELLULAR in matched) matched += SectionKey.NETWORK
        // "Why is my battery hot" etc. are better answered with CPU/thermal state.
        if (SectionKey.BATTERY in matched) matched += SectionKey.HARDWARE
        // Missing data is often caused by missing permissions; give the model a way to explain that.
        if (matched.any { it in radioSections }) matched += SectionKey.PERMISSIONS

        val wantsScan = scanWords.anyIn(q)
        return Route(
            sections = matched.ifEmpty { mutableSetOf(SectionKey.SYSTEM) },
            wantsBleScan = SectionKey.BLUETOOTH in matched && wantsScan,
            wantsWifiScan = SectionKey.WIFI in matched && wantsScan,
        )
    }
}

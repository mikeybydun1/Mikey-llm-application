package com.example.mikeyllmapplication.device

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

class NetworkCollectors(private val context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val wm = context.applicationContext.getSystemService(WifiManager::class.java)

    fun network() = section(SectionKey.NETWORK) {
        val active = cm.activeNetwork
        if (active == null) {
            put("Active network") { "none — device is offline" }
        } else {
            add(Entry("Active network", describeNetwork(active).ifEmpty { "unknown" }))
            cm.getNetworkCapabilities(active)?.let { caps -> capabilityEntries(caps).forEach { add(it) } }
            cm.getLinkProperties(active)?.let { lp -> linkEntries(lp).forEach { add(it) } }
        }
        @Suppress("DEPRECATION")
        val all = cm.allNetworks
        group("All networks", all.size.toString(), all.map { n ->
            Entry(if (n == active) "$n (active/default)" else "$n", describeNetwork(n))
        })
        put("Data saver") {
            when (cm.restrictBackgroundStatus) {
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "on"
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "on (this app exempt)"
                else -> "off"
            }
        }
        put("Active network metered") { Format.bool(cm.isActiveNetworkMetered) }
    }

    private fun describeNetwork(n: Network): String {
        val caps = cm.getNetworkCapabilities(n) ?: return ""
        val lp = cm.getLinkProperties(n)
        return listOfNotNull(transports(caps), lp?.interfaceName?.let { "iface $it" }).joinToString(" · ")
    }

    private fun transports(caps: NetworkCapabilities) = listOf(
        NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi",
        NetworkCapabilities.TRANSPORT_CELLULAR to "Cellular",
        NetworkCapabilities.TRANSPORT_ETHERNET to "Ethernet",
        NetworkCapabilities.TRANSPORT_VPN to "VPN",
        NetworkCapabilities.TRANSPORT_BLUETOOTH to "Bluetooth tethering",
        NetworkCapabilities.TRANSPORT_WIFI_AWARE to "Wi-Fi Aware",
        NetworkCapabilities.TRANSPORT_USB to "USB",
        NetworkCapabilities.TRANSPORT_SATELLITE to "Satellite",
        NetworkCapabilities.TRANSPORT_THREAD to "Thread",
    ).filter { runCatching { caps.hasTransport(it.first) }.getOrDefault(false) }
        .joinToString("+") { it.second }.ifEmpty { "unknown transport" }

    private fun capabilityEntries(caps: NetworkCapabilities) = entries {
        put("Transport") { transports(caps) }
        put("Internet capability") { Format.bool(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) }
        put("Validated (internet actually reachable)") {
            Format.bool(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }
        put("Captive portal") { Format.bool(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) }
        put("Metered") { Format.bool(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) }
        put("Roaming") { Format.bool(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) }
        put("VPN active") { Format.bool(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) }
        put("Estimated bandwidth") {
            "↓ ${caps.linkDownstreamBandwidthKbps / 1000.0} Mbps / ↑ ${caps.linkUpstreamBandwidthKbps / 1000.0} Mbps"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put("Signal strength (framework)") {
                caps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
            }
        }
    }

    private fun linkEntries(lp: LinkProperties) = entries {
        put("Interface") { lp.interfaceName }
        put("IP addresses") { lp.linkAddresses.joinToString { it.toString() } }
        put("DNS servers") { lp.dnsServers.joinToString { it.hostAddress ?: "" } }
        put("Search domains") { lp.domains }
        put("Default gateway") {
            lp.routes.filter { it.isDefaultRoute && it.gateway != null }
                .joinToString { it.gateway!!.hostAddress ?: "" }.ifEmpty { null }
        }
        put("HTTP proxy") { lp.httpProxy?.toString() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            put("Private DNS (DoT)") {
                if (lp.isPrivateDnsActive) "active${lp.privateDnsServerName?.let { " ($it)" } ?: " (automatic)"}"
                else "inactive"
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put("MTU") { lp.mtu.takeIf { it > 0 } }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun wifi() = section(SectionKey.WIFI) {
        put("Wi-Fi enabled") { Format.bool(wm.isWifiEnabled) }
        put("Wi-Fi state") {
            when (wm.wifiState) {
                WifiManager.WIFI_STATE_ENABLED -> "enabled"
                WifiManager.WIFI_STATE_DISABLED -> "disabled"
                WifiManager.WIFI_STATE_ENABLING -> "enabling"
                WifiManager.WIFI_STATE_DISABLING -> "disabling"
                else -> "unknown"
            }
        }
        val hasLocation = Permissions.hasFineLocation(context)
        // The legacy call still returns SSID/BSSID when location is granted; the WifiInfo attached
        // to NetworkCapabilities (API 31+) is always location-redacted, so it is only a fallback.
        val info: WifiInfo? = runCatching { wm.connectionInfo }.getOrNull()?.takeIf { it.networkId != -1 || it.rssi > -127 }
            ?: currentWifiInfo()
        val connected = info != null && info.networkId != -1 || isOnWifi()
        put("Connected") { Format.bool(connected) }
        if (info != null && connected) {
            group("Current connection", children = entries {
                put("SSID") {
                    info.ssid?.trim('"')?.takeIf { it != WifiManager.UNKNOWN_SSID.trim('<', '>') && it != "<unknown ssid>" }
                        ?: if (!hasLocation) "hidden (needs location permission)" else "unknown"
                }
                put("BSSID (AP MAC)") { info.bssid?.takeIf { it != "02:00:00:00:00:00" } }
                put("RSSI") { "${info.rssi} dBm (${Format.wifiRssiQuality(info.rssi)})" }
                put("Signal level") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "${wm.calculateSignalLevel(info.rssi)}/${wm.maxSignalLevel}" else null
                }
                put("Frequency") {
                    info.frequency.takeIf { it > 0 }?.let {
                        "$it MHz (${Format.wifiBand(it)}, channel ${Format.wifiChannel(it) ?: "?"})"
                    }
                }
                put("Link speed") { "${info.linkSpeed} Mbps" }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put("Tx / Rx link speed") { "${info.txLinkSpeedMbps} / ${info.rxLinkSpeedMbps} Mbps" }
                    put("Max supported Tx / Rx") { "${info.maxSupportedTxLinkSpeedMbps} / ${info.maxSupportedRxLinkSpeedMbps} Mbps" }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    put("Wi-Fi standard") { wifiStandard(info.wifiStandard) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    put("Security") { securityType(info.currentSecurityType) }
                }
                put("Hidden SSID") { Format.bool(info.hiddenSSID) }
                put("IP address") { info.ipAddress.takeIf { it != 0 }?.let { Format.ipv4FromLittleEndianInt(it) } }
                put("Device MAC") { info.macAddress?.takeIf { it != "02:00:00:00:00:00" } ?: "randomized/hidden by Android" }
            })
            wm.dhcpInfo?.let { d ->
                group("DHCP", children = entries {
                    put("Gateway") { d.gateway.takeIf { it != 0 }?.let { Format.ipv4FromLittleEndianInt(it) } }
                    put("Netmask") { d.netmask.takeIf { it != 0 }?.let { Format.ipv4FromLittleEndianInt(it) } }
                    put("DNS 1") { d.dns1.takeIf { it != 0 }?.let { Format.ipv4FromLittleEndianInt(it) } }
                    put("DNS 2") { d.dns2.takeIf { it != 0 }?.let { Format.ipv4FromLittleEndianInt(it) } }
                    put("DHCP server") { d.serverAddress.takeIf { it != 0 }?.let { Format.ipv4FromLittleEndianInt(it) } }
                    put("Lease") { d.leaseDuration.takeIf { it > 0 }?.let { "${it / 3600} h" } }
                })
            }
        }
        group("Chipset capabilities", children = entries {
            put("5 GHz") { Format.bool(wm.is5GHzBandSupported) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) put("6 GHz") { Format.bool(wm.is6GHzBandSupported) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) put("60 GHz") { Format.bool(wm.is60GHzBandSupported) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                put("Wi-Fi 6 (ax)") { Format.bool(wm.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX)) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                put("Wi-Fi 7 (be)") { Format.bool(wm.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11BE)) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put("WPA3-SAE") { Format.bool(wm.isWpa3SaeSupported) }
                put("WPA3-Enterprise 192-bit") { Format.bool(wm.isWpa3SuiteBSupported) }
                put("Enhanced Open (OWE)") { Format.bool(wm.isEnhancedOpenSupported) }
            }
            put("Wi-Fi Direct (P2P)") { Format.bool(wm.isP2pSupported) }
            put("Wi-Fi RTT / 802.11mc") { Format.bool(wm.isDeviceToApRttSupported) }
            put("STA + AP concurrency") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Format.bool(wm.isStaApConcurrencySupported) else null
            }
            put("Scan always available") { Format.bool(wm.isScanAlwaysAvailable) }
        })

        if (!hasLocation) {
            note("Location permission not granted: SSID, BSSID and nearby-network scan results are hidden by Android.")
        } else {
            val results = runCatching { wm.scanResults }.getOrDefault(emptyList())
                .sortedByDescending { it.level }
            group("Nearby networks (last scan)", results.size.toString(), results.map { r -> scanEntry(r) })
            if (results.isEmpty()) note("No scan results yet. Use the Device tab's refresh to trigger a Wi-Fi scan.")
        }
    }

    @Suppress("DEPRECATION")
    private fun scanEntry(r: ScanResult): Entry {
        val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            r.wifiSsid?.toString()?.trim('"')
        } else r.SSID
        val details = buildList {
            add("${r.level} dBm (${Format.wifiRssiQuality(r.level)})")
            add("${r.frequency} MHz ${Format.wifiBand(r.frequency)} ch ${Format.wifiChannel(r.frequency) ?: "?"}")
            add(channelWidth(r.channelWidth))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(wifiStandard(r.wifiStandard))
            add(r.capabilities)
            add("BSSID ${r.BSSID}")
        }.joinToString(" · ")
        return Entry(ssid?.ifEmpty { null } ?: "(hidden SSID)", details)
    }

    /** Asks Android for a fresh Wi-Fi scan. Throttled by the OS (4 scans / 2 min for foreground apps). */
    @Suppress("DEPRECATION")
    fun requestWifiScan(): Boolean = runCatching { wm.startScan() }.getOrDefault(false)

    private fun isOnWifi(): Boolean = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    private fun currentWifiInfo(): WifiInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstNotNullOfOrNull { n ->
            cm.getNetworkCapabilities(n)?.transportInfo as? WifiInfo
        }
    }

    companion object {
        fun wifiStandard(s: Int) = when (s) {
            ScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g (legacy)"
            ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (802.11n)"
            ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (802.11ac)"
            ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6/6E (802.11ax)"
            ScanResult.WIFI_STANDARD_11AD -> "WiGig (802.11ad)"
            ScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7 (802.11be)"
            else -> "unknown standard"
        }

        fun channelWidth(w: Int) = when (w) {
            ScanResult.CHANNEL_WIDTH_20MHZ -> "20 MHz"
            ScanResult.CHANNEL_WIDTH_40MHZ -> "40 MHz"
            ScanResult.CHANNEL_WIDTH_80MHZ -> "80 MHz"
            ScanResult.CHANNEL_WIDTH_160MHZ -> "160 MHz"
            ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> "80+80 MHz"
            ScanResult.CHANNEL_WIDTH_320MHZ -> "320 MHz"
            else -> "? MHz"
        }

        fun securityType(t: Int) = when (t) {
            WifiInfo.SECURITY_TYPE_OPEN -> "Open"
            WifiInfo.SECURITY_TYPE_WEP -> "WEP"
            WifiInfo.SECURITY_TYPE_PSK -> "WPA/WPA2-Personal (PSK)"
            WifiInfo.SECURITY_TYPE_EAP -> "WPA/WPA2-Enterprise (EAP)"
            WifiInfo.SECURITY_TYPE_SAE -> "WPA3-Personal (SAE)"
            WifiInfo.SECURITY_TYPE_OWE -> "Enhanced Open (OWE)"
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE -> "WPA3-Enterprise"
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> "WPA3-Enterprise 192-bit"
            WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2, WifiInfo.SECURITY_TYPE_PASSPOINT_R3 -> "Passpoint"
            else -> "unknown"
        }
    }
}

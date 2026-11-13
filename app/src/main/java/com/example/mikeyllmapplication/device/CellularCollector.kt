package com.example.mikeyllmapplication.device

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

@SuppressLint("MissingPermission") // guarded by Permissions.* checks; SecurityExceptions are caught by the DSL
class CellularCollector(private val context: Context) {

    private val tm: TelephonyManager? = context.getSystemService(TelephonyManager::class.java)

    suspend fun cellular(): DeviceSection {
        val displayInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) readDisplayInfo() else null
        return build(displayInfo)
    }

    private fun build(displayInfo: String?) = section(SectionKey.CELLULAR) {
        val tm = tm
        if (tm == null || !context.packageManager.hasSystemFeature("android.hardware.telephony")) {
            put("Telephony") { "this device has no cellular radio" }
            return@section
        }
        val phone = Permissions.hasPhoneState(context)
        val fineLoc = Permissions.hasFineLocation(context)
        if (!phone) note("READ_PHONE_STATE not granted: network type, data state and SIM details are limited.")
        if (!fineLoc) note("Fine location not granted: cell tower IDs and neighbour cells are hidden.")

        put("Phone type") {
            when (tm.phoneType) {
                TelephonyManager.PHONE_TYPE_GSM -> "GSM/UMTS/LTE/NR"
                TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
                TelephonyManager.PHONE_TYPE_SIP -> "SIP"
                else -> "none"
            }
        }
        put("SIM state") { simState(tm.simState) }
        put("Network operator") { "${tm.networkOperatorName} (MCC/MNC ${tm.networkOperator})" }
        put("Network country") { tm.networkCountryIso.uppercase() }
        put("SIM operator") { "${tm.simOperatorName} (MCC/MNC ${tm.simOperator})" }
        put("SIM country") { tm.simCountryIso.uppercase() }
        put("Roaming") { Format.bool(tm.isNetworkRoaming) }
        if (phone) {
            put("Data network type") { networkType(tm.dataNetworkType) }
            put("Voice network type") { networkType(tm.voiceNetworkType) }
            put("Mobile data enabled") { Format.bool(tm.isDataEnabled) }
        }
        displayInfo?.let { put("Displayed network (5G icon logic)") { it } }
        put("Data connection") {
            when (tm.dataState) {
                TelephonyManager.DATA_CONNECTED -> "connected"
                TelephonyManager.DATA_CONNECTING -> "connecting"
                TelephonyManager.DATA_DISCONNECTED -> "disconnected"
                TelephonyManager.DATA_SUSPENDED -> "suspended"
                else -> "unknown"
            }
        }
        put("Data activity") {
            when (tm.dataActivity) {
                TelephonyManager.DATA_ACTIVITY_IN -> "receiving"
                TelephonyManager.DATA_ACTIVITY_OUT -> "sending"
                TelephonyManager.DATA_ACTIVITY_INOUT -> "sending & receiving"
                TelephonyManager.DATA_ACTIVITY_DORMANT -> "dormant"
                else -> "idle"
            }
        }
        put("Voice capable / SMS capable") {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Format.bool(tm.isSmsCapable) else "?"
            "${Format.bool(tm.isVoiceCapable)} / $sms"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            put("Active modems / SIM slots") { "${tm.activeModemCount} / ${tm.supportedModemCount}" }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tm.signalStrength?.let { ss ->
                put("Overall signal level") { "${ss.level}/4 (${levelName(ss.level)})" }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ss.cellSignalStrengths.forEach { css ->
                        add(Entry("Signal (${css.javaClass.simpleName.removePrefix("CellSignalStrength")})", css.dbm
                            .let { if (it == Int.MAX_VALUE) "level ${css.level}/4" else "$it dBm, level ${css.level}/4" }))
                    }
                }
            }
        }

        if (phone) {
            runCatching { tm.serviceState }.getOrNull()?.let { st ->
                group("Service state", children = entries {
                    put("State") {
                        when (st.state) {
                            ServiceState.STATE_IN_SERVICE -> "in service"
                            ServiceState.STATE_OUT_OF_SERVICE -> "out of service"
                            ServiceState.STATE_EMERGENCY_ONLY -> "emergency calls only"
                            ServiceState.STATE_POWER_OFF -> "radio off (airplane mode?)"
                            else -> "unknown"
                        }
                    }
                    put("Registered operator") { st.operatorAlphaLong }
                    put("Manual network selection") { Format.bool(st.isManualSelection) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        put("Channel number (EARFCN/NR-ARFCN)") { st.channelNumber.takeIf { it > 0 && it != Int.MAX_VALUE } }
                        put("Cell bandwidths") {
                            st.cellBandwidths.takeIf { it.isNotEmpty() }?.joinToString { "${it / 1000} MHz" }
                        }
                        put("Duplex mode") {
                            when (st.duplexMode) {
                                ServiceState.DUPLEX_MODE_FDD -> "FDD"
                                ServiceState.DUPLEX_MODE_TDD -> "TDD"
                                else -> null
                            }
                        }
                    }
                })
            }
            subscriptions()?.let { add(it) }
        }

        if (fineLoc) {
            val cells = runCatching { tm.allCellInfo }.getOrNull().orEmpty()
            val (serving, neighbours) = cells.partition { it.isRegistered }
            group("Serving cell(s)", serving.size.toString(), serving.map { cellEntry(it) })
            group("Neighbour cells", neighbours.size.toString(), neighbours.map { cellEntry(it) })
            if (cells.isEmpty()) note("The modem reported no cell info (common on emulators or in airplane mode).")
        }
    }

    private fun subscriptions(): Entry? {
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return null
        val subs = runCatching { sm.activeSubscriptionInfoList }.getOrNull() ?: return null
        val defaultData = SubscriptionManager.getDefaultDataSubscriptionId()
        return Entry("Active SIM subscriptions", subs.size.toString(), subs.map { s ->
            val details = entries {
                put("Slot") { s.simSlotIndex }
                put("Carrier") { s.carrierName }
                put("MCC/MNC") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "${s.mccString}/${s.mncString}" else null
                }
                put("Country") { s.countryIso.uppercase() }
                put("eSIM") { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Format.bool(s.isEmbedded) else null }
                put("Data roaming allowed") { Format.bool(s.dataRoaming == SubscriptionManager.DATA_ROAMING_ENABLE) }
                put("Default for data") { Format.bool(s.subscriptionId == defaultData) }
            }
            Entry(s.displayName?.toString() ?: "SIM ${s.simSlotIndex}", details.joinToString(" · ") { "${it.label}: ${it.value}" })
        })
    }

    private fun cellEntry(c: CellInfo): Entry {
        val rows = mutableListOf<String>()
        fun add(label: String, v: String?) {
            if (v != null) rows += "$label $v"
        }
        val tech: String
        when {
            c is CellInfoLte -> {
                tech = "LTE (4G)"
                val id = c.cellIdentity
                val s = c.cellSignalStrength
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add("MCC/MNC", "${id.mccString}/${id.mncString}")
                add("CI", Format.cellInt(id.ci))
                add("PCI", Format.cellInt(id.pci))
                add("TAC", Format.cellInt(id.tac))
                add("EARFCN", Format.cellInt(id.earfcn))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    id.bands.takeIf { it.isNotEmpty() }?.let { add("band", it.joinToString { b -> "B$b" }) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add("BW", Format.cellInt(id.bandwidth)?.let { "${it.toInt() / 1000} MHz" })
                add("RSRP", Format.cellInt(s.rsrp, "dBm")?.let { "$it (${Format.lteRsrpQuality(s.rsrp)})" })
                add("RSRQ", Format.cellInt(s.rsrq, "dB"))
                add("RSSNR", Format.cellInt(s.rssnr, "dB"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add("RSSI", Format.cellInt(s.rssi, "dBm"))
                add("CQI", Format.cellInt(s.cqi))
                add("TA", Format.cellInt(s.timingAdvance))
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && c is CellInfoNr -> {
                tech = "NR (5G)"
                val id = c.cellIdentity as CellIdentityNr
                val s = c.cellSignalStrength as CellSignalStrengthNr
                add("MCC/MNC", "${id.mccString}/${id.mncString}")
                add("NCI", Format.cellLong(id.nci))
                add("PCI", Format.cellInt(id.pci))
                add("TAC", Format.cellInt(id.tac))
                add("NR-ARFCN", Format.cellInt(id.nrarfcn))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    id.bands.takeIf { it.isNotEmpty() }?.let { add("band", it.joinToString { b -> "n$b" }) }
                }
                add("SS-RSRP", Format.cellInt(s.ssRsrp, "dBm"))
                add("SS-RSRQ", Format.cellInt(s.ssRsrq, "dB"))
                add("SS-SINR", Format.cellInt(s.ssSinr, "dB"))
                add("CSI-RSRP", Format.cellInt(s.csiRsrp, "dBm"))
            }

            c is CellInfoGsm -> {
                tech = "GSM (2G)"
                val id = c.cellIdentity
                val s = c.cellSignalStrength
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add("MCC/MNC", "${id.mccString}/${id.mncString}")
                add("LAC", Format.cellInt(id.lac))
                add("CID", Format.cellInt(id.cid))
                add("ARFCN", Format.cellInt(id.arfcn))
                add("BSIC", Format.cellInt(id.bsic))
                add("RSSI", Format.cellInt(s.dbm, "dBm"))
                add("TA", Format.cellInt(s.timingAdvance))
            }

            c is CellInfoWcdma -> {
                tech = "WCDMA (3G)"
                val id = c.cellIdentity
                val s = c.cellSignalStrength
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add("MCC/MNC", "${id.mccString}/${id.mncString}")
                add("LAC", Format.cellInt(id.lac))
                add("CID", Format.cellInt(id.cid))
                add("PSC", Format.cellInt(id.psc))
                add("UARFCN", Format.cellInt(id.uarfcn))
                add("RSCP", Format.cellInt(s.dbm, "dBm"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add("Ec/No", Format.cellInt(s.ecNo, "dB"))
            }

            c is CellInfoCdma -> {
                tech = "CDMA"
                add("BSID", Format.cellInt(c.cellIdentity.basestationId))
                add("dBm", Format.cellInt(c.cellSignalStrength.dbm, "dBm"))
            }

            else -> tech = c.javaClass.simpleName.removePrefix("CellInfo")
        }
        return Entry(tech + if (c.isRegistered) " [serving]" else "", rows.joinToString(" · "))
    }

    /** Reads TelephonyDisplayInfo once (tells LTE vs LTE-CA vs 5G NSA vs 5G SA, like the status-bar icon). */
    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun readDisplayInfo(): String? {
        val tm = tm ?: return null
        if (!Permissions.hasPhoneState(context)) return null
        val executor = Executors.newSingleThreadExecutor()
        return try {
            withTimeoutOrNull(1_500) {
                suspendCancellableCoroutine { cont ->
                    val cb = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                            runCatching { tm.unregisterTelephonyCallback(this) }
                            if (cont.isActive) cont.resume(describeDisplayInfo(info))
                        }
                    }
                    try {
                        tm.registerTelephonyCallback(executor, cb)
                    } catch (e: SecurityException) {
                        if (cont.isActive) cont.resume(null)
                    }
                    cont.invokeOnCancellation { runCatching { tm.unregisterTelephonyCallback(cb) } }
                }
            }
        } finally {
            executor.shutdown()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describeDisplayInfo(info: TelephonyDisplayInfo): String {
        val override = when (info.overrideNetworkType) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE carrier aggregation (LTE+)"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE Advanced Pro (5Ge)"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "5G NSA (anchored on LTE)"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G+ / 5G UW (mmWave or advanced NR)"
            else -> null
        }
        return networkType(info.networkType) + (override?.let { " → shown as $it" } ?: "")
    }

    companion object {
        fun networkType(t: Int) = when (t) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G NR (SA)"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE (4G)"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN (Wi-Fi calling)"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+ (3.5G)"
            TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA -> "HSPA (3.5G)"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS (3G)"
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA (3G)"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE (2.75G)"
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS (2.5G)"
            TelephonyManager.NETWORK_TYPE_GSM -> "GSM (2G)"
            TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT -> "CDMA (2G)"
            TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD -> "EV-DO (3G)"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "unknown / not connected"
            else -> "type $t"
        }

        fun simState(s: Int) = when (s) {
            TelephonyManager.SIM_STATE_READY -> "ready"
            TelephonyManager.SIM_STATE_ABSENT -> "no SIM"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network locked"
            TelephonyManager.SIM_STATE_NOT_READY -> "not ready"
            TelephonyManager.SIM_STATE_PERM_DISABLED -> "permanently disabled"
            TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "card error"
            TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "restricted"
            else -> "unknown"
        }

        fun levelName(l: Int) = when (l) {
            0 -> "none/unknown"
            1 -> "poor"
            2 -> "moderate"
            3 -> "good"
            else -> "great"
        }
    }
}

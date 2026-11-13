package com.example.mikeyllmapplication.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {

    /** Every runtime permission the app asks for, with the reason shown to the user. */
    val runtime: List<Pair<String, String>> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION to "Wi-Fi SSID & scan results, cell tower info, GPS")
        add(Manifest.permission.ACCESS_COARSE_LOCATION to "Approximate location")
        add(Manifest.permission.READ_PHONE_STATE to "Cellular network type, SIM, signal & service state")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN to "Scan for nearby BLE devices")
            add(Manifest.permission.BLUETOOTH_CONNECT to "Bluetooth adapter name & paired devices")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES to "Nearby Wi-Fi devices")
        }
    }

    val runtimeNames: Array<String> get() = runtime.map { it.first }.toTypedArray()

    fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasLocation(context: Context) = has(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
        has(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasFineLocation(context: Context) = has(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPhoneState(context: Context) = has(context, Manifest.permission.READ_PHONE_STATE)

    fun hasBtConnect(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        has(context, Manifest.permission.BLUETOOTH_CONNECT)

    fun hasBtScan(context: Context) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        has(context, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        hasFineLocation(context)
    }

    fun missing(context: Context): List<String> = runtime.map { it.first }.filterNot { has(context, it) }

    fun shortName(permission: String) = permission.substringAfterLast('.')
}

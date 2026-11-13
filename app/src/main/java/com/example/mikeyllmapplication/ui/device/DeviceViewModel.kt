package com.example.mikeyllmapplication.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mikeyllmapplication.AppContainer
import com.example.mikeyllmapplication.device.DeviceSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DeviceViewModel(private val c: AppContainer) : ViewModel() {

    private val _sections = MutableStateFlow<List<DeviceSection>>(emptyList())
    val sections: StateFlow<List<DeviceSection>> = _sections.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
    }

    fun refresh(triggerWifiScan: Boolean = false) {
        if (_loading.value) return
        _loading.value = true
        viewModelScope.launch {
            if (triggerWifiScan && !c.device.requestWifiScan()) {
                _message.value = "Android throttled the Wi-Fi scan; showing the latest cached results."
            }
            _sections.value = c.device.collectAll()
            _loading.value = false
        }
    }

    fun scanBle() {
        if (_scanning.value) return
        _scanning.value = true
        viewModelScope.launch {
            val err = c.device.scanBle(6_000)
            _message.value = err?.let { "BLE scan: $it" }
                ?: "BLE scan found ${c.device.bluetooth.lastScan.size} device(s)."
            _scanning.value = false
            refresh()
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}

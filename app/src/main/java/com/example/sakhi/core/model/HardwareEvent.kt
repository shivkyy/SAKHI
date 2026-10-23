package com.example.sakhi.core.model

sealed class HardwareEvent {
    data class DoublePress(val timestamp: Long = System.currentTimeMillis()) : HardwareEvent()
    data class TapHold(val timestamp: Long = System.currentTimeMillis(), val durationMs: Long = 1500L) : HardwareEvent()
    data class TripleTap(val timestamp: Long = System.currentTimeMillis()) : HardwareEvent()
    data class ButtonHoldStart(val timestamp: Long = System.currentTimeMillis()) : HardwareEvent()
    data class ButtonHoldRelease(val timestamp: Long = System.currentTimeMillis(), val durationMs: Long = 0L) : HardwareEvent()
    data class BatteryStatus(val percentage: Int, val isCharging: Boolean) : HardwareEvent()
    data class DeviceStatus(val firmwareVersion: String? = null, val rssi: Int? = null) : HardwareEvent()
    data class Disconnected(val reason: String) : HardwareEvent()
    data class Connected(val deviceName: String, val deviceAddress: String) : HardwareEvent()
}

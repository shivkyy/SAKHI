package com.example.sakhi.data.ble

import com.example.sakhi.core.model.HardwareEvent
import com.example.sakhi.core.model.KeychainLinkState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class BleDeviceInfo(
    val name: String,
    val address: String,
    val rssi: Int
)

interface BleManager {
    val linkState: StateFlow<KeychainLinkState>
    val hardwareEvents: Flow<HardwareEvent>
    val scannedDevices: StateFlow<List<BleDeviceInfo>>
    val isSimulated: Boolean

    fun startScan()
    fun stopScan()
    fun connect(address: String)
    fun disconnect()
    fun sendVibration(patternCode: Byte): Boolean
}

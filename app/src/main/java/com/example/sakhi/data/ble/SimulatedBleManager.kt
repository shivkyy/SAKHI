package com.example.sakhi.data.ble

import com.example.sakhi.core.model.HardwareEvent
import com.example.sakhi.core.model.KeychainLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SimulatedBleManager(
    private val scope: CoroutineScope
) : BleManager {

    override val isSimulated: Boolean = true

    // Truthful default: starts disconnected until explicitly connected
    private val _linkState = MutableStateFlow(KeychainLinkState.DISCONNECTED)
    override val linkState: StateFlow<KeychainLinkState> = _linkState.asStateFlow()

    private val _hardwareEvents = MutableSharedFlow<HardwareEvent>(extraBufferCapacity = 64)
    override val hardwareEvents: SharedFlow<HardwareEvent> = _hardwareEvents.asSharedFlow()

    private val _scannedDevices = MutableStateFlow<List<BleDeviceInfo>>(emptyList())
    override val scannedDevices: StateFlow<List<BleDeviceInfo>> = _scannedDevices.asStateFlow()

    val simulatedVibrationHistory = mutableListOf<Byte>()

    override fun startScan() {
        _linkState.value = KeychainLinkState.SCANNING
        _scannedDevices.value = listOf(
            BleDeviceInfo("SAKHI-ESP32-S3 (Simulated)", "AA:BB:CC:DD:EE:01", -58),
            BleDeviceInfo("SAKHI-Lab-Key (Simulated)", "AA:BB:CC:DD:EE:02", -72)
        )
    }

    override fun stopScan() {
        if (_linkState.value == KeychainLinkState.SCANNING) {
            _linkState.value = KeychainLinkState.DISCONNECTED
        }
    }

    override fun connect(address: String) {
        _linkState.value = KeychainLinkState.CONNECTING
        scope.launch {
            _linkState.value = KeychainLinkState.CONNECTED
            _hardwareEvents.emit(HardwareEvent.Connected("SAKHI-ESP32-S3 (Simulated)", address))
            _hardwareEvents.emit(HardwareEvent.BatteryStatus(percentage = 85, isCharging = false))
            _hardwareEvents.emit(HardwareEvent.DeviceStatus(firmwareVersion = "v1.2.0-sim", rssi = -60))
        }
    }

    override fun disconnect() {
        _linkState.value = KeychainLinkState.DISCONNECTED
        scope.launch {
            _hardwareEvents.emit(HardwareEvent.Disconnected("Simulated Disconnect"))
        }
    }

    override fun sendVibration(patternCode: Byte): Boolean {
        simulatedVibrationHistory.add(patternCode)
        return true
    }

    // Simulation hooks for Demo Lab & Unit Tests
    fun simulateDoublePress() {
        scope.launch { _hardwareEvents.emit(HardwareEvent.DoublePress()) }
    }

    fun simulateTapHold(durationMs: Long = 1500L) {
        scope.launch { _hardwareEvents.emit(HardwareEvent.TapHold(durationMs = durationMs)) }
    }

    fun simulateTripleTap() {
        scope.launch { _hardwareEvents.emit(HardwareEvent.TripleTap()) }
    }

    fun simulateBatteryStatus(percentage: Int, isCharging: Boolean = false) {
        scope.launch { _hardwareEvents.emit(HardwareEvent.BatteryStatus(percentage, isCharging)) }
    }

    fun simulateDeviceStatus(version: String = "v1.2.0-sim", rssi: Int = -60) {
        scope.launch { _hardwareEvents.emit(HardwareEvent.DeviceStatus(version, rssi)) }
    }

    fun setLinkState(state: KeychainLinkState) {
        _linkState.value = state
    }
}

package com.example.sakhi.data.ble

import com.example.sakhi.core.model.HardwareEvent
import com.example.sakhi.core.model.KeychainLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SwitchableBleManager(
    private val scope: CoroutineScope,
    val realBleManager: AndroidBleManager,
    val simBleManager: SimulatedBleManager
) : BleManager {

    private var activeManager: BleManager = realBleManager

    private val _linkState = MutableStateFlow(activeManager.linkState.value)
    override val linkState: StateFlow<KeychainLinkState> = _linkState.asStateFlow()

    private val _hardwareEvents = MutableSharedFlow<HardwareEvent>(extraBufferCapacity = 64)
    override val hardwareEvents: Flow<HardwareEvent> = _hardwareEvents.asSharedFlow()

    private val _scannedDevices = MutableStateFlow(activeManager.scannedDevices.value)
    override val scannedDevices: StateFlow<List<BleDeviceInfo>> = _scannedDevices.asStateFlow()

    override val isSimulated: Boolean
        get() = activeManager.isSimulated

    private var linkJob: Job? = null
    private var eventsJob: Job? = null
    private var devicesJob: Job? = null

    init {
        bindToActiveManager()
    }

    private fun bindToActiveManager() {
        linkJob?.cancel()
        eventsJob?.cancel()
        devicesJob?.cancel()

        _linkState.value = activeManager.linkState.value
        _scannedDevices.value = activeManager.scannedDevices.value

        linkJob = scope.launch {
            activeManager.linkState.collect { _linkState.value = it }
        }
        eventsJob = scope.launch {
            activeManager.hardwareEvents.collect { _hardwareEvents.emit(it) }
        }
        devicesJob = scope.launch {
            activeManager.scannedDevices.collect { _scannedDevices.value = it }
        }
    }

    fun switchToReal() {
        if (activeManager != realBleManager) {
            activeManager.disconnect()
            activeManager = realBleManager
            bindToActiveManager()
        }
    }

    fun switchToSimulated() {
        if (activeManager != simBleManager) {
            activeManager.disconnect()
            activeManager = simBleManager
            bindToActiveManager()
        }
    }

    fun isCurrentlySimulated(): Boolean = activeManager == simBleManager

    override fun startScan() = activeManager.startScan()
    override fun stopScan() = activeManager.stopScan()
    override fun connect(address: String) = activeManager.connect(address)
    override fun disconnect() = activeManager.disconnect()
    override fun sendVibration(patternCode: Byte): Boolean = activeManager.sendVibration(patternCode)
}

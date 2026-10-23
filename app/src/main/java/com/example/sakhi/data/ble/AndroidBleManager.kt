package com.example.sakhi.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import com.example.sakhi.core.model.HardwareEvent
import com.example.sakhi.core.model.KeychainLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AndroidBleManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val parser: IHardwareProtocolParser = HardwareProtocolParser()
) : BleManager {

    override val isSimulated: Boolean = false

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _linkState = MutableStateFlow(KeychainLinkState.DISCONNECTED)
    override val linkState: StateFlow<KeychainLinkState> = _linkState.asStateFlow()

    private val _hardwareEvents = MutableSharedFlow<HardwareEvent>(extraBufferCapacity = 64)
    override val hardwareEvents: SharedFlow<HardwareEvent> = _hardwareEvents.asSharedFlow()

    private val _scannedDevices = MutableStateFlow<List<BleDeviceInfo>>(emptyList())
    override val scannedDevices: StateFlow<List<BleDeviceInfo>> = _scannedDevices.asStateFlow()

    private var activeGatt: BluetoothGatt? = null
    private var lastTargetAddress: String? = null
    private var isScanning = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3
    private var rssiPollingJob: Job? = null

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val name = device.name ?: "Unknown SAKHI Device"
                val address = device.address
                val rssi = result.rssi

                val current = _scannedDevices.value.toMutableList()
                val existingIndex = current.indexOfFirst { it.address == address }
                if (existingIndex >= 0) {
                    current[existingIndex] = BleDeviceInfo(name, address, rssi)
                } else {
                    current.add(BleDeviceInfo(name, address, rssi))
                }
                _scannedDevices.value = current
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            _linkState.value = KeychainLinkState.DISCONNECTED
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reconnectAttempts = 0
                    _linkState.value = KeychainLinkState.CONNECTING
                    gatt?.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    stopRssiPolling()
                    activeGatt?.close()
                    activeGatt = null

                    // Check for bounded automatic reconnect if unexpected loss
                    if (status != BluetoothGatt.GATT_SUCCESS && lastTargetAddress != null && reconnectAttempts < maxReconnectAttempts) {
                        reconnectAttempts++
                        _linkState.value = KeychainLinkState.CONNECTING
                        scope.launch {
                            delay(1500L * reconnectAttempts)
                            lastTargetAddress?.let { connect(it) }
                        }
                    } else {
                        reconnectAttempts = 0
                        _linkState.value = KeychainLinkState.DISCONNECTED
                        scope.launch {
                            _hardwareEvents.emit(HardwareEvent.Disconnected("Connection terminated (status $status)"))
                        }
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                val service = gatt.getService(HardwareProtocolConfig.SERVICE_UUID)
                val eventChar = service?.getCharacteristic(HardwareProtocolConfig.EVENT_CHAR_UUID)

                if (eventChar != null) {
                    gatt.setCharacteristicNotification(eventChar, true)
                    val descriptor = eventChar.getDescriptor(HardwareProtocolConfig.CCCD_UUID)
                    if (descriptor != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                    _linkState.value = KeychainLinkState.CONNECTED
                    val deviceName = gatt.device.name ?: "SAKHI Keychain"
                    scope.launch {
                        _hardwareEvents.emit(HardwareEvent.Connected(deviceName, gatt.device.address))
                    }
                    startRssiPolling()
                } else {
                    _linkState.value = KeychainLinkState.CONNECTED
                    startRssiPolling()
                }
            } else {
                _linkState.value = KeychainLinkState.DISCONNECTED
            }
        }

        @SuppressLint("MissingPermission")
        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                scope.launch {
                    _hardwareEvents.emit(HardwareEvent.DeviceStatus(firmwareVersion = null, rssi = rssi))
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            @Suppress("DEPRECATION")
            characteristic?.value?.let { data ->
                val event = parser.parsePacket(data)
                if (event != null) {
                    scope.launch { _hardwareEvents.emit(event) }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val event = parser.parsePacket(value)
            if (event != null) {
                scope.launch { _hardwareEvents.emit(event) }
            }
        }
    }

    private fun startRssiPolling() {
        rssiPollingJob?.cancel()
        rssiPollingJob = scope.launch {
            while (isActive && _linkState.value == KeychainLinkState.CONNECTED) {
                delay(10000L) // Read RSSI every 10 seconds while connected
                readRssi()
            }
        }
    }

    private fun stopRssiPolling() {
        rssiPollingJob?.cancel()
        rssiPollingJob = null
    }

    @SuppressLint("MissingPermission")
    fun readRssi(): Boolean {
        return try {
            activeGatt?.readRemoteRssi() ?: false
        } catch (e: SecurityException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    override fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        if (isScanning) return
        _scannedDevices.value = emptyList()
        _linkState.value = KeychainLinkState.SCANNING
        isScanning = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            isScanning = false
            _linkState.value = KeychainLinkState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        if (!isScanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(scanCallback)
        } catch (e: SecurityException) {
            // Ignore if permission revoked
        } finally {
            isScanning = false
            if (_linkState.value == KeychainLinkState.SCANNING) {
                _linkState.value = KeychainLinkState.DISCONNECTED
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun connect(address: String) {
        stopScan()
        lastTargetAddress = address
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return

        _linkState.value = KeychainLinkState.CONNECTING
        try {
            activeGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            _linkState.value = KeychainLinkState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        lastTargetAddress = null
        reconnectAttempts = 0
        stopRssiPolling()
        try {
            activeGatt?.disconnect()
            activeGatt?.close()
        } catch (e: SecurityException) {
            // Ignore
        } finally {
            activeGatt = null
            _linkState.value = KeychainLinkState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    override fun sendVibration(patternCode: Byte): Boolean {
        val gatt = activeGatt ?: return false
        val service = gatt.getService(HardwareProtocolConfig.SERVICE_UUID) ?: return false
        val char = service.getCharacteristic(HardwareProtocolConfig.CONTROL_CHAR_UUID) ?: return false

        val payload = parser.encodeVibrationCommand(patternCode)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = payload
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        } catch (e: SecurityException) {
            false
        }
    }
}

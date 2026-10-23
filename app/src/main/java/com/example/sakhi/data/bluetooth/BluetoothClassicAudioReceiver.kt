package com.example.sakhi.data.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.sakhi.core.model.HardwareEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.math.sqrt

/**
 * Connection states for Bluetooth Classic RFCOMM SPP audio stream.
 */
enum class BtAudioConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    STREAMING,
    ERROR
}

/**
 * Telemetry snapshot for the external Bluetooth Classic audio stream.
 */
data class BtAudioTelemetry(
    val state: BtAudioConnectionState = BtAudioConnectionState.DISCONNECTED,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val totalBytesReceived: Long = 0L,
    val currentRms: Float = 0f,
    val isReceivingData: Boolean = false,
    val lastDataReceivedTimestamp: Long? = null,
    val errorMessage: String? = null,
    val isAutoReconnecting: Boolean = false,
    val reconnectAttempt: Int = 0
)

/**
 * Basic device summary for paired Bluetooth Classic devices.
 */
data class BtClassicDeviceInfo(
    val name: String,
    val address: String,
    val isSakhiAudio: Boolean
)

/**
 * Thread-safe, fixed-capacity circular ring buffer for incoming audio PCM bytes.
 * Prevents memory growth and guarantees bounded memory consumption.
 *
 * Default capacity: 192,000 bytes (6 seconds of 16kHz 16-bit mono PCM).
 */
class AudioCircularBuffer(val capacity: Int = 192000) {
    private val buffer = ByteArray(capacity)
    private var writeHead = 0
    private var availableBytes = 0
    private val lock = Any()

    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size): Int = synchronized(lock) {
        val bytesToWrite = minOf(length, capacity)
        for (i in 0 until bytesToWrite) {
            buffer[writeHead] = data[offset + i]
            writeHead = (writeHead + 1) % capacity
        }
        availableBytes = minOf(availableBytes + bytesToWrite, capacity)
        bytesToWrite
    }

    fun read(dest: ByteArray, offset: Int = 0, length: Int = dest.size): Int = synchronized(lock) {
        val bytesToRead = minOf(length, availableBytes)
        val readHead = (writeHead - availableBytes + capacity) % capacity
        for (i in 0 until bytesToRead) {
            dest[offset + i] = buffer[(readHead + i) % capacity]
        }
        availableBytes -= bytesToRead
        bytesToRead
    }

    fun snapshot(): ByteArray = synchronized(lock) {
        val result = ByteArray(availableBytes)
        val readHead = (writeHead - availableBytes + capacity) % capacity
        for (i in 0 until availableBytes) {
            result[i] = buffer[(readHead + i) % capacity]
        }
        result
    }

    /**
     * Returns the most recent N bytes (strictly aligned to 16-bit samples) from the ring buffer.
     */
    fun snapshotRecent(maxBytes: Int): ByteArray = synchronized(lock) {
        val evenMax = maxBytes - (maxBytes % 2)
        val evenAvailable = availableBytes - (availableBytes % 2)
        val bytesToTake = minOf(evenAvailable, evenMax)
        val result = ByteArray(bytesToTake)
        val readHead = (writeHead - bytesToTake + capacity) % capacity
        for (i in 0 until bytesToTake) {
            result[i] = buffer[(readHead + i) % capacity]
        }
        result
    }

    fun size(): Int = synchronized(lock) { availableBytes }

    fun clear() = synchronized(lock) {
        writeHead = 0
        availableBytes = 0
    }
}

/**
 * Manages the Bluetooth Classic RFCOMM SPP connection to the ESP32 SAKHI_AUDIO device.
 * Receives raw microphone audio bytes into a bounded circular buffer and calculates
 * live acoustic RMS volume telemetry.
 */
open class BluetoothClassicAudioReceiver(
    private val context: Context? = null,
    private val scope: CoroutineScope,
    val circularBuffer: AudioCircularBuffer = AudioCircularBuffer()
) {

    companion object {
        // Standard Bluetooth Serial Port Profile (SPP) UUID
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val DEFAULT_DEVICE_NAME = "SAKHI_AUDIO"

        const val INITIAL_RECONNECT_DELAY_MS = 1500L
        const val MAX_RECONNECT_DELAY_MS = 12000L
        const val MAX_RECONNECT_ATTEMPTS = 10

        fun calculateReconnectDelayMs(attempt: Int): Long {
            val shift = (attempt - 1).coerceIn(0, 10)
            return minOf(INITIAL_RECONNECT_DELAY_MS * (1L shl shift), MAX_RECONNECT_DELAY_MS)
        }
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val ctx = context
        if (ctx != null) {
            val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        } else {
            try {
                BluetoothAdapter.getDefaultAdapter()
            } catch (_: Exception) {
                null
            }
        }
    }

    protected open fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    protected val _telemetry = MutableStateFlow(BtAudioTelemetry())
    open val telemetry: StateFlow<BtAudioTelemetry> = _telemetry.asStateFlow()

    protected val _audioChunkFlow = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    open val audioChunkFlow: SharedFlow<ByteArray> = _audioChunkFlow.asSharedFlow()

    protected val _hardwareEvents = MutableSharedFlow<HardwareEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    open val hardwareEvents: SharedFlow<HardwareEvent> = _hardwareEvents.asSharedFlow()

    private var activeSocket: BluetoothSocket? = null
    private var connectionJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile
    var isAutoReconnectActive: Boolean = false
        internal set

    @Volatile
    var reconnectAttempt: Int = 0
        internal set

    private var lastTargetAddress: String? = null

    /**
     * Checks if the app has required Bluetooth permissions.
     */
    open fun hasBluetoothPermission(): Boolean {
        val ctx = context ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Retrieves all paired Bluetooth Classic devices, highlighting any named SAKHI_AUDIO.
     */
    @SuppressLint("MissingPermission")
    open fun getPairedDevices(): List<BtClassicDeviceInfo> {
        if (!hasBluetoothPermission()) return emptyList()
        val adapter = bluetoothAdapter ?: return emptyList()
        return try {
            adapter.bondedDevices?.map { device ->
                val name = device.name ?: "Unknown Bluetooth Device"
                BtClassicDeviceInfo(
                    name = name,
                    address = device.address,
                    isSakhiAudio = name.contains(DEFAULT_DEVICE_NAME, ignoreCase = true)
                )
            } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Connects to the ESP32 SAKHI_AUDIO device over Bluetooth Classic RFCOMM SPP.
     * @param targetAddress Optional MAC address of the target device. If null, searches bonded devices for SAKHI_AUDIO.
     * @param isAutoRetry Indicates if this invocation is an internal exponential backoff retry attempt.
     */
    open fun isSocketConnected(): Boolean {
        return activeSocket?.isConnected == true
    }

    @SuppressLint("MissingPermission")
    open fun connect(targetAddress: String? = null, isAutoRetry: Boolean = false) {
        if (!isAutoRetry) {
            // Guard: If we are already CONNECTED or STREAMING on an active open socket to the same address,
            // do not sever the healthy connection during Activity or Compose recreation.
            val currentState = _telemetry.value.state
            if ((currentState == BtAudioConnectionState.CONNECTED || currentState == BtAudioConnectionState.STREAMING) &&
                isSocketConnected() &&
                (targetAddress == null || targetAddress == _telemetry.value.deviceAddress)
            ) {
                return
            }

            lastTargetAddress = targetAddress
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempt = 0
            isAutoReconnectActive = true
            _telemetry.update {
                it.copy(isAutoReconnecting = false, reconnectAttempt = 0)
            }
        }

        if (!hasBluetoothPermission()) {
            isAutoReconnectActive = false
            _telemetry.update {
                it.copy(
                    state = BtAudioConnectionState.ERROR,
                    isAutoReconnecting = false,
                    reconnectAttempt = 0,
                    errorMessage = "Bluetooth Connect permission required to connect to $DEFAULT_DEVICE_NAME."
                )
            }
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            isAutoReconnectActive = false
            _telemetry.update {
                it.copy(
                    state = BtAudioConnectionState.ERROR,
                    isAutoReconnecting = false,
                    reconnectAttempt = 0,
                    errorMessage = "Bluetooth is turned off. Please enable Bluetooth in Android settings."
                )
            }
            return
        }

        connectionJob?.cancel()
        connectionJob = scope.launch(Dispatchers.IO) {
            disconnectInternal()

            _telemetry.update {
                it.copy(
                    state = BtAudioConnectionState.CONNECTING,
                    errorMessage = null
                )
            }

            // Find target device
            val targetDevice: BluetoothDevice? = try {
                if (!targetAddress.isNullOrBlank()) {
                    adapter.getRemoteDevice(targetAddress)
                } else {
                    adapter.bondedDevices?.firstOrNull {
                        it.name?.contains(DEFAULT_DEVICE_NAME, ignoreCase = true) == true
                    }
                }
            } catch (e: Exception) {
                null
            }

            if (targetDevice == null) {
                _telemetry.update {
                    it.copy(
                        state = BtAudioConnectionState.ERROR,
                        errorMessage = "ESP32 device '$DEFAULT_DEVICE_NAME' not found in paired Bluetooth devices. Please pair it in Android Bluetooth Settings first."
                    )
                }
                if (isAutoReconnectActive) {
                    scheduleAutoReconnect()
                }
                return@launch
            }

            val devName = try { targetDevice.name ?: DEFAULT_DEVICE_NAME } catch (_: SecurityException) { DEFAULT_DEVICE_NAME }
            val devAddr = targetDevice.address

            _telemetry.update {
                it.copy(
                    deviceName = devName,
                    deviceAddress = devAddr
                )
            }

            // Create RFCOMM socket with standard SPP UUID
            val socket: BluetoothSocket? = try {
                targetDevice.createRfcommSocketToServiceRecord(SPP_UUID)
            } catch (e: Exception) {
                _telemetry.update {
                    it.copy(
                        state = BtAudioConnectionState.ERROR,
                        errorMessage = "Failed to create RFCOMM socket: ${e.message}"
                    )
                }
                null
            }

            if (socket == null) {
                if (isAutoReconnectActive) {
                    scheduleAutoReconnect()
                }
                return@launch
            }

            activeSocket = socket
            try {
                // Cancel inquiry discovery before connecting per Android Bluetooth guidelines
                try { adapter.cancelDiscovery() } catch (_: Exception) {}

                socket.connect()
                reconnectAttempt = 0
                framer.reset()
                circularBuffer.clear()
                _telemetry.update {
                    it.copy(
                        state = BtAudioConnectionState.CONNECTED,
                        isAutoReconnecting = false,
                        reconnectAttempt = 0,
                        errorMessage = null
                    )
                }

                // Process continuous incoming audio stream
                processInputStream(socket.inputStream)
            } catch (e: Exception) {
                disconnectInternal()
                _telemetry.update {
                    it.copy(
                        state = BtAudioConnectionState.ERROR,
                        errorMessage = "Could not connect to $devName: ${e.localizedMessage ?: "Connection refused"}"
                    )
                }
                if (isAutoReconnectActive) {
                    scheduleAutoReconnect()
                }
            }
        }
    }

    /**
     * Schedules a bounded exponential backoff reconnection attempt to the ESP32 audio receiver.
     */
    fun scheduleAutoReconnect() {
        if (!isAutoReconnectActive) return
        if (!hasBluetoothPermission() || !isBluetoothEnabled()) {
            cancelAutoReconnect()
            return
        }
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            isAutoReconnectActive = false
            _telemetry.update {
                it.copy(
                    isAutoReconnecting = false,
                    reconnectAttempt = reconnectAttempt,
                    errorMessage = "Auto-reconnect stopped after $MAX_RECONNECT_ATTEMPTS attempts."
                )
            }
            return
        }
        reconnectAttempt++
        val delayMs = calculateReconnectDelayMs(reconnectAttempt)
        _telemetry.update {
            it.copy(
                isAutoReconnecting = true,
                reconnectAttempt = reconnectAttempt
            )
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (isActive && isAutoReconnectActive) {
                connect(lastTargetAddress, isAutoRetry = true)
            }
        }
    }

    /**
     * Cancels any active auto-reconnection job and resets attempt counters.
     */
    open fun cancelAutoReconnect() {
        isAutoReconnectActive = false
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        _telemetry.update {
            it.copy(isAutoReconnecting = false, reconnectAttempt = 0)
        }
    }

    /**
     * Proactively triggers reconnection if currently disconnected and a paired SAKHI_AUDIO device exists.
     */
    open fun triggerReconnectIfAppropriate() {
        val currState = _telemetry.value.state
        if (currState == BtAudioConnectionState.DISCONNECTED || currState == BtAudioConnectionState.ERROR) {
            if (!isAutoReconnectActive && hasBluetoothPermission() && isBluetoothEnabled()) {
                val hasBondedSakhi = try {
                    bluetoothAdapter?.bondedDevices?.any {
                        it.name?.contains(DEFAULT_DEVICE_NAME, ignoreCase = true) == true
                    } == true
                } catch (_: SecurityException) {
                    false
                }
                if (hasBondedSakhi) {
                    connect(null, isAutoRetry = false)
                }
            }
        }
    }

    val framer: SakhiStreamFramer = SakhiStreamFramer(
        onAudioChunk = { alignedBuffer ->
            val evenLength = alignedBuffer.size - (alignedBuffer.size % 2)
            if (evenLength > 0) {
                circularBuffer.write(alignedBuffer, 0, evenLength)
                val totalBytes = _telemetry.value.totalBytesReceived + evenLength

                val now = System.currentTimeMillis()
                val rms = computePcmRms(alignedBuffer, evenLength)

                // Deliver bounded PCM chunk (strictly even-byte length = complete 16-bit samples)
                _audioChunkFlow.tryEmit(alignedBuffer)

                _telemetry.update {
                    it.copy(
                        state = BtAudioConnectionState.STREAMING,
                        totalBytesReceived = totalBytes,
                        currentRms = rms,
                        isReceivingData = true,
                        lastDataReceivedTimestamp = now,
                        errorMessage = null
                    )
                }
            }
        },
        onControlEvent = { event ->
            _hardwareEvents.tryEmit(event)
        }
    )

    /**
     * Continuously reads bytes from the input stream, buffers them into the bounded circular buffer,
     * computes live acoustic RMS volume, and updates telemetry.
     */
    open fun processInputStream(inputStream: InputStream) {
        val buffer = ByteArray(1024)

        try {
            while (scope.isActive && (activeSocket?.isConnected != false)) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) {
                    break
                }
                if (bytesRead > 0) {
                    framer.feedBytes(buffer, 0, bytesRead)
                }
            }
        } catch (_: IOException) {
            // Stream closed or connection lost
        } catch (_: Exception) {
            // Unexpected stream error
        } finally {
            _hardwareEvents.tryEmit(HardwareEvent.Disconnected("Bluetooth Classic SPP disconnected"))
            disconnectInternal()
            _telemetry.update {
                it.copy(
                    state = BtAudioConnectionState.DISCONNECTED,
                    isReceivingData = false,
                    currentRms = 0f
                )
            }
            if (isAutoReconnectActive) {
                scheduleAutoReconnect()
            }
        }
    }

    /**
     * Calculates the Root Mean Square (RMS) volume level assuming 16-bit signed PCM little-endian samples.
     * Returns a normalized float between 0.0f and 1.0f.
     */
    open fun computePcmRms(data: ByteArray, length: Int): Float {
        val sampleCount = length / 2
        if (sampleCount <= 0) return 0f

        var sumSquares = 0.0
        for (i in 0 until length - 1 step 2) {
            val sample = (((data[i + 1].toInt() and 0xFF) shl 8) or (data[i].toInt() and 0xFF)).toShort()
            sumSquares += (sample * sample).toDouble()
        }
        val meanSquare = sumSquares / sampleCount
        val rawRms = sqrt(meanSquare).toFloat()
        // Perceptually scaled (square-root curve) for responsive acoustic VU metering
        return sqrt((rawRms / 16384f).coerceIn(0f, 1f))
    }

    private fun disconnectInternal() {
        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        activeSocket = null
    }

    /**
     * Disconnects the RFCOMM socket, cancels background reader coroutines, cancels auto-reconnect, and resets streaming state.
     */
    open fun disconnect() {
        cancelAutoReconnect()
        connectionJob?.cancel()
        connectionJob = null
        disconnectInternal()
        framer.reset()
        circularBuffer.clear()
        _hardwareEvents.tryEmit(HardwareEvent.Disconnected("User disconnected"))
        _telemetry.update {
            it.copy(
                state = BtAudioConnectionState.DISCONNECTED,
                isReceivingData = false,
                currentRms = 0f,
                errorMessage = null,
                isAutoReconnecting = false,
                reconnectAttempt = 0
            )
        }
    }

    /**
     * Returns a snapshot of available PCM bytes in the circular buffer.
     */
    fun getBufferedPcmData(): ByteArray {
        return circularBuffer.snapshot()
    }
}

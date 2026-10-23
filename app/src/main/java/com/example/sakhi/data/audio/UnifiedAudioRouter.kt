package com.example.sakhi.data.audio

import com.example.sakhi.data.bluetooth.BluetoothClassicAudioReceiver
import com.example.sakhi.data.bluetooth.BtAudioConnectionState
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

/**
 * Identifies the currently selected active microphone audio source.
 */
enum class ActiveAudioSource {
    NONE,
    ESP32_BLUETOOTH,
    PHONE_MICROPHONE
}

/**
 * High-level operational state of the UnifiedAudioRouter.
 */
enum class RouterOperationalState {
    STOPPED,
    ROUTING_ESP32,
    ROUTING_PHONE_MIC,
    FAILOVER_ACTIVE,
    ALL_SOURCES_UNAVAILABLE
}

/**
 * Telemetry snapshot for the UnifiedAudioRouter.
 */
data class UnifiedAudioRouterTelemetry(
    val operationalState: RouterOperationalState = RouterOperationalState.STOPPED,
    val activeSource: ActiveAudioSource = ActiveAudioSource.NONE,
    val esp32State: BtAudioConnectionState = BtAudioConnectionState.DISCONNECTED,
    val phoneMicState: PhoneAudioState = PhoneAudioState.IDLE,
    val isEsp32Usable: Boolean = false,
    val isPhoneMicUsable: Boolean = false,
    val totalRoutedBytes: Long = 0L,
    val switchCount: Int = 0,
    val lastSwitchTimestamp: Long? = null,
    val lastSwitchReason: String? = null,
    val failureReason: String? = null,
    val lastAudioChunkTimestamp: Long? = null,
    val isDataCurrentlyArriving: Boolean = false,
    val isSwitching: Boolean = false
)

/**
 * Unified Audio Router managing automatic, seamless failover between ESP32 Bluetooth Classic
 * SPP microphone audio and device internal Phone Microphone (AudioRecord).
 *
 * Rules:
 * 1. Prefer ESP32 audio whenever the incoming stream is actively streaming and usable.
 * 2. Fail over immediately to phone microphone upon confirmed Bluetooth disconnect or error.
 * 3. Fail over to phone microphone if ESP32 connection is stalled/starving for > [starvationTimeoutMs].
 * 4. Fail back to ESP32 only after incoming ESP32 audio packets are confirmed stable for > [stableRecoveryDurationMs].
 * 5. Strictly mutual exclusion: never emits audio from both sources simultaneously.
 * 6. Guarantees consistent 8,000 Hz mono 16-bit signed LE PCM output.
 * 7. Clean lifecycle: phone microphone is stopped when ESP32 audio is active to conserve battery.
 */
class UnifiedAudioRouter(
    val esp32AudioReceiver: BluetoothClassicAudioReceiver,
    val phoneAudioSource: IPhoneMicrophoneAudioSource,
    private val scope: CoroutineScope,
    val starvationTimeoutMs: Long = DEFAULT_STARVATION_TIMEOUT_MS,
    val stableRecoveryDurationMs: Long = DEFAULT_STABLE_RECOVERY_DURATION_MS,
    private val timeProvider: () -> Long = System::currentTimeMillis,
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    private val onSourceTransition: ((from: ActiveAudioSource, to: ActiveAudioSource) -> Unit)? = null
) {
    companion object {
        const val DEFAULT_STARVATION_TIMEOUT_MS = 2500L
        const val DEFAULT_STABLE_RECOVERY_DURATION_MS = 600L
        const val WATCHDOG_INTERVAL_MS = 200L
    }

    private val lock = Any()

    private val _telemetry = MutableStateFlow(UnifiedAudioRouterTelemetry())
    val telemetry: StateFlow<UnifiedAudioRouterTelemetry> = _telemetry.asStateFlow()

    private val _routedAudioFlow = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val routedAudioFlow: SharedFlow<ByteArray> = _routedAudioFlow.asSharedFlow()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var activeSource: ActiveAudioSource = ActiveAudioSource.NONE
        private set

    private var esp32CollectorJob: Job? = null
    private var phoneCollectorJob: Job? = null
    private var watchdogJob: Job? = null

    // Tracking for stable recovery before switching back to ESP32
    private var esp32StableSinceTimestamp: Long? = null
    private var esp32StableChunkCount: Int = 0

    private val transitionListeners = java.util.concurrent.CopyOnWriteArrayList<(ActiveAudioSource, ActiveAudioSource) -> Unit>()

    fun addTransitionListener(listener: (from: ActiveAudioSource, to: ActiveAudioSource) -> Unit) {
        transitionListeners.add(listener)
    }

    fun removeTransitionListener(listener: (from: ActiveAudioSource, to: ActiveAudioSource) -> Unit) {
        transitionListeners.remove(listener)
    }

    /**
     * Starts audio routing and watchdog monitoring.
     * Guaranteed idempotent and thread-safe.
     */
    fun start() {
        synchronized(lock) {
            if (isRunning) return
            isRunning = true

            // Launch ESP32 Chunk Collector
            esp32CollectorJob = scope.launch(dispatcher) {
                esp32AudioReceiver.audioChunkFlow.collect { chunk ->
                    if (!isRunning) return@collect
                    handleEsp32Chunk(chunk)
                }
            }

            // Launch Phone Mic Chunk Collector
            phoneCollectorJob = scope.launch(dispatcher) {
                phoneAudioSource.audioChunkFlow.collect { chunk ->
                    if (!isRunning) return@collect
                    handlePhoneMicChunk(chunk)
                }
            }

            // Launch Watchdog Monitor
            watchdogJob = scope.launch(dispatcher) {
                while (isActive && isRunning) {
                    evaluateSourceSelection()
                    delay(WATCHDOG_INTERVAL_MS)
                }
            }

            // Initial immediate evaluation
            evaluateSourceSelection()
        }
    }

    /**
     * Handles incoming PCM chunks from ESP32 Bluetooth Classic receiver.
     */
    private fun handleEsp32Chunk(chunk: ByteArray) {
        if (chunk.isEmpty()) return

        val now = timeProvider()
        synchronized(lock) {
            // Track recovery stability
            if (esp32StableSinceTimestamp == null) {
                esp32StableSinceTimestamp = now
                esp32StableChunkCount = 1
            } else {
                esp32StableChunkCount++
            }

            if (activeSource == ActiveAudioSource.NONE) {
                switchSourceLocked(
                    ActiveAudioSource.ESP32_BLUETOOTH,
                    "ESP32 audio chunk received on startup"
                )
                emitRoutedChunk(chunk)
            } else if (activeSource == ActiveAudioSource.ESP32_BLUETOOTH) {
                emitRoutedChunk(chunk)
            } else {
                // If currently on phone mic, check if ESP32 has recovered stably
                val stableDuration = now - (esp32StableSinceTimestamp ?: now)
                if (stableDuration >= stableRecoveryDurationMs && esp32StableChunkCount >= 2) {
                    switchSourceLocked(
                        ActiveAudioSource.ESP32_BLUETOOTH,
                        "ESP32 audio restored and stable (${stableDuration}ms, $esp32StableChunkCount chunks)"
                    )
                    emitRoutedChunk(chunk)
                }
            }
        }
    }

    /**
     * Handles incoming PCM chunks from the Phone Microphone capture source.
     */
    private fun handlePhoneMicChunk(chunk: ByteArray) {
        if (chunk.isEmpty()) return

        synchronized(lock) {
            // Strictly mutual exclusion: only emit phone mic when active
            if (activeSource == ActiveAudioSource.PHONE_MICROPHONE) {
                emitRoutedChunk(chunk)
            }
        }
    }

    private fun emitRoutedChunk(chunk: ByteArray) {
        // Enforce strictly even length (complete 16-bit samples)
        val validLength = chunk.size - (chunk.size % 2)
        if (validLength <= 0) return

        val chunkToEmit = if (validLength == chunk.size) chunk else chunk.copyOf(validLength)
        val now = timeProvider()

        _routedAudioFlow.tryEmit(chunkToEmit)

        _telemetry.update {
            it.copy(
                totalRoutedBytes = it.totalRoutedBytes + chunkToEmit.size,
                lastAudioChunkTimestamp = now,
                isDataCurrentlyArriving = true
            )
        }
    }

    /**
     * Periodic watchdog evaluating stream liveness and selecting the optimal source.
     */
    fun evaluateSourceSelection() {
        if (!isRunning) return

        val now = timeProvider()
        val btTelemetry = esp32AudioReceiver.telemetry.value
        val phoneTelemetry = phoneAudioSource.telemetry.value

        synchronized(lock) {
            val isBtDisconnected = btTelemetry.state == BtAudioConnectionState.DISCONNECTED ||
                    btTelemetry.state == BtAudioConnectionState.ERROR

            val lastDataTs = btTelemetry.lastDataReceivedTimestamp
            val isStalled = lastDataTs == null || (now - lastDataTs) >= starvationTimeoutMs

            val isEsp32Usable = btTelemetry.state == BtAudioConnectionState.STREAMING &&
                    btTelemetry.isReceivingData &&
                    !isStalled

            val isPhoneUsable = phoneTelemetry.state == PhoneAudioState.RECORDING ||
                    phoneTelemetry.state == PhoneAudioState.STARTING ||
                    phoneTelemetry.state == PhoneAudioState.IDLE ||
                    phoneTelemetry.state == PhoneAudioState.STOPPED

            if (!isEsp32Usable) {
                // Reset ESP32 stable tracker
                esp32StableSinceTimestamp = null
                esp32StableChunkCount = 0

                // Proactively attempt auto-reconnect if ESP32 disconnected
                if (isBtDisconnected) {
                    esp32AudioReceiver.triggerReconnectIfAppropriate()
                }

                // ESP32 is unusable; fail over to Phone Microphone
                val switchReason = when {
                    isBtDisconnected -> "Bluetooth connection lost (${btTelemetry.state})"
                    isStalled -> "ESP32 stream starved (${now - (lastDataTs ?: 0)}ms without incoming data)"
                    else -> "ESP32 stream not ready (${btTelemetry.state})"
                }
                switchSourceLocked(ActiveAudioSource.PHONE_MICROPHONE, switchReason)
            } else {
                // ESP32 is usable
                if (activeSource == ActiveAudioSource.NONE) {
                    switchSourceLocked(
                        ActiveAudioSource.ESP32_BLUETOOTH,
                        "Initial source selection: ESP32 audio is streaming"
                    )
                } else if (activeSource != ActiveAudioSource.ESP32_BLUETOOTH) {
                    val stableDuration = now - (esp32StableSinceTimestamp ?: now)
                    if (stableDuration >= stableRecoveryDurationMs && esp32StableChunkCount >= 2) {
                        switchSourceLocked(
                            ActiveAudioSource.ESP32_BLUETOOTH,
                            "ESP32 stream stable ($stableDuration ms)"
                        )
                    }
                }
            }

            // Update Telemetry Snapshot
            val currentActive = activeSource
            val operationalState = when {
                !isRunning -> RouterOperationalState.STOPPED
                currentActive == ActiveAudioSource.ESP32_BLUETOOTH -> RouterOperationalState.ROUTING_ESP32
                currentActive == ActiveAudioSource.PHONE_MICROPHONE -> {
                    if (phoneTelemetry.state == PhoneAudioState.PERMISSION_DENIED ||
                        phoneTelemetry.state == PhoneAudioState.INITIALIZATION_FAILED ||
                        phoneTelemetry.state == PhoneAudioState.RECORDING_FAILED
                    ) {
                        RouterOperationalState.ALL_SOURCES_UNAVAILABLE
                    } else {
                        RouterOperationalState.FAILOVER_ACTIVE
                    }
                }
                else -> RouterOperationalState.ALL_SOURCES_UNAVAILABLE
            }

            val failureMsg = if (operationalState == RouterOperationalState.ALL_SOURCES_UNAVAILABLE) {
                "ESP32 unusable (${btTelemetry.errorMessage ?: btTelemetry.state}) and Phone mic failed (${phoneTelemetry.errorMessage ?: phoneTelemetry.state})"
            } else null

            val lastChunkTs = _telemetry.value.lastAudioChunkTimestamp
            val isArriving = isRunning && (lastChunkTs != null && (now - lastChunkTs) < 1500L)

            _telemetry.update {
                it.copy(
                    operationalState = operationalState,
                    activeSource = currentActive,
                    esp32State = btTelemetry.state,
                    phoneMicState = phoneTelemetry.state,
                    isEsp32Usable = isEsp32Usable,
                    isPhoneMicUsable = isPhoneUsable,
                    failureReason = failureMsg,
                    lastAudioChunkTimestamp = lastChunkTs,
                    isDataCurrentlyArriving = isArriving,
                    isSwitching = false
                )
            }
        }
    }

    /**
     * Executes transition between audio sources with mutual exclusion and framing protection.
     */
    private fun switchSourceLocked(newSource: ActiveAudioSource, reason: String) {
        if (activeSource == newSource) return

        val previousSource = activeSource
        activeSource = newSource
        val now = timeProvider()

        // Manage child capture states to conserve resources
        when (newSource) {
            ActiveAudioSource.PHONE_MICROPHONE -> {
                // Start phone recording upon failover
                phoneAudioSource.startCapture()
            }
            ActiveAudioSource.ESP32_BLUETOOTH -> {
                // Stop phone recording when failing back to ESP32
                phoneAudioSource.stopCapture()
            }
            ActiveAudioSource.NONE -> {
                phoneAudioSource.stopCapture()
            }
        }

        // Notify downstream listener (e.g. SpeexNoiseSuppressor framing reset)
        onSourceTransition?.invoke(previousSource, newSource)
        for (listener in transitionListeners) {
            listener.invoke(previousSource, newSource)
        }

        val phoneTelemetry = phoneAudioSource.telemetry.value
        val operationalState = when {
            !isRunning -> RouterOperationalState.STOPPED
            newSource == ActiveAudioSource.ESP32_BLUETOOTH -> RouterOperationalState.ROUTING_ESP32
            newSource == ActiveAudioSource.PHONE_MICROPHONE -> {
                if (phoneTelemetry.state == PhoneAudioState.PERMISSION_DENIED ||
                    phoneTelemetry.state == PhoneAudioState.INITIALIZATION_FAILED ||
                    phoneTelemetry.state == PhoneAudioState.RECORDING_FAILED
                ) {
                    RouterOperationalState.ALL_SOURCES_UNAVAILABLE
                } else {
                    RouterOperationalState.FAILOVER_ACTIVE
                }
            }
            else -> RouterOperationalState.ALL_SOURCES_UNAVAILABLE
        }

        _telemetry.update {
            it.copy(
                operationalState = operationalState,
                activeSource = newSource,
                switchCount = it.switchCount + 1,
                lastSwitchTimestamp = now,
                lastSwitchReason = reason
            )
        }
    }

    /**
     * Stops audio routing and releases all capture jobs and resources.
     */
    fun stop() {
        synchronized(lock) {
            if (!isRunning) return
            isRunning = false

            watchdogJob?.cancel()
            watchdogJob = null

            esp32CollectorJob?.cancel()
            esp32CollectorJob = null

            phoneCollectorJob?.cancel()
            phoneCollectorJob = null

            // Stop phone capture
            phoneAudioSource.stopCapture()

            val previousSource = activeSource
            activeSource = ActiveAudioSource.NONE
            onSourceTransition?.invoke(previousSource, ActiveAudioSource.NONE)
            for (listener in transitionListeners) {
                listener.invoke(previousSource, ActiveAudioSource.NONE)
            }

            _telemetry.update {
                it.copy(
                    operationalState = RouterOperationalState.STOPPED,
                    activeSource = ActiveAudioSource.NONE
                )
            }
        }
    }
}

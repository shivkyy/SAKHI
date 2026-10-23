package com.example.sakhi.domain.emergency

import com.example.sakhi.core.database.ContactRepository
import com.example.sakhi.core.database.SessionHistoryRepository
import com.example.sakhi.core.model.AiState
import com.example.sakhi.core.model.ContactAlertState
import com.example.sakhi.core.model.DistressAssessment
import com.example.sakhi.core.model.EmergencySession
import com.example.sakhi.core.model.EmergencyState
import com.example.sakhi.core.model.HardwareEvent
import com.example.sakhi.core.model.KeychainLinkState
import com.example.sakhi.core.model.PoliceState
import com.example.sakhi.core.model.SessionState
import com.example.sakhi.core.model.TimelineEvent
import com.example.sakhi.core.model.TimelineEventType
import com.example.sakhi.core.model.TranscriptSegment
import com.example.sakhi.core.model.DistressCategory
import com.example.sakhi.core.preferences.SakhiPreferences
import com.example.sakhi.data.ai.AssessmentEngine
import com.example.sakhi.data.ai.EmergencyRiskAnalyzer
import com.example.sakhi.data.alerts.AlertDispatcher
import com.example.sakhi.data.assistant.ExternalSpeechLanguage
import com.example.sakhi.data.assistant.SpeechTranscriptionService
import com.example.sakhi.data.assistant.VoskAudioTranscriptionService
import com.example.sakhi.data.audio.AudioPipeline
import com.example.sakhi.data.ble.BleManager
import com.example.sakhi.data.ble.HardwareProtocolConfig
import com.example.sakhi.data.bluetooth.BluetoothClassicAudioReceiver
import com.example.sakhi.data.bluetooth.BtAudioConnectionState
import com.example.sakhi.data.location.LocationProvider
import com.example.sakhi.data.location.SakhiLocation
import com.example.sakhi.data.tracking.HttpLiveTrackingClient
import com.example.sakhi.data.tracking.LiveTrackingClient
import com.example.sakhi.domain.escalation.EscalationPolicy
import com.example.sakhi.domain.escalation.PoliceCallManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class EmergencyStateManager(
    val scope: CoroutineScope,
    val bleManager: BleManager,
    val locationProvider: LocationProvider,
    val alertDispatcher: AlertDispatcher,
    val audioPipeline: AudioPipeline,
    val assessmentEngine: AssessmentEngine,
    val escalationPolicy: EscalationPolicy,
    val policeCallManager: PoliceCallManager,
    val contactRepository: ContactRepository,
    val sessionHistoryRepository: SessionHistoryRepository,
    val preferences: SakhiPreferences,
    val liveTrackingClient: LiveTrackingClient = HttpLiveTrackingClient(),
    val transcriptionService: SpeechTranscriptionService? = null,
    val riskAnalyzer: EmergencyRiskAnalyzer = EmergencyRiskAnalyzer(),
    val voskTranscriptionService: VoskAudioTranscriptionService? = null,
    val btAudioReceiver: BluetoothClassicAudioReceiver? = null,
    val foregroundServiceStarter: (() -> Unit)? = null,
    val foregroundServiceStopper: (() -> Unit)? = null,
    val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    private val countdownController = CountdownController(scope)

    private val _state = MutableStateFlow(EmergencyState())
    val state: StateFlow<EmergencyState> = _state.asStateFlow()

    private var sessionDurationJob: Job? = null
    private var aiAnalysisJob: Job? = null
    private var speechRestartJob: Job? = null
    private var periodicLocationUpdateJob: Job? = null
    private var isSpeechMonitoringActive: Boolean = false
    private var isHoldOriginCountdown: Boolean = false
    private var buttonHoldOriginState: SessionState? = null
    private var buttonHoldStartTimeMs: Long? = null
    private var lastDoublePressTriggerTimestamp: Long = 0L

    init {
        // Observe hardware events from BleManager
        scope.launch {
            bleManager.hardwareEvents.collect { event ->
                handleHardwareEvent(event)
            }
        }

        val activeReceiver = btAudioReceiver ?: voskTranscriptionService?.audioRouter?.esp32AudioReceiver

        // Observe BLE link state (authoritative only if simulated demo mode or no BT Classic receiver)
        scope.launch {
            bleManager.linkState.collect { linkState ->
                if (bleManager.isSimulated || activeReceiver == null) {
                    _state.update { current ->
                        current.copy(
                            keychainLinkState = linkState,
                            isSimulatedDevice = bleManager.isSimulated,
                            deviceRssi = if (linkState == KeychainLinkState.CONNECTED) current.deviceRssi else null
                        )
                    }
                    if (linkState == KeychainLinkState.DISCONNECTED && _state.value.isEmergencyActive) {
                        addTimelineEvent(
                            "Keychain Disconnected",
                            "BLE link lost during active session. Phone-side safety workflows continue.",
                            TimelineEventType.BLE
                        )
                    }
                }
            }
        }

        // Authoritative Bluetooth Classic SPP telemetry for physical ESP32 hardware
        if (activeReceiver != null) {
            scope.launch {
                activeReceiver.telemetry.collect { btTelemetry ->
                    val linkState = when (btTelemetry.state) {
                        BtAudioConnectionState.CONNECTED,
                        BtAudioConnectionState.STREAMING -> KeychainLinkState.CONNECTED
                        BtAudioConnectionState.CONNECTING -> {
                            if (btTelemetry.isAutoReconnecting) KeychainLinkState.RECONNECTING
                            else KeychainLinkState.CONNECTING
                        }
                        BtAudioConnectionState.ERROR -> KeychainLinkState.LOST
                        BtAudioConnectionState.DISCONNECTED -> KeychainLinkState.DISCONNECTED
                    }

                    val wasConnected = _state.value.keychainLinkState == KeychainLinkState.CONNECTED
                    val isNowConnected = linkState == KeychainLinkState.CONNECTED

                    _state.update { current ->
                        if (bleManager.isSimulated) {
                            current.copy(
                                btClassicState = btTelemetry.state.name,
                                btClassicErrorMessage = btTelemetry.errorMessage,
                                btReconnectAttempts = btTelemetry.reconnectAttempt
                            )
                        } else {
                            current.copy(
                                keychainLinkState = linkState,
                                btClassicState = btTelemetry.state.name,
                                btClassicErrorMessage = btTelemetry.errorMessage,
                                btReconnectAttempts = btTelemetry.reconnectAttempt,
                                deviceName = btTelemetry.deviceName ?: current.deviceName,
                                deviceAddress = btTelemetry.deviceAddress ?: current.deviceAddress,
                                lastSeenTimestamp = btTelemetry.lastDataReceivedTimestamp ?: if (isNowConnected) (current.lastSeenTimestamp ?: timeProvider()) else current.lastSeenTimestamp,
                                // Physical SPP hardware does not send BLE battery/RSSI telemetry
                                deviceBatteryPercent = null,
                                deviceRssi = null,
                                deviceFirmwareVersion = null
                            )
                        }
                    }

                    if (wasConnected && !isNowConnected && _state.value.isEmergencyActive) {
                        addTimelineEvent(
                            "Keychain Disconnected",
                            "Bluetooth Classic SPP link lost during active session. Phone failover active.",
                            TimelineEventType.BLE
                        )
                    } else if (!wasConnected && isNowConnected && _state.value.isEmergencyActive) {
                        addTimelineEvent(
                            "Keychain Reconnected",
                            "Bluetooth Classic SPP link restored: ${btTelemetry.deviceName ?: "SAKHI_AUDIO"}",
                            TimelineEventType.BLE
                        )
                    }
                }
            }

            scope.launch {
                activeReceiver.hardwareEvents.collect { event ->
                    handleHardwareEvent(event)
                }
            }
        }

        // Observe location updates
        scope.launch {
            locationProvider.currentLocation.collect { loc ->
                if (loc != null) {
                    val now = timeProvider()
                    val isFresh = loc.isFresh(now)
                    val currentState = _state.value
                    val wasStaleOrNull = currentState.latitude == null || currentState.isLocationStale

                    _state.update { current ->
                        current.copy(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            locationAccuracy = loc.accuracy,
                            locationAddress = loc.address,
                            isLocationStale = !isFresh,
                            locationTimestamp = loc.timestamp,
                            locationProviderType = loc.provider,
                            locationAccuracyStatus = loc.accuracyStatus,
                            isLocationHighAccuracy = loc.isHighAccuracy && isFresh
                        )
                    }

                    if (currentState.isEmergencyActive && isFresh && wasStaleOrNull) {
                        addTimelineEvent(
                            "Fresh Location Acquired",
                            "GPS lock established: Lat ${"%.4f".format(loc.latitude)}, Lng ${"%.4f".format(loc.longitude)} (±${loc.accuracy?.toInt() ?: 0}m)",
                            TimelineEventType.LOCATION
                        )

                        if (currentState.isLiveTrackingActive &&
                            currentState.liveTrackingSessionId != null &&
                            currentState.liveTrackingWriterKey != null
                        ) {
                            scope.launch {
                                try {
                                    val backendUrl = preferences.liveTrackingUrlFlow.first()
                                    val uploadResult = liveTrackingClient.uploadLocation(
                                        baseUrl = backendUrl,
                                        sessionId = currentState.liveTrackingSessionId,
                                        writerKey = currentState.liveTrackingWriterKey,
                                        location = loc.copy(isStale = false)
                                    )
                                    uploadResult.onSuccess {
                                        _state.update { it.copy(lastLiveSyncTimestamp = timeProvider()) }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
            }
        }

        // Observe Vosk service telemetry and dual ASR flows if available
        if (voskTranscriptionService != null) {
            scope.launch {
                voskTranscriptionService.audioRouter.telemetry.collect { routerTelemetry ->
                    _state.update { current ->
                        current.copy(
                            activeAudioSource = routerTelemetry.activeSource.name,
                            isAudioSwitching = routerTelemetry.isSwitching,
                            isAudioDataArriving = routerTelemetry.isDataCurrentlyArriving,
                            lastAudioChunkTimestamp = routerTelemetry.lastAudioChunkTimestamp,
                            audioSourceErrorMessage = routerTelemetry.failureReason
                        )
                    }
                }
            }
            scope.launch {
                voskTranscriptionService.noiseSuppressor.telemetry.collect { speexTelemetry ->
                    _state.update { current ->
                        current.copy(
                            speexState = speexTelemetry.state.name,
                            speexFramesProcessed = speexTelemetry.framesProcessed,
                            speexErrorMessage = speexTelemetry.errorMessage
                        )
                    }
                }
            }
            scope.launch {
                voskTranscriptionService.safetyPartialText.collect { enPart ->
                    _state.update { it.copy(englishPartialText = enPart) }
                }
            }
            scope.launch {
                voskTranscriptionService.unrestrictedPartialText.collect { hiPart ->
                    _state.update { it.copy(hindiPartialText = hiPart) }
                }
            }
            scope.launch {
                voskTranscriptionService.safetyStatusText.collect { enStatus ->
                    _state.update { it.copy(englishStatusText = enStatus) }
                }
            }
            scope.launch {
                voskTranscriptionService.unrestrictedStatusText.collect { hiStatus ->
                    _state.update { it.copy(hindiStatusText = hiStatus) }
                }
            }
        }
    }

    fun handleHardwareEvent(event: HardwareEvent) {
        when (event) {
            is HardwareEvent.DoublePress -> handleDoublePress()
            is HardwareEvent.ButtonHoldStart -> handleButtonHoldStart()
            is HardwareEvent.ButtonHoldRelease -> handleButtonHoldRelease(event.durationMs)
            is HardwareEvent.TapHold -> handleButtonHoldComplete()
            is HardwareEvent.TripleTap -> handleTripleTap()
            is HardwareEvent.BatteryStatus -> {
                _state.update {
                    it.copy(deviceBatteryPercent = event.percentage, lastSeenTimestamp = System.currentTimeMillis())
                }
            }
            is HardwareEvent.DeviceStatus -> {
                _state.update {
                    it.copy(
                        deviceFirmwareVersion = event.firmwareVersion ?: it.deviceFirmwareVersion,
                        deviceRssi = event.rssi ?: it.deviceRssi,
                        lastSeenTimestamp = System.currentTimeMillis()
                    )
                }
            }
            is HardwareEvent.Disconnected -> {
                // If button hold was in progress, release hold and resume countdown if active
                if ((isHoldOriginCountdown || buttonHoldOriginState == SessionState.ACTIVATING) && _state.value.isEmergencyActive) {
                    countdownController.resumeCountdown()
                }
                isHoldOriginCountdown = false
                buttonHoldOriginState = null
                buttonHoldStartTimeMs = null

                val activeReceiver = btAudioReceiver ?: voskTranscriptionService?.audioRouter?.esp32AudioReceiver
                if (bleManager.isSimulated || activeReceiver == null) {
                    _state.update {
                        it.copy(
                            keychainLinkState = KeychainLinkState.DISCONNECTED,
                            deviceRssi = null
                        )
                    }
                    if (_state.value.isEmergencyActive) {
                        addTimelineEvent("BLE Connection Lost", event.reason, TimelineEventType.BLE)
                    }
                }
            }
            is HardwareEvent.Connected -> {
                val activeReceiver = btAudioReceiver ?: voskTranscriptionService?.audioRouter?.esp32AudioReceiver
                if (bleManager.isSimulated || activeReceiver == null) {
                    _state.update {
                        it.copy(
                            keychainLinkState = KeychainLinkState.CONNECTED,
                            deviceName = event.deviceName,
                            deviceAddress = event.deviceAddress,
                            lastSeenTimestamp = System.currentTimeMillis()
                        )
                    }
                    addTimelineEvent("Keychain Connected", "Connected to ${event.deviceName}", TimelineEventType.BLE)
                }
            }
        }
    }

    /**
     * Handles the start of a physical button hold.
     * Pauses the countdown if activating or counting down, guaranteeing the countdown cannot expire
     * while a hold-to-cancel is being evaluated.
     */
    fun handleButtonHoldStart() {
        val current = _state.value
        val isDuringCountdown = (current.sessionState == SessionState.ACTIVATING || current.sessionState == SessionState.ACTIVE) &&
                (current.contactAlertState == ContactAlertState.COUNTDOWN || countdownController.remainingSeconds > 0)

        isHoldOriginCountdown = isDuringCountdown
        buttonHoldOriginState = current.sessionState
        buttonHoldStartTimeMs = timeProvider()

        if (isDuringCountdown) {
            countdownController.pauseCountdown()
            addTimelineEvent(
                "Hold-to-Cancel Started",
                "Physical button pressed during countdown. Alert dispatch paused.",
                TimelineEventType.COUNTDOWN
            )
        } else if (current.sessionState == SessionState.ACTIVE) {
            addTimelineEvent(
                "Hold-to-End Started",
                "Physical button pressed during active session. Evaluating 3s hold to end session.",
                TimelineEventType.CANCELLATION
            )
        }
    }

    /**
     * Handles early release of physical button before 3 seconds.
     * Resumes the countdown with the exact remaining time if held during countdown.
     */
    fun handleButtonHoldRelease(durationMs: Long = 0L) {
        val wasCountdown = isHoldOriginCountdown || buttonHoldOriginState == SessionState.ACTIVATING
        isHoldOriginCountdown = false
        buttonHoldOriginState = null
        buttonHoldStartTimeMs = null

        if (wasCountdown && _state.value.isEmergencyActive) {
            countdownController.resumeCountdown()
            addTimelineEvent(
                "Hold Released Early",
                "Physical button released before 3s. Countdown resumed.",
                TimelineEventType.COUNTDOWN
            )
        }
    }

    /**
     * Handles completion of a continuous 3-second button hold.
     * Reuses the existing cancellation pathway (Workflow D).
     * Gestures are strictly assigned to origin state.
     */
    fun handleButtonHoldComplete() {
        val wasCountdown = isHoldOriginCountdown || buttonHoldOriginState == SessionState.ACTIVATING
        val origin = buttonHoldOriginState
        isHoldOriginCountdown = false
        buttonHoldOriginState = null
        buttonHoldStartTimeMs = null

        if (wasCountdown) {
            // Requirement B: 3-second hold during countdown cancels the pending alert
            handleTapHold(originatedInCountdown = true)
        } else if (origin == SessionState.ACTIVE || _state.value.sessionState == SessionState.ACTIVE) {
            // Requirement C: 3-second hold after dispatch ends active session
            handleTapHold(originatedInCountdown = false)
        } else if (_state.value.isEmergencyActive) {
            handleTapHold()
        }
    }

    // Workflows

    // Workflow B: Double-Press Activation
    fun handleDoublePress() {
        val current = _state.value
        // Prevent duplicate emergency sessions if already activating or active
        if (current.sessionState == SessionState.ACTIVATING || current.sessionState == SessionState.ACTIVE) {
            return
        }

        val now = timeProvider()
        val newSessionId = UUID.randomUUID().toString()
        lastManualTripleTapTimestamp = 0L
        riskAnalyzer.resetSession()
        policeCallManager.resetState()

        scope.launch {
            val configuredSeconds = preferences.countdownSecondsFlow.first()
            val configuredEscalationNumber = preferences.escalationPhoneNumberFlow.first()

            _state.update {
                it.copy(
                    sessionState = SessionState.ACTIVATING,
                    contactAlertState = ContactAlertState.COUNTDOWN,
                    policeState = PoliceState.NOT_REQUESTED,
                    activeSessionId = newSessionId,
                    sessionStartTimestamp = now,
                    sessionDurationSeconds = 0L,
                    countdownTotalSeconds = configuredSeconds,
                    countdownRemainingSeconds = configuredSeconds,
                    contactAlertStatuses = emptyMap(),
                    contactMessageCounts = emptyMap(),
                    contactLastSendTimestamps = emptyMap(),
                    consecutiveDistressCount = 0,
                    latestAssessment = null,
                    liveTranscript = "",
                    recentRelevantPhrases = emptyList(),
                    speechRecognitionStatus = "INACTIVE",
                    escalationDestinationNumber = configuredEscalationNumber.ifBlank { null },
                    callAttemptedInSession = false,
                    activeCallInProgress = false,
                    lastEscalatedUtterance = null,
                    lastCallEndedTimestamp = null,
                    escalationFailureReason = null,
                    timeline = emptyList(),
                    activeSessionOutcome = null,
                    liveTrackingUrl = null,
                    liveTrackingSessionId = null,
                    liveTrackingWriterKey = null,
                    isLiveTrackingActive = false,
                    lastLiveSyncTimestamp = null,
                    periodicSmsCount = 0,
                    hindiStatusText = if (voskTranscriptionService != null) "LISTENING" else "INACTIVE",
                    hindiPartialText = "",
                    hindiLatestFinalText = "",
                    hindiTranscriptHistory = emptyList(),
                    englishStatusText = if (voskTranscriptionService != null) "LISTENING" else "INACTIVE",
                    englishPartialText = "",
                    englishLatestFinalText = "",
                    englishTranscriptHistory = emptyList(),
                    speexState = if (voskTranscriptionService != null) "ACTIVE_PROCESSING" else "OFF"
                )
            }

            // Vibrate wearable (non-critical — BLE may not be connected)
            try {
                bleManager.sendVibration(HardwareProtocolConfig.CMD_VIBRATE_ACTIVATION)
            } catch (e: Exception) {
                // BLE vibration is non-critical; continue without it
            }

            addTimelineEvent(
                "Emergency Session Activated",
                "Triggered via double-press hardware event",
                TimelineEventType.ACTIVATION
            )

            // Start location provider (may fail if permission denied)
            try {
                locationProvider.startLocationUpdates()
            } catch (e: Exception) {
                addTimelineEvent("Location Unavailable", "Location permission not granted: ${e.message}", TimelineEventType.LOCATION)
            }

            // Asynchronously initiate live web tracking session
            initiateLiveTrackingSession()

            // Start session duration ticker
            startSessionDurationTicker()

            // Transition to ACTIVE before starting speech monitoring & countdown
            _state.update { it.copy(sessionState = SessionState.ACTIVE) }

            // Start foreground service if starter is provided
            try {
                foregroundServiceStarter?.invoke()
            } catch (e: Exception) {
                Log.e("EmergencyStateManager", "Failed to start foreground service: ${e.message}")
            }

            // Start emergency speech monitoring (non-blocking; never delays SOS or SMS)
            startEmergencySpeechMonitoring()

            // Evaluate CALL_ON_SOS_START policy if enabled (non-blocking)
            checkSosStartCallEscalation()

            countdownController.startCountdown(
                totalSeconds = configuredSeconds,
                onTick = { remaining ->
                    _state.update { it.copy(countdownRemainingSeconds = remaining) }
                },
                onFinished = {
                    handleCountdownExpired()
                }
            )
        }
    }

    private suspend fun checkSosStartCallEscalation() {
        try {
            val autoCallEnabled = preferences.autoCallEscalationEnabledFlow.first()
            val policy = preferences.escalationPolicyFlow.first()
            val destNumber = preferences.escalationPhoneNumberFlow.first()
            _state.update { it.copy(escalationDestinationNumber = destNumber.ifBlank { null }) }

            val decision = escalationPolicy.evaluateSosStartEscalation(
                state = _state.value,
                autoCallEnabled = autoCallEnabled,
                policy = policy,
                destinationNumber = destNumber
            )
            if (decision.shouldRequestPolice) {
                executeAutomaticCallEscalation(destNumber, decision.reason)
            }
        } catch (_: Exception) {
            // Never block SOS workflow on escalation check
        }
    }

    private suspend fun executeAutomaticCallEscalation(destinationNumber: String, reason: String) {
        val current = _state.value
        val isCallActive = current.activeCallInProgress ||
                current.policeState == PoliceState.CALL_INITIATED ||
                current.policeState == PoliceState.CONNECTED ||
                current.policeState == PoliceState.REQUESTED
        if (isCallActive) {
            return
        }

        val now = timeProvider()
        val lastEnded = current.lastCallEndedTimestamp
        if (lastEnded != null && (now - lastEnded) < 3000L) {
            return
        }

        val transcript = current.latestAssessment?.transcript?.trim()

        _state.update {
            it.copy(
                policeState = PoliceState.REQUESTED,
                callAttemptedInSession = true,
                activeCallInProgress = true,
                lastEscalatedUtterance = transcript,
                escalationDestinationNumber = destinationNumber,
                escalationFailureReason = null
            )
        }

        try {
            bleManager.sendVibration(HardwareProtocolConfig.CMD_VIBRATE_POLICE)
        } catch (_: Exception) {}

        addTimelineEvent(
            "Automatic Call Escalation",
            reason,
            TimelineEventType.POLICE
        )

        val callState = policeCallManager.initiateEmergencyCall(destinationNumber)
        val isSuccess = callState == PoliceState.CALL_INITIATED || callState == PoliceState.CONNECTED
        val failureReason = if (callState == PoliceState.FAILED) {
            "Unable to launch dialer for $destinationNumber"
        } else null

        _state.update {
            it.copy(
                policeState = callState,
                activeCallInProgress = isSuccess,
                escalationFailureReason = failureReason
            )
        }
        addTimelineEvent(
            "Escalation Call Status",
            "Target: $destinationNumber · State: $callState",
            TimelineEventType.POLICE
        )
    }

    /**
     * Invoked when an emergency call finishes or is terminated by the user/system.
     * Resets active call lock and allows subsequent distress utterances to trigger escalation again.
     */
    fun onEmergencyCallEnded() {
        policeCallManager.onCallEnded()
        val now = timeProvider()
        _state.update {
            it.copy(
                policeState = PoliceState.CALL_ENDED,
                activeCallInProgress = false,
                completedCallCount = it.completedCallCount + 1,
                lastCallEndedTimestamp = now
            )
        }
        addTimelineEvent(
            "Emergency Call Ended",
            "Completed call #${_state.value.completedCallCount}. Subsequent distress speech will re-trigger escalation.",
            TimelineEventType.POLICE
        )
    }

    // Continuous Emergency Speech Monitoring
    private suspend fun startEmergencySpeechMonitoring() {
        val speechMonitoringEnabled = preferences.emergencySpeechMonitoringFlow.first()
        if (!speechMonitoringEnabled) {
            _state.update {
                it.copy(
                    aiState = AiState.OFF,
                    speechRecognitionStatus = "DISABLED"
                )
            }
            return
        }

        // Primary Production Path: Unified Bilingual Vosk Speech Recognition
        if (voskTranscriptionService != null) {
            val sessionId = _state.value.activeSessionId ?: UUID.randomUUID().toString()
            isSpeechMonitoringActive = true
            _state.update {
                it.copy(
                    aiState = AiState.LISTENING,
                    speechRecognitionStatus = "LISTENING"
                )
            }
            addTimelineEvent(
                "Unified Speech Recognition Active",
                "Bilingual Vosk listening on session $sessionId",
                TimelineEventType.AI
            )
            voskTranscriptionService.onTranscriptSegmentCallback = { seg ->
                if (isSpeechMonitoringActive && _state.value.isEmergencyActive) {
                    onSpeechSegmentRecognized(seg)
                }
            }
            voskTranscriptionService.onSecondaryRiskHypothesisCallback = { hypothesisText ->
                if (isSpeechMonitoringActive && _state.value.isEmergencyActive) {
                    evaluateRiskHypothesis(hypothesisText)
                }
            }
            voskTranscriptionService.startProductionSession(
                sessionId = sessionId,
                onTranscript = { recognizedSegment ->
                    if (isSpeechMonitoringActive && _state.value.isEmergencyActive) {
                        onSpeechSegmentRecognized(recognizedSegment)
                    }
                },
                onError = { errorMsg ->
                    Log.e("EmergencyStateManager", "Vosk production ASR error: $errorMsg")
                    _state.update {
                        it.copy(speechRecognitionStatus = "ERROR ($errorMsg)")
                    }
                }
            )
            return
        }

        val recognizer = transcriptionService
        if (recognizer != null) {
            if (!recognizer.isAvailable) {
                _state.update {
                    it.copy(
                        aiState = AiState.OFFLINE,
                        speechRecognitionStatus = "UNAVAILABLE (Speech recognition service unavailable)"
                    )
                }
                addTimelineEvent(
                    "Speech Recognition Unavailable",
                    "Speech service not available on device. SOS and SMS continue normally.",
                    TimelineEventType.AI
                )
                return
            }

            isSpeechMonitoringActive = true
            _state.update {
                it.copy(
                    aiState = AiState.LISTENING,
                    speechRecognitionStatus = "LISTENING"
                )
            }
            startSingleSpeechRecognitionCycle(recognizer)
        } else {
            // Fallback to AudioPipeline when SpeechTranscriptionService is not attached
            try {
                audioPipeline.startCapture(scope)
                _state.update {
                    it.copy(
                        aiState = AiState.LISTENING,
                        speechRecognitionStatus = "LISTENING"
                    )
                }
                startAiAssessmentLoop()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        aiState = AiState.OFF,
                        speechRecognitionStatus = "PERMISSION_MISSING (${e.message ?: "Audio unavailable"})"
                    )
                }
                addTimelineEvent(
                    "Audio Capture Unavailable",
                    "Microphone permission not granted: ${e.message}",
                    TimelineEventType.AI
                )
            }
        }
    }

    private fun startSingleSpeechRecognitionCycle(recognizer: SpeechTranscriptionService) {
        if (!isSpeechMonitoringActive || !_state.value.isEmergencyActive) return

        recognizer.startListening(
            onReady = {
                if (isSpeechMonitoringActive && _state.value.isEmergencyActive) {
                    _state.update {
                        it.copy(
                            aiState = AiState.LISTENING,
                            speechRecognitionStatus = "LISTENING"
                        )
                    }
                }
            },
            onBeginningOfSpeech = {
                if (isSpeechMonitoringActive && _state.value.isEmergencyActive) {
                    _state.update {
                        it.copy(speechRecognitionStatus = "TRANSCRIBING")
                    }
                }
            },
            onRmsChanged = { _ -> },
            onPartialResult = { partial ->
                if (isSpeechMonitoringActive && _state.value.isEmergencyActive && partial.isNotBlank()) {
                    _state.update { current ->
                        current.copy(
                            speechRecognitionStatus = "TRANSCRIBING",
                            liveTranscript = partial
                        )
                    }
                }
            },
            onFinalResult = { finalSegment ->
                if (isSpeechMonitoringActive && _state.value.isEmergencyActive) {
                    if (finalSegment.isNotBlank()) {
                        onSpeechSegmentRecognized(finalSegment)
                    }
                    scheduleSpeechRecognizerRestart(recognizer, 350L)
                }
            },
            onError = { message, errorCode ->
                if (!isSpeechMonitoringActive || !_state.value.isEmergencyActive) return@startListening
                // Error code 9 = ERROR_INSUFFICIENT_PERMISSIONS, -1 = Unavailable
                if (errorCode == 9 || errorCode == -1 || message.contains("permission", ignoreCase = true)) {
                    isSpeechMonitoringActive = false
                    _state.update {
                        it.copy(
                            aiState = AiState.OFF,
                            speechRecognitionStatus = "UNAVAILABLE ($message)"
                        )
                    }
                    addTimelineEvent("Speech Monitoring Paused", message, TimelineEventType.AI)
                } else {
                    // Recoverable pause/timeout (e.g. ERROR_NO_MATCH or ERROR_SPEECH_TIMEOUT)
                    _state.update {
                        it.copy(speechRecognitionStatus = "LISTENING")
                    }
                    scheduleSpeechRecognizerRestart(recognizer, 600L)
                }
            }
        )
    }

    private fun scheduleSpeechRecognizerRestart(recognizer: SpeechTranscriptionService, delayMs: Long) {
        speechRestartJob?.cancel()
        speechRestartJob = scope.launch {
            delay(delayMs)
            if (isActive && isSpeechMonitoringActive && _state.value.isEmergencyActive) {
                startSingleSpeechRecognitionCycle(recognizer)
            }
        }
    }

    private fun stopEmergencySpeechMonitoring() {
        isSpeechMonitoringActive = false
        speechRestartJob?.cancel()
        speechRestartJob = null
        try {
            voskTranscriptionService?.stopProductionSession(_state.value.activeSessionId)
        } catch (_: Exception) {}
        try {
            transcriptionService?.stopListening()
        } catch (_: Exception) {}
        try {
            audioPipeline.stopCapture()
        } catch (_: Exception) {}
        stopAiAssessmentLoop()
    }

    /**
     * Processes a newly recognized speech segment during an active emergency session.
     * Supports English, Hindi (Devanagari), and Hinglish code-switched transcripts.
     * Never blocks SOS or SMS workflows if AI analysis fails.
     */
    private var lastProcessedSegmentText: String = ""
    private var lastProcessedSegmentTime: Long = 0L

    /**
     * Processes a newly recognized speech segment string during an active emergency session.
     * Maps to appropriate language TranscriptSegment and forwards to the dual-stream handler.
     */
    fun onSpeechSegmentRecognized(segmentText: String) {
        val trimmed = segmentText.trim()
        if (trimmed.isBlank() || !_state.value.isEmergencyActive) return

        val isHindi = trimmed.any { it in '\u0900'..'\u097F' } ||
                trimmed.contains("bachao", ignoreCase = true) ||
                trimmed.contains("madad", ignoreCase = true) ||
                trimmed.contains("chhod", ignoreCase = true) ||
                trimmed.contains("khatra", ignoreCase = true)
        val lang = if (isHindi) ExternalSpeechLanguage.HINDI else ExternalSpeechLanguage.ENGLISH
        val segment = TranscriptSegment(
            text = trimmed,
            language = lang,
            confidence = null,
            timestamp = timeProvider(),
            isFinal = true
        )
        onSpeechSegmentRecognized(segment)
    }

    /**
     * Processes a detailed TranscriptSegment.
     * Records into respective Hindi or English session transcript history,
     * updates live transcript, evaluates risk, and deduplicates rapid dual-engine triggers.
     */
    fun onSpeechSegmentRecognized(segment: TranscriptSegment) {
        val trimmed = segment.text.trim()
        if (trimmed.isBlank() || !_state.value.isEmergencyActive) return

        val now = timeProvider()
        // Deduplicate identical transcript decodings within 1500 ms to avoid double escalation
        if (trimmed.equals(lastProcessedSegmentText, ignoreCase = true) && (now - lastProcessedSegmentTime) < 1500L) {
            return
        }
        lastProcessedSegmentText = trimmed
        lastProcessedSegmentTime = now

        scope.launch {
            try {
                // 1. Update live transcript & language-specific history/status
                _state.update { current ->
                    val combined = if (current.liveTranscript.isBlank()) {
                        trimmed
                    } else {
                        "${current.liveTranscript} · $trimmed"
                    }
                    val bounded = if (combined.length > 400) combined.takeLast(400) else combined

                    if (segment.language == ExternalSpeechLanguage.HINDI) {
                        current.copy(
                            liveTranscript = bounded,
                            speechRecognitionStatus = "ANALYZING",
                            hindiLatestFinalText = trimmed,
                            hindiTranscriptHistory = current.hindiTranscriptHistory + segment
                        )
                    } else {
                        current.copy(
                            liveTranscript = bounded,
                            speechRecognitionStatus = "ANALYZING",
                            englishLatestFinalText = trimmed,
                            englishTranscriptHistory = current.englishTranscriptHistory + segment
                        )
                    }
                }

                // 2. Run local bilingual risk analysis (extracts & deduplicates relevant phrases)
                val localAssessment = riskAnalyzer.analyzeSegment(trimmed)

                // 3. Optionally consult configured assessmentEngine if no immediate local trigger matched
                val finalAssessment = try {
                    if (localAssessment.triggerPhraseMatch) {
                        localAssessment
                    } else {
                        val engineAssessment = assessmentEngine.assessText(trimmed)
                        if (engineAssessment.category == DistressCategory.POSSIBLE_DISTRESS ||
                            engineAssessment.category == DistressCategory.ELEVATED_CONCERN
                        ) {
                            engineAssessment
                        } else {
                            localAssessment
                        }
                    }
                } catch (_: Exception) {
                    localAssessment
                }

                val extractedPhraseTexts = riskAnalyzer.getExtractedPhrases().map { it.exactText }

                _state.update { current ->
                    current.copy(
                        latestAssessment = finalAssessment,
                        recentRelevantPhrases = extractedPhraseTexts,
                        speechRecognitionStatus = if (isSpeechMonitoringActive) "LISTENING" else current.speechRecognitionStatus
                    )
                }

                if (finalAssessment.triggerPhraseMatch) {
                    addTimelineEvent(
                        "Relevant Speech Detected",
                        "\"$trimmed\" (${finalAssessment.category})",
                        TimelineEventType.AI
                    )
                }

                // 4. Evaluate automatic call escalation if configured for CALL_ON_RISK_ESCALATION
                val autoCallEnabled = preferences.autoCallEscalationEnabledFlow.first()
                val policy = preferences.escalationPolicyFlow.first()
                val destNumber = preferences.escalationPhoneNumberFlow.first()
                val sensitivity = preferences.escalationSensitivityFlow.first()

                if (autoCallEnabled) {
                    val decision = escalationPolicy.evaluateRiskEscalation(
                        state = _state.value,
                        assessment = finalAssessment,
                        autoCallEnabled = autoCallEnabled,
                        policy = policy,
                        destinationNumber = destNumber,
                        sensitivity = sensitivity,
                        currentTime = timeProvider()
                    )
                    _state.update { it.copy(consecutiveDistressCount = decision.updatedConsecutiveCount) }
                    if (decision.shouldRequestPolice) {
                        executeAutomaticCallEscalation(destNumber, decision.reason)
                    }
                }
            } catch (_: Exception) {
                // Safety rule: AI/speech failure must never interrupt active emergency session
            }
        }
    }

    /**
     * Evaluates a secondary risk hypothesis (e.g. from an alternate decoder candidate during ambiguous speech)
     * strictly for emergency distress risk analysis without adding it to user-visible transcript history.
     */
    fun evaluateRiskHypothesis(hypothesisText: String) {
        val trimmed = hypothesisText.trim()
        if (trimmed.isBlank() || !_state.value.isEmergencyActive) return

        scope.launch {
            try {
                val localAssessment = riskAnalyzer.analyzeSegment(trimmed)
                if (localAssessment.triggerPhraseMatch) {
                    val extractedPhraseTexts = riskAnalyzer.getExtractedPhrases().map { it.exactText }
                    _state.update { current ->
                        current.copy(
                            latestAssessment = localAssessment,
                            recentRelevantPhrases = extractedPhraseTexts
                        )
                    }
                    addTimelineEvent(
                        "Relevant Speech Detected",
                        "\"$trimmed\" (${localAssessment.category})",
                        TimelineEventType.AI
                    )
                    val autoCallEnabled = preferences.autoCallEscalationEnabledFlow.first()
                    val policy = preferences.escalationPolicyFlow.first()
                    val destNumber = preferences.escalationPhoneNumberFlow.first()
                    val sensitivity = preferences.escalationSensitivityFlow.first()

                    if (autoCallEnabled) {
                        val decision = escalationPolicy.evaluateRiskEscalation(
                            state = _state.value,
                            assessment = localAssessment,
                            autoCallEnabled = autoCallEnabled,
                            policy = policy,
                            destinationNumber = destNumber,
                            sensitivity = sensitivity,
                            currentTime = timeProvider()
                        )
                        _state.update { it.copy(consecutiveDistressCount = decision.updatedConsecutiveCount) }
                        if (decision.shouldRequestPolice) {
                            executeAutomaticCallEscalation(destNumber, decision.reason)
                        }
                    }
                }
            } catch (_: Exception) {
                // Safety rule: AI/speech failure must never interrupt active emergency session
            }
        }
    }

    // Workflow C: Countdown Expiry & Contact Alert Dispatch
    private suspend fun handleCountdownExpired() {
        val current = _state.value
        // If session was cancelled in the meantime, do nothing
        if (current.sessionState != SessionState.ACTIVE) return

        _state.update { it.copy(contactAlertState = ContactAlertState.SENDING) }
        addTimelineEvent(
            "Countdown Expired",
            "Contact alert dispatch initiated without waiting for AI",
            TimelineEventType.COUNTDOWN
        )

        val contacts = contactRepository.getContacts()
        if (contacts.isEmpty()) {
            _state.update { it.copy(contactAlertState = ContactAlertState.FAILED) }
            addTimelineEvent(
                "Contact Alert Failed",
                "No trusted contacts configured",
                TimelineEventType.CONTACT_ALERT
            )
            return
        }

        val location = locationProvider.getBestLocation()
        if (location != null) {
            val now = timeProvider()
            val isStale = location.isStale(now)
            val eventTitle = if (isStale) "Stale Location Acquired" else "Location Acquired"
            val staleNote = if (isStale) " (stale fix)" else ""
            addTimelineEvent(
                eventTitle,
                "Lat: ${"%.4f".format(location.latitude)}, Lng: ${"%.4f".format(location.longitude)} (Accuracy: ${location.accuracy?.toInt() ?: 0}m)$staleNote",
                TimelineEventType.LOCATION
            )
        } else {
            addTimelineEvent(
                "Location Unavailable",
                "Dispatching with unavailable location indicator",
                TimelineEventType.LOCATION
            )
        }

        val template = preferences.messageTemplateFlow.first()
        val includeAccuracy = preferences.includeLocationAccuracyFlow.first()
        val liveUrl = _state.value.liveTrackingUrl

        val finalAlertState = alertDispatcher.dispatchAlertToContacts(
            contacts = contacts,
            location = location,
            template = template,
            includeAccuracy = includeAccuracy,
            liveTrackingUrl = liveUrl,
            onContactStatusUpdate = { contactId, cState ->
                _state.update { state ->
                    val statusMap = state.contactAlertStatuses.toMutableMap()
                    statusMap[contactId] = cState

                    val countMap = state.contactMessageCounts.toMutableMap()
                    val timeMap = state.contactLastSendTimestamps.toMutableMap()
                    if (cState == ContactAlertState.SENT || cState == ContactAlertState.DELIVERED) {
                        countMap[contactId] = (countMap[contactId] ?: 0) + 1
                        timeMap[contactId] = System.currentTimeMillis()
                    }

                    state.copy(
                        contactAlertStatuses = statusMap,
                        contactMessageCounts = countMap,
                        contactLastSendTimestamps = timeMap
                    )
                }
            }
        )

        _state.update { it.copy(contactAlertState = finalAlertState) }
        addTimelineEvent(
            "Contact Alert Result: $finalAlertState",
            "Processed for ${contacts.size} contact(s)",
            TimelineEventType.CONTACT_ALERT
        )

        // Launch periodic location update loop (uploads to backend and sends periodic SMS)
        if (_state.value.isEmergencyActive) {
            startPeriodicLocationUpdateLoop()
        }
    }

    // Workflow D: Tap-and-Hold Cancellation
    fun handleTapHold(originatedInCountdown: Boolean? = null) {
        val current = _state.value
        // Accept cancellation only when session is active or activating
        if (current.sessionState != SessionState.ACTIVE && current.sessionState != SessionState.ACTIVATING) {
            return
        }

        val now = System.currentTimeMillis()
        lastManualTripleTapTimestamp = 0L
        isHoldOriginCountdown = false
        buttonHoldOriginState = null
        buttonHoldStartTimeMs = null
        countdownController.stopCountdown()
        stopEmergencySpeechMonitoring()
        stopSessionDurationTicker()
        stopPeriodicLocationUpdateLoop()
        locationProvider.stopLocationUpdates()
        try {
            foregroundServiceStopper?.invoke()
        } catch (_: Exception) {}

        // Provide cancellation vibration: 2 short pulses
        bleManager.sendVibration(HardwareProtocolConfig.CMD_VIBRATE_CANCEL)

        val alertStateAtCancel = current.contactAlertState
        val wasAlertSent = if (originatedInCountdown == true) {
            false
        } else if (originatedInCountdown == false) {
            true
        } else {
            alertStateAtCancel == ContactAlertState.SENT || alertStateAtCancel == ContactAlertState.DELIVERED
        }

        val outcome = if (wasAlertSent) "ALERT_SENT_THEN_CANCELLED" else "CANCELLED_BEFORE_ALERT"

        val updatedContactAlertState = if (!wasAlertSent) ContactAlertState.CANCELLED else alertStateAtCancel
        val sessionPoliceState = current.policeState

        _state.update {
            it.copy(
                sessionState = SessionState.CANCELLED,
                contactAlertState = updatedContactAlertState,
                policeState = PoliceState.NOT_REQUESTED,
                activeCallInProgress = false,
                callAttemptedInSession = false,
                lastEscalatedUtterance = null,
                lastCallEndedTimestamp = null,
                aiState = AiState.OFF,
                speechRecognitionStatus = "STOPPED",
                countdownRemainingSeconds = 0,
                activeSessionOutcome = outcome
            )
        }
        policeCallManager.resetState()

        // Cancel remote live tracking session
        cancelLiveTrackingSession(outcome)

        addTimelineEvent(
            "Session Cancelled",
            if (wasAlertSent) "Cancelled after alert was already sent. Recorded in session history."
            else "Cancelled before alert countdown expired. Alert cancelled.",
            TimelineEventType.CANCELLATION
        )

        saveSessionRecord(outcome = outcome, cancellationTimestamp = now, sessionPoliceState = sessionPoliceState)
    }

    // Workflow E: Active-State-Only Triple-Tap (Manual Police Escalation)
    private var lastManualTripleTapTimestamp: Long = 0L
    private val TRIPLE_TAP_DEBOUNCE_MS = 1000L

    fun handleTripleTap() {
        val current = _state.value
        val decision = escalationPolicy.evaluateManualTripleTap(current)

        if (!decision.shouldRequestPolice) {
            // Inactive or in countdown: triple-tap cannot request police
            addTimelineEvent("Triple-Tap Ignored", decision.reason, TimelineEventType.POLICE)
            return
        }

        val now = timeProvider()
        if (now - lastManualTripleTapTimestamp < TRIPLE_TAP_DEBOUNCE_MS) {
            addTimelineEvent(
                "Triple-Tap Debounced",
                "Ignored duplicate triple-tap within ${TRIPLE_TAP_DEBOUNCE_MS}ms",
                TimelineEventType.POLICE
            )
            return
        }
        lastManualTripleTapTimestamp = now

        // Valid manual request
        _state.update {
            it.copy(
                policeState = PoliceState.REQUESTED,
                callAttemptedInSession = true,
                activeCallInProgress = true
            )
        }
        bleManager.sendVibration(HardwareProtocolConfig.CMD_VIBRATE_POLICE)

        addTimelineEvent(
            "Police Requested (Manual)",
            "Hardware triple-tap received. Initiating emergency call.",
            TimelineEventType.POLICE
        )

        scope.launch {
            val escalationNum = preferences.escalationPhoneNumberFlow.first()
            val policeNumber = escalationNum.ifBlank { preferences.policeNumberFlow.first() }
            val callState = policeCallManager.initiateEmergencyCall(policeNumber, forceInitiate = true)
            val isSuccess = callState == PoliceState.CALL_INITIATED || callState == PoliceState.CONNECTED
            _state.update {
                it.copy(
                    policeState = callState,
                    activeCallInProgress = isSuccess,
                    escalationDestinationNumber = policeNumber
                )
            }
            addTimelineEvent("Police Call Status", "Call state: $callState", TimelineEventType.POLICE)
        }
    }

    // Workflow F: AI-Assisted Assessment & Escalation (AudioPipeline fallback loop)
    private fun startAiAssessmentLoop() {
        aiAnalysisJob?.cancel()
        aiAnalysisJob = scope.launch {
            while (isActive && _state.value.sessionState == SessionState.ACTIVE) {
                delay(4000L) // Analyze speech sample window every 4 seconds
                if (!isActive) break

                val sampleText = "[Audio captured from emergency microphone]"
                val assessment = try {
                    assessmentEngine.assessText(sampleText)
                } catch (_: Exception) {
                    continue
                }

                _state.update { it.copy(latestAssessment = assessment) }

                val aiEscalationEnabled = preferences.aiEscalationEnabledFlow.first()
                val sensitivity = preferences.escalationSensitivityFlow.first()

                val decision = escalationPolicy.evaluateAiAssessment(
                    state = _state.value,
                    assessment = assessment,
                    aiEscalationEnabled = aiEscalationEnabled,
                    sensitivity = sensitivity
                )

                _state.update { it.copy(consecutiveDistressCount = decision.updatedConsecutiveCount) }

                if (decision.shouldRequestPolice) {
                    val escalationNum = preferences.escalationPhoneNumberFlow.first()
                    val targetNumber = escalationNum.ifBlank { preferences.policeNumberFlow.first() }
                    executeAutomaticCallEscalation(targetNumber, decision.reason)
                    break
                }
            }
        }
    }

    private fun stopAiAssessmentLoop() {
        aiAnalysisJob?.cancel()
        aiAnalysisJob = null
    }

    fun endSession() {
        val current = _state.value
        if (!current.isEmergencyActive) return

        lastManualTripleTapTimestamp = 0L
        countdownController.stopCountdown()
        stopEmergencySpeechMonitoring()
        stopSessionDurationTicker()
        stopPeriodicLocationUpdateLoop()
        locationProvider.stopLocationUpdates()
        try {
            foregroundServiceStopper?.invoke()
        } catch (_: Exception) {}

        val outcome = when {
            current.policeState == PoliceState.CALL_INITIATED || current.policeState == PoliceState.CONNECTED -> "POLICE_REQUESTED"
            current.contactAlertState == ContactAlertState.SENT || current.contactAlertState == ContactAlertState.DELIVERED -> "ALERT_SENT"
            else -> "ENDED_SAFELY"
        }
        val sessionPoliceState = current.policeState

        _state.update {
            it.copy(
                sessionState = SessionState.ENDED,
                policeState = PoliceState.NOT_REQUESTED,
                activeCallInProgress = false,
                callAttemptedInSession = false,
                lastEscalatedUtterance = null,
                lastCallEndedTimestamp = null,
                aiState = AiState.OFF,
                speechRecognitionStatus = "STOPPED",
                countdownRemainingSeconds = 0,
                activeSessionOutcome = outcome
            )
        }
        policeCallManager.resetState()

        // Cancel remote live tracking session
        cancelLiveTrackingSession(outcome)

        addTimelineEvent("Session Ended", "Emergency session safely terminated by user.", TimelineEventType.SYSTEM)
        saveSessionRecord(outcome = outcome, cancellationTimestamp = null, sessionPoliceState = sessionPoliceState)
    }

    fun toggleStealthMode() {
        _state.update { it.copy(isStealthMode = !it.isStealthMode) }
    }

    fun triggerManualSosBackup() {
        handleDoublePress()
    }

    /**
     * Reconnects to the authoritative ESP32 Bluetooth Classic device (or simulated BLE if in demo mode).
     */
    fun reconnectDevice(targetAddress: String? = null) {
        val receiver = btAudioReceiver ?: voskTranscriptionService?.audioRouter?.esp32AudioReceiver
        if (receiver != null) {
            receiver.connect(targetAddress)
        } else if (bleManager.isSimulated) {
            bleManager.connect(targetAddress ?: "AA:BB:CC:DD:EE:01")
        }
    }

    /**
     * Disconnects the authoritative ESP32 Bluetooth Classic device (or simulated BLE if in demo mode).
     */
    fun disconnectDevice() {
        val receiver = btAudioReceiver ?: voskTranscriptionService?.audioRouter?.esp32AudioReceiver
        if (receiver != null) {
            receiver.disconnect()
        } else if (bleManager.isSimulated) {
            bleManager.disconnect()
        }
    }

    fun triggerManualPoliceCall() {
        val current = _state.value
        val isCountdownRunning = current.sessionState == SessionState.ACTIVATING ||
                current.isCountdownActive ||
                current.contactAlertState == ContactAlertState.COUNTDOWN ||
                current.countdownRemainingSeconds > 0
        if (!current.isEmergencyActive || isCountdownRunning) {
            return
        }

        val now = timeProvider()
        if (now - lastManualTripleTapTimestamp < TRIPLE_TAP_DEBOUNCE_MS) {
            return
        }
        lastManualTripleTapTimestamp = now

        _state.update {
            it.copy(
                policeState = PoliceState.REQUESTED,
                callAttemptedInSession = true,
                activeCallInProgress = true
            )
        }
        scope.launch {
            val escalationNum = preferences.escalationPhoneNumberFlow.first()
            val targetNumber = escalationNum.ifBlank { preferences.policeNumberFlow.first() }
            val state = policeCallManager.initiateEmergencyCall(targetNumber, forceInitiate = true)
            val isSuccess = state == PoliceState.CALL_INITIATED || state == PoliceState.CONNECTED
            _state.update {
                it.copy(
                    policeState = state,
                    activeCallInProgress = isSuccess,
                    escalationDestinationNumber = targetNumber
                )
            }
            addTimelineEvent("Police Call Initiated", "Manual in-app backup call to $targetNumber", TimelineEventType.POLICE)
        }
    }

    fun sendSafeFollowUpMessage(onComplete: (Boolean) -> Unit = {}) {
        scope.launch {
            val contacts = contactRepository.getContacts()
            if (contacts.isEmpty()) {
                onComplete(false)
                return@launch
            }
            addTimelineEvent("Safe Follow-up", "Sending 'I am safe' SMS to trusted contacts.", TimelineEventType.CONTACT_ALERT)
            alertDispatcher.sendSafeFollowUp(contacts) { contactId, alertState ->
                _state.update { current ->
                    val map = current.contactAlertStatuses.toMutableMap()
                    map[contactId] = alertState
                    current.copy(contactAlertStatuses = map)
                }
            }
            addTimelineEvent("Safe Follow-up Sent", "Delivery reported to all configured contacts.", TimelineEventType.CONTACT_ALERT)
            onComplete(true)
        }
    }

    private fun addTimelineEvent(title: String, detail: String, type: TimelineEventType) {
        val event = TimelineEvent(title = title, detail = detail, type = type)
        _state.update {
            val updated = it.timeline.toMutableList()
            updated.add(0, event) // Most recent first
            it.copy(timeline = updated)
        }
    }

    private fun startSessionDurationTicker() {
        sessionDurationJob?.cancel()
        sessionDurationJob = scope.launch {
            while (isActive) {
                delay(1000L)
                val now = timeProvider()
                _state.update { current ->
                    val updatedDuration = current.sessionDurationSeconds + 1
                    val locTimestamp = current.locationTimestamp
                    val isNowStale = if (locTimestamp != null && locTimestamp > 0) {
                        (now - locTimestamp) > com.example.sakhi.data.location.AndroidLocationProvider.FRESHNESS_THRESHOLD_MS
                    } else {
                        current.isLocationStale
                    }
                    val updatedHighAccuracy = current.isLocationHighAccuracy && !isNowStale
                    current.copy(
                        sessionDurationSeconds = updatedDuration,
                        isLocationStale = isNowStale,
                        isLocationHighAccuracy = updatedHighAccuracy
                    )
                }
            }
        }
    }

    private fun stopSessionDurationTicker() {
        sessionDurationJob?.cancel()
        sessionDurationJob = null
    }

    private fun initiateLiveTrackingSession() {
        scope.launch {
            try {
                val enabled = preferences.liveTrackingEnabledFlow.first()
                if (!enabled) return@launch

                val backendUrl = preferences.liveTrackingUrlFlow.first()
                val loc = locationProvider.getBestLocation()
                val result = liveTrackingClient.createSession(backendUrl, loc)

                result.onSuccess { session ->
                    _state.update {
                        it.copy(
                            liveTrackingUrl = session.trackingUrl,
                            liveTrackingSessionId = session.sessionId,
                            liveTrackingWriterKey = session.writerKey,
                            isLiveTrackingActive = true,
                            lastLiveSyncTimestamp = timeProvider()
                        )
                    }
                    addTimelineEvent(
                        "Live Tracking Active",
                        "Live tracking link created: ${session.trackingUrl}",
                        TimelineEventType.LOCATION
                    )
                }.onFailure { e ->
                    addTimelineEvent(
                        "Live Tracking Offline",
                        "Tracking server unreachable (${e.message}). Direct Maps link active.",
                        TimelineEventType.LOCATION
                    )
                }
            } catch (e: Exception) {
                // Non-critical: offline SMS safety invariants remain intact
            }
        }
    }

    fun onPeriodicIntervalChanged() {
        val current = _state.value
        if (current.isEmergencyActive &&
            (current.contactAlertState == ContactAlertState.SENT || current.contactAlertState == ContactAlertState.DELIVERED)
        ) {
            startPeriodicLocationUpdateLoop()
        }
    }

    private fun startPeriodicLocationUpdateLoop() {
        periodicLocationUpdateJob?.cancel()
        periodicLocationUpdateJob = scope.launch {
            var updateCycleNumber = 0
            var lastUploadedLocTimestamp: Long? = null

            while (isActive && _state.value.isEmergencyActive) {
                val intervalSec = preferences.periodicSmsIntervalFlow.first().coerceAtLeast(15)
                val intervalMs = intervalSec * 1000L
                delay(intervalMs)
                if (!isActive || !_state.value.isEmergencyActive) break

                val rawLoc = locationProvider.getBestLocation()
                val now = timeProvider()
                val loc = rawLoc?.withFreshness(now)
                val current = _state.value

                // 1. Upload updated coordinates to backend if live tracking is active
                if (current.isLiveTrackingActive && current.liveTrackingSessionId != null && current.liveTrackingWriterKey != null && loc != null && !loc.isStale) {
                    val shouldUpload = lastUploadedLocTimestamp != loc.timestamp
                    if (shouldUpload) {
                        val backendUrl = preferences.liveTrackingUrlFlow.first()
                        val uploadResult = liveTrackingClient.uploadLocation(
                            baseUrl = backendUrl,
                            sessionId = current.liveTrackingSessionId,
                            writerKey = current.liveTrackingWriterKey,
                            location = loc
                        )
                        uploadResult.onSuccess {
                            lastUploadedLocTimestamp = loc.timestamp
                            _state.update { it.copy(lastLiveSyncTimestamp = timeProvider()) }
                        }
                    }
                }

                // 2. Dispatch periodic location update SMS if enabled
                val periodicEnabled = preferences.periodicSmsEnabledFlow.first()
                if (periodicEnabled && (current.contactAlertState == ContactAlertState.SENT || current.contactAlertState == ContactAlertState.DELIVERED)) {
                    updateCycleNumber++
                    val contacts = contactRepository.getContacts()
                    val includeSpeech = preferences.includeSpeechInSmsFlow.first()
                    val nextPhrase = if (includeSpeech) riskAnalyzer.consumeNextPhraseForSms() else null
                    val speechExcerpt = nextPhrase?.exactText
                    val assessmentLabel = when (nextPhrase?.indicator) {
                        DistressCategory.POSSIBLE_DISTRESS -> "Possible distress indicator"
                        DistressCategory.ELEVATED_CONCERN -> "Elevated concern"
                        DistressCategory.UNCERTAIN -> "Uncertain speech context"
                        else -> null
                    }

                    val sentCount = alertDispatcher.sendPeriodicLocationUpdate(
                        contacts = contacts,
                        location = loc,
                        liveTrackingUrl = current.liveTrackingUrl,
                        updateNumber = updateCycleNumber,
                        speechExcerpt = speechExcerpt,
                        assessmentLabel = assessmentLabel,
                        onContactStatusUpdate = { contactId, cState ->
                            _state.update { state ->
                                val statusMap = state.contactAlertStatuses.toMutableMap()
                                statusMap[contactId] = cState

                                val countMap = state.contactMessageCounts.toMutableMap()
                                val timeMap = state.contactLastSendTimestamps.toMutableMap()
                                if (cState == ContactAlertState.SENT || cState == ContactAlertState.DELIVERED) {
                                    countMap[contactId] = (countMap[contactId] ?: 0) + 1
                                    timeMap[contactId] = timeProvider()
                                }

                                state.copy(
                                    contactAlertStatuses = statusMap,
                                    contactMessageCounts = countMap,
                                    contactLastSendTimestamps = timeMap
                                )
                            }
                        }
                    )
                    if (sentCount > 0) {
                        _state.update { it.copy(periodicSmsCount = it.periodicSmsCount + sentCount) }
                        val speechNote = if (!speechExcerpt.isNullOrBlank()) " (with speech excerpt)" else ""
                        val staleNote = if (loc?.isStale == true) " (stale coordinates)" else ""
                        addTimelineEvent(
                            "Periodic Location SMS #$updateCycleNumber",
                            "Dispatched updated coordinates$speechNote$staleNote to $sentCount contact(s).",
                            TimelineEventType.CONTACT_ALERT
                        )
                    }
                }
            }
        }
    }

    private fun stopPeriodicLocationUpdateLoop() {
        periodicLocationUpdateJob?.cancel()
        periodicLocationUpdateJob = null
    }

    private fun cancelLiveTrackingSession(outcome: String) {
        val current = _state.value
        val sessionId = current.liveTrackingSessionId
        val writerKey = current.liveTrackingWriterKey
        if (current.isLiveTrackingActive && sessionId != null && writerKey != null) {
            scope.launch {
                try {
                    val backendUrl = preferences.liveTrackingUrlFlow.first()
                    liveTrackingClient.cancelSession(
                        baseUrl = backendUrl,
                        sessionId = sessionId,
                        writerKey = writerKey,
                        reason = outcome
                    )
                    _state.update { it.copy(isLiveTrackingActive = false) }
                    addTimelineEvent(
                        "Live Tracking Revoked",
                        "Tracking session ended on remote gateway.",
                        TimelineEventType.SYSTEM
                    )
                } catch (_: Exception) {}
            }
        }
    }

    private fun saveSessionRecord(
        outcome: String,
        cancellationTimestamp: Long?,
        sessionPoliceState: PoliceState? = null
    ) {
        val current = _state.value
        val session = EmergencySession(
            id = current.activeSessionId ?: UUID.randomUUID().toString(),
            startTime = current.sessionStartTimestamp ?: System.currentTimeMillis(),
            endTime = System.currentTimeMillis(),
            outcome = outcome,
            latitude = current.latitude,
            longitude = current.longitude,
            locationAccuracy = current.locationAccuracy,
            locationAddress = current.locationAddress,
            contactAlertState = current.contactAlertState,
            policeState = sessionPoliceState ?: current.policeState,
            aiState = current.aiState,
            cancellationTimestamp = cancellationTimestamp,
            timeline = current.timeline,
            liveTrackingUrl = current.liveTrackingUrl,
            hindiTranscripts = current.hindiTranscriptHistory,
            englishTranscripts = current.englishTranscriptHistory
        )

        scope.launch {
            sessionHistoryRepository.saveSession(session)
        }
    }
}

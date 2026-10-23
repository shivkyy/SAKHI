package com.example.sakhi.core.model

data class EmergencyState(
    val sessionState: SessionState = SessionState.IDLE,
    val contactAlertState: ContactAlertState = ContactAlertState.NOT_STARTED,
    val policeState: PoliceState = PoliceState.NOT_REQUESTED,
    val keychainLinkState: KeychainLinkState = KeychainLinkState.DISCONNECTED,
    val aiState: AiState = AiState.OFF,

    // Active session details
    val activeSessionId: String? = null,
    val sessionStartTimestamp: Long? = null,
    val sessionDurationSeconds: Long = 0L,

    // Countdown details
    val countdownRemainingSeconds: Int = 0,
    val countdownTotalSeconds: Int = 5,

    // Location info
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracy: Float? = null,
    val locationAddress: String? = null,
    val isLocationStale: Boolean = false,
    val locationTimestamp: Long? = null,

    // Hardware device status
    val deviceName: String = "SAKHI Keychain",
    val deviceAddress: String? = null,
    val deviceBatteryPercent: Int? = null,
    val deviceRssi: Int? = null,
    val deviceFirmwareVersion: String? = null,
    val lastSeenTimestamp: Long? = null,

    // Contact alert tracking per contact
    val contactAlertStatuses: Map<Long, ContactAlertState> = emptyMap(),
    val contactMessageCounts: Map<Long, Int> = emptyMap(),
    val contactLastSendTimestamps: Map<Long, Long> = emptyMap(),

    // AI distress assessment & Emergency Speech Intelligence
    val latestAssessment: DistressAssessment? = null,
    val consecutiveDistressCount: Int = 0,
    val liveTranscript: String = "",
    val recentRelevantPhrases: List<String> = emptyList(),
    val speechRecognitionStatus: String = "INACTIVE",

    // Call Escalation status
    val escalationDestinationNumber: String? = null,
    val callAttemptedInSession: Boolean = false,
    val activeCallInProgress: Boolean = false,
    val completedCallCount: Int = 0,
    val lastCallEndedTimestamp: Long? = null,
    val lastEscalatedUtterance: String? = null,
    val escalationFailureReason: String? = null,

    // Timeline of events for current/latest session
    val timeline: List<TimelineEvent> = emptyList(),

    // In-app flags
    val isStealthMode: Boolean = false,
    val isSimulatedDevice: Boolean = false,
    val activeSessionOutcome: String? = null,

    // Live Web Tracking
    val liveTrackingUrl: String? = null,
    val liveTrackingSessionId: String? = null,
    val liveTrackingWriterKey: String? = null,
    val isLiveTrackingActive: Boolean = false,
    val lastLiveSyncTimestamp: Long? = null,
    val periodicSmsCount: Int = 0,

    // Parallel Dual ASR streams
    val hindiStatusText: String = "INACTIVE",
    val hindiPartialText: String = "",
    val hindiLatestFinalText: String = "",
    val hindiTranscriptHistory: List<TranscriptSegment> = emptyList(),

    val englishStatusText: String = "INACTIVE",
    val englishPartialText: String = "",
    val englishLatestFinalText: String = "",
    val englishTranscriptHistory: List<TranscriptSegment> = emptyList(),

    // Live Microphone Telemetry
    val activeAudioSource: String = "NONE",
    val isAudioSwitching: Boolean = false,
    val isAudioDataArriving: Boolean = false,
    val lastAudioChunkTimestamp: Long? = null,
    val audioRmsVolume: Float = 0f,
    val audioSampleRateHz: Int = 8000,
    val btReconnectAttempts: Int = 0,
    val audioSourceErrorMessage: String? = null,
    val btClassicState: String = "DISCONNECTED",
    val btClassicErrorMessage: String? = null,

    // Noise Suppression Telemetry
    val speexState: String = "OFF",
    val speexFramesProcessed: Long = 0L,
    val speexErrorMessage: String? = null,

    // Location Accuracy Telemetry
    val locationProviderType: String? = null,
    val locationAccuracyStatus: String = "ACQUIRING LOCATION",
    val isLocationHighAccuracy: Boolean = false
) {
    val isEmergencyActive: Boolean
        get() = sessionState == SessionState.ACTIVATING || sessionState == SessionState.ACTIVE

    val isCountdownActive: Boolean
        get() = contactAlertState == ContactAlertState.COUNTDOWN && countdownRemainingSeconds > 0

    val googleMapsUrl: String?
        get() = if (latitude != null && longitude != null) {
            "https://maps.google.com/?q=$latitude,$longitude"
        } else null

    val microphoneDisplayStatus: String
        get() = when {
            isAudioSwitching -> "SWITCHING AUDIO SOURCE"
            audioSourceErrorMessage != null -> "AUDIO SOURCE ERROR"
            activeAudioSource == "ESP32_BLUETOOTH" -> "ESP32 MICROPHONE — ACTIVE"
            activeAudioSource == "PHONE_MICROPHONE" -> "PHONE MICROPHONE — ACTIVE"
            else -> "NO ACTIVE MICROPHONE"
        }

    val noiseSuppressionDisplayStatus: String
        get() = when (speexState) {
            "ACTIVE_PROCESSING" -> "ON / PROCESSING AUDIO"
            "INITIALIZING" -> "ON / INITIALIZING"
            "FALLBACK_UNAVAILABLE" -> "ERROR / Speech enhancement is unavailable"
            else -> if (isEmergencyActive) "MANDATORY / CONNECTING" else "OFF"
        }

    val locationDisplayStatus: String
        get() = when {
            latitude == null || longitude == null -> "ACQUIRING LOCATION..."
            isLocationStale -> "LOCATION STALE"
            locationAccuracy == null -> "APPROXIMATE LOCATION"
            locationAccuracy <= 20f -> "HIGH ACCURACY FIX (±${locationAccuracy.toInt()}m)"
            locationAccuracy <= 100f -> "MEDIUM ACCURACY FIX (±${locationAccuracy.toInt()}m)"
            else -> "APPROXIMATE LOCATION (±${locationAccuracy.toInt()}m)"
        }
}

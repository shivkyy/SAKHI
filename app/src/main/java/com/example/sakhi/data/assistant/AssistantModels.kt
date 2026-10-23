package com.example.sakhi.data.assistant

import com.example.sakhi.core.model.DistressAssessment

/**
 * Visual lifecycle states for the SAKHI Intelligence floating orb and bottom sheet.
 */
enum class AssistantVisualState {
    /** Microphone inactive, resting state. No looping animations running. */
    IDLE,

    /** Microphone actively capturing audio stream. */
    LISTENING,

    /** Speech recognizer actively receiving voice input and decoding partial/final text. */
    TRANSCRIBING,

    /** User requested explicit AI evaluation (e.g. Gemini / distress analysis). */
    PROCESSING,

    /** Recoverable error state (e.g. permission denied, recognizer unavailable). */
    ERROR
}

/**
 * Pluggable audio-source interface for SAKHI Intelligence.
 * Allows seamless extension from phone microphone to wearable BLE audio when firmware supports it.
 */
interface AssistantAudioSource {
    val id: String
    val displayName: String
    val isAvailable: Boolean
    val statusDescription: String
}

/**
 * Default audio source: Android phone built-in microphone.
 */
class PhoneMicrophoneAudioSource(
    private val hasPermission: () -> Boolean
) : AssistantAudioSource {
    override val id: String = "PHONE_MIC"
    override val displayName: String = "Phone Microphone"
    override val isAvailable: Boolean get() = hasPermission()
    override val statusDescription: String = "Capturing from device microphone (foreground)"
}

/**
 * Placeholder audio source for future SAKHI ESP32-S3 BLE wearable audio streaming.
 * NOTE: ESP32-S3 BLE audio streaming is currently NOT implemented in the wearable firmware
 * or Android BLE GATT profile. This stub ensures clean architecture without false claims.
 */
class BleKeychainAudioSource(
    private val isConnected: () -> Boolean
) : AssistantAudioSource {
    override val id: String = "BLE_KEYCHAIN"
    override val displayName: String = "SAKHI Wearable (BLE)"
    override val isAvailable: Boolean = false // Not yet supported in hardware
    override val statusDescription: String = "Wearable audio streaming not supported in current firmware"
}

/**
 * Immutable state snapshot for SAKHI Intelligence.
 */
data class AssistantState(
    val visualState: AssistantVisualState = AssistantVisualState.IDLE,
    val transcript: String = "",
    val partialText: String = "",
    val audioLevelRms: Float = 0f,
    val latestAnalysis: DistressAssessment? = null,
    val errorMessage: String? = null,
    val isBottomSheetVisible: Boolean = false,
    val activeAudioSourceId: String = "PHONE_MIC",
    val requiresPermissionPrompt: Boolean = false
) {
    /**
     * Complete visible transcript combining finalized text and live in-progress speech.
     */
    val fullDisplayTranscript: String
        get() = when {
            transcript.isBlank() && partialText.isBlank() -> ""
            transcript.isBlank() -> partialText
            partialText.isBlank() -> transcript
            else -> "$transcript $partialText"
        }

    val isListeningOrTranscribing: Boolean
        get() = visualState == AssistantVisualState.LISTENING || visualState == AssistantVisualState.TRANSCRIBING
}

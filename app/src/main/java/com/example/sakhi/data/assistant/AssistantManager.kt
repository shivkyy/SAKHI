package com.example.sakhi.data.assistant

import com.example.sakhi.data.ai.AssessmentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Central State Manager for SAKHI Intelligence.
 * Preserves assistant state, transcript, and audio configuration across screen navigation.
 */
class AssistantManager(
    private val transcriptionService: SpeechTranscriptionService,
    private val assessmentEngineProvider: () -> AssessmentEngine,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {

    private val _state = MutableStateFlow(AssistantState())
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    private var analysisJob: Job? = null

    /**
     * Toggles or sets bottom sheet visibility.
     */
    fun setBottomSheetVisible(visible: Boolean) {
        _state.update { it.copy(isBottomSheetVisible = visible) }
    }

    /**
     * Starts microphone capture and live speech transcription.
     * Guaranteed never to run automatically on app launch.
     */
    fun startListening(hasMicPermission: Boolean) {
        if (!hasMicPermission) {
            _state.update {
                it.copy(
                    visualState = AssistantVisualState.ERROR,
                    errorMessage = "Microphone permission is required for SAKHI Intelligence to transcribe speech.",
                    requiresPermissionPrompt = true
                )
            }
            return
        }

        if (!transcriptionService.isAvailable) {
            _state.update {
                it.copy(
                    visualState = AssistantVisualState.ERROR,
                    errorMessage = "Speech recognition service is not available on this device. Please check Google Speech Services settings."
                )
            }
            return
        }

        // Cancel any pending analysis
        analysisJob?.cancel()

        _state.update {
            it.copy(
                visualState = AssistantVisualState.LISTENING,
                errorMessage = null,
                partialText = "",
                requiresPermissionPrompt = false
            )
        }

        transcriptionService.startListening(
            onReady = {
                _state.update {
                    it.copy(
                        visualState = AssistantVisualState.LISTENING,
                        errorMessage = null
                    )
                }
            },
            onBeginningOfSpeech = {
                _state.update {
                    it.copy(visualState = AssistantVisualState.TRANSCRIBING)
                }
            },
            onRmsChanged = { level ->
                _state.update {
                    it.copy(audioLevelRms = level)
                }
            },
            onPartialResult = { partial ->
                _state.update {
                    it.copy(
                        partialText = partial,
                        visualState = AssistantVisualState.TRANSCRIBING
                    )
                }
            },
            onFinalResult = { text ->
                _state.update { current ->
                    val combined = if (current.transcript.isBlank()) {
                        text
                    } else if (text.isNotBlank()) {
                        "${current.transcript} $text"
                    } else {
                        current.transcript
                    }
                    current.copy(
                        transcript = combined.trim(),
                        partialText = "",
                        audioLevelRms = 0f,
                        visualState = AssistantVisualState.IDLE
                    )
                }
            },
            onError = { message, _ ->
                _state.update {
                    it.copy(
                        visualState = AssistantVisualState.ERROR,
                        errorMessage = message,
                        partialText = "",
                        audioLevelRms = 0f
                    )
                }
            }
        )
    }

    /**
     * Explicitly stops microphone capture and releases audio resources.
     */
    fun stopListening() {
        transcriptionService.stopListening()
        _state.update { current ->
            // Promote partial text to transcript if speech recognizer stopped mid-utterance
            val finalTranscript = if (current.partialText.isNotBlank()) {
                if (current.transcript.isBlank()) current.partialText
                else "${current.transcript} ${current.partialText}"
            } else {
                current.transcript
            }
            current.copy(
                transcript = finalTranscript.trim(),
                partialText = "",
                audioLevelRms = 0f,
                visualState = AssistantVisualState.IDLE
            )
        }
    }

    /**
     * Clears transcript and resets previous assessment results.
     */
    fun clearTranscript() {
        stopListening()
        _state.update {
            it.copy(
                transcript = "",
                partialText = "",
                latestAnalysis = null,
                errorMessage = null
            )
        }
    }

    /**
     * Runs an explicit AI safety assessment on the current transcript.
     * Uses the configured AssessmentEngine (local benchmark or Gemini API).
     * Completely decoupled from emergency workflows; never dispatches SMS or alerts contacts.
     */
    fun runAiAnalysis() {
        val currentText = _state.value.fullDisplayTranscript.trim()
        if (currentText.isBlank()) {
            _state.update {
                it.copy(
                    visualState = AssistantVisualState.ERROR,
                    errorMessage = "No speech captured. Tap 'Start Listening' or speak into the microphone before requesting AI Analysis."
                )
            }
            return
        }

        // Stop active listening before running AI assessment
        if (_state.value.isListeningOrTranscribing) {
            stopListening()
        }

        analysisJob?.cancel()
        _state.update {
            it.copy(
                visualState = AssistantVisualState.PROCESSING,
                errorMessage = null
            )
        }

        analysisJob = scope.launch {
            try {
                val engine = assessmentEngineProvider()
                val assessment = engine.assessText(currentText)
                _state.update {
                    it.copy(
                        latestAnalysis = assessment,
                        visualState = AssistantVisualState.IDLE
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        visualState = AssistantVisualState.ERROR,
                        errorMessage = "AI Analysis failed: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Dismisses the error state and returns to IDLE.
     */
    fun dismissError() {
        _state.update {
            it.copy(
                visualState = AssistantVisualState.IDLE,
                errorMessage = null,
                requiresPermissionPrompt = false
            )
        }
    }

    /**
     * Releases recognizer resources.
     */
    fun release() {
        analysisJob?.cancel()
        transcriptionService.destroy()
    }
}

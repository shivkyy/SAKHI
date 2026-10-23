package com.example.sakhi.data.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Service interface abstracting speech transcription.
 * Enables clean unit testing and pluggable audio sources (e.g. Android Phone Mic vs Wearable BLE).
 */
interface SpeechTranscriptionService {
    val isAvailable: Boolean
    fun startListening(
        onReady: () -> Unit,
        onBeginningOfSpeech: () -> Unit,
        onRmsChanged: (Float) -> Unit,
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (message: String, errorCode: Int) -> Unit
    )
    fun stopListening()
    fun destroy()
}

/**
 * Production Android SpeechRecognizer implementation utilizing the device's
 * installed speech recognition engine (Google Speech Services or on-device engine).
 */
class AndroidSpeechTranscriptionService(
    private val context: Context
) : SpeechTranscriptionService {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isCurrentlyListening = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun runOnMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    override val isAvailable: Boolean
        get() = try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (_: Exception) {
            false
        }

    override fun startListening(
        onReady: () -> Unit,
        onBeginningOfSpeech: () -> Unit,
        onRmsChanged: (Float) -> Unit,
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (message: String, errorCode: Int) -> Unit
    ) {
        if (!isAvailable) {
            onError("Speech recognition service is unavailable on this device. Please install or enable Google Speech Services.", -1)
            return
        }

        runOnMain {
            // Cleanup any stale instance first
            destroyInternal()

            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            isCurrentlyListening = true
                            onReady()
                        }

                        override fun onBeginningOfSpeech() {
                            onBeginningOfSpeech()
                        }

                        override fun onRmsChanged(rmsdB: Float) {
                            // Normalize -2dB..10dB to 0f..1f for visualizer
                            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                            onRmsChanged(normalized)
                        }

                        override fun onBufferReceived(buffer: ByteArray?) {
                            // Transient buffer not retained (preserving privacy)
                        }

                        override fun onEndOfSpeech() {
                            isCurrentlyListening = false
                        }

                        override fun onError(error: Int) {
                            isCurrentlyListening = false
                            val userMessage = mapSpeechError(error)
                            onError(userMessage, error)
                        }

                        override fun onResults(results: Bundle?) {
                            isCurrentlyListening = false
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull() ?: ""
                            onFinalResult(text)
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull() ?: ""
                            onPartialResult(text)
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                }

                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                isCurrentlyListening = false
                onError("Failed to initialize speech recognizer: ${e.message}", -2)
            }
        }
    }

    override fun stopListening() {
        runOnMain {
            if (isCurrentlyListening) {
                try {
                    speechRecognizer?.stopListening()
                } catch (_: Exception) {}
                isCurrentlyListening = false
            }
        }
    }

    override fun destroy() {
        runOnMain {
            destroyInternal()
        }
    }

    private fun destroyInternal() {
        isCurrentlyListening = false
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }

    private fun mapSpeechError(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording failed. Check if another app is using the microphone."
            SpeechRecognizer.ERROR_CLIENT -> "Speech recognition client error. Please try again."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required for speech recognition."
            SpeechRecognizer.ERROR_NETWORK -> "Network error during speech recognition. Ensure internet access is available."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network connection timed out while processing speech."
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Please speak clearly into the microphone."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service is busy. Please wait a moment and try again."
            SpeechRecognizer.ERROR_SERVER -> "Speech server encountered an error. Please retry."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Microphone stopped listening."
            else -> "Speech recognition error (Code $errorCode)."
        }
    }
}

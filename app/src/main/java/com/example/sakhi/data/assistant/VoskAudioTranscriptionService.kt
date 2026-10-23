package com.example.sakhi.data.assistant

import android.content.Context
import android.util.Log
import com.example.sakhi.core.model.DistressAssessment
import com.example.sakhi.core.model.TranscriptSegment
import com.example.sakhi.core.preferences.SakhiPreferences
import com.example.sakhi.data.ai.EmergencyRiskAnalyzer
import com.example.sakhi.data.audio.ActiveAudioSource
import com.example.sakhi.data.audio.PcmAudioResampler
import com.example.sakhi.data.audio.PhoneMicrophoneAudioSource
import com.example.sakhi.data.audio.SpeexNoiseSuppressor
import com.example.sakhi.data.audio.SpeexOperationalState
import com.example.sakhi.data.audio.SpeexTelemetry
import com.example.sakhi.data.audio.UnifiedAudioRouter
import com.example.sakhi.data.bluetooth.BluetoothClassicAudioReceiver
import com.example.sakhi.data.bluetooth.BtAudioConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Operating modes for Vosk offline speech recognition.
 */
enum class VoskRecognitionMode {
    /** Constrained to safety distress grammar (production keywords). */
    SAFETY_GRAMMAR,

    /** Unrestricted open vocabulary (unconstrained Kaldi language model). */
    UNRESTRICTED,

    /** Dual diagnostic: Both recognizers run concurrently on identical audio chunks for direct comparison. */
    DUAL_DIAGNOSTIC,

    /** Unified Bilingual: English (8 kHz) and Hindi (16 kHz resampled) recognizers decode simultaneously with arbitration. */
    BILINGUAL
}

/**
 * Explicit session types for Vosk speech recognition.
 * Enforces strict isolation between diagnostic tests and production emergency sessions.
 */
enum class RecognitionSessionType {
    /** Recognition is idle / stopped. */
    INACTIVE,

    /** Diagnostic test session: isolation is strictly enforced; emergency callbacks are disabled. */
    DIAGNOSTIC,

    /** Production SOS emergency session: validated against an active emergency session ID. */
    PRODUCTION_SOS
}

/**
 * Acoustic model verification details confirming sample rate, files, and compatibility.
 */
data class GrammarValidationResult(
    val totalPhrases: Int,
    val supportedPhrases: List<String>,
    val unsupportedPhrases: List<String>,
    val missingWords: Set<String>,
    val isValid: Boolean
)

data class ModelVerificationResult(
    val modelDir: String,
    val sampleFrequency: Float?,
    val is8kSupported: Boolean,
    val hasAcousticModel: Boolean,
    val hasGraph: Boolean,
    val verificationDetails: String,
    val grammarValidation: GrammarValidationResult? = null
)

/**
 * Unified Bilingual Speech Recognition Service for SAKHI.
 *
 * Architecture:
 * UnifiedAudioRouter (ESP32 Bluetooth Classic SPP or Phone Mic)
 *   -> Source-transition handling (flushes SpeexDSP framing buffer & Kaldi lattices)
 *   -> Shared SpeexNoiseSuppressor (8 kHz denoiser)
 *   -> English Vosk recognizer at 8 kHz
 *   -> Hindi Vosk recognizer at 16 kHz after linear resampling (PcmAudioResampler)
 *   -> BilingualTranscriptArbitrator (objective arbitration comparing confidence, completeness, timing)
 *   -> Authoritative transcript dispatch to EmergencyRiskAnalyzer & SAKHI emergency workflow
 *
 * Invariants:
 * 1. Automatic bilingual recognition without manual language toggling.
 * 2. Exactly one authoritative transcript dispatched per spoken utterance (zero duplicate or conflicting dispatches).
 * 3. Never selects a transcript solely because it contains a distress keyword.
 * 4. Diagnostic Isolation is ACTIVE by default (zero unintended emergency calls or SMS during testing).
 * 5. SpeexDSP framing buffer and recognizer states are cleanly reset on source transitions.
 */
open class VoskAudioTranscriptionService(
    private val context: Context? = null,
    val audioRouter: UnifiedAudioRouter,
    val modelManager: VoskModelManager? = null,
    private val scope: CoroutineScope,
    private val preferences: SakhiPreferences? = null,
    val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    /**
     * Backwards-compatible constructor for existing tests and callers passing BluetoothClassicAudioReceiver directly.
     */
    constructor(
        context: Context,
        audioReceiver: BluetoothClassicAudioReceiver,
        modelManager: VoskModelManager,
        scope: CoroutineScope,
        preferences: SakhiPreferences? = null
    ) : this(
        context = context,
        audioRouter = UnifiedAudioRouter(
            esp32AudioReceiver = audioReceiver,
            phoneAudioSource = PhoneMicrophoneAudioSource(context = context, scope = scope),
            scope = scope
        ),
        modelManager = modelManager,
        scope = scope,
        preferences = preferences
    )

    companion object {
        private const val TAG = "VoskAudioService"
        private const val REPEAT_SUPPRESSION_WINDOW_MS = 3500L

        // High-priority English emergency vocabulary grammar
        val SAKHI_SAFETY_GRAMMAR = """
            [
                "help",
                "help me",
                "please help",
                "please help me",
                "somebody help",
                "someone help",
                "save me",
                "call police",
                "call the police",
                "leave me alone",
                "get away",
                "get away from me",
                "let me go",
                "stop following me",
                "don't touch me",
                "do not touch me",
                "i am in danger",
                "in danger",
                "emergency",
                "call emergency",
                "[unk]"
            ]
        """.trimIndent()

        // High-priority Hindi Devanagari emergency vocabulary grammar
        val SAKHI_HINDI_SAFETY_GRAMMAR = """
            [
                "मुझे बचाओ",
                "बचा लो",
                "कृपया बचाओ",
                "मेरी मदद करो",
                "कोई मेरी मदद करो",
                "मदद करो",
                "कृपया मदद करो",
                "पुलिस बुलाओ",
                "पुलिस को बुलाओ",
                "मेरा पीछा मत करो",
                "मुझे छोड़ दो",
                "हाथ मत लगाओ",
                "दूर रहो",
                "[unk]"
            ]
        """.trimIndent()

        // List of legacy Hindi distress phrases that are unsupported by the English model
        val UNSUPPORTED_HINDI_PHRASES = listOf(
            "bachao",
            "madad karo",
            "chhod do",
            "police bulao",
            "khatra hai",
            "dar lag raha hai"
        )

        fun countGrammarPhrases(grammarJson: String): Int {
            val phrases = Regex(""""([^"]+)"""").findAll(grammarJson).map { it.groupValues[1] }
            return phrases.count { it != "[unk]" }
        }

        fun extractTextFromJson(jsonStr: String): String {
            val match = Regex(""""text"\s*:\s*"([^"]*)"""").find(jsonStr)
            if (match != null) {
                return match.groupValues[1]
            }
            return try {
                JSONObject(jsonStr).optString("text", "")
            } catch (_: Exception) {
                ""
            }
        }

        fun extractPartialFromJson(jsonStr: String): String {
            val match = Regex(""""partial"\s*:\s*"([^"]*)"""").find(jsonStr)
            if (match != null) {
                return match.groupValues[1]
            }
            return try {
                JSONObject(jsonStr).optString("partial", "")
            } catch (_: Exception) {
                ""
            }
        }

        fun detectModelSampleRate(modelPath: String): Float? {
            return try {
                val confFile = File(modelPath, "conf/mfcc.conf")
                if (confFile.exists()) {
                    val lines = confFile.readLines()
                    for (line in lines) {
                        if (line.startsWith("--sample-frequency=")) {
                            val freqStr = line.substringAfter("--sample-frequency=").trim()
                            return freqStr.toFloatOrNull()
                        }
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }

        fun extractWordsFromGrFst(grFstFile: File): Set<String> {
            if (!grFstFile.exists() || !grFstFile.isFile) return emptySet()
            return try {
                val bytes = grFstFile.readBytes()
                val target = "words.txt".toByteArray(Charsets.US_ASCII)
                var idx = -1
                for (i in 0 until bytes.size - target.size) {
                    var matches = true
                    for (j in target.indices) {
                        if (bytes[i + j] != target[j]) {
                            matches = false
                            break
                        }
                    }
                    if (matches) {
                        idx = i
                        break
                    }
                }
                if (idx == -1) return emptySet()

                var pos = idx + target.size
                val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val numSymbols = buffer.getLong(pos)
                pos += 16

                val words = HashSet<String>(numSymbols.coerceAtMost(100_000L).toInt())
                for (i in 0 until numSymbols) {
                    if (pos + 4 > bytes.size) break
                    val strLen = buffer.getInt(pos)
                    pos += 4
                    if (pos + strLen > bytes.size) break
                    val word = String(bytes, pos, strLen, Charsets.UTF_8)
                    pos += strLen
                    pos += 8 // skip symbol ID
                    words.add(word)
                }
                words
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract words from Gr.fst: ${e.message}")
                emptySet()
            }
        }

        fun validateGrammar(grammarJson: String, vocabulary: Set<String>): GrammarValidationResult {
            val phrases = Regex(""""([^"]+)"""").findAll(grammarJson).map { it.groupValues[1] }.toList()

            val supported = mutableListOf<String>()
            val unsupported = mutableListOf<String>()
            val missing = mutableSetOf<String>()

            for (phrase in phrases) {
                if (phrase == "[unk]") {
                    supported.add(phrase)
                    continue
                }
                val phraseWords = phrase.split("\\s+".toRegex()).filter { it.isNotBlank() }
                val phraseMissing = phraseWords.filter { !vocabulary.contains(it.lowercase()) }
                if (phraseMissing.isNotEmpty()) {
                    unsupported.add(phrase)
                    missing.addAll(phraseMissing)
                } else {
                    supported.add(phrase)
                }
            }

            return GrammarValidationResult(
                totalPhrases = phrases.size,
                supportedPhrases = supported,
                unsupportedPhrases = unsupported,
                missingWords = missing,
                isValid = unsupported.isEmpty()
            )
        }

        fun verifyModel(modelPath: String): ModelVerificationResult {
            val modelDir = File(modelPath)
            val confFile = File(modelDir, "conf/mfcc.conf")
            val mdlFile = File(modelDir, "am/final.mdl")
            val altMdl = File(modelDir, "final.mdl")
            val hclrFile = File(modelDir, "graph/HCLr.fst")
            val grFile = File(modelDir, "graph/Gr.fst")

            val sampleRate = detectModelSampleRate(modelPath)
            val is8k = sampleRate?.toInt() == 8000
            val hasAcoustic = (mdlFile.exists() && mdlFile.length() > 50_000L) ||
                              (altMdl.exists() && altMdl.length() > 50_000L)
            val hasGraph = hclrFile.exists() || grFile.exists()

            val vocab = if (grFile.exists()) extractWordsFromGrFst(grFile) else emptySet()
            val grammarValidation = if (vocab.isNotEmpty()) validateGrammar(SAKHI_SAFETY_GRAMMAR, vocab) else null

            val details = buildString {
                append("Sample Rate: ${sampleRate?.toInt() ?: "Unknown"} Hz")
                if (is8k) append(" (Native 8 kHz match)") else append(" (16 kHz Resampled)")
                append(" | Acoustic: ")
                if (hasAcoustic) append("OK") else append("Missing")
                append(" | Graph: ")
                if (hasGraph) append("OK") else append("Missing")
            }

            return ModelVerificationResult(
                modelDir = modelPath,
                sampleFrequency = sampleRate,
                is8kSupported = is8k,
                hasAcousticModel = hasAcoustic,
                hasGraph = hasGraph,
                verificationDetails = details,
                grammarValidation = grammarValidation
            )
        }

        fun isHighUrgencyDistressPhrase(phrase: String): Boolean {
            val lower = phrase.lowercase().trim()
            return lower.contains("help") || lower.contains("someone help") ||
                   lower.contains("save me") || lower.contains("bachao") ||
                   lower.contains("बचाओ") || lower.contains("police") ||
                   lower.contains("पुलिस") || lower.contains("danger") ||
                   lower.contains("emergency") || lower.contains("chhod do") ||
                   lower.contains("छोड़ दो")
        }

        /**
         * Selects the authoritative transcript for distress assessment based on language and recognition mode.
         * Maintained for backwards-compatibility with existing tests and single-language diagnostics.
         */
        fun selectAuthoritativeTranscript(
            safetyText: String,
            unrestrictedText: String,
            language: ExternalSpeechLanguage,
            mode: VoskRecognitionMode
        ): String {
            val sText = safetyText.trim()
            val uText = unrestrictedText.trim()

            return when (language) {
                ExternalSpeechLanguage.ENGLISH -> {
                    if (mode == VoskRecognitionMode.UNRESTRICTED) {
                        uText.ifBlank { sText }
                    } else {
                        sText.ifBlank { uText }
                    }
                }
                ExternalSpeechLanguage.HINDI -> {
                    if (uText.isNotBlank()) uText else sText
                }
            }
        }
    }

    data class SpeechRecognitionDiagnosticSnapshot(
        val language: ExternalSpeechLanguage,
        val safetyTranscript: String,
        val unrestrictedTranscript: String,
        val selectedTranscript: String,
        val classification: com.example.sakhi.core.model.DistressCategory?,
        val triggerPhraseMatch: Boolean,
        val isIsolationEnabled: Boolean,
        val isNoiseSuppressionEnabled: Boolean = false,
        val speexOperationalState: SpeexOperationalState = SpeexOperationalState.OFF,
        val speexFramesProcessed: Long = 0L,
        val englishTranscript: String = "",
        val hindiTranscript: String = "",
        val activeAudioSource: String = "NONE",
        val arbitrationWinner: String = "",
        val sessionType: RecognitionSessionType = RecognitionSessionType.INACTIVE,
        val activeSessionId: String? = null
    )

    fun getDiagnosticSnapshot(): SpeechRecognitionDiagnosticSnapshot {
        val assessment = _diagnosticAssessment.value
        val speex = noiseSuppressor.telemetry.value
        return SpeechRecognitionDiagnosticSnapshot(
            language = _currentLanguage.value,
            safetyTranscript = _safetyFinalTranscript.value,
            unrestrictedTranscript = _unrestrictedFinalTranscript.value,
            selectedTranscript = _lastFinalTranscript.value,
            classification = assessment?.category,
            triggerPhraseMatch = assessment?.triggerPhraseMatch ?: false,
            isIsolationEnabled = _isDiagnosticIsolationEnabled.value,
            isNoiseSuppressionEnabled = _isNoiseSuppressionEnabled.value,
            speexOperationalState = speex.state,
            speexFramesProcessed = speex.framesProcessed,
            englishTranscript = _safetyFinalTranscript.value,
            hindiTranscript = _unrestrictedFinalTranscript.value,
            activeAudioSource = audioRouter.activeSource.name,
            arbitrationWinner = arbitrator.lastAuthoritativeTranscript,
            sessionType = _sessionType.value,
            activeSessionId = activeSessionId
        )
    }

    private val recognizerMutex = Mutex()

    val arbitrator = BilingualTranscriptArbitrator()

    val audioReceiver: BluetoothClassicAudioReceiver
        get() = audioRouter.esp32AudioReceiver

    private val _currentLanguage = MutableStateFlow(ExternalSpeechLanguage.ENGLISH)
    val currentLanguage: StateFlow<ExternalSpeechLanguage> = _currentLanguage.asStateFlow()

    private val _isRecognizing = MutableStateFlow(false)
    val isRecognizing: StateFlow<Boolean> = _isRecognizing.asStateFlow()

    private val _statusText = MutableStateFlow("Offline Bilingual ASR Idle")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    // Recognition Mode: Defaults to BILINGUAL for automatic language handling
    private val _recognitionMode = MutableStateFlow(VoskRecognitionMode.BILINGUAL)
    val recognitionMode: StateFlow<VoskRecognitionMode> = _recognitionMode.asStateFlow()

    // Recognition Session Type: INACTIVE, DIAGNOSTIC, or PRODUCTION_SOS
    private val _sessionType = MutableStateFlow(RecognitionSessionType.INACTIVE)
    val sessionType: StateFlow<RecognitionSessionType> = _sessionType.asStateFlow()

    @Volatile
    var activeSessionId: String? = null
        private set

    // Diagnostic Isolation Interlock: when true, speech will NOT trigger emergency calls or SMS
    private val _isDiagnosticIsolationEnabled = MutableStateFlow(true)
    val isDiagnosticIsolationEnabled: StateFlow<Boolean> = _isDiagnosticIsolationEnabled.asStateFlow()

    // Shared SpeexDSP Noise Suppression (OFF by default for exact bit-for-bit PCM passthrough)
    val noiseSuppressor = SpeexNoiseSuppressor()
    private val _isNoiseSuppressionEnabled = MutableStateFlow(false)
    val isNoiseSuppressionEnabled: StateFlow<Boolean> = _isNoiseSuppressionEnabled.asStateFlow()
    val speexTelemetry: StateFlow<SpeexTelemetry> = noiseSuppressor.telemetry

    // English Recognizer Telemetry (mapped to safety flows for UI compatibility)
    private val _safetyStatusText = MutableStateFlow("English Recognizer: Idle")
    val safetyStatusText: StateFlow<String> = _safetyStatusText.asStateFlow()
    val englishStatusText: StateFlow<String> = _safetyStatusText.asStateFlow()

    private val _safetyPartialText = MutableStateFlow("")
    val safetyPartialText: StateFlow<String> = _safetyPartialText.asStateFlow()
    val englishPartialText: StateFlow<String> = _safetyPartialText.asStateFlow()

    private val _safetyFinalTranscript = MutableStateFlow("")
    val safetyFinalTranscript: StateFlow<String> = _safetyFinalTranscript.asStateFlow()
    val englishFinalTranscript: StateFlow<String> = _safetyFinalTranscript.asStateFlow()

    // Hindi Recognizer Telemetry (mapped to unrestricted flows for UI compatibility)
    private val _unrestrictedStatusText = MutableStateFlow("Hindi Recognizer: Idle")
    val unrestrictedStatusText: StateFlow<String> = _unrestrictedStatusText.asStateFlow()
    val hindiStatusText: StateFlow<String> = _unrestrictedStatusText.asStateFlow()

    private val _unrestrictedPartialText = MutableStateFlow("")
    val unrestrictedPartialText: StateFlow<String> = _unrestrictedPartialText.asStateFlow()
    val hindiPartialText: StateFlow<String> = _unrestrictedPartialText.asStateFlow()

    private val _unrestrictedFinalTranscript = MutableStateFlow("")
    val unrestrictedFinalTranscript: StateFlow<String> = _unrestrictedFinalTranscript.asStateFlow()
    val hindiFinalTranscript: StateFlow<String> = _unrestrictedFinalTranscript.asStateFlow()

    // Model Verification Metadata
    private val _modelVerification = MutableStateFlow<ModelVerificationResult?>(null)
    val modelVerification: StateFlow<ModelVerificationResult?> = _modelVerification.asStateFlow()

    // Backwards-compatible flows for UI observers
    private val _livePartialText = MutableStateFlow("")
    val livePartialText: StateFlow<String> = _livePartialText.asStateFlow()

    private val _lastFinalTranscript = MutableStateFlow("")
    val lastFinalTranscript: StateFlow<String> = _lastFinalTranscript.asStateFlow()

    // Diagnostic Distress Assessment Flow
    private val diagnosticRiskAnalyzer = EmergencyRiskAnalyzer()
    private val _diagnosticAssessment = MutableStateFlow<DistressAssessment?>(null)
    val diagnosticAssessment: StateFlow<DistressAssessment?> = _diagnosticAssessment.asStateFlow()

    private var activeJob: Job? = null

    // Native Kaldi models
    var englishModel: Model? = null
        private set
    var hindiModel: Model? = null
        private set

    // Recognizers
    var englishRecognizer: Recognizer? = null
        private set
    var hindiRecognizer: Recognizer? = null
        private set

    // Backwards-compatible recognizer accessors
    val safetyRecognizer: Recognizer? get() = englishRecognizer
    val unrestrictedRecognizer: Recognizer? get() = hindiRecognizer
    val voskModel: Model? get() = englishModel ?: hindiModel

    // In-flight candidate holding buffer for timing synchronization
    private var pendingEnglishCandidate: RecognizerCandidate? = null
    private var pendingHindiCandidate: RecognizerCandidate? = null
    private var arbitrationHoldRemaining: Int = 0

    var onSegmentRecognizedCallback: ((String) -> Unit)? = null
    var onTranscriptSegmentCallback: ((TranscriptSegment) -> Unit)? = null
    var onSecondaryRiskHypothesisCallback: ((String) -> Unit)? = null

    // Listener for UnifiedAudioRouter source switches
    private val routerTransitionListener: (ActiveAudioSource, ActiveAudioSource) -> Unit = { from, to ->
        scope.launch(Dispatchers.Default) {
            handleSourceTransition(from, to)
        }
    }

    init {
        scope.launch(Dispatchers.IO) {
            preferences?.externalAudioLanguageFlow?.collect { langCode ->
                val targetLang = ExternalSpeechLanguage.fromCode(langCode)
                if (targetLang != _currentLanguage.value) {
                    _currentLanguage.value = targetLang
                }
            }
        }
    }

    /**
     * Resets DSP framing and recognizer state upon audio source transition.
     * Prevents cross-contamination of samples between ESP32 and Phone Microphone.
     */
    suspend fun handleSourceTransition(from: ActiveAudioSource, to: ActiveAudioSource) {
        recognizerMutex.withLock {
            Log.i(TAG, "Audio source transition detected ($from -> $to). Resetting DSP framing and Kaldi lattices.")
            // 1. If there is a completed candidate already decoded from previous stream, arbitrate & dispatch it
            if (pendingEnglishCandidate != null || pendingHindiCandidate != null) {
                try {
                    val decision = arbitrator.arbitrate(pendingEnglishCandidate, pendingHindiCandidate)
                    if (decision != null) {
                        dispatchDecision(decision)
                    }
                } catch (_: Exception) {}
            }

            // 2. Reset SpeexDSP framing buffer so incomplete frames from previous source are flushed
            noiseSuppressor.reset()

            // 3. Reset recognizer internal decoding graphs only if switching from an active audio stream
            if (from != ActiveAudioSource.NONE) {
                englishRecognizer?.reset()
                hindiRecognizer?.reset()
            }

            // 4. Clear pending arbitration candidates
            pendingEnglishCandidate = null
            pendingHindiCandidate = null
            arbitrationHoldRemaining = 0

            // 5. Clear live partials
            _safetyPartialText.value = ""
            _unrestrictedPartialText.value = ""
            _livePartialText.value = ""
        }
    }

    /**
     * Sets the active language focus (bilingual mode still operates concurrently).
     */
    suspend fun setLanguage(language: ExternalSpeechLanguage) = withContext(Dispatchers.Default) {
        recognizerMutex.withLock {
            _currentLanguage.value = language
            preferences?.setExternalAudioLanguage(language.code)
        }
    }

    /**
     * Sets the active recognition mode.
     */
    fun setRecognitionMode(mode: VoskRecognitionMode) {
        _recognitionMode.value = mode
        Log.i(TAG, "Recognition mode set to: $mode")
    }

    /**
     * Toggles diagnostic isolation. When enabled, transcripts are never forwarded
     * to the automatic emergency call / SMS escalation pipeline.
     */
    fun setDiagnosticIsolation(enabled: Boolean) {
        if (_sessionType.value == RecognitionSessionType.DIAGNOSTIC && !enabled) {
            Log.w(TAG, "Cannot disable diagnostic isolation during an active diagnostic session.")
            return
        }
        _isDiagnosticIsolationEnabled.value = enabled
        Log.i(TAG, "Diagnostic isolation interlock set to: $enabled")
    }

    /**
     * Toggles SpeexDSP noise suppression on incoming 8 kHz audio stream.
     * Guaranteed to remain MANDATORY (always ON) during active PRODUCTION_SOS sessions.
     */
    fun setNoiseSuppressionEnabled(enabled: Boolean) {
        if (_sessionType.value == RecognitionSessionType.PRODUCTION_SOS && !enabled) {
            Log.w(TAG, "Cannot disable noise suppression during an active production SOS session.")
            return
        }
        _isNoiseSuppressionEnabled.value = enabled
        noiseSuppressor.isEnabled = enabled
        Log.i(TAG, "SpeexDSP noise suppression set to: $enabled")
    }

    /**
     * Starts production bilingual speech recognition tied to an active emergency session.
     * Automatically turns OFF diagnostic isolation and enforces MANDATORY noise suppression.
     */
    open fun startProductionSession(
        sessionId: String,
        onTranscript: (String) -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        if (activeJob?.isActive == true && _sessionType.value == RecognitionSessionType.PRODUCTION_SOS && activeSessionId == sessionId) {
            Log.i(TAG, "Production session $sessionId already active. Ignoring redundant start call.")
            return
        }

        stop()

        _sessionType.value = RecognitionSessionType.PRODUCTION_SOS
        activeSessionId = sessionId
        _isDiagnosticIsolationEnabled.value = false
        _isNoiseSuppressionEnabled.value = true
        noiseSuppressor.isEnabled = true
        onSegmentRecognizedCallback = onTranscript

        audioRouter.addTransitionListener(routerTransitionListener)
        if (!audioRouter.isRunning) {
            audioRouter.start()
        }

        launchAsrLoop(onError)
    }

    /**
     * Starts an isolated diagnostic speech recognition test session.
     * Strictly enforces diagnostic isolation so speech is NEVER forwarded to emergency dispatch.
     */
    open fun startDiagnosticSession(onError: ((String) -> Unit)? = null) {
        if (_sessionType.value == RecognitionSessionType.PRODUCTION_SOS) {
            Log.w(TAG, "Cannot start diagnostic session: Production SOS session is currently active ($activeSessionId)")
            return
        }
        if (activeJob?.isActive == true && _sessionType.value == RecognitionSessionType.DIAGNOSTIC) {
            Log.i(TAG, "Diagnostic session already running.")
            return
        }

        stop()

        _sessionType.value = RecognitionSessionType.DIAGNOSTIC
        activeSessionId = "DIAGNOSTIC_${System.currentTimeMillis()}"
        _isDiagnosticIsolationEnabled.value = true
        onSegmentRecognizedCallback = null

        audioRouter.addTransitionListener(routerTransitionListener)
        if (!audioRouter.isRunning) {
            audioRouter.start()
        }

        launchAsrLoop(onError)
    }

    /**
     * Stops diagnostic speech recognition session.
     * Guaranteed to never stop or interfere with an active production emergency session.
     */
    open fun stopDiagnosticSession() {
        if (_sessionType.value != RecognitionSessionType.DIAGNOSTIC) {
            Log.w(TAG, "Ignoring stopDiagnosticSession because current session type is ${_sessionType.value}")
            return
        }
        stop()
    }

    /**
     * Stops production speech recognition session if matching the active session ID.
     */
    open fun stopProductionSession(sessionId: String? = null) {
        if (sessionId != null && activeSessionId != null && activeSessionId != sessionId) {
            Log.w(TAG, "Ignoring stop request for mismatched session '$sessionId' (current active: '$activeSessionId')")
            return
        }
        stop()
    }

    /**
     * Starts continuous background bilingual speech recognition over UnifiedAudioRouter (Legacy path).
     * @param onTranscript Callback invoked with authoritative transcripts in production mode.
     */
    fun start(onTranscript: (String) -> Unit) {
        startProductionSession("LEGACY_DEFAULT_SESSION", onTranscript)
    }

    /**
     * Prewarms the native Vosk models and recognizers in the background.
     * Prevents latency spikes and dropped audio packets on initial recognition attempts.
     */
    open suspend fun prewarmModels() = withContext(Dispatchers.IO) {
        recognizerMutex.withLock {
            loadAndInitBilingualModels()
        }
    }

    private fun launchAsrLoop(onError: ((String) -> Unit)? = null) {
        activeJob = scope.launch(Dispatchers.Default) {
            _isRecognizing.value = true
            _statusText.value = "Initializing bilingual recognizers..."

            recognizerMutex.withLock {
                loadAndInitBilingualModels()
            }

            try {
                // Collect routed 8 kHz PCM chunks from UnifiedAudioRouter
                audioRouter.routedAudioFlow.collect { rawChunk ->
                    if (!isActive) return@collect
                    recognizerMutex.withLock {
                        processAudioChunk(rawChunk)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bilingual ASR loop error", e)
                _statusText.value = "ASR Error: ${e.localizedMessage ?: e.message}"
                onError?.invoke(e.localizedMessage ?: e.message ?: "ASR loop error")
            } finally {
                _isRecognizing.value = false
            }
        }
    }

    /**
     * Processes a single 8 kHz audio chunk through the SpeexDSP stage and feeds both
     * English (8 kHz) and Hindi (16 kHz resampled) recognizers.
     */
    private fun processAudioChunk(rawChunk: ByteArray) {
        if (rawChunk.isEmpty()) return

        // 1. Shared SpeexDSP Noise Suppression on incoming 8 kHz stream
        val shouldSuppress = _sessionType.value == RecognitionSessionType.PRODUCTION_SOS || _isNoiseSuppressionEnabled.value
        val pcmChunk = if (shouldSuppress) {
            val processed = noiseSuppressor.process(rawChunk)
            if (processed.isEmpty()) return
            processed
        } else {
            rawChunk
        }

        // Guarantee strictly even length (complete 16-bit samples)
        val validLength = pcmChunk.size - (pcmChunk.size % 2)
        if (validLength <= 0) return
        val chunk8k = if (validLength == pcmChunk.size) pcmChunk else pcmChunk.copyOf(validLength)

        // 2. Feed English Recognizer directly at 8,000 Hz
        var enResultJson: String? = null
        var enPartial: String? = null
        englishRecognizer?.let { rec ->
            try {
                if (rec.acceptWaveForm(chunk8k, chunk8k.size)) {
                    enResultJson = rec.result
                } else {
                    enPartial = rec.partialResult
                }
            } catch (e: Exception) {
                Log.e(TAG, "English recognizer error: ${e.message}")
            }
        }

        // 3. Feed Hindi Recognizer at 16,000 Hz after linear upsampling
        val chunk16k = PcmAudioResampler.upsample8kTo16k(chunk8k)
        var hiResultJson: String? = null
        var hiPartial: String? = null
        hindiRecognizer?.let { rec ->
            try {
                if (rec.acceptWaveForm(chunk16k, chunk16k.size)) {
                    hiResultJson = rec.result
                } else {
                    hiPartial = rec.partialResult
                }
            } catch (e: Exception) {
                Log.e(TAG, "Hindi recognizer error: ${e.message}")
            }
        }

        // 4. Update partial flows for live UI preview
        handlePartials(enPartial, hiPartial)

        // 5. Handle completed results and coordinate arbitration
        handleCompletedUtterances(enResultJson, hiResultJson)
    }

    private fun handlePartials(enPartialJson: String?, hiPartialJson: String?) {
        val enPart = enPartialJson?.let { extractPartialFromJson(it) }.orEmpty().trim()
        val hiPart = hiPartialJson?.let { extractPartialFromJson(it) }.orEmpty().trim()

        if (enPart.isNotEmpty()) {
            _safetyPartialText.value = enPart
        }
        if (hiPart.isNotEmpty()) {
            _unrestrictedPartialText.value = hiPart
        }

        val activePart = when {
            hiPart.isNotEmpty() && enPart.isNotEmpty() -> if (hiPart.length >= enPart.length) hiPart else enPart
            hiPart.isNotEmpty() -> hiPart
            else -> enPart
        }
        if (activePart.isNotEmpty()) {
            _livePartialText.value = activePart
        }
    }

    private fun handleCompletedUtterances(enResultJson: String?, hiResultJson: String?) {
        if (enResultJson != null) {
            val candidate = BilingualTranscriptArbitrator.parseVoskJson(enResultJson, ExternalSpeechLanguage.ENGLISH, isPartial = false)
            if (candidate.text.isNotBlank()) {
                pendingEnglishCandidate = candidate
                _safetyPartialText.value = ""
            }
        }

        if (hiResultJson != null) {
            val candidate = BilingualTranscriptArbitrator.parseVoskJson(hiResultJson, ExternalSpeechLanguage.HINDI, isPartial = false)
            if (candidate.text.isNotBlank()) {
                pendingHindiCandidate = candidate
                _unrestrictedPartialText.value = ""
            }
        }

        val hasEn = pendingEnglishCandidate != null
        val hasHi = pendingHindiCandidate != null

        if (hasEn && hasHi) {
            // Both candidates ready -> arbitrate immediately
            val decision = arbitrator.arbitrate(pendingEnglishCandidate, pendingHindiCandidate)
            pendingEnglishCandidate = null
            pendingHindiCandidate = null
            arbitrationHoldRemaining = 0
            if (decision != null) {
                dispatchDecision(decision)
            }
        } else if (hasEn || hasHi) {
            // Wait up to 8 chunks (~350-400 ms) for the second decoder to complete
            if (arbitrationHoldRemaining <= 0) {
                arbitrationHoldRemaining = 8
            } else {
                arbitrationHoldRemaining--
                if (arbitrationHoldRemaining <= 0) {
                    val decision = arbitrator.arbitrate(pendingEnglishCandidate, pendingHindiCandidate)
                    pendingEnglishCandidate = null
                    pendingHindiCandidate = null
                    if (decision != null) {
                        dispatchDecision(decision)
                    }
                }
            }
        }
    }

    private suspend fun loadAndInitBilingualModels() {
        val englishPath = modelManager?.ensureModelReady(ExternalSpeechLanguage.ENGLISH)
        val hindiPath = modelManager?.ensureModelReady(ExternalSpeechLanguage.HINDI)

        if (englishPath == null && hindiPath == null) {
            _statusText.value = "ASR Idle: No models available"
            _safetyStatusText.value = "English: Unavailable"
            _unrestrictedStatusText.value = "Hindi: Unavailable"
            return
        }

        try {
            if (englishPath != null && englishModel == null) {
                Log.i(TAG, "Loading English Vosk Model from $englishPath")
                englishModel = Model(englishPath)
                scope.launch(Dispatchers.IO) {
                    val verification = verifyModel(englishPath)
                    _modelVerification.value = verification
                }
            }

            if (hindiPath != null && hindiModel == null) {
                Log.i(TAG, "Loading Hindi Vosk Model from $hindiPath")
                hindiModel = Model(hindiPath)
            }

            initRecognizers()
            if (!_isRecognizing.value) {
                _statusText.value = "Bilingual ASR Ready (Prewarmed)"
            } else {
                _statusText.value = "Bilingual ASR Active (EN 8kHz + HI 16kHz)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing bilingual models: ${e.message}", e)
            _statusText.value = "ASR Error: ${e.message}"
        }
    }

    private var currentRecognizerMode: VoskRecognitionMode? = null

    private fun initRecognizers() {
        val enModel = englishModel
        val hiModel = hindiModel
        val mode = _recognitionMode.value

        // 1. Initialize or reset English Recognizer at 8000 Hz
        if (enModel != null) {
            if (englishRecognizer == null || currentRecognizerMode != mode) {
                try { englishRecognizer?.close() } catch (_: Exception) {}
                englishRecognizer = try {
                    if (mode == VoskRecognitionMode.SAFETY_GRAMMAR) {
                        Recognizer(enModel, 8000.0f, SAKHI_SAFETY_GRAMMAR).apply { setWords(true) }
                    } else {
                        Recognizer(enModel, 8000.0f).apply { setWords(true) }
                    }.also {
                        _safetyStatusText.value = "English Active (@ 8000Hz)"
                        Log.i(TAG, "English recognizer initialized at 8000Hz")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "English recognizer init failed: ${e.message}", e)
                    _safetyStatusText.value = "English error: ${e.message}"
                    null
                }
            } else {
                englishRecognizer?.reset()
                _safetyStatusText.value = "English Active (@ 8000Hz)"
            }
        }

        // 2. Initialize or reset Hindi Recognizer at 16000 Hz
        if (hiModel != null) {
            if (hindiRecognizer == null || currentRecognizerMode != mode) {
                try { hindiRecognizer?.close() } catch (_: Exception) {}
                hindiRecognizer = try {
                    if (mode == VoskRecognitionMode.SAFETY_GRAMMAR) {
                        Recognizer(hiModel, 16000.0f, SAKHI_HINDI_SAFETY_GRAMMAR).apply { setWords(true) }
                    } else {
                        Recognizer(hiModel, 16000.0f).apply { setWords(true) }
                    }.also {
                        _unrestrictedStatusText.value = "Hindi Active (@ 16000Hz)"
                        Log.i(TAG, "Hindi recognizer initialized at 16000Hz")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Hindi recognizer init failed: ${e.message}", e)
                    _unrestrictedStatusText.value = "Hindi error: ${e.message}"
                    null
                }
            } else {
                hindiRecognizer?.reset()
                _unrestrictedStatusText.value = "Hindi Active (@ 16000Hz)"
            }
        }

        currentRecognizerMode = mode
    }

    private fun releaseActiveRecognizersAndModels() {
        try { englishRecognizer?.close() } catch (_: Exception) {}
        englishRecognizer = null

        try { hindiRecognizer?.close() } catch (_: Exception) {}
        hindiRecognizer = null

        try { englishModel?.close() } catch (_: Exception) {}
        englishModel = null

        try { hindiModel?.close() } catch (_: Exception) {}
        hindiModel = null

        _modelVerification.value = null
        currentRecognizerMode = null
    }

    /**
     * Dispatches authoritative decision with duplicate suppression, language-aware false-transcript
     * suppression, and diagnostic isolation.
     */
    private fun dispatchDecision(decision: ArbitrationDecision?) {
        if (decision == null) return

        if (decision.outcome == DisambiguationOutcome.UNCERTAIN_DROPPED) {
            Log.d(TAG, "Dropped uncertain/out-of-vocabulary candidate: ${decision.reason}")
            return
        }

        val winner = decision.winner
        val trimmed = winner.text.trim()
        if (trimmed.isEmpty()) return

        val now = decision.winner.timestamp.takeIf { it > 0 } ?: timeProvider()
        if (!arbitrator.shouldDispatch(winner, now)) {
            Log.d(TAG, "Suppressed duplicate or conflicting transcript: '$trimmed'")
            return
        }

        arbitrator.recordDispatch(winner, now)

        // Update diagnostic flows and language-specific final text based on disambiguation outcome
        when (decision.outcome) {
            DisambiguationOutcome.ENGLISH_ONLY -> {
                _safetyFinalTranscript.value = trimmed
                // Suppress Hindi hallucination from diagnostic preview
                _unrestrictedFinalTranscript.value = ""
                _lastFinalTranscript.value = trimmed
                _currentLanguage.value = ExternalSpeechLanguage.ENGLISH
            }
            DisambiguationOutcome.HINDI_ONLY -> {
                _unrestrictedFinalTranscript.value = trimmed
                // Suppress English hallucination from diagnostic preview
                _safetyFinalTranscript.value = ""
                _lastFinalTranscript.value = trimmed
                _currentLanguage.value = ExternalSpeechLanguage.HINDI
            }
            DisambiguationOutcome.MIXED_BILINGUAL -> {
                decision.preservedEnglish?.let { _safetyFinalTranscript.value = it.text.trim() }
                decision.preservedHindi?.let { _unrestrictedFinalTranscript.value = it.text.trim() }
                _lastFinalTranscript.value = trimmed
                _currentLanguage.value = winner.language
            }
            DisambiguationOutcome.UNCERTAIN_DROPPED -> return
        }

        // Evaluate through EmergencyRiskAnalyzer for live diagnostic preview
        var assessment = diagnosticRiskAnalyzer.analyzeSegment(trimmed, now)
        if (!assessment.triggerPhraseMatch && decision.loser != null && decision.loser.text.isNotBlank()) {
            val loserAssessment = diagnosticRiskAnalyzer.analyzeSegment(decision.loser.text.trim(), now)
            if (loserAssessment.triggerPhraseMatch) {
                assessment = loserAssessment
            }
        }
        _diagnosticAssessment.value = assessment

        Log.i(
            TAG,
            "Authoritative Transcript Dispatched: '$trimmed' [lang=${winner.language.code}, outcome=${decision.outcome}, " +
            "conf=${winner.averageConfidence?.let { "%.2f".format(it) } ?: "N/A"}, reason='${decision.reason}', " +
            "classification=${assessment.category}, trigger=${assessment.triggerPhraseMatch}, " +
            "sessionType=${_sessionType.value}, sessionId=$activeSessionId, isolation=${_isDiagnosticIsolationEnabled.value}]"
        )

        // Safety interlock 1: Strictly forbid dispatching when not in PRODUCTION_SOS
        if (_sessionType.value != RecognitionSessionType.PRODUCTION_SOS) {
            Log.d(TAG, "Held from emergency pipeline: sessionType is ${_sessionType.value}")
            return
        }

        // Safety interlock 2: Diagnostic isolation flag check
        if (_isDiagnosticIsolationEnabled.value) {
            Log.d(TAG, "Held from emergency pipeline: diagnostic isolation is enabled")
            return
        }

        // Safety interlock 3: Active session validation
        if (activeSessionId == null) {
            Log.w(TAG, "Held from emergency pipeline: activeSessionId is null")
            return
        }

        // Emit disambiguated TranscriptSegment(s) strictly adhering to outcome
        var dispatchedViaSegmentCallback = false
        when (decision.outcome) {
            DisambiguationOutcome.ENGLISH_ONLY -> {
                val seg = TranscriptSegment(
                    text = trimmed,
                    language = ExternalSpeechLanguage.ENGLISH,
                    confidence = winner.averageConfidence,
                    timestamp = winner.timestamp,
                    isFinal = true
                )
                onTranscriptSegmentCallback?.let {
                    it.invoke(seg)
                    dispatchedViaSegmentCallback = true
                }
            }
            DisambiguationOutcome.HINDI_ONLY -> {
                val seg = TranscriptSegment(
                    text = trimmed,
                    language = ExternalSpeechLanguage.HINDI,
                    confidence = winner.averageConfidence,
                    timestamp = winner.timestamp,
                    isFinal = true
                )
                onTranscriptSegmentCallback?.let {
                    it.invoke(seg)
                    dispatchedViaSegmentCallback = true
                }
            }
            DisambiguationOutcome.MIXED_BILINGUAL -> {
                decision.preservedEnglish?.let { en ->
                    val segEn = TranscriptSegment(
                        text = en.text.trim(),
                        language = ExternalSpeechLanguage.ENGLISH,
                        confidence = en.averageConfidence,
                        timestamp = en.timestamp,
                        isFinal = true
                    )
                    onTranscriptSegmentCallback?.let {
                        it.invoke(segEn)
                        dispatchedViaSegmentCallback = true
                    }
                }
                decision.preservedHindi?.let { hi ->
                    val segHi = TranscriptSegment(
                        text = hi.text.trim(),
                        language = ExternalSpeechLanguage.HINDI,
                        confidence = hi.averageConfidence,
                        timestamp = hi.timestamp,
                        isFinal = true
                    )
                    onTranscriptSegmentCallback?.let {
                        it.invoke(segHi)
                        dispatchedViaSegmentCallback = true
                    }
                }
            }
            DisambiguationOutcome.UNCERTAIN_DROPPED -> {}
        }

        // If loser candidate contained an unselected distress phrase, evaluate it for risk without adding to transcript history
        if (decision.loser != null && !BilingualTranscriptArbitrator.isDistressPhrase(winner.text) &&
            BilingualTranscriptArbitrator.isDistressPhrase(decision.loser.text)
        ) {
            onSecondaryRiskHypothesisCallback?.invoke(decision.loser.text.trim())
        }

        if (!dispatchedViaSegmentCallback) {
            onSegmentRecognizedCallback?.invoke(trimmed)
        }
    }

    /**
     * Test helper to inject simulated transcript decisions and test interlock & isolation logic.
     */
    fun dispatchForTesting(
        text: String,
        language: ExternalSpeechLanguage = ExternalSpeechLanguage.ENGLISH,
        confidence: Double? = 0.95,
        reason: String = "Simulated candidate for test",
        outcome: DisambiguationOutcome = when (language) {
            ExternalSpeechLanguage.ENGLISH -> DisambiguationOutcome.ENGLISH_ONLY
            ExternalSpeechLanguage.HINDI -> DisambiguationOutcome.HINDI_ONLY
        },
        timestamp: Long = timeProvider()
    ) {
        val candidate = RecognizerCandidate(
            text = text,
            language = language,
            isPartial = false,
            averageConfidence = confidence,
            timestamp = timestamp
        )
        dispatchDecision(ArbitrationDecision(candidate, null, reason, arbitrationTimestamp = timestamp, outcome = outcome))
    }

    fun finalizeUtterance(): Job = scope.launch(Dispatchers.Default) {
        recognizerMutex.withLock {
            try {
                if (_isNoiseSuppressionEnabled.value) {
                    val flushed = noiseSuppressor.flush()
                    if (flushed.isNotEmpty()) {
                        englishRecognizer?.acceptWaveForm(flushed, flushed.size)
                        val flushed16k = PcmAudioResampler.upsample8kTo16k(flushed)
                        hindiRecognizer?.acceptWaveForm(flushed16k, flushed16k.size)
                    }
                }

                val enFinal = englishRecognizer?.finalResult ?: ""
                val hiFinal = hindiRecognizer?.finalResult ?: ""

                val enCandidate = BilingualTranscriptArbitrator.parseVoskJson(enFinal, ExternalSpeechLanguage.ENGLISH, isPartial = false)
                val hiCandidate = BilingualTranscriptArbitrator.parseVoskJson(hiFinal, ExternalSpeechLanguage.HINDI, isPartial = false)

                val mergedEn = if (enCandidate.text.isNotBlank()) enCandidate else pendingEnglishCandidate
                val mergedHi = if (hiCandidate.text.isNotBlank()) hiCandidate else pendingHindiCandidate

                pendingEnglishCandidate = null
                pendingHindiCandidate = null
                arbitrationHoldRemaining = 0

                _safetyPartialText.value = ""
                _unrestrictedPartialText.value = ""
                _livePartialText.value = ""

                val decision = arbitrator.arbitrate(mergedEn, mergedHi)
                if (decision != null) {
                    dispatchDecision(decision)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error finalizing utterance: ${e.message}")
            }
        }
    }

    fun clearTranscripts() {
        reset()
        _safetyFinalTranscript.value = ""
        _unrestrictedFinalTranscript.value = ""
        _lastFinalTranscript.value = ""
        _diagnosticAssessment.value = null
        diagnosticRiskAnalyzer.resetSession()
    }

    fun reset() {
        try {
            noiseSuppressor.reset()
            englishRecognizer?.reset()
            hindiRecognizer?.reset()
            pendingEnglishCandidate = null
            pendingHindiCandidate = null
            arbitrationHoldRemaining = 0
            arbitrator.reset()
            _safetyPartialText.value = ""
            _unrestrictedPartialText.value = ""
            _livePartialText.value = ""
        } catch (_: Exception) {}
    }

    open fun stop() {
        activeJob?.cancel()
        activeJob = null
        _sessionType.value = RecognitionSessionType.INACTIVE
        activeSessionId = null
        onSegmentRecognizedCallback = null
        audioRouter.removeTransitionListener(routerTransitionListener)
        audioRouter.stop()
        _isRecognizing.value = false
        _statusText.value = "Bilingual ASR Stopped"
        _safetyStatusText.value = "English: Stopped"
        _unrestrictedStatusText.value = "Hindi: Stopped"
        reset()
    }

    fun release() {
        stop()
        noiseSuppressor.release()
        releaseActiveRecognizersAndModels()
    }
}

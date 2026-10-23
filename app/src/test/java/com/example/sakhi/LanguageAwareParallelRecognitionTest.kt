package com.example.sakhi

import com.example.sakhi.core.database.SessionHistoryRepository
import com.example.sakhi.core.model.DistressCategory
import com.example.sakhi.core.model.SessionState
import com.example.sakhi.core.model.TranscriptSegment
import com.example.sakhi.data.assistant.ArbitrationDecision
import com.example.sakhi.data.assistant.BilingualTranscriptArbitrator
import com.example.sakhi.data.assistant.DisambiguationOutcome
import com.example.sakhi.data.assistant.ExternalSpeechLanguage
import com.example.sakhi.data.assistant.RecognitionSessionType
import com.example.sakhi.data.assistant.RecognizerCandidate
import com.example.sakhi.data.assistant.VoskAudioTranscriptionService
import com.example.sakhi.data.assistant.VoskRecognitionMode
import com.example.sakhi.data.assistant.VoskWord
import com.example.sakhi.data.audio.ActiveAudioSource
import com.example.sakhi.data.audio.IPhoneMicrophoneAudioSource
import com.example.sakhi.data.audio.PhoneAudioTelemetry
import com.example.sakhi.data.audio.UnifiedAudioRouter
import com.example.sakhi.data.bluetooth.BluetoothClassicAudioReceiver
import com.example.sakhi.domain.emergency.EmergencyStateManager
import com.example.sakhi.domain.escalation.EscalationPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanguageAwareParallelRecognitionTest {

    private lateinit var arbitrator: BilingualTranscriptArbitrator
    private lateinit var mockBleManager: MockBleManager
    private lateinit var mockLocationProvider: MockLocationProvider
    private lateinit var mockAlertDispatcher: MockAlertDispatcher
    private lateinit var mockAudioPipeline: MockAudioPipeline
    private lateinit var mockAssessmentEngine: MockAssessmentEngine
    private lateinit var escalationPolicy: EscalationPolicy
    private lateinit var mockPoliceCallManager: MockPoliceCallManager
    private lateinit var mockContactRepository: MockContactRepository
    private lateinit var mockSessionHistoryRepository: SessionHistoryRepository
    private lateinit var mockPreferences: MockPreferences
    private lateinit var mockLiveTrackingClient: MockLiveTrackingClient

    @Before
    fun setUp() {
        arbitrator = BilingualTranscriptArbitrator()
        mockBleManager = MockBleManager()
        mockLocationProvider = MockLocationProvider()
        mockAlertDispatcher = MockAlertDispatcher()
        mockAudioPipeline = MockAudioPipeline()
        mockAssessmentEngine = MockAssessmentEngine()
        escalationPolicy = EscalationPolicy()
        mockPoliceCallManager = MockPoliceCallManager()
        mockContactRepository = MockContactRepository()
        mockSessionHistoryRepository = SessionHistoryRepository(null)
        mockPreferences = MockPreferences()
        mockLiveTrackingClient = MockLiveTrackingClient()
    }

    private fun createManager(
        scope: CoroutineScope,
        voskService: VoskAudioTranscriptionService? = null,
        timeProvider: () -> Long = { System.currentTimeMillis() }
    ): EmergencyStateManager {
        return EmergencyStateManager(
            scope = scope,
            bleManager = mockBleManager,
            locationProvider = mockLocationProvider,
            alertDispatcher = mockAlertDispatcher,
            audioPipeline = mockAudioPipeline,
            assessmentEngine = mockAssessmentEngine,
            escalationPolicy = escalationPolicy,
            policeCallManager = mockPoliceCallManager,
            contactRepository = mockContactRepository,
            sessionHistoryRepository = mockSessionHistoryRepository,
            preferences = mockPreferences,
            liveTrackingClient = mockLiveTrackingClient,
            voskTranscriptionService = voskService,
            foregroundServiceStarter = {},
            foregroundServiceStopper = {},
            timeProvider = timeProvider
        )
    }

    private fun createTestRouter(scope: CoroutineScope): UnifiedAudioRouter {
        val fakeReceiver = BluetoothClassicAudioReceiver(scope = scope)
        val fakePhoneSource = FakePhoneSource()
        return UnifiedAudioRouter(fakeReceiver, fakePhoneSource, scope)
    }

    // =========================================================================
    // SCENARIO 1: English hypothesis wins over phonetic Hindi hallucination
    // =========================================================================
    @Test
    fun testScenario1_englishHypothesisWinsOverPhoneticHindiHallucination() {
        val enCandidate = RecognizerCandidate(
            text = "i need help someone is following me",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = 0.85,
            words = listOf(
                VoskWord("i", 0.9, 1.0, 1.2),
                VoskWord("need", 0.9, 1.2, 1.4),
                VoskWord("help", 0.9, 1.4, 1.7),
                VoskWord("someone", 0.8, 1.8, 2.1),
                VoskWord("is", 0.8, 2.1, 2.2),
                VoskWord("following", 0.8, 2.2, 2.7),
                VoskWord("me", 0.9, 2.7, 2.9)
            )
        )

        // Hindi decoder received the same audio, but acoustic graph approximated English phonemes in Devanagari
        val hiHallucination = RecognizerCandidate(
            text = "आई नीड हेल्प समवन इज फॉलोइंग मी",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = 0.94,
            words = listOf(
                VoskWord("आई", 0.95, 1.0, 1.2),
                VoskWord("नीड", 0.95, 1.2, 1.4),
                VoskWord("हेल्प", 0.95, 1.4, 1.7),
                VoskWord("समवन", 0.92, 1.8, 2.1),
                VoskWord("इज", 0.92, 2.1, 2.2),
                VoskWord("फॉलोइंग", 0.92, 2.2, 2.7),
                VoskWord("मी", 0.96, 2.7, 2.9)
            )
        )

        val decision = arbitrator.arbitrate(enCandidate, hiHallucination)
        assertNotNull(decision)
        assertEquals(ExternalSpeechLanguage.ENGLISH, decision!!.winner.language)
        assertEquals("i need help someone is following me", decision.winner.text)
        assertEquals(DisambiguationOutcome.ENGLISH_ONLY, decision.outcome)
        assertTrue(decision.reason.contains("phonetic transliteration echo"))
    }

    // =========================================================================
    // SCENARIO 2: Hindi hypothesis wins over false English interpretation
    // =========================================================================
    @Test
    fun testScenario2_hindiHypothesisWinsOverFalseEnglishInterpretation() {
        val hiCandidate = RecognizerCandidate(
            text = "मुझे मदद चाहिए कोई मेरा पीछा कर रहा है",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = 0.89,
            words = listOf(
                VoskWord("मुझे", 0.9, 1.0, 1.3),
                VoskWord("मदद", 0.9, 1.3, 1.6),
                VoskWord("चाहिए", 0.9, 1.6, 2.0),
                VoskWord("कोई", 0.85, 2.1, 2.3),
                VoskWord("मेरा", 0.88, 2.3, 2.6),
                VoskWord("पीछा", 0.9, 2.6, 2.9),
                VoskWord("कर", 0.85, 2.9, 3.1),
                VoskWord("रहा", 0.85, 3.1, 3.3),
                VoskWord("है", 0.9, 3.3, 3.5)
            )
        )

        // English decoder receives Hindi audio and either fails or outputs fragmented gibberish
        val enGarbage = RecognizerCandidate(
            text = "much [unk]",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = 0.40,
            words = listOf(
                VoskWord("much", 0.45, 1.0, 1.4),
                VoskWord("[unk]", 0.35, 1.5, 3.5)
            )
        )

        val decision = arbitrator.arbitrate(enGarbage, hiCandidate)
        assertNotNull(decision)
        assertEquals(ExternalSpeechLanguage.HINDI, decision!!.winner.language)
        assertEquals("मुझे मदद चाहिए कोई मेरा पीछा कर रहा है", decision.winner.text)
        assertEquals(DisambiguationOutcome.HINDI_ONLY, decision.outcome)
    }

    // =========================================================================
    // SCENARIO 3: Empty and [unk] candidates do not override valid transcripts
    // =========================================================================
    @Test
    fun testScenario3_emptyAndUnkCandidatesDoNotOverrideValidTranscripts() {
        // Subcase 3A: English produces [unk], Hindi produces valid emergency speech
        val enUnk = RecognizerCandidate(text = "[unk]", language = ExternalSpeechLanguage.ENGLISH)
        val hiValid = RecognizerCandidate(text = "बचाओ मुझे", language = ExternalSpeechLanguage.HINDI, averageConfidence = 0.88)
        val decisionHi = arbitrator.arbitrate(enUnk, hiValid)
        assertNotNull(decisionHi)
        assertEquals(ExternalSpeechLanguage.HINDI, decisionHi!!.winner.language)
        assertEquals("बचाओ मुझे", decisionHi.winner.text)
        assertEquals(DisambiguationOutcome.HINDI_ONLY, decisionHi.outcome)

        // Subcase 3B: Hindi produces [unk], English produces valid speech
        val enValid = RecognizerCandidate(text = "call police", language = ExternalSpeechLanguage.ENGLISH, averageConfidence = 0.85)
        val hiUnk = RecognizerCandidate(text = "<unk>", language = ExternalSpeechLanguage.HINDI)
        val decisionEn = arbitrator.arbitrate(enValid, hiUnk)
        assertNotNull(decisionEn)
        assertEquals(ExternalSpeechLanguage.ENGLISH, decisionEn!!.winner.language)
        assertEquals("call police", decisionEn.winner.text)
        assertEquals(DisambiguationOutcome.ENGLISH_ONLY, decisionEn.outcome)

        // Subcase 3C: Both produce only [unk] -> Dropped as uncertain noise
        val decisionBothUnk = arbitrator.arbitrate(enUnk, hiUnk)
        assertNotNull(decisionBothUnk)
        assertEquals(DisambiguationOutcome.UNCERTAIN_DROPPED, decisionBothUnk!!.outcome)
    }

    // =========================================================================
    // SCENARIO 4: Uncalibrated confidences from different models do not cause false wins
    // =========================================================================
    @Test
    fun testScenario4_uncalibratedConfidencesDoNotCauseFalseWins() {
        // English speaker says "i need help".
        // English model reports real confidence 0.80.
        // Hindi model produces a phonetic echo ("आई नीड हेल्प") with uncalibrated posterior 0.98.
        val enReal = RecognizerCandidate(
            text = "i need help",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = 0.80,
            words = listOf(
                VoskWord("i", 0.8, 1.0, 1.2),
                VoskWord("need", 0.8, 1.2, 1.4),
                VoskWord("help", 0.8, 1.4, 1.7)
            )
        )
        val hiHallucinationHighConf = RecognizerCandidate(
            text = "आई नीड हेल्प",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = 0.98,
            words = listOf(
                VoskWord("आई", 0.98, 1.0, 1.2),
                VoskWord("नीड", 0.98, 1.2, 1.4),
                VoskWord("हेल्प", 0.98, 1.4, 1.7)
            )
        )

        val decision = arbitrator.arbitrate(enReal, hiHallucinationHighConf)
        assertNotNull(decision)
        assertEquals(
            "Phonetic transliteration echo must be suppressed even when its raw posterior confidence is artificially high",
            ExternalSpeechLanguage.ENGLISH,
            decision!!.winner.language
        )
        assertEquals("i need help", decision.winner.text)
        assertEquals(DisambiguationOutcome.ENGLISH_ONLY, decision.outcome)
    }

    // =========================================================================
    // SCENARIO 5: Delayed results from previous utterance do not suppress next utterance
    // =========================================================================
    @Test
    fun testScenario5_delayedResultsFromPreviousUtteranceDoNotSuppressNextUtterance() {
        // Delayed candidate from past utterance (t = 1.0s to 2.0s)
        val delayedPastHindiCandidate = RecognizerCandidate(
            text = "जाओ",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = 0.90,
            words = listOf(VoskWord("जाओ", 0.9, 1.0, 1.8)),
            timestamp = 1000L
        )

        // Fresh candidate from current utterance (t = 3.5s to 4.5s)
        val currentEnglishCandidate = RecognizerCandidate(
            text = "help me please",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = 0.85,
            words = listOf(
                VoskWord("help", 0.85, 3.5, 3.8),
                VoskWord("me", 0.85, 3.8, 4.0),
                VoskWord("please", 0.85, 4.0, 4.4)
            ),
            timestamp = 4500L
        )

        assertFalse(
            "Candidates separated by > 1.2s without overlap are not the same audio interval",
            BilingualTranscriptArbitrator.isSameAudioInterval(currentEnglishCandidate, delayedPastHindiCandidate)
        )

        val decision = arbitrator.arbitrate(currentEnglishCandidate, delayedPastHindiCandidate)
        assertNotNull(decision)
        assertEquals(
            "Current utterance must win and not be suppressed by older delayed candidate",
            ExternalSpeechLanguage.ENGLISH,
            decision!!.winner.language
        )
        assertEquals("help me please", decision.winner.text)
    }

    // =========================================================================
    // SCENARIO 6: No duplicate history entries in the wrong language
    // =========================================================================
    @Test
    fun testScenario6_noDuplicateHistoryEntriesInWrongLanguage() = runTest {
        var testTime = 1000L
        val router = createTestRouter(backgroundScope)
        val service = VoskAudioTranscriptionService(
            context = null,
            audioRouter = router,
            modelManager = null,
            scope = backgroundScope,
            preferences = mockPreferences,
            timeProvider = { testTime }
        )

        val manager = createManager(backgroundScope, service, timeProvider = { testTime })
        manager.handleDoublePress()
        advanceTimeBy(100L)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)

        // 1. Dispatch English utterance through production service
        service.dispatchForTesting(
            text = "someone is following me",
            language = ExternalSpeechLanguage.ENGLISH,
            confidence = 0.89,
            outcome = DisambiguationOutcome.ENGLISH_ONLY,
            timestamp = testTime
        )
        advanceTimeBy(100L)

        // Verify: English history contains 1 item, Hindi history remains EMPTY
        val stateAfterEn = manager.state.value
        assertEquals(1, stateAfterEn.englishTranscriptHistory.size)
        assertEquals(0, stateAfterEn.hindiTranscriptHistory.size)
        assertEquals("someone is following me", stateAfterEn.englishTranscriptHistory.first().text)
        assertEquals("", stateAfterEn.hindiLatestFinalText)

        // 2. Dispatch Hindi utterance after repeat and conflict window
        testTime += 2500L
        advanceTimeBy(2000L)
        service.dispatchForTesting(
            text = "मेरी मदद करो",
            language = ExternalSpeechLanguage.HINDI,
            confidence = 0.92,
            outcome = DisambiguationOutcome.HINDI_ONLY,
            timestamp = testTime
        )
        advanceTimeBy(100L)

        // Verify: Hindi history now contains 1 item, English history still contains only the 1 original item
        val stateAfterHi = manager.state.value
        assertEquals(1, stateAfterHi.englishTranscriptHistory.size)
        assertEquals(1, stateAfterHi.hindiTranscriptHistory.size)
        assertEquals("मेरी मदद करो", stateAfterHi.hindiTranscriptHistory.first().text)
    }

    // =========================================================================
    // SCENARIO 7: English distress speech remains eligible for risk analysis
    // =========================================================================
    @Test
    fun testScenario7_englishDistressSpeechRemainsEligibleForRiskAnalysis() = runTest {
        val router = createTestRouter(backgroundScope)
        val service = VoskAudioTranscriptionService(
            context = null,
            audioRouter = router,
            modelManager = null,
            scope = backgroundScope,
            preferences = mockPreferences
        )

        val manager = createManager(backgroundScope, service)
        manager.handleDoublePress()
        advanceTimeBy(100L)

        service.dispatchForTesting(
            text = "please stop following me",
            language = ExternalSpeechLanguage.ENGLISH,
            confidence = 0.88,
            outcome = DisambiguationOutcome.ENGLISH_ONLY
        )
        advanceTimeBy(100L)

        val state = manager.state.value
        assertNotNull(state.latestAssessment)
        assertTrue(
            "English distress speech must trigger distress assessment",
            state.latestAssessment!!.triggerPhraseMatch
        )
        assertTrue(
            state.latestAssessment!!.category == DistressCategory.POSSIBLE_DISTRESS ||
            state.latestAssessment!!.category == DistressCategory.ELEVATED_CONCERN
        )
        assertTrue(state.timeline.any { it.title == "Relevant Speech Detected" })
    }

    // =========================================================================
    // SCENARIO 8: Hindi distress speech remains eligible for risk analysis
    // =========================================================================
    @Test
    fun testScenario8_hindiDistressSpeechRemainsEligibleForRiskAnalysis() = runTest {
        val router = createTestRouter(backgroundScope)
        val service = VoskAudioTranscriptionService(
            context = null,
            audioRouter = router,
            modelManager = null,
            scope = backgroundScope,
            preferences = mockPreferences
        )

        val manager = createManager(backgroundScope, service)
        manager.handleDoublePress()
        advanceTimeBy(100L)

        service.dispatchForTesting(
            text = "मुझे बचाओ",
            language = ExternalSpeechLanguage.HINDI,
            confidence = 0.90,
            outcome = DisambiguationOutcome.HINDI_ONLY
        )
        advanceTimeBy(100L)

        val state = manager.state.value
        assertNotNull(state.latestAssessment)
        assertTrue(
            "Hindi distress speech must trigger distress assessment",
            state.latestAssessment!!.triggerPhraseMatch
        )
        assertEquals(DistressCategory.POSSIBLE_DISTRESS, state.latestAssessment!!.category)
        assertTrue(state.timeline.any { it.title == "Relevant Speech Detected" })
    }

    // =========================================================================
    // SCENARIO 9: Genuine mixed speech preserves supported segments
    // =========================================================================
    @Test
    fun testScenario9_genuineMixedSpeechPreservesSupportedSegments() {
        val enCandidate = RecognizerCandidate(
            text = "please help",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = 0.85
        )
        val hiCandidate = RecognizerCandidate(
            text = "मेरी मदद करो",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = 0.87
        )

        val decision = arbitrator.arbitrate(enCandidate, hiCandidate)
        assertNotNull(decision)
        assertEquals(DisambiguationOutcome.MIXED_BILINGUAL, decision!!.outcome)
        assertTrue(decision.isMixedSpeech)
        assertNotNull(decision.preservedEnglish)
        assertNotNull(decision.preservedHindi)
        assertEquals("please help", decision.preservedEnglish!!.text)
        assertEquals("मेरी मदद करो", decision.preservedHindi!!.text)
    }

    // =========================================================================
    // SCENARIO 10: Diagnostic isolation remains enforced
    // =========================================================================
    @Test
    fun testScenario10_diagnosticIsolationRemainsEnforced() = runTest {
        val router = createTestRouter(backgroundScope)
        val service = VoskAudioTranscriptionService(
            context = null,
            audioRouter = router,
            modelManager = null,
            scope = backgroundScope,
            preferences = mockPreferences
        )

        val manager = createManager(backgroundScope, service)

        // Start diagnostic session (enforces diagnostic isolation)
        service.startDiagnosticSession()
        assertEquals(RecognitionSessionType.DIAGNOSTIC, service.sessionType.value)
        assertTrue(service.isDiagnosticIsolationEnabled.value)

        // Inject simulated English distress speech during diagnostic session
        service.dispatchForTesting(
            text = "danger someone is following me",
            language = ExternalSpeechLanguage.ENGLISH,
            confidence = 0.95
        )
        advanceTimeBy(100L)

        // Diagnostic preview in service is updated
        assertEquals("danger someone is following me", service.safetyFinalTranscript.value)
        assertNotNull(service.diagnosticAssessment.value)

        // BUT manager emergency state is strictly isolated: no transcripts added
        assertEquals(0, manager.state.value.englishTranscriptHistory.size)
        assertEquals(0, manager.state.value.hindiTranscriptHistory.size)
    }

    // =========================================================================
    // SCENARIO 11: Repeated distress utterances remain eligible under escalation cooldown
    // =========================================================================
    @Test
    fun testScenario11_repeatedDistressUtterancesRemainEligibleUnderCooldown() = runTest {
        mockPreferences.autoCallEscalationEnabled = true
        mockPreferences.escalationPolicy = "CALL_ON_RISK_ESCALATION"
        mockPreferences.escalationPhoneNumber = "+919111222333"
        var virtualTime = 1000L
        val manager = createManager(backgroundScope, timeProvider = { virtualTime })
        manager.handleDoublePress()
        advanceTimeBy(100L)

        val distress1 = TranscriptSegment(
            id = "en-d1",
            timestamp = virtualTime,
            text = "help me please",
            language = ExternalSpeechLanguage.ENGLISH,
            confidence = 0.90,
            isFinal = true
        )

        // 1. First distress utterance
        manager.onSpeechSegmentRecognized(distress1)
        advanceTimeBy(50L)
        assertEquals(1, manager.state.value.englishTranscriptHistory.size)
        assertEquals(1, manager.state.value.consecutiveDistressCount)

        // 2. Rapid duplicate within 1500 ms deduplication window is ignored
        virtualTime = 1500L
        val duplicateImmediate = TranscriptSegment(
            id = "en-d2",
            timestamp = virtualTime,
            text = "help me please",
            language = ExternalSpeechLanguage.ENGLISH,
            confidence = 0.90,
            isFinal = true
        )
        manager.onSpeechSegmentRecognized(duplicateImmediate)
        advanceTimeBy(50L)
        assertEquals(1, manager.state.value.englishTranscriptHistory.size)

        // 3. Repeated distress utterance after > 1500 ms is eligible and processed
        virtualTime = 3200L
        val duplicateAfterWindow = TranscriptSegment(
            id = "en-d3",
            timestamp = virtualTime,
            text = "help me please",
            language = ExternalSpeechLanguage.ENGLISH,
            confidence = 0.90,
            isFinal = true
        )
        manager.onSpeechSegmentRecognized(duplicateAfterWindow)
        advanceTimeBy(50L)
        assertEquals(2, manager.state.value.englishTranscriptHistory.size)
    }

    // =========================================================================
    // SCENARIO 12: VoskRecognitionMode.BILINGUAL English Recognizer is Unrestricted
    // =========================================================================
    @Test
    fun testScenario12_englishRecognizerIsUnrestrictedInBilingualMode() = runTest {
        val router = createTestRouter(backgroundScope)
        val service = VoskAudioTranscriptionService(
            context = null,
            audioRouter = router,
            modelManager = null,
            scope = backgroundScope,
            preferences = mockPreferences
        )

        // Set mode to BILINGUAL
        service.setRecognitionMode(VoskRecognitionMode.BILINGUAL)
        assertEquals(VoskRecognitionMode.BILINGUAL, service.recognitionMode.value)

        // SAKHI_SAFETY_GRAMMAR is reserved strictly for SAFETY_GRAMMAR mode
        service.setRecognitionMode(VoskRecognitionMode.SAFETY_GRAMMAR)
        assertEquals(VoskRecognitionMode.SAFETY_GRAMMAR, service.recognitionMode.value)
    }

    // =========================================================================
    // SCENARIO 13: Prewarm models handles background initialization gracefully
    // =========================================================================
    @Test
    fun testScenario13_prewarmModels_completesGracefully() = runTest {
        val router = createTestRouter(backgroundScope)
        val service = VoskAudioTranscriptionService(
            context = null,
            audioRouter = router,
            modelManager = null,
            scope = backgroundScope,
            preferences = mockPreferences
        )

        service.prewarmModels()
        // When modelManager is null, status reflects idle gracefully
        assertEquals("ASR Idle: No models available", service.statusText.value)
    }

    // =========================================================================
    // SCENARIO 14: handleSourceTransition flushes DSP and maintains stability
    // =========================================================================
    @Test
    fun testScenario14_handleSourceTransition_resetsDspWithoutCrashing() = runTest {
        val router = createTestRouter(backgroundScope)
        val service = VoskAudioTranscriptionService(
            context = null,
            audioRouter = router,
            modelManager = null,
            scope = backgroundScope,
            preferences = mockPreferences
        )

        service.handleSourceTransition(ActiveAudioSource.PHONE_MICROPHONE, ActiveAudioSource.ESP32_BLUETOOTH)
        assertEquals("", service.livePartialText.value)
        assertEquals("", service.safetyPartialText.value)
        assertEquals("", service.unrestrictedPartialText.value)
    }

    private class FakePhoneSource : IPhoneMicrophoneAudioSource {
        private val _telemetry = MutableStateFlow(PhoneAudioTelemetry())
        override val telemetry: StateFlow<PhoneAudioTelemetry> = _telemetry.asStateFlow()
        private val _chunkFlow = MutableSharedFlow<ByteArray>(replay = 0)
        override val audioChunkFlow: SharedFlow<ByteArray> = _chunkFlow.asSharedFlow()
        override var isCapturing: Boolean = false

        override fun startCapture() { isCapturing = true }
        override fun stopCapture() { isCapturing = false }
        override fun release() { isCapturing = false }
    }
}

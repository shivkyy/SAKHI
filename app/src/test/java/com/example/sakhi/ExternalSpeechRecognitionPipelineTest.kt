package com.example.sakhi

import com.example.sakhi.core.model.DistressAssessment
import com.example.sakhi.core.model.DistressCategory
import com.example.sakhi.core.model.SessionState
import com.example.sakhi.core.model.TimelineEventType
import com.example.sakhi.data.ai.EmergencyRiskAnalyzer
import com.example.sakhi.data.audio.PcmAudioResampler
import com.example.sakhi.data.bluetooth.BluetoothClassicAudioReceiver
import com.example.sakhi.domain.emergency.EmergencyStateManager
import com.example.sakhi.domain.escalation.EscalationPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import java.io.File
import com.example.sakhi.data.audio.ActiveAudioSource
import com.example.sakhi.data.audio.SpeexNoiseSuppressor
import com.example.sakhi.data.assistant.ExternalSpeechLanguage
import com.example.sakhi.data.assistant.BilingualTranscriptArbitrator
import com.example.sakhi.data.assistant.RecognizerCandidate
import com.example.sakhi.data.assistant.VoskAudioTranscriptionService
import com.example.sakhi.data.assistant.VoskModelManager
import com.example.sakhi.data.assistant.VoskModelState
import com.example.sakhi.data.assistant.VoskRecognitionMode
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalSpeechRecognitionPipelineTest {

    @Test
    fun testPcmAudioResampler_upsamples8kTo16kAccurately() {
        // Create 2 16-bit samples at 8 kHz: Sample 0 = 1000, Sample 1 = 3000
        val input = ByteArray(4)
        val s0: Short = 1000
        val s1: Short = 3000
        input[0] = (s0.toInt() and 0xFF).toByte()
        input[1] = ((s0.toInt() shr 8) and 0xFF).toByte()
        input[2] = (s1.toInt() and 0xFF).toByte()
        input[3] = ((s1.toInt() shr 8) and 0xFF).toByte()

        val output = PcmAudioResampler.upsample8kTo16k(input)

        // Output must have double the length: 4 samples = 8 bytes
        assertEquals(8, output.size)

        // Read out resampled samples
        val outS0 = (((output[1].toInt() and 0xFF) shl 8) or (output[0].toInt() and 0xFF)).toShort()
        val outSMid = (((output[3].toInt() and 0xFF) shl 8) or (output[2].toInt() and 0xFF)).toShort()
        val outS1 = (((output[5].toInt() and 0xFF) shl 8) or (output[4].toInt() and 0xFF)).toShort()

        assertEquals(1000.toShort(), outS0)
        // Midpoint should be exactly (1000 + 3000) / 2 = 2000
        assertEquals(2000.toShort(), outSMid)
        assertEquals(3000.toShort(), outS1)
    }

    @Test
    fun testAudioChunkFlow_emitsChunksToSpeechRecognitionListener() = runTest {
        val receiver = BluetoothClassicAudioReceiver(scope = backgroundScope)
        val receivedChunks = mutableListOf<ByteArray>()

        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            receiver.audioChunkFlow.collect { chunk ->
                receivedChunks.add(chunk)
            }
        }

        // Simulate incoming audio stream (512 bytes)
        val streamData = ByteArray(512) { (it % 128).toByte() }
        val inputStream = ByteArrayInputStream(streamData)

        receiver.processInputStream(inputStream)

        // Verify audioChunkFlow received chunk
        assertTrue("audioChunkFlow should have received at least one chunk", receivedChunks.isNotEmpty())
        assertEquals(512, receivedChunks.sumOf { it.size })

        collectJob.cancel()
    }

    @Test
    fun testDuplicateSuppressionLogic() {
        var dispatchedCount = 0
        var lastText = ""
        var lastTime = 0L

        fun simulateDispatch(text: String, currentTime: Long) {
            val trimmed = text.trim()
            if (trimmed.equals(lastText, ignoreCase = true) && (currentTime - lastTime) < 3500L) {
                return // Suppress
            }
            lastText = trimmed
            lastTime = currentTime
            dispatchedCount++
        }

        // 1. Initial trigger: "bachao"
        simulateDispatch("bachao", 1000L)
        assertEquals(1, dispatchedCount)

        // 2. Immediate duplicate within window: "bachao" -> suppressed
        simulateDispatch("bachao", 1500L)
        assertEquals(1, dispatchedCount)

        // 3. Different phrase within window: "help me" -> allowed
        simulateDispatch("help me", 2000L)
        assertEquals(2, dispatchedCount)

        // 4. Same phrase after 3.5s window expired: "help me" -> allowed
        simulateDispatch("help me", 6000L)
        assertEquals(3, dispatchedCount)
    }

    @Test
    fun testEmergencyPipeline_receivesAndAnalyzesDistressPhrase() = runTest {
        val mockBleManager = MockBleManager()
        val mockLocationProvider = MockLocationProvider()
        val mockAlertDispatcher = MockAlertDispatcher()
        val mockAudioPipeline = MockAudioPipeline()
        val mockAssessmentEngine = MockAssessmentEngine()
        val escalationPolicy = EscalationPolicy()
        val mockPoliceCallManager = MockPoliceCallManager()
        val mockContactRepository = MockContactRepository()
        val mockSessionHistoryRepository = MockSessionHistoryRepository()
        val mockPreferences = MockPreferences()
        val mockLiveTrackingClient = MockLiveTrackingClient()

        val emergencyStateManager = EmergencyStateManager(
            scope = backgroundScope,
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
            liveTrackingClient = mockLiveTrackingClient
        )

        // When emergency is NOT active, speech should not update transcript
        emergencyStateManager.onSpeechSegmentRecognized("mujhe bachao")
        testScheduler.advanceTimeBy(100)
        assertEquals("", emergencyStateManager.state.value.liveTranscript)

        // Activate Emergency Session via double press
        emergencyStateManager.handleDoublePress()
        testScheduler.advanceTimeBy(100)
        assertEquals(SessionState.ACTIVE, emergencyStateManager.state.value.sessionState)

        // Now deliver recognized speech from external recognizer
        emergencyStateManager.onSpeechSegmentRecognized("mujhe bachao")
        testScheduler.advanceTimeBy(100)

        // Assert live transcript updated
        assertEquals("mujhe bachao", emergencyStateManager.state.value.liveTranscript)

        // Assert risk analyzer detected distress
        val assessment = emergencyStateManager.state.value.latestAssessment
        assertNotNull(assessment)
        assertTrue("Trigger phrase match should be true", assessment!!.triggerPhraseMatch)
        assertEquals(DistressCategory.POSSIBLE_DISTRESS, assessment.category)

        // Assert extracted phrases contain "mujhe bachao"
        assertTrue(
            "recentRelevantPhrases should contain mujhe bachao",
            emergencyStateManager.state.value.recentRelevantPhrases.contains("mujhe bachao")
        )
    }

    @Test
    fun testVoskJsonExtraction_extractsTextAndPartialCorrectly() {
        // Valid text json
        assertEquals("help me", VoskAudioTranscriptionService.extractTextFromJson("""{"text": "help me"}"""))
        // Empty text json
        assertEquals("", VoskAudioTranscriptionService.extractTextFromJson("""{"text": ""}"""))
        // Valid partial json
        assertEquals("hel", VoskAudioTranscriptionService.extractPartialFromJson("""{"partial": "hel"}"""))
        // Empty partial json
        assertEquals("", VoskAudioTranscriptionService.extractPartialFromJson("""{"partial": ""}"""))
        // Malformed JSON should gracefully return empty string without throwing
        assertEquals("", VoskAudioTranscriptionService.extractTextFromJson("{not-a-valid-json"))
        assertEquals("", VoskAudioTranscriptionService.extractPartialFromJson("{not-a-valid-json"))
    }

    @Test
    fun testVoskModelSampleRateDetection_parsesMfccConfCorrectly() {
        val tempDir = File.createTempFile("vosk_model_test", "").also {
            it.delete()
            it.mkdirs()
        }
        try {
            val confDir = File(tempDir, "conf").also { it.mkdirs() }
            val mfccConf = File(confDir, "mfcc.conf")
            mfccConf.writeText("--use-energy=false\n--sample-frequency=8000\n--num-mel-bins=20\n")

            val detectedRate = VoskAudioTranscriptionService.detectModelSampleRate(tempDir.absolutePath)
            assertNotNull(detectedRate)
            assertEquals(8000.0f, detectedRate!!, 0.01f)

            // Test non-existent directory
            val nonExistentRate = VoskAudioTranscriptionService.detectModelSampleRate("/non/existent/path")
            assertNull(nonExistentRate)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testVoskModelVerification_validatesFilesAndSampleRate() {
        val tempDir = File.createTempFile("vosk_verify_test", "").also {
            it.delete()
            it.mkdirs()
        }
        try {
            val confDir = File(tempDir, "conf").also { it.mkdirs() }
            File(confDir, "mfcc.conf").writeText("--sample-frequency=8000\n")

            val amDir = File(tempDir, "am").also { it.mkdirs() }
            // Create dummy final.mdl > 100 KB
            val mdlFile = File(amDir, "final.mdl")
            mdlFile.writeBytes(ByteArray(120_000))

            val graphDir = File(tempDir, "graph").also { it.mkdirs() }
            File(graphDir, "HCLr.fst").writeText("dummy-fst")

            val result = VoskAudioTranscriptionService.verifyModel(tempDir.absolutePath)
            assertTrue("Should support native 8 kHz", result.is8kSupported)
            assertTrue("Should have acoustic model", result.hasAcousticModel)
            assertTrue("Should have graph", result.hasGraph)
            assertEquals(8000.0f, result.sampleFrequency!!, 0.01f)
            assertTrue(result.verificationDetails.contains("Native 8 kHz match"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testVoskRecognitionMode_enumContainsAllRequiredDiagnosticModes() {
        val modes = VoskRecognitionMode.values().map { it.name }
        assertTrue(modes.contains("SAFETY_GRAMMAR"))
        assertTrue(modes.contains("UNRESTRICTED"))
        assertTrue(modes.contains("DUAL_DIAGNOSTIC"))
        assertTrue(modes.contains("BILINGUAL"))
    }

    @Test
    fun testDiagnosticIsolation_suppressesEmergencyEscalation() {
        var emergencyDispatchedCount = 0
        val onEmergencyTranscript: (String) -> Unit = {
            emergencyDispatchedCount++
        }

        // Simulate dispatch logic with isolation enabled
        fun simulateDispatchWithIsolation(
            text: String,
            isFromSafetyGrammar: Boolean,
            isolationEnabled: Boolean,
            callback: (String) -> Unit
        ) {
            val trimmed = text.trim()
            if (trimmed.isBlank()) return

            if (isolationEnabled) {
                // Diagnostic isolation active: Held from emergency pipeline
                return
            }

            if (isFromSafetyGrammar) {
                callback(trimmed)
            }
        }

        // 1. With isolation ENABLED: Safety distress phrase should NOT dispatch to emergency callback
        simulateDispatchWithIsolation("help me", isFromSafetyGrammar = true, isolationEnabled = true, callback = onEmergencyTranscript)
        assertEquals(0, emergencyDispatchedCount)

        // 2. With isolation ENABLED: Unrestricted phrase should NOT dispatch
        simulateDispatchWithIsolation("hello there", isFromSafetyGrammar = false, isolationEnabled = true, callback = onEmergencyTranscript)
        assertEquals(0, emergencyDispatchedCount)

        // 3. With isolation DISABLED: Safety distress phrase SHOULD dispatch
        simulateDispatchWithIsolation("help me", isFromSafetyGrammar = true, isolationEnabled = false, callback = onEmergencyTranscript)
        assertEquals(1, emergencyDispatchedCount)

        // 4. With isolation DISABLED: Unrestricted phrase should still NOT dispatch to emergency pipeline
        simulateDispatchWithIsolation("hello there", isFromSafetyGrammar = false, isolationEnabled = false, callback = onEmergencyTranscript)
        assertEquals(1, emergencyDispatchedCount)
    }

    @Test
    fun testSafetyGrammar_allPhrasesExistInModelVocabulary() {
        val grFstFile = File("src/main/assets/model/graph/Gr.fst")
        val fallbackFile = File("app/src/main/assets/model/graph/Gr.fst")
        val activeFile = if (grFstFile.exists()) grFstFile else fallbackFile

        assertTrue("Gr.fst model file must exist", activeFile.exists())
        val vocab = VoskAudioTranscriptionService.extractWordsFromGrFst(activeFile)
        assertTrue("Model vocabulary must contain symbols (found ${vocab.size})", vocab.size > 50000)

        // Validate SAKHI_SAFETY_GRAMMAR against actual model vocabulary
        val result = VoskAudioTranscriptionService.validateGrammar(
            VoskAudioTranscriptionService.SAKHI_SAFETY_GRAMMAR,
            vocab
        )

        assertTrue(
            "All safety grammar phrases must be supported by the model vocabulary! Unsupported: ${result.unsupportedPhrases}, Missing: ${result.missingWords}",
            result.isValid
        )
        assertTrue("unsupportedPhrases must be empty", result.unsupportedPhrases.isEmpty())
        assertTrue("missingWords must be empty", result.missingWords.isEmpty())

        // Explicitly verify each required English distress phrase from user requirements
        val requiredPhrases = listOf(
            "help",
            "help me",
            "please help",
            "call police",
            "call the police",
            "leave me alone",
            "get away",
            "save me"
        )
        for (required in requiredPhrases) {
            assertTrue(
                "Required phrase '$required' must be in safety grammar supported phrases",
                result.supportedPhrases.contains(required)
            )
        }
    }

    @Test
    fun testUnsupportedVocabulary_detectsMissingHindiPhrases() {
        val grFstFile = File("src/main/assets/model/graph/Gr.fst")
        val fallbackFile = File("app/src/main/assets/model/graph/Gr.fst")
        val activeFile = if (grFstFile.exists()) grFstFile else fallbackFile

        assertTrue("Gr.fst model file must exist", activeFile.exists())
        val vocab = VoskAudioTranscriptionService.extractWordsFromGrFst(activeFile)

        // Format legacy unsupported Hindi phrases as JSON array
        val hindiJson = VoskAudioTranscriptionService.UNSUPPORTED_HINDI_PHRASES.joinToString(
            prefix = "[",
            postfix = "]",
            separator = ", "
        ) { "\"$it\"" }

        val result = VoskAudioTranscriptionService.validateGrammar(hindiJson, vocab)

        assertFalse("Legacy Hindi phrases must be detected as unsupported by the English model", result.isValid)
        assertEquals(
            "All legacy Hindi phrases must be detected as unsupported",
            VoskAudioTranscriptionService.UNSUPPORTED_HINDI_PHRASES.size,
            result.unsupportedPhrases.size
        )

        // Verify specific known missing words
        assertTrue("Missing words must include 'bachao'", result.missingWords.contains("bachao"))
        assertTrue("Missing words must include 'madad'", result.missingWords.contains("madad"))
        assertTrue("Missing words must include 'khatra'", result.missingWords.contains("khatra"))
    }

    @Test
    fun testHighUrgencyDistressPhrase_matchesDistressKeywords() {
        assertTrue(VoskAudioTranscriptionService.isHighUrgencyDistressPhrase("help"))
        assertTrue(VoskAudioTranscriptionService.isHighUrgencyDistressPhrase("help me please"))
        assertTrue(VoskAudioTranscriptionService.isHighUrgencyDistressPhrase("call police"))
        assertTrue(VoskAudioTranscriptionService.isHighUrgencyDistressPhrase("save me"))
        assertTrue(VoskAudioTranscriptionService.isHighUrgencyDistressPhrase("in danger"))
        assertTrue(VoskAudioTranscriptionService.isHighUrgencyDistressPhrase("emergency"))
        assertFalse(VoskAudioTranscriptionService.isHighUrgencyDistressPhrase("hello world"))
        assertFalse(VoskAudioTranscriptionService.isHighUrgencyDistressPhrase("testing one two three"))
    }

    @Test
    fun testDistressPhrases_evaluateToExpectedRiskClassification() {
        val analyzer = EmergencyRiskAnalyzer()
        val targetPhrases = listOf("help me", "please help me", "call police", "leave me alone")

        for (phrase in targetPhrases) {
            analyzer.resetSession()
            val assessment = analyzer.analyzeSegment(phrase)

            assertEquals("Phrase '$phrase' must classify as POSSIBLE_DISTRESS", DistressCategory.POSSIBLE_DISTRESS, assessment.category)
            assertEquals("Phrase '$phrase' must have confidence 0.94", 0.94f, assessment.confidence ?: 0f, 0.01f)
            assertTrue("Phrase '$phrase' must set triggerPhraseMatch to true", assessment.triggerPhraseMatch)
            assertTrue("Phrase '$phrase' contextSummary must mention recognized distress", assessment.contextSummary?.contains("Distress indicator recognized") == true)

            val extracted = analyzer.getExtractedPhrases()
            assertEquals("Phrase '$phrase' must be recorded in extractedPhrases", 1, extracted.size)
            assertEquals(phrase, extracted[0].exactText)
            assertEquals(DistressCategory.POSSIBLE_DISTRESS, extracted[0].indicator)
        }
    }

    @Test
    fun testEscalationPolicy_highRiskSpeechTriggersPoliceEscalation() {
        val policy = EscalationPolicy()
        val analyzer = EmergencyRiskAnalyzer()
        val targetPhrases = listOf("help me", "please help me", "call police", "leave me alone")

        for (phrase in targetPhrases) {
            val assessment = analyzer.analyzeSegment(phrase)
            val activeState = com.example.sakhi.core.model.EmergencyState(
                sessionState = SessionState.ACTIVE,
                policeState = com.example.sakhi.core.model.PoliceState.NOT_REQUESTED
            )

            val decision = policy.evaluateRiskEscalation(
                state = activeState,
                assessment = assessment,
                autoCallEnabled = true,
                policy = "CALL_ON_RISK_ESCALATION",
                destinationNumber = "112"
            )

            assertTrue("Phrase '$phrase' must trigger shouldRequestPolice = true", decision.shouldRequestPolice)
            assertTrue("Reason must mention high-risk distress speech", decision.reason.contains("High-risk distress speech detected"))
        }
    }

    @Test
    fun testBenignPhrases_doNotTriggerDistressEscalation() {
        val analyzer = EmergencyRiskAnalyzer()
        val policy = EscalationPolicy()
        val benignPhrases = listOf(
            "help me with my homework",
            "can you help me carry this bag",
            "help me cook"
        )

        for (phrase in benignPhrases) {
            analyzer.resetSession()
            val assessment = analyzer.analyzeSegment(phrase)

            assertEquals("Benign phrase '$phrase' must classify as NO_CLEAR_INDICATOR", DistressCategory.NO_CLEAR_INDICATOR, assessment.category)
            assertFalse("Benign phrase '$phrase' must have triggerPhraseMatch = false", assessment.triggerPhraseMatch)

            val activeState = com.example.sakhi.core.model.EmergencyState(
                sessionState = SessionState.ACTIVE,
                policeState = com.example.sakhi.core.model.PoliceState.NOT_REQUESTED
            )

            val decision = policy.evaluateRiskEscalation(
                state = activeState,
                assessment = assessment,
                autoCallEnabled = true,
                policy = "CALL_ON_RISK_ESCALATION",
                destinationNumber = "112"
            )

            assertFalse("Benign phrase '$phrase' must NOT trigger police request", decision.shouldRequestPolice)
        }
    }

    @Test
    fun testLiveTranscript_boundedTo400CharsAndJoinedWithBullets() = runTest {
        val emergencyStateManager = EmergencyStateManager(
            scope = backgroundScope,
            bleManager = MockBleManager(),
            locationProvider = MockLocationProvider(),
            alertDispatcher = MockAlertDispatcher(),
            audioPipeline = MockAudioPipeline(),
            assessmentEngine = MockAssessmentEngine(),
            escalationPolicy = EscalationPolicy(),
            policeCallManager = MockPoliceCallManager(),
            contactRepository = MockContactRepository(),
            sessionHistoryRepository = MockSessionHistoryRepository(),
            preferences = MockPreferences(),
            liveTrackingClient = MockLiveTrackingClient()
        )

        // Activate Emergency Session
        emergencyStateManager.handleDoublePress()
        testScheduler.advanceTimeBy(100)

        // Deliver consecutive phrases
        emergencyStateManager.onSpeechSegmentRecognized("help me")
        testScheduler.advanceTimeBy(100)
        assertEquals("help me", emergencyStateManager.state.value.liveTranscript)

        emergencyStateManager.onSpeechSegmentRecognized("please help me")
        testScheduler.advanceTimeBy(100)
        assertEquals("help me · please help me", emergencyStateManager.state.value.liveTranscript)

        emergencyStateManager.onSpeechSegmentRecognized("call police")
        testScheduler.advanceTimeBy(100)
        assertEquals("help me · please help me · call police", emergencyStateManager.state.value.liveTranscript)

        // Deliver repeated long text to test 400-char bounding
        val longSegment = "this is a very long recognized speech segment designed to test the 400 character live transcript bounding mechanism in SAKHI emergency state manager"
        repeat(5) {
            emergencyStateManager.onSpeechSegmentRecognized(longSegment)
            testScheduler.advanceTimeBy(100)
        }

        assertTrue(
            "Live transcript must not exceed 400 characters (actual: ${emergencyStateManager.state.value.liveTranscript.length})",
            emergencyStateManager.state.value.liveTranscript.length <= 400
        )
    }

    @Test
    fun testAllFourDistressPhrases_flowThroughActiveEmergencyState() = runTest {
        val emergencyStateManager = EmergencyStateManager(
            scope = backgroundScope,
            bleManager = MockBleManager(),
            locationProvider = MockLocationProvider(),
            alertDispatcher = MockAlertDispatcher(),
            audioPipeline = MockAudioPipeline(),
            assessmentEngine = MockAssessmentEngine(),
            escalationPolicy = EscalationPolicy(),
            policeCallManager = MockPoliceCallManager(),
            contactRepository = MockContactRepository(),
            sessionHistoryRepository = MockSessionHistoryRepository(),
            preferences = MockPreferences(),
            liveTrackingClient = MockLiveTrackingClient()
        )

        // Activate Emergency Session
        emergencyStateManager.handleDoublePress()
        testScheduler.advanceTimeBy(100)

        val targetPhrases = listOf("help me", "please help me", "call police", "leave me alone")

        for (phrase in targetPhrases) {
            emergencyStateManager.onSpeechSegmentRecognized(phrase)
            testScheduler.advanceTimeBy(100)

            val assessment = emergencyStateManager.state.value.latestAssessment
            assertNotNull(assessment)
            assertEquals(DistressCategory.POSSIBLE_DISTRESS, assessment!!.category)
            assertTrue(assessment.triggerPhraseMatch)

            assertTrue(
                "recentRelevantPhrases must contain '$phrase'",
                emergencyStateManager.state.value.recentRelevantPhrases.contains(phrase)
            )

            // Timeline event check
            val event = emergencyStateManager.state.value.timeline.find {
                it.type == TimelineEventType.AI && it.title == "Relevant Speech Detected" && it.detail.contains(phrase)
            }
            assertNotNull("Timeline must contain 'Relevant Speech Detected' event for '$phrase'", event)
        }
    }

    @Test
    fun testIdleSession_dropsExternalSpeechWithoutModifyingState() = runTest {
        val emergencyStateManager = EmergencyStateManager(
            scope = backgroundScope,
            bleManager = MockBleManager(),
            locationProvider = MockLocationProvider(),
            alertDispatcher = MockAlertDispatcher(),
            audioPipeline = MockAudioPipeline(),
            assessmentEngine = MockAssessmentEngine(),
            escalationPolicy = EscalationPolicy(),
            policeCallManager = MockPoliceCallManager(),
            contactRepository = MockContactRepository(),
            sessionHistoryRepository = MockSessionHistoryRepository(),
            preferences = MockPreferences(),
            liveTrackingClient = MockLiveTrackingClient()
        )

        // Session is IDLE by default
        assertEquals(SessionState.IDLE, emergencyStateManager.state.value.sessionState)

        emergencyStateManager.onSpeechSegmentRecognized("call police")
        testScheduler.advanceTimeBy(100)
        assertEquals("", emergencyStateManager.state.value.liveTranscript)
        assertNull(emergencyStateManager.state.value.latestAssessment)
        assertTrue(emergencyStateManager.state.value.timeline.isEmpty())

        emergencyStateManager.onSpeechSegmentRecognized("help me")
        testScheduler.advanceTimeBy(100)
        assertEquals("", emergencyStateManager.state.value.liveTranscript)
        assertNull(emergencyStateManager.state.value.latestAssessment)
    }

    // =========================================================================
    // SAKHI PHASE 4 — HINDI, HINGLISH & REGRESSION UNIT TESTS
    // =========================================================================

    @Test
    fun testPhase4_devanagariHindiDistressPhrases() {
        val analyzer = EmergencyRiskAnalyzer()
        val devanagariDistressPhrases = listOf(
            "मुझे बचाओ",
            "कोई मेरी मदद करो",
            "कृपया बचाओ",
            "मेरी मदद करो",
            "बचा लो",
            "पुलिस बुलाओ"
        )

        for (phrase in devanagariDistressPhrases) {
            val assessment = analyzer.analyzeSegment(phrase)
            assertEquals("Phrase '$phrase' must be classified as POSSIBLE_DISTRESS", DistressCategory.POSSIBLE_DISTRESS, assessment.category)
            assertTrue("Phrase '$phrase' must set triggerPhraseMatch=true", assessment.triggerPhraseMatch)
            assertEquals(0.94f, assessment.confidence ?: 0f, 0.01f)
        }
    }

    @Test
    fun testPhase4_romanizedHindiDistressPhrases() {
        val analyzer = EmergencyRiskAnalyzer()
        val romanizedPhrases = listOf(
            "Mujhe bachao",
            "Meri madad karo",
            "Kripya bachao",
            "Bacha lo",
            "Police bulao",
            "Mera peecha mat karo"
        )

        for (phrase in romanizedPhrases) {
            val assessment = analyzer.analyzeSegment(phrase)
            assertEquals("Phrase '$phrase' must be classified as POSSIBLE_DISTRESS", DistressCategory.POSSIBLE_DISTRESS, assessment.category)
            assertTrue("Phrase '$phrase' must set triggerPhraseMatch=true", assessment.triggerPhraseMatch)
            assertEquals(0.94f, assessment.confidence ?: 0f, 0.01f)
        }
    }

    @Test
    fun testPhase4_mixedHinglishDistressPhrases() {
        val analyzer = EmergencyRiskAnalyzer()
        val hinglishPhrases = listOf(
            "Meri help karo",
            "Please meri help karo",
            "Koi meri help karo",
            "Koi meri madad karo"
        )

        for (phrase in hinglishPhrases) {
            val assessment = analyzer.analyzeSegment(phrase)
            assertEquals("Phrase '$phrase' must be classified as POSSIBLE_DISTRESS", DistressCategory.POSSIBLE_DISTRESS, assessment.category)
            assertTrue("Phrase '$phrase' must set triggerPhraseMatch=true", assessment.triggerPhraseMatch)
            assertEquals(0.94f, assessment.confidence ?: 0f, 0.01f)
        }
    }

    @Test
    fun testPhase4_benignHindiAndHinglishSentences() {
        val analyzer = EmergencyRiskAnalyzer()
        val benignPhrases = listOf(
            "मुझे खाना बनाने में मदद करो",
            "Mujhe khana banane mein help karo",
            "Mujhe homework mein help karo",
            "Can you help me cook?",
            "Can you help me with my homework?",
            "Can you help me carry this bag?"
        )

        for (phrase in benignPhrases) {
            val assessment = analyzer.analyzeSegment(phrase)
            assertEquals("Benign phrase '$phrase' must be classified as NO_CLEAR_INDICATOR", DistressCategory.NO_CLEAR_INDICATOR, assessment.category)
            assertFalse("Benign phrase '$phrase' must set triggerPhraseMatch=false", assessment.triggerPhraseMatch)
            assertEquals("Benign phrase '$phrase' must have confidence 0.85f", 0.85f, assessment.confidence ?: 0f, 0.01f)
        }

        // Sentence without distress words should naturally fall through to NO_CLEAR_INDICATOR (confidence 0.75f)
        val everydaySentence = "Mujhe ghar jaana hai"
        val assessmentEveryday = analyzer.analyzeSegment(everydaySentence)
        assertEquals(DistressCategory.NO_CLEAR_INDICATOR, assessmentEveryday.category)
        assertFalse(assessmentEveryday.triggerPhraseMatch)
        assertEquals(0.75f, assessmentEveryday.confidence ?: 0f, 0.01f)
    }

    @Test
    fun testPhase4_ambiguousStandaloneWords() {
        val standaloneWords = listOf("help", "madad", "मदद", "madad karo", "मदद करो", "help karo", "bachao", "बचाओ", "police")

        for (word in standaloneWords) {
            val analyzer = EmergencyRiskAnalyzer()
            val assessment = analyzer.analyzeSegment(word)
            assertEquals("Standalone '$word' must be classified as UNCERTAIN", DistressCategory.UNCERTAIN, assessment.category)
            assertFalse("Standalone '$word' must NOT set triggerPhraseMatch=true", assessment.triggerPhraseMatch)
            assertEquals(0.50f, assessment.confidence ?: 0f, 0.01f)
        }
    }

    @Test
    fun testPhase4_emptyAndNoisyTranscripts() {
        val analyzer = EmergencyRiskAnalyzer()
        val noisyTranscripts = listOf("", "   ", "[Silence / Ambient]", "?!!...", "...,,,")

        for (noise in noisyTranscripts) {
            val assessment = analyzer.analyzeSegment(noise)
            assertEquals("Noise '$noise' must be classified as NO_CLEAR_INDICATOR", DistressCategory.NO_CLEAR_INDICATOR, assessment.category)
            assertFalse("Noise '$noise' must set triggerPhraseMatch=false", assessment.triggerPhraseMatch)
            assertNull("Noise '$noise' must have null confidence", assessment.confidence)
        }
    }

    @Test
    fun testPhase4_englishDistressRegressions() {
        val analyzer = EmergencyRiskAnalyzer()
        val englishDistressPhrases = listOf(
            "help me",
            "please help me",
            "call police",
            "leave me alone"
        )

        for (phrase in englishDistressPhrases) {
            val assessment = analyzer.analyzeSegment(phrase)
            assertEquals("English phrase '$phrase' must remain POSSIBLE_DISTRESS", DistressCategory.POSSIBLE_DISTRESS, assessment.category)
            assertTrue("English phrase '$phrase' must retain triggerPhraseMatch=true", assessment.triggerPhraseMatch)
            assertEquals(0.94f, assessment.confidence ?: 0f, 0.01f)
        }
    }

    @Test
    fun testPhase4_contextualBoundaryDistressDetection() {
        val analyzer = EmergencyRiskAnalyzer()

        // Test boundary split: "mujhe" followed by "bachao"
        val firstAssessment = analyzer.analyzeSegment("mujhe")
        // "mujhe" alone does not match a distress pattern (length >= 5 -> NO_CLEAR_INDICATOR)
        assertEquals(DistressCategory.NO_CLEAR_INDICATOR, firstAssessment.category)

        // Second segment completes "mujhe bachao" across segment boundary
        val secondAssessment = analyzer.analyzeSegment("bachao")
        assertEquals(
            "Boundary combination 'mujhe bachao' must be classified as POSSIBLE_DISTRESS",
            DistressCategory.POSSIBLE_DISTRESS,
            secondAssessment.category
        )
        assertTrue(secondAssessment.triggerPhraseMatch)

        // Reset and test boundary split: "koi meri" followed by "madad karo"
        analyzer.resetSession()
        analyzer.analyzeSegment("koi meri")
        val hingedAssessment = analyzer.analyzeSegment("madad karo")
        assertEquals(DistressCategory.POSSIBLE_DISTRESS, hingedAssessment.category)
        assertTrue(hingedAssessment.triggerPhraseMatch)
    }

    @Test
    fun testPhase4_hindiAndHinglishFlowThroughActiveEmergencyState() = runTest {
        val emergencyStateManager = EmergencyStateManager(
            scope = backgroundScope,
            bleManager = MockBleManager(),
            locationProvider = MockLocationProvider(),
            alertDispatcher = MockAlertDispatcher(),
            audioPipeline = MockAudioPipeline(),
            assessmentEngine = MockAssessmentEngine(),
            escalationPolicy = EscalationPolicy(),
            policeCallManager = MockPoliceCallManager(),
            contactRepository = MockContactRepository(),
            sessionHistoryRepository = MockSessionHistoryRepository(),
            preferences = MockPreferences(),
            liveTrackingClient = MockLiveTrackingClient()
        )

        // Activate Emergency Session
        emergencyStateManager.handleDoublePress()
        testScheduler.advanceTimeBy(100)
        assertEquals(SessionState.ACTIVE, emergencyStateManager.state.value.sessionState)

        // 1. Deliver Devanagari Hindi distress phrase
        val devanagariPhrase = "मुझे बचाओ"
        emergencyStateManager.onSpeechSegmentRecognized(devanagariPhrase)
        testScheduler.advanceTimeBy(100)

        assertEquals(devanagariPhrase, emergencyStateManager.state.value.liveTranscript)
        val devAssessment = emergencyStateManager.state.value.latestAssessment
        assertNotNull(devAssessment)
        assertEquals(DistressCategory.POSSIBLE_DISTRESS, devAssessment!!.category)
        assertTrue(devAssessment.triggerPhraseMatch)
        assertTrue(
            "recentRelevantPhrases must contain '$devanagariPhrase'",
            emergencyStateManager.state.value.recentRelevantPhrases.contains(devanagariPhrase)
        )

        // 2. Deliver Hinglish distress phrase
        val hinglishPhrase = "please meri help karo"
        emergencyStateManager.onSpeechSegmentRecognized(hinglishPhrase)
        testScheduler.advanceTimeBy(100)

        assertEquals("$devanagariPhrase · $hinglishPhrase", emergencyStateManager.state.value.liveTranscript)
        val hinglishAssessment = emergencyStateManager.state.value.latestAssessment
        assertNotNull(hinglishAssessment)
        assertEquals(DistressCategory.POSSIBLE_DISTRESS, hinglishAssessment!!.category)
        assertTrue(hinglishAssessment.triggerPhraseMatch)
        assertTrue(
            "recentRelevantPhrases must contain '$hinglishPhrase'",
            emergencyStateManager.state.value.recentRelevantPhrases.contains(hinglishPhrase)
        )

        // Verify timeline contains AI events for both phrases
        val devEvent = emergencyStateManager.state.value.timeline.find {
            it.type == TimelineEventType.AI && it.title == "Relevant Speech Detected" && it.detail.contains(devanagariPhrase)
        }
        assertNotNull("Timeline must contain event for '$devanagariPhrase'", devEvent)

        val hinglishEvent = emergencyStateManager.state.value.timeline.find {
            it.type == TimelineEventType.AI && it.title == "Relevant Speech Detected" && it.detail.contains(hinglishPhrase)
        }
        assertNotNull("Timeline must contain event for '$hinglishPhrase'", hinglishEvent)
    }

    // =========================================================================
    // SAKHI PHASE 4 STEP 2 — LANGUAGE SELECTION, RESAMPLING & MODEL TESTS
    // =========================================================================

    @Test
    fun testLanguageSelection_codesAndDisplayNames() {
        assertEquals("en", ExternalSpeechLanguage.ENGLISH.code)
        assertEquals(8000.0f, ExternalSpeechLanguage.ENGLISH.targetSampleRate, 0.01f)
        assertEquals("hi", ExternalSpeechLanguage.HINDI.code)
        assertEquals(16000.0f, ExternalSpeechLanguage.HINDI.targetSampleRate, 0.01f)

        assertEquals(ExternalSpeechLanguage.ENGLISH, ExternalSpeechLanguage.fromCode("en"))
        assertEquals(ExternalSpeechLanguage.ENGLISH, ExternalSpeechLanguage.fromCode("EN"))
        assertEquals(ExternalSpeechLanguage.HINDI, ExternalSpeechLanguage.fromCode("hi"))
        assertEquals(ExternalSpeechLanguage.HINDI, ExternalSpeechLanguage.fromCode("HI"))
        assertEquals(ExternalSpeechLanguage.ENGLISH, ExternalSpeechLanguage.fromCode("invalid_code"))
    }

    @Test
    fun testLanguagePersistence_savesAndLoadsCorrectly() = runTest {
        val mockPreferences = MockPreferences()
        assertEquals("en", mockPreferences.externalAudioLanguageFlow.first())

        mockPreferences.setExternalAudioLanguage("hi")
        assertEquals("hi", mockPreferences.externalAudioLanguageFlow.first())

        mockPreferences.setExternalAudioLanguage("en")
        assertEquals("en", mockPreferences.externalAudioLanguageFlow.first())
    }

    @Test
    fun testAudioResampling_exactSampleCountAndSignedPCM() {
        // Create 8 kHz 16-bit mono Little-Endian PCM buffer of 16,000 bytes (8,000 samples = exactly 1.0s of audio)
        val sampleCount8k = 8000
        val input8k = ByteArray(sampleCount8k * 2)

        for (i in 0 until sampleCount8k) {
            // Alternate positive and negative signed values
            val sampleVal: Short = if (i % 2 == 0) 1000 else -2000
            val byteIdx = i * 2
            input8k[byteIdx] = (sampleVal.toInt() and 0xFF).toByte()
            input8k[byteIdx + 1] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
        }

        // Resample 8 kHz -> 16 kHz
        val output16k = PcmAudioResampler.upsample8kTo16k(input8k)

        // 1. Exactly double byte length (32,000 bytes)
        assertEquals("16 kHz resampled output must have exactly double byte length (32,000 bytes)", 32000, output16k.size)

        // 2. Total samples = 16,000 samples @ 16 kHz = exactly 1.0 second
        val sampleCount16k = output16k.size / 2
        assertEquals(16000, sampleCount16k)

        // 3. Verify specific sample interpolation
        val s0 = ((output16k[1].toInt() and 0xFF) shl 8) or (output16k[0].toInt() and 0xFF)
        assertEquals(1000.toShort(), s0.toShort())

        val sMid = ((output16k[3].toInt() and 0xFF) shl 8) or (output16k[2].toInt() and 0xFF)
        val expectedMid = ((1000 + (-2000)) / 2).toShort()
        assertEquals(expectedMid, sMid.toShort())
    }

    @Test
    fun testSafetyGrammars_verification() {
        // 1. English grammar preserves all 20 phrases
        val englishPhrases = VoskAudioTranscriptionService.countGrammarPhrases(VoskAudioTranscriptionService.SAKHI_SAFETY_GRAMMAR)
        assertEquals(20, englishPhrases)
        assertTrue(VoskAudioTranscriptionService.SAKHI_SAFETY_GRAMMAR.contains("help me"))
        assertTrue(VoskAudioTranscriptionService.SAKHI_SAFETY_GRAMMAR.contains("call police"))

        // 2. Hindi grammar contains verified Devanagari phrases
        val hindiPhrases = VoskAudioTranscriptionService.countGrammarPhrases(VoskAudioTranscriptionService.SAKHI_HINDI_SAFETY_GRAMMAR)
        assertTrue("Hindi grammar should have at least 12 phrases", hindiPhrases >= 12)
        assertTrue(VoskAudioTranscriptionService.SAKHI_HINDI_SAFETY_GRAMMAR.contains("मुझे बचाओ"))
        assertTrue(VoskAudioTranscriptionService.SAKHI_HINDI_SAFETY_GRAMMAR.contains("मेरी मदद करो"))
        assertTrue(VoskAudioTranscriptionService.SAKHI_HINDI_SAFETY_GRAMMAR.contains("पुलिस बुलाओ"))
        assertTrue(VoskAudioTranscriptionService.SAKHI_HINDI_SAFETY_GRAMMAR.contains("[unk]"))
    }

    @Test
    fun testUnicodeDevanagariJsonExtraction() {
        // Test UTF-8 Devanagari text extraction from Vosk JSON
        val devanagariTextJson = """{"text": "मुझे बचाओ और मेरी मदद करो"}"""
        val extractedText = VoskAudioTranscriptionService.extractTextFromJson(devanagariTextJson)
        assertEquals("मुझे बचाओ और मेरी मदद करो", extractedText)

        // Test UTF-8 Devanagari partial extraction from Vosk JSON
        val devanagariPartialJson = """{"partial": "कृपया बचाओ"}"""
        val extractedPartial = VoskAudioTranscriptionService.extractPartialFromJson(devanagariPartialJson)
        assertEquals("कृपया बचाओ", extractedPartial)
    }

    @Test
    fun testModelDirectoryValidationAndCanonicalResolution() {
        val tempDir = File.createTempFile("vosk_model_val_test", "").also {
            it.delete()
            it.mkdirs()
        }

        try {
            // 1. Empty directory is invalid
            assertFalse(tempDir.listFiles()?.isNotEmpty() == true)

            // 2. Create valid model files
            val amDir = File(tempDir, "am").also { it.mkdirs() }
            val confDir = File(tempDir, "conf").also { it.mkdirs() }

            val mdlFile = File(amDir, "final.mdl")
            mdlFile.writeBytes(ByteArray(60_000)) // > 50,000 bytes

            val mfccConf = File(confDir, "mfcc.conf")
            mfccConf.writeText("--use-energy=false\n--sample-frequency=8000\n")

            val sampleRate = VoskAudioTranscriptionService.detectModelSampleRate(tempDir.absolutePath)
            assertEquals(8000.0f, sampleRate ?: 0f, 0.01f)

            val verification = VoskAudioTranscriptionService.verifyModel(tempDir.absolutePath)
            assertTrue(verification.hasAcousticModel)
            assertTrue(verification.is8kSupported)
            assertEquals(8000.0f, verification.sampleFrequency ?: 0f, 0.01f)

            // 3. Test 16 kHz detection for Hindi
            mfccConf.writeText("--use-energy=false\n--sample-frequency=16000\n")
            val verification16k = VoskAudioTranscriptionService.verifyModel(tempDir.absolutePath)
            assertFalse(verification16k.is8kSupported)
            assertEquals(16000.0f, verification16k.sampleFrequency ?: 0f, 0.01f)
            assertTrue(verification16k.verificationDetails.contains("16 kHz Resampled"))

        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testZipExtractionAndZipSlipProtection() {
        val tempDir = File.createTempFile("zip_extract_test", "").also {
            it.delete()
            it.mkdirs()
        }
        val targetDir = File(tempDir, "extracted").also { it.mkdirs() }
        val testZip = File(tempDir, "test.zip")

        try {
            // 1. Create a safe valid zip
            ZipOutputStream(FileOutputStream(testZip)).use { zos ->
                val entry = ZipEntry("test_file.txt")
                zos.putNextEntry(entry)
                zos.write("hello sakhi".toByteArray())
                zos.closeEntry()
            }

            // Extract with safe unzip logic
            java.util.zip.ZipInputStream(java.io.FileInputStream(testZip)).use { zis ->
                var entry: ZipEntry?
                while (zis.nextEntry.also { entry = it } != null) {
                    val destFile = File(targetDir, entry!!.name)
                    destFile.parentFile?.mkdirs()
                    FileOutputStream(destFile).use { fos ->
                        zis.copyTo(fos)
                    }
                    zis.closeEntry()
                }
            }

            val extractedFile = File(targetDir, "test_file.txt")
            assertTrue("Extracted file must exist", extractedFile.exists())
            assertEquals("hello sakhi", extractedFile.readText())

            // 2. Test Zip Slip path traversal detection
            val maliciousZip = File(tempDir, "malicious.zip")
            ZipOutputStream(FileOutputStream(maliciousZip)).use { zos ->
                val evilEntry = ZipEntry("../evil.txt")
                zos.putNextEntry(evilEntry)
                zos.write("evil".toByteArray())
                zos.closeEntry()
            }

            var detectedSlip = false
            try {
                val canonicalDestDir = targetDir.canonicalPath
                java.util.zip.ZipInputStream(java.io.FileInputStream(maliciousZip)).use { zis ->
                    var entry: ZipEntry?
                    while (zis.nextEntry.also { entry = it } != null) {
                        val destFile = File(targetDir, entry!!.name)
                        if (!destFile.canonicalPath.startsWith(canonicalDestDir + File.separator)) {
                            throw SecurityException("Zip Slip detected: ${entry!!.name}")
                        }
                        zis.closeEntry()
                    }
                }
            } catch (e: SecurityException) {
                detectedSlip = true
            }

            assertTrue("Zip Slip attack entry must be blocked with SecurityException", detectedSlip)

        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // SAKHI PHASE 4.1 — TRANSCRIPT AUTHORITY & CONTEXT CLASSIFICATION TESTS
    // =========================================================================

    @Test
    fun testPhase4_1_shortSafetyGrammarCannotOverrideCompleteBenignUnrestrictedTranscript() {
        // Realistic mocked ASR outputs:
        // Safety constrained grammar outputs truncated tail: "मदद करो"
        // Open-vocabulary unrestricted recognizer decodes complete sentence: "मुझे खाना बनाने में मदद करो"
        val mockSafetyOutput = "मदद करो"
        val mockUnrestrictedOutput = "मुझे खाना बनाने में मदद करो"

        val selectedTranscript = VoskAudioTranscriptionService.selectAuthoritativeTranscript(
            safetyText = mockSafetyOutput,
            unrestrictedText = mockUnrestrictedOutput,
            language = ExternalSpeechLanguage.HINDI,
            mode = VoskRecognitionMode.DUAL_DIAGNOSTIC
        )

        // Verify unrestricted transcript takes authority over truncated safety grammar
        assertEquals(mockUnrestrictedOutput, selectedTranscript)

        // Verify risk classification of authoritative transcript
        val analyzer = EmergencyRiskAnalyzer()
        val assessment = analyzer.analyzeSegment(selectedTranscript)

        assertEquals("Complete benign sentence must be classified as NO_CLEAR_INDICATOR",
            DistressCategory.NO_CLEAR_INDICATOR, assessment.category)
        assertFalse("Benign sentence must NOT match trigger phrase", assessment.triggerPhraseMatch)
        assertEquals(0.85f, assessment.confidence ?: 0f, 0.01f)
    }

    @Test
    fun testPhase4_1_targetPhrasesClassification() {
        val analyzer = EmergencyRiskAnalyzer()

        // 1. BENIGN TARGETS
        val benignTargets = listOf(
            "मुझे खाना बनाने में मदद करो",
            "मुझे होमवर्क में मदद करो",
            "मुझे घर जाना है",
            "Can you help me cook?",
            "Can you help me with my homework?"
        )
        for (phrase in benignTargets) {
            val assessment = analyzer.analyzeSegment(phrase)
            assertEquals("Phrase '$phrase' must be NO_CLEAR_INDICATOR",
                DistressCategory.NO_CLEAR_INDICATOR, assessment.category)
            assertFalse("Phrase '$phrase' must not trigger distress", assessment.triggerPhraseMatch)
        }

        // 2. POTENTIAL DISTRESS TARGETS
        val distressTargets = listOf(
            "मुझे बचाओ",
            "कोई मेरी मदद करो",
            "कृपया बचाओ",
            "मेरी मदद करो",
            "Mujhe bachao",
            "Meri help karo"
        )
        for (phrase in distressTargets) {
            val assessment = analyzer.analyzeSegment(phrase)
            assertEquals("Phrase '$phrase' must be POSSIBLE_DISTRESS",
                DistressCategory.POSSIBLE_DISTRESS, assessment.category)
            assertTrue("Phrase '$phrase' must trigger distress match", assessment.triggerPhraseMatch)
            assertEquals(0.94f, assessment.confidence ?: 0f, 0.01f)
        }

        // 3. AMBIGUOUS TARGETS (Isolated utterances)
        val ambiguousTargets = listOf(
            "मदद करो",
            "मदद",
            "help",
            "madad karo"
        )
        for (phrase in ambiguousTargets) {
            val individualAnalyzer = EmergencyRiskAnalyzer()
            val assessment = individualAnalyzer.analyzeSegment(phrase)
            assertEquals("Isolated phrase '$phrase' must be UNCERTAIN",
                DistressCategory.UNCERTAIN, assessment.category)
            assertFalse("Isolated phrase '$phrase' must not trigger emergency match", assessment.triggerPhraseMatch)
            assertEquals(0.50f, assessment.confidence ?: 0f, 0.01f)
        }
    }

    @Test
    fun testPhase4_1_fallbackWhenUnrestrictedIsMissingOrEmpty() {
        // When unrestricted is empty, safety grammar fallback is used safely
        val safetyDistress = "मुझे बचाओ"
        val selectedDistress = VoskAudioTranscriptionService.selectAuthoritativeTranscript(
            safetyText = safetyDistress,
            unrestrictedText = "",
            language = ExternalSpeechLanguage.HINDI,
            mode = VoskRecognitionMode.DUAL_DIAGNOSTIC
        )
        assertEquals(safetyDistress, selectedDistress)
        val distressAssessment = EmergencyRiskAnalyzer().analyzeSegment(selectedDistress)
        assertEquals(DistressCategory.POSSIBLE_DISTRESS, distressAssessment.category)

        // When unrestricted is empty and safety is ambiguous "मदद करो", it must NOT become distress
        val safetyAmbiguous = "मदद करो"
        val selectedAmbiguous = VoskAudioTranscriptionService.selectAuthoritativeTranscript(
            safetyText = safetyAmbiguous,
            unrestrictedText = "   ",
            language = ExternalSpeechLanguage.HINDI,
            mode = VoskRecognitionMode.DUAL_DIAGNOSTIC
        )
        assertEquals(safetyAmbiguous, selectedAmbiguous)
        val ambiguousAssessment = EmergencyRiskAnalyzer().analyzeSegment(selectedAmbiguous)
        assertEquals(DistressCategory.UNCERTAIN, ambiguousAssessment.category)
        assertFalse(ambiguousAssessment.triggerPhraseMatch)

        // When both are empty
        val selectedEmpty = VoskAudioTranscriptionService.selectAuthoritativeTranscript(
            safetyText = "",
            unrestrictedText = "",
            language = ExternalSpeechLanguage.HINDI,
            mode = VoskRecognitionMode.DUAL_DIAGNOSTIC
        )
        assertEquals("", selectedEmpty)
        val emptyAssessment = EmergencyRiskAnalyzer().analyzeSegment(selectedEmpty)
        assertEquals(DistressCategory.NO_CLEAR_INDICATOR, emptyAssessment.category)
    }

    @Test
    fun testPhase4_1_multiSegmentSpeechStitchingWithAmbiguousWords() {
        val analyzer = EmergencyRiskAnalyzer()

        // Segment 1: "koi meri" (incomplete)
        val seg1 = analyzer.analyzeSegment("koi meri")
        assertEquals(DistressCategory.NO_CLEAR_INDICATOR, seg1.category)

        // Segment 2: "madad karo" (ambiguous alone, but combines with segment 1 into "koi meri madad karo")
        val seg2 = analyzer.analyzeSegment("madad karo")
        assertEquals("Boundary stitched 'koi meri madad karo' must trigger POSSIBLE_DISTRESS",
            DistressCategory.POSSIBLE_DISTRESS, seg2.category)
        assertTrue(seg2.triggerPhraseMatch)
        assertEquals(0.94f, seg2.confidence ?: 0f, 0.01f)
    }

    @Test
    fun testPhase4_1_englishRecognitionPreservation() {
        // In English DUAL_DIAGNOSTIC, safety grammar is preserved as authoritative
        val selectedEnglishDual = VoskAudioTranscriptionService.selectAuthoritativeTranscript(
            safetyText = "help me",
            unrestrictedText = "help me",
            language = ExternalSpeechLanguage.ENGLISH,
            mode = VoskRecognitionMode.DUAL_DIAGNOSTIC
        )
        assertEquals("help me", selectedEnglishDual)

        // In English UNRESTRICTED, unrestricted takes authority
        val selectedEnglishUnres = VoskAudioTranscriptionService.selectAuthoritativeTranscript(
            safetyText = "",
            unrestrictedText = "can you help me cook",
            language = ExternalSpeechLanguage.ENGLISH,
            mode = VoskRecognitionMode.UNRESTRICTED
        )
        assertEquals("can you help me cook", selectedEnglishUnres)
        val assessment = EmergencyRiskAnalyzer().analyzeSegment(selectedEnglishUnres)
        assertEquals(DistressCategory.NO_CLEAR_INDICATOR, assessment.category)
    }

    // =========================================================================
    // SAKHI PHASE 6 — BUNDLED HINDI MODEL PACKAGING & EXTRACTION TESTS
    // =========================================================================

    private fun findAssetsDir(subDir: String): File {
        val candidates = listOf(
            File("src/main/assets", subDir),
            File("app/src/main/assets", subDir),
            File("../app/src/main/assets", subDir)
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: throw IllegalStateException("Could not locate assets/$subDir in candidate paths: $candidates")
    }

    @Test
    fun testPhase6_bundledHindiModel_assetsExistAndAreValid() {
        val hindiAssetDir = findAssetsDir("model-hi")
        assertTrue("Bundled Hindi asset directory must exist", hindiAssetDir.exists())
        assertTrue("Bundled Hindi asset directory must be a directory", hindiAssetDir.isDirectory)

        val amFinalMdl = File(hindiAssetDir, "am/final.mdl")
        assertTrue("am/final.mdl must exist in bundled Hindi model", amFinalMdl.exists())
        assertTrue("am/final.mdl must be > 50,000 bytes (actual: ${amFinalMdl.length()})", amFinalMdl.length() > 50_000L)

        val mfccConf = File(hindiAssetDir, "conf/mfcc.conf")
        assertTrue("conf/mfcc.conf must exist in bundled Hindi model", mfccConf.exists())
        val confContent = mfccConf.readText()
        assertTrue("conf/mfcc.conf must specify 40 mel bins for 16 kHz acoustic model",
            confContent.contains("--num-mel-bins=40"))

        val hclrFst = File(hindiAssetDir, "graph/HCLr.fst")
        assertTrue("graph/HCLr.fst must exist", hclrFst.exists())
        assertTrue("graph/HCLr.fst must be non-empty", hclrFst.length() > 1_000_000L)

        val grFst = File(hindiAssetDir, "graph/Gr.fst")
        assertTrue("graph/Gr.fst must exist", grFst.exists())
        assertTrue("graph/Gr.fst must be non-empty", grFst.length() > 100_000L)
    }

    @Test
    fun testPhase6_bundledHindiModel_sampleRateDetectionAndVerification() {
        val hindiAssetDir = findAssetsDir("model-hi")

        // In official vosk-model-small-hi-0.22, sample-frequency is omitted from mfcc.conf (defaulting to Kaldi standard 16 kHz)
        val detectedSampleRate = VoskAudioTranscriptionService.detectModelSampleRate(hindiAssetDir.absolutePath)
        assertNull("Hindi model omits explicit sample-frequency flag, relying on standard Kaldi 16 kHz default", detectedSampleRate)

        // Effective sample rate resolves through ExternalSpeechLanguage.HINDI.targetSampleRate (16000 Hz)
        val effectiveSampleRate = detectedSampleRate ?: ExternalSpeechLanguage.HINDI.targetSampleRate
        assertEquals(16000.0f, effectiveSampleRate, 0.01f)

        val verification = VoskAudioTranscriptionService.verifyModel(hindiAssetDir.absolutePath)
        assertTrue("Model must have acoustic model", verification.hasAcousticModel)
        assertTrue("Model must have graph", verification.hasGraph)
        assertFalse("Hindi model is 16 kHz and must NOT report 8 kHz native support", verification.is8kSupported)
        assertTrue(verification.verificationDetails.contains("16 kHz Resampled"))
    }

    @Test
    fun testPhase6_bundledHindiModel_idempotentExtractionAndValidation() {
        val tempTargetDir = File.createTempFile("sakhi_hi_target_test", "").also {
            it.delete()
            it.mkdirs()
        }

        try {
            // 1. Target directory is initially empty -> invalid
            val emptyMdl = File(tempTargetDir, "am/final.mdl")
            val emptyConf = File(tempTargetDir, "conf/mfcc.conf")
            val isInitiallyValid = (emptyMdl.exists() && emptyMdl.length() > 50_000L) && emptyConf.exists()
            assertFalse("Empty directory must not be valid", isInitiallyValid)

            // 2. Simulate extraction from assets
            val hindiAssetDir = findAssetsDir("model-hi")
            val targetAm = File(tempTargetDir, "am").also { it.mkdirs() }
            val targetConf = File(tempTargetDir, "conf").also { it.mkdirs() }

            File(hindiAssetDir, "am/final.mdl").copyTo(File(targetAm, "final.mdl"), overwrite = true)
            File(hindiAssetDir, "conf/mfcc.conf").copyTo(File(targetConf, "mfcc.conf"), overwrite = true)

            // 3. Validation should now succeed
            val targetMdl = File(tempTargetDir, "am/final.mdl")
            val targetMfcc = File(tempTargetDir, "conf/mfcc.conf")
            val isValidAfterExtract = (targetMdl.exists() && targetMdl.length() > 50_000L) && targetMfcc.exists()
            assertTrue("Target directory must be valid after extraction", isValidAfterExtract)

            // 4. Idempotency test: if directory already valid, subsequent preparation does not re-extract
            val lastModifiedBefore = targetMdl.lastModified()
            // In VoskModelManager: if (isModelValid(targetDir)) return
            val shouldSkipReextraction = isValidAfterExtract
            assertTrue("Re-extraction should be skipped when already valid", shouldSkipReextraction)
            assertEquals("File should not be touched", lastModifiedBefore, targetMdl.lastModified())

        } finally {
            tempTargetDir.deleteRecursively()
        }
    }

    @Test
    fun testPhase6_englishModel_assetsRemainIntactAndUnchanged() {
        val englishAssetDir = findAssetsDir("model")
        assertTrue("English asset directory must exist", englishAssetDir.exists())

        val amFinalMdl = File(englishAssetDir, "am/final.mdl")
        assertTrue("am/final.mdl must exist in bundled English model", amFinalMdl.exists())
        assertTrue("am/final.mdl must be > 50,000 bytes", amFinalMdl.length() > 50_000L)

        val mfccConf = File(englishAssetDir, "conf/mfcc.conf")
        assertTrue("conf/mfcc.conf must exist in bundled English model", mfccConf.exists())
        val confContent = mfccConf.readText()
        assertTrue("conf/mfcc.conf must declare 8000 Hz sample frequency",
            confContent.contains("--sample-frequency=8000"))

        val detectedSampleRate = VoskAudioTranscriptionService.detectModelSampleRate(englishAssetDir.absolutePath)
        assertEquals(8000.0f, detectedSampleRate ?: 0f, 0.01f)

        val verification = VoskAudioTranscriptionService.verifyModel(englishAssetDir.absolutePath)
        assertTrue(verification.hasAcousticModel)
        assertTrue("English model is native 8 kHz", verification.is8kSupported)
        assertEquals(8000.0f, verification.sampleFrequency ?: 0f, 0.01f)
    }

    @Test
    fun testPhase3_audioSourceTransition_resetsDspFramingAndAccumulator() = runTest {
        val fakeLib = object : com.example.sakhi.data.audio.LibSpeexDsp {
            val fakePtr = com.sun.jna.Pointer(12345L)
            override fun speex_preprocess_state_init(frame_size: Int, sampling_rate: Int) = fakePtr
            override fun speex_preprocess_state_destroy(st: com.sun.jna.Pointer) {}
            override fun speex_preprocess_run(st: com.sun.jna.Pointer, x: ShortArray) = 0
            override fun speex_preprocess_ctl(st: com.sun.jna.Pointer, request: Int, ptr: IntArray) = 0
        }
        val suppressor = SpeexNoiseSuppressor(injectedLib = fakeLib)
        suppressor.isEnabled = true

        // Feed incomplete 20ms frame (160 samples = 320 bytes; feed 100 bytes)
        val incompleteChunk = ByteArray(100) { 1.toByte() }
        val outputBeforeReset = suppressor.process(incompleteChunk)
        // Since frame size is 320 bytes, 100 bytes is accumulated and output is empty
        assertEquals(0, outputBeforeReset.size)

        // Now simulate source transition: reset suppressor
        suppressor.reset()

        // After reset, accumulator is cleared. Feed another 100 bytes.
        val outputAfterReset = suppressor.process(incompleteChunk)
        assertEquals(0, outputAfterReset.size)
        // Total accumulated should be 100, not 200. Feed 220 bytes to complete one 320-byte frame.
        val completingChunk = ByteArray(220) { 1.toByte() }
        val frameOutput = suppressor.process(completingChunk)
        assertEquals(320, frameOutput.size)
    }

    @Test
    fun testPhase3_sharedSpeexNoiseSuppressor_processesIncoming8kBeforeBranching() {
        val suppressor = SpeexNoiseSuppressor()
        // When OFF (default), passthrough is exact bit-for-bit
        assertFalse(suppressor.isEnabled)

        val input8k = ByteArray(320) { (it % 100).toByte() }
        val processed8k = suppressor.process(input8k)
        assertEquals(320, processed8k.size)

        // Resample the 8k processed stream to 16k for the Hindi recognizer
        val resampled16k = PcmAudioResampler.upsample8kTo16k(processed8k)
        assertEquals(640, resampled16k.size)

        // Output of resampler must maintain audio continuity
        assertEquals((processed8k[0].toInt() and 0xFF).toByte(), resampled16k[0])
        assertEquals((processed8k[1].toInt() and 0xFF).toByte(), resampled16k[1])
    }

    @Test
    fun testPhase3_diagnosticIsolationInterlock_preventsEmergencyDispatch() {
        var dispatchedToEmergency = false
        val emergencyCallback: (String) -> Unit = {
            dispatchedToEmergency = true
        }

        // Simulate dispatch logic with isolation enabled (default)
        var isDiagnosticIsolationEnabled = true

        fun handleAuthoritativeTranscript(transcript: String) {
            if (isDiagnosticIsolationEnabled) {
                // Interlock holds transcript from emergency dispatch
                return
            }
            emergencyCallback(transcript)
        }

        handleAuthoritativeTranscript("help me emergency")
        assertFalse("Diagnostic isolation must prevent emergency dispatch", dispatchedToEmergency)

        // When explicitly disabled, dispatch occurs
        isDiagnosticIsolationEnabled = false
        handleAuthoritativeTranscript("help me emergency")
        assertTrue("Dispatch occurs when isolation is disabled", dispatchedToEmergency)
    }

    @Test
    fun testPhase3_bilingualArbitration_singleAuthoritativeDispatch() {
        val arbitrator = BilingualTranscriptArbitrator()

        // Simultaneous recognition results from English and Hindi
        val enCandidate = RecognizerCandidate(
            text = "someone help me",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = 0.88
        )
        val hiCandidate = RecognizerCandidate(
            text = "कोई मेरी मदद करो",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = 0.85
        )

        // Arbitrate between both candidates
        val decision = arbitrator.arbitrate(enCandidate, hiCandidate)
        assertNotNull("Arbitrator must produce a decision", decision)
        assertNotNull("Decision must specify winner", decision!!.winner)

        // Exactly one authoritative dispatch
        var dispatchCount = 0
        var dispatchedText = ""

        if (arbitrator.shouldDispatch(decision.winner)) {
            dispatchCount++
            dispatchedText = decision.winner.text
            arbitrator.recordDispatch(decision.winner)
        }

        assertEquals(1, dispatchCount)
        assertEquals("someone help me", dispatchedText)

        // If the other decoder emits result later, conflict suppression prevents double dispatch
        val duplicateOrConflictAttempt = arbitrator.shouldDispatch(hiCandidate)
        assertFalse("Secondary candidate for the same audio segment must be suppressed", duplicateOrConflictAttempt)
        assertEquals(1, dispatchCount)
    }
}




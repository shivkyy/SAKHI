package com.example.sakhi

import com.example.sakhi.core.model.DistressAssessment
import com.example.sakhi.core.model.DistressCategory
import com.example.sakhi.data.ai.AssessmentEngine
import com.example.sakhi.data.assistant.AssistantManager
import com.example.sakhi.data.assistant.AssistantVisualState
import com.example.sakhi.data.assistant.SpeechTranscriptionService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit test suite for SAKHI Intelligence AssistantManager and state transitions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantManagerTest {

    private lateinit var fakeTranscriptionService: FakeSpeechTranscriptionService
    private lateinit var fakeAssessmentEngine: FakeAssessmentEngine
    private lateinit var testScope: TestScope
    private lateinit var assistantManager: AssistantManager

    @Before
    fun setup() {
        val testDispatcher = UnconfinedTestDispatcher()
        testScope = TestScope(testDispatcher)
        fakeTranscriptionService = FakeSpeechTranscriptionService()
        fakeAssessmentEngine = FakeAssessmentEngine()

        assistantManager = AssistantManager(
            transcriptionService = fakeTranscriptionService,
            assessmentEngineProvider = { fakeAssessmentEngine },
            scope = testScope
        )
    }

    @Test
    fun initialState_isIdleWithEmptyTranscript() {
        val state = assistantManager.state.value
        assertEquals(AssistantVisualState.IDLE, state.visualState)
        assertEquals("", state.transcript)
        assertEquals("", state.partialText)
        assertNull(state.latestAnalysis)
        assertNull(state.errorMessage)
        assertFalse(state.isBottomSheetVisible)
        assertFalse(state.isListeningOrTranscribing)
    }

    @Test
    fun bottomSheetVisibility_togglesCorrectly() {
        assistantManager.setBottomSheetVisible(true)
        assertTrue(assistantManager.state.value.isBottomSheetVisible)

        assistantManager.setBottomSheetVisible(false)
        assertFalse(assistantManager.state.value.isBottomSheetVisible)
    }

    @Test
    fun startListening_whenMicPermissionDenied_transitionsToErrorState() {
        assistantManager.startListening(hasMicPermission = false)

        val state = assistantManager.state.value
        assertEquals(AssistantVisualState.ERROR, state.visualState)
        assertTrue(state.errorMessage?.contains("Microphone permission is required") == true)
        assertTrue(state.requiresPermissionPrompt)
        assertFalse(fakeTranscriptionService.isListening)
    }

    @Test
    fun startListening_whenServiceUnavailable_transitionsToErrorState() {
        fakeTranscriptionService.isAvailable = false
        assistantManager.startListening(hasMicPermission = true)

        val state = assistantManager.state.value
        assertEquals(AssistantVisualState.ERROR, state.visualState)
        assertTrue(state.errorMessage?.contains("Speech recognition service is not available") == true)
        assertFalse(fakeTranscriptionService.isListening)
    }

    @Test
    fun startListening_completeTranscriptionLifecycle() {
        assistantManager.startListening(hasMicPermission = true)
        assertTrue(fakeTranscriptionService.isListening)

        // 1. Service Ready
        fakeTranscriptionService.emitReady()
        assertEquals(AssistantVisualState.LISTENING, assistantManager.state.value.visualState)

        // 2. Speech Begins
        fakeTranscriptionService.emitBeginningOfSpeech()
        assertEquals(AssistantVisualState.TRANSCRIBING, assistantManager.state.value.visualState)

        // 3. Audio Level RMS changes
        fakeTranscriptionService.emitRmsChanged(0.75f)
        assertEquals(0.75f, assistantManager.state.value.audioLevelRms, 0.01f)

        // 4. Partial Recognition Result
        fakeTranscriptionService.emitPartialResult("someone is")
        assertEquals(AssistantVisualState.TRANSCRIBING, assistantManager.state.value.visualState)
        assertEquals("someone is", assistantManager.state.value.partialText)
        assertEquals("someone is", assistantManager.state.value.fullDisplayTranscript)

        // 5. Final Recognition Result
        fakeTranscriptionService.emitFinalResult("someone is following me")
        val finalState = assistantManager.state.value
        assertEquals(AssistantVisualState.IDLE, finalState.visualState)
        assertEquals("someone is following me", finalState.transcript)
        assertEquals("", finalState.partialText)
        assertEquals(0f, finalState.audioLevelRms, 0.01f)
    }

    @Test
    fun multipleUtterances_areConcatenatedInTranscript() {
        assistantManager.startListening(hasMicPermission = true)
        fakeTranscriptionService.emitReady()
        fakeTranscriptionService.emitFinalResult("First sentence.")

        assertEquals("First sentence.", assistantManager.state.value.transcript)

        // Second utterance
        assistantManager.startListening(hasMicPermission = true)
        fakeTranscriptionService.emitReady()
        fakeTranscriptionService.emitFinalResult("Second sentence.")

        assertEquals("First sentence. Second sentence.", assistantManager.state.value.transcript)
    }

    @Test
    fun stopListening_promotesPartialTextAndReleasesMicrophone() {
        assistantManager.startListening(hasMicPermission = true)
        fakeTranscriptionService.emitReady()
        fakeTranscriptionService.emitBeginningOfSpeech()
        fakeTranscriptionService.emitPartialResult("incomplete thought")

        assistantManager.stopListening()

        val state = assistantManager.state.value
        assertEquals(AssistantVisualState.IDLE, state.visualState)
        assertEquals("incomplete thought", state.transcript)
        assertEquals("", state.partialText)
        assertFalse(fakeTranscriptionService.isListening)
    }

    @Test
    fun clearTranscript_resetsAllTranscriptAndAnalysisFields() {
        assistantManager.startListening(hasMicPermission = true)
        fakeTranscriptionService.emitReady()
        fakeTranscriptionService.emitFinalResult("Hello test")
        assistantManager.clearTranscript()

        val state = assistantManager.state.value
        assertEquals("", state.transcript)
        assertEquals("", state.partialText)
        assertNull(state.latestAnalysis)
        assertNull(state.errorMessage)
    }

    @Test
    fun runAiAnalysis_whenTranscriptEmpty_showsError() {
        assistantManager.runAiAnalysis()

        val state = assistantManager.state.value
        assertEquals(AssistantVisualState.ERROR, state.visualState)
        assertTrue(state.errorMessage?.contains("No speech captured") == true)
        assertNull(state.latestAnalysis)
    }

    @Test
    fun runAiAnalysis_evaluatesTranscriptAndPopulatesResult() = testScope.runTest {
        assistantManager.startListening(hasMicPermission = true)
        fakeTranscriptionService.emitReady()
        fakeTranscriptionService.emitFinalResult("bachao help me")

        fakeAssessmentEngine.mockAssessment = DistressAssessment(
            category = DistressCategory.POSSIBLE_DISTRESS,
            confidence = 0.98f,
            transcript = "bachao help me",
            contextSummary = "Emergency distress recognised: 'bachao'",
            providerStatus = "BENCHMARK_DETERMINISTIC",
            triggerPhraseMatch = true
        )

        assistantManager.runAiAnalysis()

        val state = assistantManager.state.value
        assertEquals(AssistantVisualState.IDLE, state.visualState)
        assertNotNull(state.latestAnalysis)
        assertEquals(DistressCategory.POSSIBLE_DISTRESS, state.latestAnalysis?.category)
        assertEquals(0.98f, state.latestAnalysis?.confidence ?: 0f, 0.01f)
        assertEquals("Emergency distress recognised: 'bachao'", state.latestAnalysis?.contextSummary)
    }

    @Test
    fun dismissError_restoresIdleState() {
        assistantManager.startListening(hasMicPermission = false)
        assertEquals(AssistantVisualState.ERROR, assistantManager.state.value.visualState)

        assistantManager.dismissError()
        val state = assistantManager.state.value
        assertEquals(AssistantVisualState.IDLE, state.visualState)
        assertNull(state.errorMessage)
        assertFalse(state.requiresPermissionPrompt)
    }

    @Test
    fun repeatedStartStop_doesNotCorruptStateOrDuplicateSessions() {
        for (i in 1..5) {
            assistantManager.startListening(hasMicPermission = true)
            fakeTranscriptionService.emitReady()
            assertEquals(AssistantVisualState.LISTENING, assistantManager.state.value.visualState)
            assistantManager.stopListening()
            assertEquals(AssistantVisualState.IDLE, assistantManager.state.value.visualState)
        }
    }
}

// --- Test Fakes ---

class FakeSpeechTranscriptionService : SpeechTranscriptionService {
    override var isAvailable: Boolean = true
    var isListening: Boolean = false

    private var onReadyCb: (() -> Unit)? = null
    private var onBeginningCb: (() -> Unit)? = null
    private var onRmsCb: ((Float) -> Unit)? = null
    private var onPartialCb: ((String) -> Unit)? = null
    private var onFinalCb: ((String) -> Unit)? = null
    private var onErrorCb: ((String, Int) -> Unit)? = null

    override fun startListening(
        onReady: () -> Unit,
        onBeginningOfSpeech: () -> Unit,
        onRmsChanged: (Float) -> Unit,
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String, Int) -> Unit
    ) {
        isListening = true
        onReadyCb = onReady
        onBeginningCb = onBeginningOfSpeech
        onRmsCb = onRmsChanged
        onPartialCb = onPartialResult
        onFinalCb = onFinalResult
        onErrorCb = onError
    }

    fun emitReady() = onReadyCb?.invoke()
    fun emitBeginningOfSpeech() = onBeginningCb?.invoke()
    fun emitRmsChanged(level: Float) = onRmsCb?.invoke(level)
    fun emitPartialResult(text: String) = onPartialCb?.invoke(text)
    fun emitFinalResult(text: String) {
        isListening = false
        onFinalCb?.invoke(text)
    }
    fun emitError(msg: String, code: Int = -1) {
        isListening = false
        onErrorCb?.invoke(msg, code)
    }

    override fun stopListening() {
        isListening = false
    }

    override fun destroy() {
        isListening = false
    }
}

class FakeAssessmentEngine : AssessmentEngine {
    override val providerName: String = "Fake Assessment Engine"
    override val isConfigured: Boolean = true

    var mockAssessment: DistressAssessment = DistressAssessment(
        category = DistressCategory.NORMAL,
        confidence = 0.9f,
        transcript = "",
        contextSummary = "Normal conversation",
        providerStatus = "FAKE"
    )

    override suspend fun assessText(transcript: String): DistressAssessment {
        return mockAssessment.copy(transcript = transcript)
    }

    override suspend fun testConnection(): Result<String> = Result.success("Connected")
}

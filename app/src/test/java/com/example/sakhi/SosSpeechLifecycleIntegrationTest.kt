package com.example.sakhi

import com.example.sakhi.core.database.ContactRepository
import com.example.sakhi.core.database.SessionHistoryRepository
import com.example.sakhi.core.model.Contact
import com.example.sakhi.core.model.ContactAlertState
import com.example.sakhi.core.model.DistressAssessment
import com.example.sakhi.core.model.DistressCategory
import com.example.sakhi.core.model.EmergencySession
import com.example.sakhi.core.model.EmergencyState
import com.example.sakhi.core.model.HardwareEvent
import com.example.sakhi.core.model.KeychainLinkState
import com.example.sakhi.core.model.PoliceState
import com.example.sakhi.core.model.SessionState
import com.example.sakhi.core.model.TimelineEventType
import com.example.sakhi.core.preferences.SakhiPreferences
import com.example.sakhi.data.ai.AssessmentEngine
import com.example.sakhi.data.alerts.AlertDispatcher
import com.example.sakhi.data.assistant.AssistantManager
import com.example.sakhi.data.assistant.AssistantVisualState
import com.example.sakhi.data.assistant.ExternalSpeechLanguage
import com.example.sakhi.data.assistant.RecognitionSessionType
import com.example.sakhi.data.assistant.SpeechTranscriptionService
import com.example.sakhi.data.assistant.VoskAudioTranscriptionService
import com.example.sakhi.data.assistant.VoskModelManager
import com.example.sakhi.data.audio.ActiveAudioSource
import com.example.sakhi.data.audio.AudioPipeline
import com.example.sakhi.data.audio.IPhoneMicrophoneAudioSource
import com.example.sakhi.data.audio.PhoneAudioTelemetry
import com.example.sakhi.data.audio.UnifiedAudioRouter
import com.example.sakhi.data.ble.BleDeviceInfo
import com.example.sakhi.data.ble.BleManager
import com.example.sakhi.data.ble.HardwareProtocolConfig
import com.example.sakhi.data.bluetooth.BluetoothClassicAudioReceiver
import com.example.sakhi.data.bluetooth.BtAudioTelemetry
import com.example.sakhi.data.location.LocationProvider
import com.example.sakhi.data.location.SakhiLocation
import com.example.sakhi.domain.emergency.EmergencyStateManager
import com.example.sakhi.domain.escalation.EscalationPolicy
import com.example.sakhi.domain.escalation.PoliceCallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 4 Integration Tests: Unified Vosk Recognition and SOS Lifecycle Integration.
 *
 * Verifies:
 * 1. SOS activation starts unified recognition exactly once.
 * 2. Duplicate activation while ACTIVE is idempotent.
 * 3. Cancellation (handleTapHold) stops recognition, router, mic, and foreground service.
 * 4. Termination (endSession) stops recognition, router, mic, and foreground service.
 * 5. Session isolation: old-session transcripts cannot dispatch into a new session.
 * 6. Triple safety interlock: diagnostic transcripts never trigger SOS emergency dispatch.
 * 7. Production transcripts reach EmergencyStateManager.onSpeechSegmentRecognized.
 * 8. AssistantManager remains independent and isolated from Vosk emergency pipeline.
 * 9. Disabled speech monitoring setting prevents Vosk startup without delaying SOS.
 * 10. Inactive session drops transcripts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SosSpeechLifecycleIntegrationTest {

    private lateinit var mockBleManager: MockBleManager
    private lateinit var mockLocationProvider: MockLocationProvider
    private lateinit var mockAlertDispatcher: MockAlertDispatcher
    private lateinit var mockAudioPipeline: MockAudioPipeline
    private lateinit var mockAssessmentEngine: MockAssessmentEngine
    private lateinit var escalationPolicy: EscalationPolicy
    private lateinit var mockPoliceCallManager: MockPoliceCallManager
    private lateinit var mockContactRepository: MockContactRepository
    private lateinit var mockSessionHistoryRepository: MockSessionHistoryRepository
    private lateinit var mockPreferences: MockPreferences
    private lateinit var mockLiveTrackingClient: MockLiveTrackingClient
    private lateinit var testVoskService: TestableVoskService

    private var foregroundServiceStarterCalls = 0
    private var foregroundServiceStopperCalls = 0

    @Before
    fun setup() {
        mockBleManager = MockBleManager()
        mockLocationProvider = MockLocationProvider()
        mockAlertDispatcher = MockAlertDispatcher()
        mockAudioPipeline = MockAudioPipeline()
        mockAssessmentEngine = MockAssessmentEngine()
        escalationPolicy = EscalationPolicy()
        mockPoliceCallManager = MockPoliceCallManager()
        mockContactRepository = MockContactRepository()
        mockSessionHistoryRepository = MockSessionHistoryRepository()
        mockPreferences = MockPreferences()
        mockLiveTrackingClient = MockLiveTrackingClient()
        foregroundServiceStarterCalls = 0
        foregroundServiceStopperCalls = 0
    }

    private fun createManager(
        scope: CoroutineScope,
        voskService: VoskAudioTranscriptionService? = null,
        legacyTranscriptionService: SpeechTranscriptionService? = null
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
            transcriptionService = legacyTranscriptionService,
            voskTranscriptionService = voskService,
            foregroundServiceStarter = { foregroundServiceStarterCalls++ },
            foregroundServiceStopper = { foregroundServiceStopperCalls++ }
        )
    }

    // =========================================================================
    // 1. SOS ACTIVATION STARTS UNIFIED RECOGNITION EXACTLY ONCE
    // =========================================================================
    @Test
    fun test1_sosActivation_startsUnifiedRecognitionExactlyOnce_andStartsForegroundService() = runTest {
        val testRouter = createTestRouter(backgroundScope)
        testVoskService = TestableVoskService(testRouter, backgroundScope)
        val manager = createManager(backgroundScope, voskService = testVoskService)

        assertEquals(SessionState.IDLE, manager.state.value.sessionState)
        assertEquals(0, foregroundServiceStarterCalls)
        assertEquals(0, testVoskService.startProductionSessionCalls)

        manager.handleDoublePress()
        advanceTimeBy(150L)

        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertEquals(1, foregroundServiceStarterCalls)
        assertEquals(1, testVoskService.startProductionSessionCalls)
        assertNotNull(manager.state.value.activeSessionId)
        assertEquals(manager.state.value.activeSessionId, testVoskService.lastSessionId)
        assertEquals(RecognitionSessionType.PRODUCTION_SOS, testVoskService.sessionType.value)
        assertFalse(testVoskService.isDiagnosticIsolationEnabled.value)
        assertEquals("LISTENING", manager.state.value.speechRecognitionStatus)
    }

    // =========================================================================
    // 2. DUPLICATE ACTIVATION IS IDEMPOTENT
    // =========================================================================
    @Test
    fun test2_duplicateActivation_isIdempotent_noRedundantSessions() = runTest {
        val testRouter = createTestRouter(backgroundScope)
        testVoskService = TestableVoskService(testRouter, backgroundScope)
        val manager = createManager(backgroundScope, voskService = testVoskService)

        manager.handleDoublePress()
        advanceTimeBy(150L)
        val originalSessionId = manager.state.value.activeSessionId

        assertEquals(1, foregroundServiceStarterCalls)
        assertEquals(1, testVoskService.startProductionSessionCalls)

        // Second double-press while already ACTIVE
        manager.handleDoublePress()
        advanceTimeBy(150L)

        assertEquals(1, foregroundServiceStarterCalls)
        assertEquals(1, testVoskService.startProductionSessionCalls)
        assertEquals(originalSessionId, manager.state.value.activeSessionId)
    }

    // =========================================================================
    // 3. CANCELLATION (TAP-HOLD) STOPS RECOGNITION, ROUTER, AND SERVICE
    // =========================================================================
    @Test
    fun test3_tapHoldCancellation_stopsRecognition_stopsForegroundService_stopsRouter() = runTest {
        val testRouter = createTestRouter(backgroundScope)
        testVoskService = TestableVoskService(testRouter, backgroundScope)
        val manager = createManager(backgroundScope, voskService = testVoskService)

        manager.handleDoublePress()
        advanceTimeBy(150L)
        assertTrue(testRouter.isRunning)
        assertEquals(RecognitionSessionType.PRODUCTION_SOS, testVoskService.sessionType.value)

        // Cancel via tap-hold
        manager.handleTapHold()

        assertEquals(SessionState.CANCELLED, manager.state.value.sessionState)
        assertEquals(1, foregroundServiceStopperCalls)
        assertEquals(1, testVoskService.stopProductionSessionCalls)
        assertEquals(RecognitionSessionType.INACTIVE, testVoskService.sessionType.value)
        assertNull(testVoskService.activeSessionId)
        assertFalse(testRouter.isRunning)
    }

    // =========================================================================
    // 4. TERMINATION (END SESSION) STOPS RECOGNITION AND SERVICE
    // =========================================================================
    @Test
    fun test4_endSession_stopsRecognition_andStopsForegroundService() = runTest {
        val testRouter = createTestRouter(backgroundScope)
        testVoskService = TestableVoskService(testRouter, backgroundScope)
        val manager = createManager(backgroundScope, voskService = testVoskService)

        manager.handleDoublePress()
        advanceTimeBy(150L)
        assertEquals(0, foregroundServiceStopperCalls)

        manager.endSession()

        assertEquals(SessionState.ENDED, manager.state.value.sessionState)
        assertEquals(1, foregroundServiceStopperCalls)
        assertEquals(1, testVoskService.stopProductionSessionCalls)
        assertEquals(RecognitionSessionType.INACTIVE, testVoskService.sessionType.value)
        assertNull(testVoskService.activeSessionId)
    }

    // =========================================================================
    // 5. SESSION ISOLATION: OLD SESSION TRANSCRIPTS CANNOT DISPATCH INTO NEW
    // =========================================================================
    @Test
    fun test5_sessionIsolation_oldSessionTranscriptsCannotDispatchIntoNewSession() = runTest {
        val testRouter = createTestRouter(backgroundScope)
        testVoskService = TestableVoskService(testRouter, backgroundScope)
        val manager = createManager(backgroundScope, voskService = testVoskService)

        // Session 1
        manager.handleDoublePress()
        advanceTimeBy(150L)
        val session1Id = manager.state.value.activeSessionId!!
        manager.handleTapHold()

        // Session 2
        manager.handleDoublePress()
        advanceTimeBy(150L)
        val session2Id = manager.state.value.activeSessionId!!
        assertNotEquals(session1Id, session2Id)

        // Attempting to stop Session 1 while Session 2 is active must NOT stop Session 2
        testVoskService.stopProductionSession(session1Id)
        assertEquals(RecognitionSessionType.PRODUCTION_SOS, testVoskService.sessionType.value)
        assertEquals(session2Id, testVoskService.activeSessionId)

        // Stopping Session 2 correctly stops it
        testVoskService.stopProductionSession(session2Id)
        assertEquals(RecognitionSessionType.INACTIVE, testVoskService.sessionType.value)
        assertNull(testVoskService.activeSessionId)
    }

    // =========================================================================
    // 6. TRIPLE SAFETY INTERLOCK: DIAGNOSTIC TRANSCRIPTS NEVER TRIGGER SOS
    // =========================================================================
    @Test
    fun test6_tripleInterlock_diagnosticTranscriptsNeverTriggerSos() = runTest {
        val testRouter = createTestRouter(backgroundScope)
        testVoskService = TestableVoskService(testRouter, backgroundScope)
        val manager = createManager(backgroundScope, voskService = testVoskService)

        // Start Diagnostic Session
        testVoskService.startDiagnosticSession()
        assertEquals(RecognitionSessionType.DIAGNOSTIC, testVoskService.sessionType.value)
        assertTrue(testVoskService.isDiagnosticIsolationEnabled.value)

        // Attempt to disable isolation while in diagnostic session -> Must be rejected!
        testVoskService.setDiagnosticIsolation(false)
        assertTrue("Isolation must remain true during diagnostic session", testVoskService.isDiagnosticIsolationEnabled.value)

        // Dispatch distress phrase through diagnostic session
        testVoskService.dispatchForTesting("help me please someone save me")

        // Invariant: Emergency session remains IDLE, zero alerts, zero calls
        assertEquals(SessionState.IDLE, manager.state.value.sessionState)
        assertEquals(0, manager.state.value.consecutiveDistressCount)
        assertEquals(PoliceState.NOT_REQUESTED, manager.state.value.policeState)
        assertEquals(0, mockPoliceCallManager.callCount)
        assertEquals(0, testVoskService.forwardedProductionTranscripts.size)
    }

    // =========================================================================
    // 7. PRODUCTION TRANSCRIPTS REACH EmergencyStateManager
    // =========================================================================
    @Test
    fun test7_productionTranscripts_reachEmergencyRiskAnalyzer_andIncrementDistress() = runTest {
        val testRouter = createTestRouter(backgroundScope)
        testVoskService = TestableVoskService(testRouter, backgroundScope)
        val manager = createManager(backgroundScope, voskService = testVoskService)

        manager.handleDoublePress()
        advanceTimeBy(150L)
        assertEquals(RecognitionSessionType.PRODUCTION_SOS, testVoskService.sessionType.value)
        assertFalse(testVoskService.isDiagnosticIsolationEnabled.value)

        // Simulate authoritative transcript recognized in production session
        testVoskService.simulateProductionTranscript("help me please")
        advanceTimeBy(150L)

        // Verify transcript reached EmergencyStateManager and was assessed by EmergencyRiskAnalyzer
        assertTrue(manager.state.value.recentRelevantPhrases.contains("help me please"))
        assertNotNull(manager.state.value.latestAssessment)
        assertEquals(DistressCategory.POSSIBLE_DISTRESS, manager.state.value.latestAssessment?.category)
        assertTrue(manager.state.value.latestAssessment?.triggerPhraseMatch == true)
    }

    // =========================================================================
    // 8. ASSISTANT MANAGER REMAINS INTACT AND ISOLATED
    // =========================================================================
    @Test
    fun test8_assistantManager_remainsIntactAndIsolated() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        val assistantScope = TestScope(testDispatcher)
        val fakeTranscription = FakeSpeechTranscriptionService()
        val fakeAssessment = FakeAssessmentEngine()

        val assistantManager = AssistantManager(
            transcriptionService = fakeTranscription,
            assessmentEngineProvider = { fakeAssessment },
            scope = assistantScope
        )

        // Start Vosk production emergency session
        val testRouter = createTestRouter(backgroundScope)
        testVoskService = TestableVoskService(testRouter, backgroundScope)
        val emergencyManager = createManager(backgroundScope, voskService = testVoskService)
        emergencyManager.handleDoublePress()
        advanceTimeBy(150L)

        // Verify AssistantManager is completely unaffected by Vosk emergency session
        assertEquals(AssistantVisualState.IDLE, assistantManager.state.value.visualState)
        assertFalse(assistantManager.state.value.isListeningOrTranscribing)

        // AssistantManager operates independently
        assistantManager.startListening(hasMicPermission = true)
        fakeTranscription.emitReady()
        assertEquals(AssistantVisualState.LISTENING, assistantManager.state.value.visualState)

        fakeTranscription.emitPartialResult("what time is it")
        assertEquals("what time is it", assistantManager.state.value.partialText)

        fakeTranscription.emitFinalResult("what time is it")
        assertEquals(AssistantVisualState.IDLE, assistantManager.state.value.visualState)
        assertEquals("what time is it", assistantManager.state.value.transcript)
    }

    // =========================================================================
    // 9. DISABLED SPEECH MONITORING DOES NOT START VOSK
    // =========================================================================
    @Test
    fun test9_disabledSpeechMonitoring_doesNotStartVosk() = runTest {
        val testRouter = createTestRouter(backgroundScope)
        testVoskService = TestableVoskService(testRouter, backgroundScope)
        mockPreferences.emergencySpeechMonitoring = false

        val manager = createManager(backgroundScope, voskService = testVoskService)
        manager.handleDoublePress()
        advanceTimeBy(150L)

        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertEquals(0, testVoskService.startProductionSessionCalls)
        assertEquals("DISABLED", manager.state.value.speechRecognitionStatus)
        // SOS and foreground service still start normally!
        assertEquals(1, foregroundServiceStarterCalls)
    }

    // =========================================================================
    // 10. INACTIVE SESSION DROPS TRANSCRIPTS
    // =========================================================================
    @Test
    fun test10_inactiveSession_dropsTranscripts() = runTest {
        val testRouter = createTestRouter(backgroundScope)
        testVoskService = TestableVoskService(testRouter, backgroundScope)
        val manager = createManager(backgroundScope, voskService = testVoskService)

        assertEquals(RecognitionSessionType.INACTIVE, testVoskService.sessionType.value)

        // Dispatch when session is inactive
        testVoskService.dispatchForTesting("emergency call police")

        assertEquals(SessionState.IDLE, manager.state.value.sessionState)
        assertEquals(0, testVoskService.forwardedProductionTranscripts.size)
    }

    // =========================================================================
    // TEST DOUBLES & HELPER FACTORIES
    // =========================================================================

    private fun createTestRouter(scope: CoroutineScope): UnifiedAudioRouter {
        val fakeReceiver = FakeBtAudioReceiver(scope)
        val fakePhoneSource = FakePhoneMicrophoneAudioSource()
        return UnifiedAudioRouter(fakeReceiver, fakePhoneSource, scope)
    }

    /**
     * Testable subclass of VoskAudioTranscriptionService that tracks lifecycle invocations
     * and allows simulated transcript injections without loading native Kaldi C++ libraries in JVM tests.
     */
    private class TestableVoskService(
        audioRouter: UnifiedAudioRouter,
        scope: CoroutineScope
    ) : VoskAudioTranscriptionService(
        context = null,
        audioRouter = audioRouter,
        modelManager = null,
        scope = scope,
        preferences = null
    ) {
        var startProductionSessionCalls = 0
        var stopProductionSessionCalls = 0
        var startDiagnosticSessionCalls = 0
        var lastSessionId: String? = null
        val forwardedProductionTranscripts = mutableListOf<String>()
        private var productionCallback: ((String) -> Unit)? = null

        override fun startProductionSession(
            sessionId: String,
            onTranscript: (String) -> Unit,
            onError: ((String) -> Unit)?
        ) {
            startProductionSessionCalls++
            lastSessionId = sessionId
            productionCallback = onTranscript
            val wrappedCallback: (String) -> Unit = { text ->
                forwardedProductionTranscripts.add(text)
                onTranscript(text)
            }
            super.startProductionSession(sessionId, wrappedCallback, onError)
        }

        override fun startDiagnosticSession(onError: ((String) -> Unit)?) {
            startDiagnosticSessionCalls++
            super.startDiagnosticSession(onError)
        }

        override fun stopProductionSession(sessionId: String?) {
            stopProductionSessionCalls++
            super.stopProductionSession(sessionId)
        }

        fun simulateProductionTranscript(text: String) {
            productionCallback?.invoke(text)
        }
    }

    private class FakeBtAudioReceiver(scope: CoroutineScope) : BluetoothClassicAudioReceiver(scope = scope)

    private class FakePhoneMicrophoneAudioSource : IPhoneMicrophoneAudioSource {
        private val _telemetry = MutableStateFlow(PhoneAudioTelemetry())
        override val telemetry: StateFlow<PhoneAudioTelemetry> = _telemetry.asStateFlow()
        private val _chunkFlow = MutableSharedFlow<ByteArray>(replay = 0)
        override val audioChunkFlow: SharedFlow<ByteArray> = _chunkFlow.asSharedFlow()
        override var isCapturing: Boolean = false

        override fun startCapture() {
            isCapturing = true
        }

        override fun stopCapture() {
            isCapturing = false
        }

        override fun release() {
            isCapturing = false
        }
    }
}

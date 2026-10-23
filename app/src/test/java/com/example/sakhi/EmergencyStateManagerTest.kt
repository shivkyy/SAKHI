package com.example.sakhi

import com.example.sakhi.core.database.AppDatabaseHelper
import com.example.sakhi.core.database.ContactRepository
import com.example.sakhi.core.database.SessionHistoryRepository
import com.example.sakhi.core.model.Contact
import com.example.sakhi.core.model.ContactAlertState
import com.example.sakhi.core.model.DistressAssessment
import com.example.sakhi.core.model.DistressCategory
import com.example.sakhi.core.model.EmergencyState
import com.example.sakhi.core.model.HardwareEvent
import com.example.sakhi.core.model.KeychainLinkState
import com.example.sakhi.core.model.PoliceState
import com.example.sakhi.core.model.SessionState
import com.example.sakhi.core.preferences.SakhiPreferences
import com.example.sakhi.data.ai.AssessmentEngine
import com.example.sakhi.data.alerts.AlertDispatcher
import com.example.sakhi.data.audio.AudioPipeline
import com.example.sakhi.data.ble.BleDeviceInfo
import com.example.sakhi.data.ble.BleManager
import com.example.sakhi.data.ble.HardwareProtocolConfig
import com.example.sakhi.data.ble.HardwareProtocolParser
import com.example.sakhi.data.ble.SimulatedBleManager
import com.example.sakhi.data.location.LocationProvider
import com.example.sakhi.data.location.SakhiLocation
import com.example.sakhi.domain.emergency.CountdownController
import com.example.sakhi.domain.emergency.EmergencyStateManager
import com.example.sakhi.domain.escalation.EscalationPolicy
import com.example.sakhi.domain.escalation.PoliceCallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
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
class EmergencyStateManagerTest {

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
    }

    private fun createManager(
        scope: CoroutineScope,
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
            timeProvider = timeProvider
        )
    }

    @Test
    fun invariant1_tripleTapWhileIdleCannotCreatePoliceRequest() = runTest {
        val manager = createManager(backgroundScope)
        assertEquals(SessionState.IDLE, manager.state.value.sessionState)
        assertEquals(PoliceState.NOT_REQUESTED, manager.state.value.policeState)

        // When triple-tap is received while IDLE
        manager.handleTripleTap()

        // Then police request must remain NOT_REQUESTED
        assertEquals(PoliceState.NOT_REQUESTED, manager.state.value.policeState)
        assertFalse(mockPoliceCallManager.callInitiated)
    }

    @Test
    fun invariant2_tripleTapWhileActiveCreatesAtMostOnePoliceRequest() = runTest {
        val manager = createManager(backgroundScope)
        manager.handleDoublePress()
        // Advance past countdown into ACTIVE session
        testScheduler.advanceTimeBy(5500)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertEquals(0, manager.state.value.countdownRemainingSeconds)

        // When triple-tap is pressed
        manager.handleTripleTap()
        testScheduler.advanceTimeBy(100)
        assertEquals(PoliceState.CALL_INITIATED, manager.state.value.policeState)
        assertEquals(1, mockPoliceCallManager.callCount)

        // Rapid duplicate triple-tap (< 1000ms) is debounced
        manager.handleTripleTap()
        testScheduler.advanceTimeBy(100)

        // Must still be 1 call (debounced)
        assertEquals(1, mockPoliceCallManager.callCount)
    }

    @Test
    fun invariant3_countdownDoesNotWaitForAi() = runTest {
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Mother", phoneNumber = "+919876543210")
        )
        mockPreferences.countdownSeconds = 3
        val manager = createManager(backgroundScope)

        // When session activated
        manager.handleDoublePress()
        testScheduler.advanceTimeBy(100)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertEquals(ContactAlertState.COUNTDOWN, manager.state.value.contactAlertState)

        // Advance countdown time to expiry (3s)
        testScheduler.advanceTimeBy(3500)

        // Alert dispatch must proceed immediately without waiting for AI
        assertEquals(ContactAlertState.SENT, manager.state.value.contactAlertState)
        assertTrue(mockAlertDispatcher.dispatchCalled)
    }

    @Test
    fun invariant4_aiUnavailableDoesNotPreventAlertDispatch() = runTest {
        mockAssessmentEngine.isConfigured = false
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Sister", phoneNumber = "+919876543210")
        )
        mockPreferences.countdownSeconds = 3
        val manager = createManager(backgroundScope)

        manager.handleDoublePress()
        testScheduler.advanceTimeBy(3500)

        // Contact alert dispatched despite AI offline
        assertEquals(ContactAlertState.SENT, manager.state.value.contactAlertState)
    }

    @Test
    fun invariant5_validCancellationBeforeExpiryCancelsPendingAlert() = runTest {
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Friend", phoneNumber = "+919876543210")
        )
        mockPreferences.countdownSeconds = 5
        val manager = createManager(backgroundScope)

        manager.handleDoublePress()
        testScheduler.advanceTimeBy(2000) // 2 seconds in, 3 seconds remaining

        // User taps and holds to cancel
        manager.handleTapHold()
        testScheduler.advanceTimeBy(500)

        assertEquals(SessionState.CANCELLED, manager.state.value.sessionState)
        assertEquals(ContactAlertState.CANCELLED, manager.state.value.contactAlertState)
        assertFalse(mockAlertDispatcher.dispatchCalled)
        assertEquals("CANCELLED_BEFORE_ALERT", manager.state.value.activeSessionOutcome)
    }

    @Test
    fun invariant6_cancellationDoesNotPretendToRecallSentAlert() = runTest {
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Friend", phoneNumber = "+919876543210")
        )
        mockPreferences.countdownSeconds = 3
        val manager = createManager(backgroundScope)

        manager.handleDoublePress()
        testScheduler.advanceTimeBy(3500) // Countdown expires, alert is SENT

        assertEquals(ContactAlertState.SENT, manager.state.value.contactAlertState)

        // Now cancel session after alert was already sent
        manager.handleTapHold()
        testScheduler.advanceTimeBy(100)

        assertEquals(SessionState.CANCELLED, manager.state.value.sessionState)
        // Alert state is preserved as SENT (not pretend-cancelled)
        assertEquals(ContactAlertState.SENT, manager.state.value.contactAlertState)
        assertEquals("ALERT_SENT_THEN_CANCELLED", manager.state.value.activeSessionOutcome)
    }

    @Test
    fun invariant7_contactAlertRequiresRealEvidenceForSent() = runTest {
        val manager = createManager(backgroundScope)
        // State begins NOT_STARTED
        assertEquals(ContactAlertState.NOT_STARTED, manager.state.value.contactAlertState)
        assertFalse(mockAlertDispatcher.dispatchCalled)
    }

    @Test
    fun invariant8_policeStatesAreDistinct() = runTest {
        val manager = createManager(backgroundScope)
        manager.handleDoublePress()
        testScheduler.advanceTimeBy(5500)

        assertEquals(PoliceState.NOT_REQUESTED, manager.state.value.policeState)

        manager.handleTripleTap()
        testScheduler.advanceTimeBy(50)

        // Distinct state: CALL_INITIATED
        assertEquals(PoliceState.CALL_INITIATED, manager.state.value.policeState)
    }

    @Test
    fun invariant9_microphoneNotStartedWhileIdle() = runTest {
        val manager = createManager(backgroundScope)
        assertEquals(SessionState.IDLE, manager.state.value.sessionState)
        assertFalse(mockAudioPipeline.isCapturing)
    }

    @Test
    fun invariant10_bleDisconnectDoesNotEraseActiveSession() = runTest {
        val manager = createManager(backgroundScope)
        manager.handleDoublePress()
        testScheduler.advanceTimeBy(100)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)

        // When BLE disconnects
        mockBleManager.linkStateFlow.value = KeychainLinkState.DISCONNECTED
        testScheduler.advanceTimeBy(50)

        // Active session remains ACTIVE
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertEquals(KeychainLinkState.DISCONNECTED, manager.state.value.keychainLinkState)
    }

    @Test
    fun invariant11_malformedAiResponseCannotTriggerEscalation() {
        val state = EmergencyState(sessionState = SessionState.ACTIVE)
        val malformed = DistressAssessment(
            category = DistressCategory.UNCERTAIN,
            confidence = null,
            transcript = "gibberish",
            contextSummary = "Parse error"
        )
        val decision = escalationPolicy.evaluateAiAssessment(state, malformed, true, "BALANCED")
        assertFalse(decision.shouldRequestPolice)
    }

    @Test
    fun protocolParser_decodesDoublePressAndBattery() {
        val parser = HardwareProtocolParser()
        val event1 = parser.parsePacket(byteArrayOf(HardwareProtocolConfig.OPCODE_DOUBLE_PRESS))
        assertTrue(event1 is HardwareEvent.DoublePress)

        val event2 = parser.parsePacket(byteArrayOf(HardwareProtocolConfig.OPCODE_BATTERY_STATUS, 75.toByte(), 1.toByte()))
        assertTrue(event2 is HardwareEvent.BatteryStatus)
        assertEquals(75, (event2 as HardwareEvent.BatteryStatus).percentage)
        assertTrue(event2.isCharging)
    }

    @Test
    fun contactValidation_validatesPhoneLength() {
        val validContact = Contact(name = "Valid", phoneNumber = "+919876543210")
        val invalidContact = Contact(name = "Invalid", phoneNumber = "123")
        assertTrue(validContact.isValid())
        assertFalse(invalidContact.isValid())
    }

    @Test
    fun invariant12_simulationEventsCannotTriggerRealEmergencyCommunication() = runTest {
        val simBle = SimulatedBleManager(backgroundScope)
        assertTrue(simBle.isSimulated)

        // Simulating double press in demo mode
        simBle.simulateDoublePress()
        testScheduler.advanceTimeBy(50)

        // Verify simulated manager received events without touching physical Bluetooth adapter
        assertEquals(0, simBle.simulatedVibrationHistory.size)
        simBle.sendVibration(HardwareProtocolConfig.CMD_VIBRATE_ACTIVATION)
        assertEquals(1, simBle.simulatedVibrationHistory.size)
        assertEquals(HardwareProtocolConfig.CMD_VIBRATE_ACTIVATION, simBle.simulatedVibrationHistory[0])
    }

    @Test
    fun escalationPolicy_sensitivityModesBehaveAccurately() {
        val state = EmergencyState(sessionState = SessionState.ACTIVE, consecutiveDistressCount = 0)
        val distressAssessment = DistressAssessment(
            category = DistressCategory.POSSIBLE_DISTRESS,
            confidence = 0.85f,
            transcript = "Bachao!",
            contextSummary = "Distress cry recognized",
            triggerPhraseMatch = true
        )

        // SENSITIVE: requires 1 detection >= 0.70f -> should escalate immediately
        val sensDecision = escalationPolicy.evaluateAiAssessment(state, distressAssessment, true, "SENSITIVE")
        assertTrue(sensDecision.shouldRequestPolice)
        assertEquals(1, sensDecision.updatedConsecutiveCount)

        // BALANCED: requires 2 detections >= 0.80f -> 1 detection must not escalate yet
        val balDecision1 = escalationPolicy.evaluateAiAssessment(state, distressAssessment, true, "BALANCED")
        assertFalse(balDecision1.shouldRequestPolice)
        assertEquals(1, balDecision1.updatedConsecutiveCount)

        // Second detection for BALANCED -> should escalate
        val stateWith1 = state.copy(consecutiveDistressCount = 1)
        val balDecision2 = escalationPolicy.evaluateAiAssessment(stateWith1, distressAssessment, true, "BALANCED")
        assertTrue(balDecision2.shouldRequestPolice)
        assertEquals(2, balDecision2.updatedConsecutiveCount)

        // CONSERVATIVE: requires 3 detections >= 0.90f -> 0.85f confidence does not qualify
        val consDecision = escalationPolicy.evaluateAiAssessment(state, distressAssessment.copy(triggerPhraseMatch = false), true, "CONSERVATIVE")
        assertFalse(consDecision.shouldRequestPolice)
    }

    @Test
    fun testRealVsSimulatedConnectionState() = runTest {
        mockBleManager.isSimulatedField = false
        val manager = createManager(backgroundScope)
        advanceTimeBy(100)
        assertFalse(manager.state.value.isSimulatedDevice)

        mockBleManager.isSimulatedField = true
        mockBleManager.linkStateFlow.value = KeychainLinkState.CONNECTING
        advanceTimeBy(100)
        assertTrue(manager.state.value.isSimulatedDevice)
    }

    @Test
    fun testConnectionLossClearsRssi() = runTest {
        val manager = createManager(backgroundScope)
        manager.handleHardwareEvent(HardwareEvent.Connected("SAKHI-ESP32", "AA:BB:CC:DD:EE:FF"))
        manager.handleHardwareEvent(HardwareEvent.DeviceStatus("v1.2.0", -55))
        assertEquals(-55, manager.state.value.deviceRssi)

        // When disconnected, RSSI must be cleared to null
        manager.handleHardwareEvent(HardwareEvent.Disconnected("Out of range"))
        assertEquals(KeychainLinkState.DISCONNECTED, manager.state.value.keychainLinkState)
        assertNull(manager.state.value.deviceRssi)
    }

    @Test
    fun testManualSosActivationWorksWhenDisconnected() = runTest {
        mockBleManager.linkStateFlow.value = KeychainLinkState.DISCONNECTED
        val manager = createManager(backgroundScope)
        assertEquals(KeychainLinkState.DISCONNECTED, manager.state.value.keychainLinkState)

        // Phone-based manual SOS should activate emergency even without keychain hardware
        manager.triggerManualSosBackup()
        advanceTimeBy(100)

        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertEquals(ContactAlertState.COUNTDOWN, manager.state.value.contactAlertState)
        assertTrue(manager.state.value.isEmergencyActive)
    }

    @Test
    fun testSafeFollowupMessageDispatchesToContacts() = runTest {
        val manager = createManager(backgroundScope)
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Family Contact", phoneNumber = "+919876543210", relationship = "Sister", priority = 1, smsEnabled = true)
        )
        var callbackInvoked = false
        manager.sendSafeFollowUpMessage { success ->
            callbackInvoked = true
            assertTrue(success)
        }
        advanceTimeBy(500)
        assertTrue(callbackInvoked)
        assertTrue(mockAlertDispatcher.safeFollowUpCalled)
    }

    @Test
    fun truthfulAlertState_handlesMessagingAppFallbackHonestly() = runTest {
        val manager = createManager(backgroundScope)
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Trusted Contact", phoneNumber = "+919876543210", relationship = "Mother", priority = 1, smsEnabled = true)
        )
        // Simulate fallback when direct SMS is restricted/fails
        mockAlertDispatcher.simulatedDispatchResult = ContactAlertState.HANDED_TO_MESSAGING_APP

        manager.handleDoublePress()
        advanceTimeBy(6000)

        // Must report HANDED_TO_MESSAGING_APP truthfully, NEVER false SENT
        assertEquals(ContactAlertState.HANDED_TO_MESSAGING_APP, manager.state.value.contactAlertState)
        assertEquals(ContactAlertState.HANDED_TO_MESSAGING_APP, manager.state.value.contactAlertStatuses[1L])
    }

    @Test
    fun emergencyActivation_missingAudioDoesNotCrash() = runTest {
        val manager = createManager(backgroundScope)
        // Audio pipeline stopped
        mockAudioPipeline.stopCapture()

        manager.handleDoublePress()
        advanceTimeBy(100)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertTrue(manager.state.value.isEmergencyActive)
    }

    @Test
    fun liveTracking_createsSessionAndEmbedsUrlInEmergencySms() = runTest {
        val manager = createManager(backgroundScope)
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Trusted Contact", phoneNumber = "+919876543210", smsEnabled = true)
        )

        // Trigger SOS double-press
        manager.handleDoublePress()
        advanceTimeBy(100) // session created in background
        assertTrue(mockLiveTrackingClient.createSessionCalled)
        assertNotNull(manager.state.value.liveTrackingUrl)
        assertTrue(manager.state.value.isLiveTrackingActive)

        // Advance past countdown (5s)
        advanceTimeBy(6000)
        assertEquals(ContactAlertState.SENT, manager.state.value.contactAlertState)
        // Verify tracking link was embedded in the dispatched SMS
        assertEquals(manager.state.value.liveTrackingUrl, mockAlertDispatcher.lastLiveTrackingUrl)
    }

    @Test
    fun liveTracking_fallsBackToGoogleMapsWhenServerUnreachable() = runTest {
        val manager = createManager(backgroundScope)
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Trusted Contact", phoneNumber = "+919876543210", smsEnabled = true)
        )
        // Simulate offline/unreachable server
        mockLiveTrackingClient.shouldSucceed = false

        manager.handleDoublePress()
        advanceTimeBy(100)
        assertTrue(mockLiveTrackingClient.createSessionCalled)
        assertNull(manager.state.value.liveTrackingUrl)
        assertFalse(manager.state.value.isLiveTrackingActive)

        // Advance past countdown
        advanceTimeBy(6000)
        // Emergency SMS still dispatches successfully using fallback Maps link
        assertEquals(ContactAlertState.SENT, manager.state.value.contactAlertState)
        assertNull(mockAlertDispatcher.lastLiveTrackingUrl)
    }

    @Test
    fun liveTracking_cancelsGatewaySessionOnTapHold() = runTest {
        val manager = createManager(backgroundScope)
        manager.handleDoublePress()
        advanceTimeBy(100)
        assertTrue(manager.state.value.isLiveTrackingActive)

        // Tap-and-hold cancellation
        manager.handleTapHold()
        advanceTimeBy(100)
        assertTrue(mockLiveTrackingClient.cancelSessionCalled)
        assertFalse(manager.state.value.isLiveTrackingActive)
    }

    @Test
    fun liveTracking_uploadsPeriodicLocationUpdates() = runTest {
        val manager = createManager(backgroundScope)
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Trusted Contact", phoneNumber = "+919876543210", smsEnabled = true)
        )
        mockPreferences.periodicSmsInterval = 15

        manager.handleDoublePress()
        advanceTimeBy(6000) // Initial SMS sent
        assertEquals(0, mockLiveTrackingClient.uploadedLocations.size)

        // Advance 16 seconds (triggers first periodic update)
        advanceTimeBy(16000)
        assertTrue(mockLiveTrackingClient.uploadLocationCalled)
        assertTrue(mockAlertDispatcher.periodicUpdateCalled)
        assertTrue(manager.state.value.periodicSmsCount > 0)
    }

    // =========================================================================
    // PHASE: EMERGENCY INTELLIGENCE UPGRADE — 16 REQUIRED UNIT TESTS
    // =========================================================================

    @Test
    fun upgrade1_emergencySpeechMonitoringStartsWhenSessionBecomesActive() = runTest {
        val fakeSpeech = FakeSpeechTranscriptionService()
        mockPreferences.emergencySpeechMonitoring = true
        val manager = EmergencyStateManager(
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
            liveTrackingClient = mockLiveTrackingClient,
            transcriptionService = fakeSpeech
        )

        manager.handleDoublePress()
        advanceTimeBy(100)

        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertTrue(fakeSpeech.isListening)
        assertEquals("LISTENING", manager.state.value.speechRecognitionStatus)
    }

    @Test
    fun upgrade2_emergencySpeechMonitoringStopsWhenSessionEndsOrIsCancelled() = runTest {
        val fakeSpeech = FakeSpeechTranscriptionService()
        val manager = EmergencyStateManager(
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
            liveTrackingClient = mockLiveTrackingClient,
            transcriptionService = fakeSpeech
        )

        manager.handleDoublePress()
        advanceTimeBy(100)
        assertTrue(fakeSpeech.isListening)

        // Cancel session via tap-and-hold
        manager.handleTapHold()
        advanceTimeBy(100)

        assertFalse(fakeSpeech.isListening)
        assertEquals(SessionState.CANCELLED, manager.state.value.sessionState)
        assertEquals("STOPPED", manager.state.value.speechRecognitionStatus)
    }

    @Test
    fun upgrade3_hindiAndHinglishDistressPhraseDetection() {
        val analyzer = com.example.sakhi.data.ai.EmergencyRiskAnalyzer()

        val r1 = analyzer.analyzeSegment("bachao")
        assertEquals(DistressCategory.UNCERTAIN, r1.category)
        assertFalse(r1.triggerPhraseMatch)

        val r1Multi = analyzer.analyzeSegment("mujhe bachao")
        assertEquals(DistressCategory.POSSIBLE_DISTRESS, r1Multi.category)
        assertTrue(r1Multi.triggerPhraseMatch)

        val r2 = analyzer.analyzeSegment("mujhe bachao please")
        assertEquals(DistressCategory.POSSIBLE_DISTRESS, r2.category)
        assertTrue(r2.triggerPhraseMatch)

        val r3 = analyzer.analyzeSegment("mera peecha mat karo")
        assertEquals(DistressCategory.POSSIBLE_DISTRESS, r3.category)
        assertTrue(r3.triggerPhraseMatch)

        val r4 = analyzer.analyzeSegment("मुझे बचाओ")
        assertEquals(DistressCategory.POSSIBLE_DISTRESS, r4.category)
        assertTrue(r4.triggerPhraseMatch)

        val r5 = analyzer.analyzeSegment("mujhe dar lag raha hai")
        assertEquals(DistressCategory.ELEVATED_CONCERN, r5.category)
        assertTrue(r5.triggerPhraseMatch)
    }

    @Test
    fun upgrade4_englishDistressPhraseDetection() {
        val analyzer = com.example.sakhi.data.ai.EmergencyRiskAnalyzer()

        val r1 = analyzer.analyzeSegment("Help me!")
        assertEquals(DistressCategory.POSSIBLE_DISTRESS, r1.category)
        assertTrue(r1.triggerPhraseMatch)

        val r2 = analyzer.analyzeSegment("Stop following me right now")
        assertEquals(DistressCategory.POSSIBLE_DISTRESS, r2.category)
        assertTrue(r2.triggerPhraseMatch)

        val r3 = analyzer.analyzeSegment("Leave me alone!")
        assertEquals(DistressCategory.POSSIBLE_DISTRESS, r3.category)
        assertTrue(r3.triggerPhraseMatch)

        // Benign contextual disambiguation check
        analyzer.resetSession()
        val benign = analyzer.analyzeSegment("Can you help me with my homework?")
        assertEquals(DistressCategory.NO_CLEAR_INDICATOR, benign.category)
        assertFalse(benign.triggerPhraseMatch)
    }

    @Test
    fun upgrade5_phraseDeduplicationAcrossRepeatedSpeechSegments() {
        val analyzer = com.example.sakhi.data.ai.EmergencyRiskAnalyzer()

        analyzer.analyzeSegment("Help me!")
        analyzer.analyzeSegment("help me")
        analyzer.analyzeSegment("  HELP ME!! ")
        analyzer.analyzeSegment("Mujhe bachao")
        analyzer.analyzeSegment("mujhe bachao!")

        val extracted = analyzer.getExtractedPhrases()
        // Only 2 unique normalized phrases should be stored
        assertEquals(2, extracted.size)
        assertEquals("Help me!", extracted[0].exactText)
        assertEquals("Mujhe bachao", extracted[1].exactText)
    }

    @Test
    fun upgrade6_escalationPhoneNumberValidationAndPersistence() = runTest {
        val normalized = mockPreferences.normalizePhoneNumber(" +91 (987) 654-3210 ")
        assertEquals("+919876543210", normalized)
        assertTrue(escalationPolicy.isValidEscalationNumber(normalized))
        assertTrue(escalationPolicy.isValidEscalationNumber("112"))
        assertFalse(escalationPolicy.isValidEscalationNumber("12"))
        assertFalse(escalationPolicy.isValidEscalationNumber(""))

        mockPreferences.setEscalationPhoneNumber(" +91 98765-43210 ")
        assertEquals("+919876543210", mockPreferences.escalationPhoneNumber)
    }

    @Test
    fun upgrade7_escalationPolicyCallOnSosStart_triggersOneCallAttempt() = runTest {
        mockPreferences.autoCallEscalationEnabled = true
        mockPreferences.escalationPolicy = "CALL_ON_SOS_START"
        mockPreferences.escalationPhoneNumber = "+919988776655"
        val manager = createManager(backgroundScope)

        manager.handleDoublePress()
        advanceTimeBy(150)

        assertEquals(PoliceState.CALL_INITIATED, manager.state.value.policeState)
        assertEquals(1, mockPoliceCallManager.callCount)
        assertEquals("+919988776655", mockPoliceCallManager.lastDialedNumber)
        assertTrue(manager.state.value.callAttemptedInSession)
    }

    @Test
    fun upgrade8_escalationPolicyCallOnRiskEscalates_triggersWhenHighRiskSpeechDetected() = runTest {
        mockPreferences.autoCallEscalationEnabled = true
        mockPreferences.escalationPolicy = "CALL_ON_RISK_ESCALATION"
        mockPreferences.escalationPhoneNumber = "+919111222333"
        val manager = createManager(backgroundScope)

        manager.handleDoublePress()
        advanceTimeBy(150)
        // Not called on start because policy is CALL_ON_RISK_ESCALATION
        assertEquals(PoliceState.NOT_REQUESTED, manager.state.value.policeState)
        assertEquals(0, mockPoliceCallManager.callCount)

        // When high-risk speech is recognized
        manager.onSpeechSegmentRecognized("Mujhe bachao please help me")
        advanceTimeBy(150)

        assertEquals(PoliceState.CALL_INITIATED, manager.state.value.policeState)
        assertEquals(1, mockPoliceCallManager.callCount)
        assertEquals("+919111222333", mockPoliceCallManager.lastDialedNumber)
    }

    @Test
    fun upgrade9_escalationDoesNotTriggerWhenDisabledOrUnconfigured() = runTest {
        // Case A: Disabled
        mockPreferences.autoCallEscalationEnabled = false
        mockPreferences.escalationPolicy = "CALL_ON_SOS_START"
        mockPreferences.escalationPhoneNumber = "+919988776655"
        val manager = createManager(backgroundScope)

        manager.handleDoublePress()
        advanceTimeBy(150)
        assertEquals(PoliceState.NOT_REQUESTED, manager.state.value.policeState)
        assertEquals(0, mockPoliceCallManager.callCount)

        manager.endSession()
        advanceTimeBy(100)

        // Case B: Enabled, but escalation phone number is blank
        mockPreferences.autoCallEscalationEnabled = true
        mockPreferences.escalationPolicy = "CALL_ON_RISK_ESCALATION"
        mockPreferences.escalationPhoneNumber = ""
        val manager2 = createManager(backgroundScope)

        manager2.handleDoublePress()
        advanceTimeBy(150)
        manager2.onSpeechSegmentRecognized("Bachao! Help me!")
        advanceTimeBy(150)

        assertEquals(PoliceState.NOT_REQUESTED, manager2.state.value.policeState)
        assertEquals(0, mockPoliceCallManager.callCount)
    }

    @Test
    fun upgrade10_automaticCallIsNotRepeatedMultipleTimesInSameSession() = runTest {
        mockPreferences.autoCallEscalationEnabled = true
        mockPreferences.escalationPolicy = "CALL_ON_RISK_ESCALATION"
        mockPreferences.escalationPhoneNumber = "+919111222333"
        val manager = createManager(backgroundScope)

        manager.handleDoublePress()
        advanceTimeBy(150)

        manager.onSpeechSegmentRecognized("Help me!")
        advanceTimeBy(150)
        assertEquals(1, mockPoliceCallManager.callCount)

        // Subsequent distress utterances in the same session must NOT trigger duplicate calls
        manager.onSpeechSegmentRecognized("Mujhe bachao!")
        advanceTimeBy(150)
        manager.onSpeechSegmentRecognized("Stop following me!")
        advanceTimeBy(150)

        assertEquals(1, mockPoliceCallManager.callCount)
    }

    @Test
    fun upgrade10b_callEnded_allowsSubsequentDistressEscalationAfterCooldown() = runTest {
        var currentTime = 100000L
        mockPreferences.autoCallEscalationEnabled = true
        mockPreferences.escalationPolicy = "CALL_ON_RISK_ESCALATION"
        mockPreferences.escalationPhoneNumber = "+919111222333"
        val manager = createManager(backgroundScope, timeProvider = { currentTime })

        manager.handleDoublePress()
        advanceTimeBy(150)

        // 1. First distress utterance initiates first call
        manager.onSpeechSegmentRecognized("Help me!")
        advanceTimeBy(150)
        assertEquals(1, mockPoliceCallManager.callCount)
        assertEquals(PoliceState.CALL_INITIATED, manager.state.value.policeState)
        assertTrue(manager.state.value.activeCallInProgress)
        assertEquals(0, manager.state.value.completedCallCount)

        // 2. Utterances while call is active are blocked
        manager.onSpeechSegmentRecognized("Bachao!")
        advanceTimeBy(150)
        assertEquals(1, mockPoliceCallManager.callCount)

        // 3. First call completes and ends at currentTime
        manager.onEmergencyCallEnded()
        assertEquals(PoliceState.CALL_ENDED, manager.state.value.policeState)
        assertFalse(manager.state.value.activeCallInProgress)
        assertEquals(1, manager.state.value.completedCallCount)

        // 4. Utterance immediately during 3s cooldown is suppressed (1s < 3s cooldown)
        currentTime += 1000L
        advanceTimeBy(1000)
        manager.onSpeechSegmentRecognized("Emergency attack!")
        advanceTimeBy(150)
        assertEquals(1, mockPoliceCallManager.callCount)

        // 5. Duplicate identical utterance within 10s of call end is suppressed (4s total > 3s cooldown, but < 10s duplicate window)
        currentTime += 3000L
        advanceTimeBy(3000)
        manager.onSpeechSegmentRecognized("Help me!") // Same as first escalated utterance
        advanceTimeBy(150)
        assertEquals(1, mockPoliceCallManager.callCount)

        // 6. Distinct distress utterance after cooldown triggers secondary escalation
        manager.onSpeechSegmentRecognized("Mujhe bachao please!")
        advanceTimeBy(150)
        assertEquals(2, mockPoliceCallManager.callCount)
        assertEquals(PoliceState.CALL_INITIATED, manager.state.value.policeState)
        assertTrue(manager.state.value.activeCallInProgress)
        assertEquals(1, manager.state.value.completedCallCount)
    }

    @Test
    fun bug1_subsequentEmergencySession_initiatesCall_afterPreviousSessionEnded() = runTest {
        mockPreferences.autoCallEscalationEnabled = true
        mockPreferences.escalationPolicy = "CALL_ON_RISK_ESCALATION"
        mockPreferences.escalationPhoneNumber = "+919111222333"
        val manager = createManager(backgroundScope)

        // --- Emergency Session 1 ---
        manager.handleDoublePress()
        advanceTimeBy(150)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)

        // 1. Distress word detected in session 1 -> triggers call 1
        manager.onSpeechSegmentRecognized("Help me!")
        advanceTimeBy(150)
        assertEquals(1, mockPoliceCallManager.callCount)
        assertEquals(PoliceState.CALL_INITIATED, manager.state.value.policeState)
        assertTrue(manager.state.value.activeCallInProgress)
        assertTrue(manager.state.value.callAttemptedInSession)

        // 2. Call ends, then user ends the session
        manager.onEmergencyCallEnded()
        manager.endSession()
        advanceTimeBy(150)

        // Verify session 1 terminated and state reset
        assertEquals(SessionState.ENDED, manager.state.value.sessionState)
        assertEquals(PoliceState.NOT_REQUESTED, manager.state.value.policeState)
        assertFalse(manager.state.value.activeCallInProgress)
        assertFalse(manager.state.value.callAttemptedInSession)
        assertNull(manager.state.value.lastEscalatedUtterance)

        // --- Emergency Session 2 ---
        manager.handleDoublePress()
        advanceTimeBy(150)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertFalse(manager.state.value.activeCallInProgress)
        assertFalse(manager.state.value.callAttemptedInSession)

        // 3. Distress word detected in session 2 -> MUST initiate call 2 (Fix for Bug 1)
        manager.onSpeechSegmentRecognized("Mujhe bachao please!")
        advanceTimeBy(150)
        assertEquals(2, mockPoliceCallManager.callCount)
        assertEquals(PoliceState.CALL_INITIATED, manager.state.value.policeState)
        assertTrue(manager.state.value.activeCallInProgress)
        assertTrue(manager.state.value.callAttemptedInSession)
    }

    @Test
    fun bug1_subsequentEmergencySession_initiatesCall_afterPreviousSessionCancelledViaTapHold() = runTest {
        mockPreferences.autoCallEscalationEnabled = true
        mockPreferences.escalationPolicy = "CALL_ON_RISK_ESCALATION"
        mockPreferences.escalationPhoneNumber = "+919111222333"
        val manager = createManager(backgroundScope)

        // --- Emergency Session 1 ---
        manager.handleDoublePress()
        advanceTimeBy(150)
        manager.onSpeechSegmentRecognized("Help me!")
        advanceTimeBy(150)
        assertEquals(1, mockPoliceCallManager.callCount)

        // Tap and hold (3-second hold to cancel during countdown)
        manager.handleTapHold()
        advanceTimeBy(150)
        assertEquals(SessionState.CANCELLED, manager.state.value.sessionState)
        assertEquals(PoliceState.NOT_REQUESTED, manager.state.value.policeState)
        assertFalse(manager.state.value.activeCallInProgress)
        assertFalse(manager.state.value.callAttemptedInSession)

        // --- Emergency Session 2 ---
        manager.handleDoublePress()
        advanceTimeBy(150)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)

        // Distress word in session 2 -> MUST initiate call 2
        manager.onSpeechSegmentRecognized("Save me someone!")
        advanceTimeBy(150)
        assertEquals(2, mockPoliceCallManager.callCount)
        assertEquals(PoliceState.CALL_INITIATED, manager.state.value.policeState)
    }

    @Test
    fun bug1_activeCallInProgress_blocksDuplicateCall_withinSameSession() = runTest {
        mockPreferences.autoCallEscalationEnabled = true
        mockPreferences.escalationPolicy = "CALL_ON_RISK_ESCALATION"
        mockPreferences.escalationPhoneNumber = "+919111222333"
        val manager = createManager(backgroundScope)

        manager.handleDoublePress()
        advanceTimeBy(150)

        // First distress word initiates call
        manager.onSpeechSegmentRecognized("Help me!")
        advanceTimeBy(150)
        assertEquals(1, mockPoliceCallManager.callCount)
        assertTrue(manager.state.value.activeCallInProgress)

        // Additional distress words while call is active in the same session must be blocked
        manager.onSpeechSegmentRecognized("Emergency! Danger!")
        advanceTimeBy(150)
        assertEquals(1, mockPoliceCallManager.callCount)
    }

    @Test
    fun upgrade11_perContactSmsCounterIncrementsFrom1To2To3OnActualSendEvents() = runTest {
        mockContactRepository.testContacts = listOf(
            Contact(id = 10L, name = "Mother", phoneNumber = "+919876543210", smsEnabled = true),
            Contact(id = 20L, name = "Sister", phoneNumber = "+919876543211", smsEnabled = true)
        )
        mockPreferences.countdownSeconds = 3
        mockPreferences.periodicSmsEnabled = true
        mockPreferences.periodicSmsInterval = 15
        val manager = createManager(backgroundScope)

        manager.handleDoublePress()
        // Advance past 3s countdown -> Initial SOS SMS sent (count = 1)
        advanceTimeBy(3500)
        assertEquals(1, manager.state.value.contactMessageCounts[10L])
        assertEquals(1, manager.state.value.contactMessageCounts[20L])
        assertNotNull(manager.state.value.contactLastSendTimestamps[10L])

        // Advance 15s -> 1st periodic location SMS sent (count = 2)
        advanceTimeBy(15500)
        assertEquals(2, manager.state.value.contactMessageCounts[10L])
        assertEquals(2, manager.state.value.contactMessageCounts[20L])

        // Advance another 15s -> 2nd periodic location SMS sent (count = 3)
        advanceTimeBy(15500)
        assertEquals(3, manager.state.value.contactMessageCounts[10L])
        assertEquals(3, manager.state.value.contactMessageCounts[20L])
    }

    @Test
    fun upgrade12_smsCounterDoesNotIncrementWhenSmsSendingFails() = runTest {
        mockContactRepository.testContacts = listOf(
            Contact(id = 10L, name = "Mother", phoneNumber = "+919876543210", smsEnabled = true)
        )
        mockPreferences.countdownSeconds = 3
        mockAlertDispatcher.simulatedDispatchResult = ContactAlertState.FAILED
        val manager = createManager(backgroundScope)

        manager.handleDoublePress()
        advanceTimeBy(3500)

        assertEquals(ContactAlertState.FAILED, manager.state.value.contactAlertState)
        assertEquals(0, manager.state.value.contactMessageCounts[10L] ?: 0)
    }

    @Test
    fun upgrade13_relevantSpeechIncludedInPeriodicSmsOnlyWhenSettingEnabled() = runTest {
        mockContactRepository.testContacts = listOf(
            Contact(id = 1L, name = "Trusted Contact", phoneNumber = "+919876543210", smsEnabled = true)
        )
        mockPreferences.countdownSeconds = 3
        mockPreferences.periodicSmsEnabled = true
        mockPreferences.periodicSmsInterval = 15
        mockPreferences.includeSpeechInSms = true
        val manager = createManager(backgroundScope)

        manager.handleDoublePress()
        advanceTimeBy(3500) // Initial SOS SMS sent immediately

        // User speaks a distress phrase during active session
        manager.onSpeechSegmentRecognized("Mera peecha mat karo")
        advanceTimeBy(200)

        // Advance 15s for first periodic location update
        advanceTimeBy(15500)
        assertEquals("Mera peecha mat karo", mockAlertDispatcher.lastSpeechExcerpt)
        assertEquals("Possible distress indicator", mockAlertDispatcher.lastAssessmentLabel)

        // Advance another 15s without new speech -> must NOT resend the same phrase again
        advanceTimeBy(15500)
        assertNull(mockAlertDispatcher.lastSpeechExcerpt)
    }

    @Test
    fun upgrade14_relevantSpeechNotIncludedInPeriodicSmsWhenSettingDisabled() = runTest {
        mockContactRepository.testContacts = listOf(
            Contact(id = 1L, name = "Trusted Contact", phoneNumber = "+919876543210", smsEnabled = true)
        )
        mockPreferences.countdownSeconds = 3
        mockPreferences.periodicSmsEnabled = true
        mockPreferences.periodicSmsInterval = 15
        mockPreferences.includeSpeechInSms = false // Disabled (Default)
        val manager = createManager(backgroundScope)

        manager.handleDoublePress()
        advanceTimeBy(3500)

        manager.onSpeechSegmentRecognized("Bachao help me")
        advanceTimeBy(200)

        // Advance 15s for periodic location update
        advanceTimeBy(15500)
        assertTrue(mockAlertDispatcher.periodicUpdateCalled)
        assertNull(mockAlertDispatcher.lastSpeechExcerpt)
    }

    @Test
    fun upgrade15_locationUpdateIntervalChangesAreSavedAndAppliedCorrectly() = runTest {
        mockContactRepository.testContacts = listOf(
            Contact(id = 1L, name = "Trusted Contact", phoneNumber = "+919876543210", smsEnabled = true)
        )
        mockPreferences.countdownSeconds = 3
        mockPreferences.setPeriodicSmsInterval(60)
        assertEquals(60, mockPreferences.periodicSmsInterval)

        val manager = createManager(backgroundScope)
        manager.handleDoublePress()
        advanceTimeBy(3500) // Initial SMS sent

        // Change interval to 15 seconds during active session
        mockPreferences.setPeriodicSmsInterval(15)
        manager.onPeriodicIntervalChanged()

        // Advance 16 seconds -> should trigger periodic update under new 15s interval
        advanceTimeBy(16000)
        assertEquals(1, mockAlertDispatcher.periodicUpdateCount)
    }

    @Test
    fun upgrade16_sosAndInitialSmsStillWorkIfSpeechRecognitionOrAiFails() = runTest {
        val unavailableSpeech = FakeSpeechTranscriptionService().apply { isAvailable = false }
        val failingAi = object : AssessmentEngine {
            override val providerName: String = "Failing AI"
            override val isConfigured: Boolean = true
            override suspend fun assessText(transcript: String): DistressAssessment {
                throw RuntimeException("Simulated network & AI crash")
            }
            override suspend fun testConnection(): Result<String> = Result.failure(Exception("Offline"))
        }
        mockContactRepository.testContacts = listOf(
            Contact(id = 1L, name = "Mother", phoneNumber = "+919876543210", smsEnabled = true)
        )
        mockPreferences.countdownSeconds = 3

        val manager = EmergencyStateManager(
            scope = backgroundScope,
            bleManager = mockBleManager,
            locationProvider = mockLocationProvider,
            alertDispatcher = mockAlertDispatcher,
            audioPipeline = mockAudioPipeline,
            assessmentEngine = failingAi,
            escalationPolicy = escalationPolicy,
            policeCallManager = mockPoliceCallManager,
            contactRepository = mockContactRepository,
            sessionHistoryRepository = mockSessionHistoryRepository,
            preferences = mockPreferences,
            liveTrackingClient = mockLiveTrackingClient,
            transcriptionService = unavailableSpeech
        )

        manager.handleDoublePress()
        advanceTimeBy(3500)

        // SOS is active and initial SMS was sent despite speech service unavailable & AI throwing
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertEquals(ContactAlertState.SENT, manager.state.value.contactAlertState)
        assertEquals(1, manager.state.value.contactMessageCounts[1L])
    }

    @Test
    fun directCall1_initiatesDirectCallViaActionCallWhenPermissionGranted() = runTest {
        var directCallInvokedWith: String? = null
        var dialerFallbackInvoked = false
        val callManager = object : PoliceCallManager(null) {
            override fun hasCallPhonePermission(): Boolean = true
            override fun performDirectCall(cleanedNumber: String): Boolean {
                directCallInvokedWith = cleanedNumber
                return true
            }
            override fun performDialerFallback(cleanedNumber: String): Boolean {
                dialerFallbackInvoked = true
                return true
            }
        }

        val state = callManager.initiateEmergencyCall("+91 98765-43210")
        assertEquals(PoliceState.CALL_INITIATED, state)
        assertEquals("+919876543210", directCallInvokedWith)
        assertFalse(dialerFallbackInvoked)
        assertEquals(android.content.Intent.ACTION_CALL, callManager.lastCallAction)
    }

    @Test
    fun directCall2_usesTelecomManagerWhenActivityStartBlockedInBackground() = runTest {
        var telecomInvokedWith: String? = null
        var dialerFallbackInvoked = false
        val callManager = object : PoliceCallManager(null) {
            override fun hasCallPhonePermission(): Boolean = true
            override fun performDirectCall(cleanedNumber: String): Boolean {
                throw SecurityException("Background activity launch blocked")
            }
            override fun performTelecomPlaceCall(cleanedNumber: String): Boolean {
                telecomInvokedWith = cleanedNumber
                return true
            }
            override fun performDialerFallback(cleanedNumber: String): Boolean {
                dialerFallbackInvoked = true
                return true
            }
        }

        val state = callManager.initiateEmergencyCall("+919876543210")
        assertEquals(PoliceState.CALL_INITIATED, state)
        assertEquals("+919876543210", telecomInvokedWith)
        assertFalse(dialerFallbackInvoked)
        assertEquals(android.content.Intent.ACTION_CALL, callManager.lastCallAction)
    }

    @Test
    fun directCall3_fallsBackSafelyAndNeverInterruptsSmsOrLocationWhenCallRestricted() = runTest {
        // Test 1: Permission denied -> falls back to ACTION_DIAL
        var dialerInvokedWith: String? = null
        val dialFallbackManager = object : PoliceCallManager(null) {
            override fun hasCallPhonePermission(): Boolean = false
            override fun performDialerFallback(cleanedNumber: String): Boolean {
                dialerInvokedWith = cleanedNumber
                return true
            }
        }
        val fallbackState = dialFallbackManager.initiateEmergencyCall("+919876543210")
        assertEquals(PoliceState.CALL_INITIATED, fallbackState)
        assertEquals("+919876543210", dialerInvokedWith)
        assertEquals(android.content.Intent.ACTION_DIAL, dialFallbackManager.lastCallAction)

        // Test 2: Complete call failure does not interrupt SMS or location delivery during active SOS
        val failingCallManager = object : PoliceCallManager(null) {
            override fun hasCallPhonePermission(): Boolean = false
            override fun performDialerFallback(cleanedNumber: String): Boolean {
                throw SecurityException("All call intents blocked by OS")
            }
        }
        mockContactRepository.testContacts = listOf(
            Contact(id = 1L, name = "Mother", phoneNumber = "+919876543210", smsEnabled = true)
        )
        mockPreferences.countdownSeconds = 3
        mockPreferences.autoCallEscalationEnabled = true
        mockPreferences.escalationPolicy = "CALL_ON_SOS_START"
        mockPreferences.escalationPhoneNumber = "+919876543210"

        val manager = EmergencyStateManager(
            scope = backgroundScope,
            bleManager = mockBleManager,
            locationProvider = mockLocationProvider,
            alertDispatcher = mockAlertDispatcher,
            audioPipeline = mockAudioPipeline,
            assessmentEngine = mockAssessmentEngine,
            escalationPolicy = escalationPolicy,
            policeCallManager = failingCallManager,
            contactRepository = mockContactRepository,
            sessionHistoryRepository = mockSessionHistoryRepository,
            preferences = mockPreferences,
            liveTrackingClient = mockLiveTrackingClient
        )

        manager.handleDoublePress()
        advanceTimeBy(3500)

        assertEquals(PoliceState.FAILED, manager.state.value.policeState)
        assertEquals(ContactAlertState.SENT, manager.state.value.contactAlertState)
        assertEquals(1, manager.state.value.contactMessageCounts[1L])
        assertNotNull(manager.state.value.latitude)
    }
}

// Mocks for Unit Testing
class MockBleManager : BleManager {
    val linkStateFlow = MutableStateFlow(KeychainLinkState.CONNECTED)
    override val linkState: StateFlow<KeychainLinkState> = linkStateFlow.asStateFlow()

    private val _events = MutableSharedFlow<HardwareEvent>(extraBufferCapacity = 16)
    override val hardwareEvents: Flow<HardwareEvent> = _events.asSharedFlow()

    override val scannedDevices: StateFlow<List<BleDeviceInfo>> = MutableStateFlow(emptyList())
    var isSimulatedField: Boolean = true
    override val isSimulated: Boolean
        get() = isSimulatedField

    override fun startScan() {}
    override fun stopScan() {}
    override fun connect(address: String) {}
    override fun disconnect() {}
    override fun sendVibration(patternCode: Byte): Boolean = true
}

class MockLocationProvider : LocationProvider {
    private val _loc = MutableStateFlow<SakhiLocation?>(SakhiLocation(28.6139, 77.2090, 10f, System.currentTimeMillis(), false))
    override val currentLocation: StateFlow<SakhiLocation?> = _loc.asStateFlow()
    override fun startLocationUpdates() {}
    override fun stopLocationUpdates() {}
    override suspend fun getBestLocation(): SakhiLocation? = _loc.value
}

class MockAlertDispatcher : AlertDispatcher {
    var dispatchCalled = false
    var simulatedDispatchResult: ContactAlertState = ContactAlertState.SENT
    var simulatedPeriodicResult: ContactAlertState = ContactAlertState.SENT
    var lastLiveTrackingUrl: String? = null
    var periodicUpdateCalled = false
    var periodicUpdateCount = 0
    var lastSpeechExcerpt: String? = null
    var lastAssessmentLabel: String? = null
    var lastUpdateNumber: Int = 0

    override suspend fun dispatchAlertToContacts(
        contacts: List<Contact>,
        location: SakhiLocation?,
        template: String,
        includeAccuracy: Boolean,
        onContactStatusUpdate: (contactId: Long, state: ContactAlertState) -> Unit
    ): ContactAlertState {
        return dispatchAlertToContacts(contacts, location, template, includeAccuracy, null, onContactStatusUpdate)
    }

    override suspend fun dispatchAlertToContacts(
        contacts: List<Contact>,
        location: SakhiLocation?,
        template: String,
        includeAccuracy: Boolean,
        liveTrackingUrl: String?,
        onContactStatusUpdate: (contactId: Long, state: ContactAlertState) -> Unit
    ): ContactAlertState {
        dispatchCalled = true
        lastLiveTrackingUrl = liveTrackingUrl
        contacts.forEach { onContactStatusUpdate(it.id, simulatedDispatchResult) }
        return simulatedDispatchResult
    }

    override suspend fun sendPeriodicLocationUpdate(
        contacts: List<Contact>,
        location: SakhiLocation?,
        liveTrackingUrl: String?
    ): Int {
        return sendPeriodicLocationUpdate(contacts, location, liveTrackingUrl, 1, null, null) { _, _ -> }
    }

    override suspend fun sendPeriodicLocationUpdate(
        contacts: List<Contact>,
        location: SakhiLocation?,
        liveTrackingUrl: String?,
        updateNumber: Int,
        speechExcerpt: String?,
        assessmentLabel: String?,
        onContactStatusUpdate: (contactId: Long, state: ContactAlertState) -> Unit
    ): Int {
        periodicUpdateCalled = true
        periodicUpdateCount++
        lastUpdateNumber = updateNumber
        lastSpeechExcerpt = speechExcerpt
        lastAssessmentLabel = assessmentLabel
        contacts.forEach {
            onContactStatusUpdate(it.id, ContactAlertState.SENDING)
            onContactStatusUpdate(it.id, simulatedPeriodicResult)
        }
        return if (simulatedPeriodicResult == ContactAlertState.SENT || simulatedPeriodicResult == ContactAlertState.DELIVERED) {
            contacts.size
        } else {
            0
        }
    }

    var safeFollowUpCalled = false
    override suspend fun sendSafeFollowUp(
        contacts: List<Contact>,
        onStatusUpdate: (contactId: Long, state: ContactAlertState) -> Unit
    ) {
        safeFollowUpCalled = true
        contacts.forEach { onStatusUpdate(it.id, ContactAlertState.DELIVERED) }
    }
}

class MockLiveTrackingClient : com.example.sakhi.data.tracking.LiveTrackingClient {
    var shouldSucceed = true
    var createSessionCalled = false
    var uploadLocationCalled = false
    var cancelSessionCalled = false
    var lastSessionId: String? = null
    var lastCancelReason: String? = null
    val uploadedLocations = mutableListOf<SakhiLocation>()

    override suspend fun createSession(
        baseUrl: String,
        initialLocation: SakhiLocation?
    ): Result<com.example.sakhi.data.tracking.LiveTrackingSession> {
        createSessionCalled = true
        return if (shouldSucceed) {
            val session = com.example.sakhi.data.tracking.LiveTrackingSession(
                sessionId = "mock-session-1234",
                trackingToken = "mock-reader-token-5678",
                writerKey = "mock-writer-key-9999",
                trackingUrl = "$baseUrl/track/mock-session-1234?token=mock-reader-token-5678",
                expiresAt = System.currentTimeMillis() + 7200000L
            )
            lastSessionId = session.sessionId
            Result.success(session)
        } else {
            Result.failure(Exception("Mock network timeout"))
        }
    }

    override suspend fun uploadLocation(
        baseUrl: String,
        sessionId: String,
        writerKey: String,
        location: SakhiLocation
    ): Result<Boolean> {
        uploadLocationCalled = true
        uploadedLocations.add(location)
        return if (shouldSucceed) Result.success(true) else Result.failure(Exception("Upload error"))
    }

    override suspend fun cancelSession(
        baseUrl: String,
        sessionId: String,
        writerKey: String,
        reason: String
    ): Result<Boolean> {
        cancelSessionCalled = true
        lastCancelReason = reason
        return Result.success(true)
    }

    override suspend fun testConnection(baseUrl: String): Result<Boolean> {
        return if (shouldSucceed) Result.success(true) else Result.failure(Exception("Failed to ping gateway"))
    }
}

class MockAudioPipeline : AudioPipeline {
    override val aiState: StateFlow<com.example.sakhi.core.model.AiState> = MutableStateFlow(com.example.sakhi.core.model.AiState.OFF)
    override val audioLevelRms: StateFlow<Float> = MutableStateFlow(0f)
    override var isCapturing: Boolean = false

    override fun startCapture(scope: CoroutineScope) { isCapturing = true }
    override fun stopCapture() { isCapturing = false }
    override fun getTransientPcmData(): ByteArray? = null
}

class MockAssessmentEngine : AssessmentEngine {
    override val providerName: String = "Mock AI"
    override var isConfigured: Boolean = true
    override suspend fun assessText(transcript: String): DistressAssessment {
        return DistressAssessment(DistressCategory.NORMAL, 0.9f, transcript, "Normal")
    }
    override suspend fun testConnection(): Result<String> = Result.success("OK")
}

class MockPoliceCallManager : PoliceCallManager(null) {
    var callCount = 0
    var callInitiated = false
    override suspend fun initiateEmergencyCall(emergencyNumber: String, forceInitiate: Boolean): PoliceState {
        val cleaned = emergencyNumber.trim().replace(Regex("[^0-9+*#]"), "")
        if (cleaned.filter { it.isDigit() }.length < 3) {
            return PoliceState.FAILED
        }
        val current = policeState.value
        if (!forceInitiate && (current == PoliceState.CALL_INITIATED || current == PoliceState.CONNECTED)) {
            return current
        }
        lastDialedNumber = cleaned
        callCount++
        callInitiated = true
        return PoliceState.CALL_INITIATED
    }
}

class MockContactRepository : ContactRepository(null) {
    var testContacts = listOf<Contact>()
    override suspend fun getContacts(): List<Contact> = testContacts
}

class MockSessionHistoryRepository : SessionHistoryRepository(null) {
    override suspend fun saveSession(session: com.example.sakhi.core.model.EmergencySession) {}
}

class MockPreferences : SakhiPreferences(null) {
    var countdownSeconds = 5
    var liveTrackingEnabled = true
    var liveTrackingUrl = "http://10.0.2.2:3000"
    var periodicSmsEnabled = true
    var periodicSmsInterval = 60
    var autoCallEscalationEnabled = false
    var escalationPhoneNumber = ""
    var escalationPolicy = "CALL_ON_RISK_ESCALATION"
    var emergencySpeechMonitoring = true
    var includeSpeechInSms = false
    var externalAudioLanguage = "en"

    override val countdownSecondsFlow: Flow<Int> get() = flowOf(countdownSeconds)
    override val messageTemplateFlow: Flow<String> get() = flowOf("TEST TEMPLATE")
    override val includeLocationAccuracyFlow: Flow<Boolean> get() = flowOf(true)
    override val aiEscalationEnabledFlow: Flow<Boolean> get() = flowOf(true)
    override val escalationSensitivityFlow: Flow<String> get() = flowOf("BALANCED")
    override val policeNumberFlow: Flow<String> get() = flowOf("112")
    override val liveTrackingEnabledFlow: Flow<Boolean> get() = flowOf(liveTrackingEnabled)
    override val liveTrackingUrlFlow: Flow<String> get() = flowOf(liveTrackingUrl)
    override val periodicSmsEnabledFlow: Flow<Boolean> get() = flowOf(periodicSmsEnabled)
    override val periodicSmsIntervalFlow: Flow<Int> get() = flowOf(periodicSmsInterval)
    override val autoCallEscalationEnabledFlow: Flow<Boolean> get() = flowOf(autoCallEscalationEnabled)
    override val escalationPhoneNumberFlow: Flow<String> get() = flowOf(escalationPhoneNumber)
    override val escalationPolicyFlow: Flow<String> get() = flowOf(escalationPolicy)
    override val emergencySpeechMonitoringFlow: Flow<Boolean> get() = flowOf(emergencySpeechMonitoring)
    override val includeSpeechInSmsFlow: Flow<Boolean> get() = flowOf(includeSpeechInSms)
    override val externalAudioLanguageFlow: Flow<String> get() = flowOf(externalAudioLanguage)

    override suspend fun setPeriodicSmsInterval(interval: Int) {
        periodicSmsInterval = interval
    }

    override suspend fun setEscalationPhoneNumber(number: String) {
        escalationPhoneNumber = normalizePhoneNumber(number)
    }

    override suspend fun setAutoCallEscalationEnabled(enabled: Boolean) {
        autoCallEscalationEnabled = enabled
    }

    override suspend fun setEscalationPolicy(policy: String) {
        escalationPolicy = policy
    }

    override suspend fun setIncludeSpeechInSms(include: Boolean) {
        includeSpeechInSms = include
    }

    override suspend fun setExternalAudioLanguage(language: String) {
        externalAudioLanguage = language
    }
}

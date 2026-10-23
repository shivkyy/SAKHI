package com.example.sakhi

import com.example.sakhi.core.database.ContactRepository
import com.example.sakhi.core.database.SessionHistoryRepository
import com.example.sakhi.core.model.AiState
import com.example.sakhi.core.model.Contact
import com.example.sakhi.core.model.ContactAlertState
import com.example.sakhi.core.model.DistressAssessment
import com.example.sakhi.core.model.DistressCategory
import com.example.sakhi.core.model.EmergencySession
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
import com.example.sakhi.data.location.LocationProvider
import com.example.sakhi.data.location.SakhiLocation
import com.example.sakhi.data.tracking.LiveTrackingClient
import com.example.sakhi.data.tracking.LiveTrackingSession
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
class EmergencySessionLifecycleHardeningTest {

    private lateinit var mockBle: MockBleManagerForLifecycle
    private lateinit var mockLocation: MockLocationProviderForLifecycle
    private lateinit var mockAlerts: MockAlertDispatcherForLifecycle
    private lateinit var mockAudio: MockAudioPipelineForLifecycle
    private lateinit var mockEngine: MockAssessmentEngineForLifecycle
    private lateinit var mockPolice: MockPoliceCallManagerForLifecycle
    private lateinit var mockContacts: MockContactRepoForLifecycle
    private lateinit var mockHistory: MockHistoryRepoForLifecycle
    private lateinit var mockPrefs: MockPrefsForLifecycle
    private lateinit var mockTracking: MockLiveTrackingForLifecycle

    @Before
    fun setup() {
        mockBle = MockBleManagerForLifecycle()
        mockLocation = MockLocationProviderForLifecycle()
        mockAlerts = MockAlertDispatcherForLifecycle()
        mockAudio = MockAudioPipelineForLifecycle()
        mockEngine = MockAssessmentEngineForLifecycle()
        mockPolice = MockPoliceCallManagerForLifecycle()
        mockContacts = MockContactRepoForLifecycle()
        mockHistory = MockHistoryRepoForLifecycle()
        mockPrefs = MockPrefsForLifecycle()
        mockTracking = MockLiveTrackingForLifecycle()
    }

    private fun createManager(scope: CoroutineScope): EmergencyStateManager {
        return EmergencyStateManager(
            scope = scope,
            bleManager = mockBle,
            locationProvider = mockLocation,
            alertDispatcher = mockAlerts,
            audioPipeline = mockAudio,
            assessmentEngine = mockEngine,
            escalationPolicy = EscalationPolicy(),
            policeCallManager = mockPolice,
            contactRepository = mockContacts,
            sessionHistoryRepository = mockHistory,
            preferences = mockPrefs,
            liveTrackingClient = mockTracking
        )
    }

    @Test
    fun testTapHoldDuringCountdown_cancelsBeforeAlertAndRevokes() = runTest {
        val manager = createManager(backgroundScope)
        manager.handleDoublePress()

        advanceTimeBy(100)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertEquals(ContactAlertState.COUNTDOWN, manager.state.value.contactAlertState)

        // Hold-to-cancel during countdown
        manager.handleTapHold(originatedInCountdown = true)
        advanceTimeBy(100)

        assertEquals(SessionState.CANCELLED, manager.state.value.sessionState)
        assertEquals("CANCELLED_BEFORE_ALERT", manager.state.value.activeSessionOutcome)
        assertEquals(ContactAlertState.CANCELLED, manager.state.value.contactAlertState)
        assertTrue(mockBle.sentVibrations.contains(HardwareProtocolConfig.CMD_VIBRATE_CANCEL))
        assertTrue(mockLocation.stopLocationUpdatesCalled)
        assertNotNull(mockHistory.savedSession)
        assertEquals("CANCELLED_BEFORE_ALERT", mockHistory.savedSession?.outcome)
    }

    @Test
    fun testTapHoldAfterCountdown_endsSessionWithAlertSentOutcome() = runTest {
        val manager = createManager(backgroundScope)
        mockContacts.contactsList = listOf(Contact(id = 1L, name = "Guardian", phoneNumber = "9876543210", smsEnabled = true))
        manager.handleDoublePress()

        // Advance past countdown (5 seconds + dispatch)
        advanceTimeBy(6000)

        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertEquals(ContactAlertState.SENT, manager.state.value.contactAlertState)

        // 3-second hold to end session
        manager.handleTapHold(originatedInCountdown = false)
        advanceTimeBy(100)

        assertEquals(SessionState.CANCELLED, manager.state.value.sessionState)
        assertEquals("ALERT_SENT_THEN_CANCELLED", manager.state.value.activeSessionOutcome)
        assertEquals(ContactAlertState.SENT, manager.state.value.contactAlertState)
        assertTrue(mockBle.sentVibrations.contains(HardwareProtocolConfig.CMD_VIBRATE_CANCEL))
        assertNotNull(mockHistory.savedSession)
        assertEquals("ALERT_SENT_THEN_CANCELLED", mockHistory.savedSession?.outcome)
    }

    @Test
    fun testTapHoldWhenLiveTrackingActive_cancelsRemoteSession() = runTest {
        val manager = createManager(backgroundScope)
        mockContacts.contactsList = listOf(Contact(id = 1L, name = "Guardian", phoneNumber = "9876543210", smsEnabled = true))
        manager.handleDoublePress()

        advanceTimeBy(6000)

        assertTrue(manager.state.value.isLiveTrackingActive)
        assertEquals("mock-session-1234", manager.state.value.liveTrackingSessionId)

        manager.handleTapHold(originatedInCountdown = false)
        advanceTimeBy(200)

        assertTrue(mockTracking.cancelSessionCalled)
        assertEquals("mock-session-1234", mockTracking.lastCancelledSessionId)
        assertEquals("mock-writer-key-9999", mockTracking.lastCancelledWriterKey)
        assertEquals("ALERT_SENT_THEN_CANCELLED", mockTracking.lastCancelledReason)
        assertFalse(manager.state.value.isLiveTrackingActive)
    }

    @Test
    fun testTapHoldWhenIdle_doesNothingSafely() = runTest {
        val manager = createManager(backgroundScope)
        assertEquals(SessionState.IDLE, manager.state.value.sessionState)

        manager.handleTapHold()

        assertEquals(SessionState.IDLE, manager.state.value.sessionState)
        assertTrue(mockBle.sentVibrations.isEmpty())
        assertNull(mockHistory.savedSession)
    }

    @Test
    fun testDoublePressWhenAlreadyActive_ignoresDuplicateTrigger() = runTest {
        val manager = createManager(backgroundScope)
        manager.handleDoublePress()
        advanceTimeBy(100)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        val initialSessionId = manager.state.value.activeSessionId
        assertNotNull(initialSessionId)

        // Second double-press while active
        manager.handleDoublePress()
        advanceTimeBy(100)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertEquals(initialSessionId, manager.state.value.activeSessionId)
    }
}

// ----------------------------------------------------
// Mock Implementations for Lifecycle Hardening Tests
// ----------------------------------------------------

class MockBleManagerForLifecycle : BleManager {
    val sentVibrations = mutableListOf<Byte>()
    private val _events = MutableSharedFlow<HardwareEvent>(extraBufferCapacity = 16)
    private val _link = MutableStateFlow(KeychainLinkState.CONNECTED)
    private val _scanned = MutableStateFlow<List<BleDeviceInfo>>(emptyList())

    override val hardwareEvents: Flow<HardwareEvent> = _events.asSharedFlow()
    override val linkState: StateFlow<KeychainLinkState> = _link.asStateFlow()
    override val scannedDevices: StateFlow<List<BleDeviceInfo>> = _scanned.asStateFlow()
    override val isSimulated: Boolean = true

    override fun sendVibration(patternCode: Byte): Boolean {
        sentVibrations.add(patternCode)
        return true
    }

    override fun connect(address: String) {}
    override fun disconnect() {}
    override fun startScan() {}
    override fun stopScan() {}
}

class MockLocationProviderForLifecycle : LocationProvider {
    var stopLocationUpdatesCalled = false
    private val _loc = MutableStateFlow<SakhiLocation?>(SakhiLocation(28.6139, 77.2090, 5.0f, System.currentTimeMillis(), false))
    override val currentLocation: StateFlow<SakhiLocation?> = _loc.asStateFlow()

    override fun startLocationUpdates() {}
    override fun stopLocationUpdates() {
        stopLocationUpdatesCalled = true
    }
    override suspend fun getBestLocation(): SakhiLocation? = _loc.value
}

class MockAlertDispatcherForLifecycle : AlertDispatcher {
    override suspend fun dispatchAlertToContacts(
        contacts: List<Contact>,
        location: SakhiLocation?,
        template: String,
        includeAccuracy: Boolean,
        onContactStatusUpdate: (contactId: Long, state: ContactAlertState) -> Unit
    ): ContactAlertState = ContactAlertState.SENT

    override suspend fun dispatchAlertToContacts(
        contacts: List<Contact>,
        location: SakhiLocation?,
        template: String,
        includeAccuracy: Boolean,
        liveTrackingUrl: String?,
        onContactStatusUpdate: (contactId: Long, state: ContactAlertState) -> Unit
    ): ContactAlertState = ContactAlertState.SENT

    override suspend fun sendSafeFollowUp(
        contacts: List<Contact>,
        onStatusUpdate: (contactId: Long, state: ContactAlertState) -> Unit
    ) {}
}

class MockAudioPipelineForLifecycle : AudioPipeline {
    override val aiState: StateFlow<AiState> = MutableStateFlow(AiState.OFF)
    override val audioLevelRms: StateFlow<Float> = MutableStateFlow(0f)
    override var isCapturing: Boolean = false

    override fun startCapture(scope: CoroutineScope) { isCapturing = true }
    override fun stopCapture() { isCapturing = false }
    override fun getTransientPcmData(): ByteArray? = null
}

class MockAssessmentEngineForLifecycle : AssessmentEngine {
    override val providerName: String = "Mock"
    override var isConfigured: Boolean = true

    override suspend fun assessText(transcript: String): DistressAssessment {
        return DistressAssessment(
            category = DistressCategory.NORMAL,
            confidence = 0f,
            transcript = transcript,
            contextSummary = "None"
        )
    }

    override suspend fun testConnection(): Result<String> = Result.success("OK")
}

class MockPoliceCallManagerForLifecycle : PoliceCallManager(null) {
    override suspend fun initiateEmergencyCall(emergencyNumber: String, forceInitiate: Boolean): PoliceState {
        return PoliceState.CALL_INITIATED
    }
}

class MockContactRepoForLifecycle : ContactRepository(null) {
    var contactsList = listOf<Contact>()
    override suspend fun getContacts(): List<Contact> = contactsList
}

class MockHistoryRepoForLifecycle : SessionHistoryRepository(null) {
    var savedSession: EmergencySession? = null
    override suspend fun saveSession(session: EmergencySession) {
        savedSession = session
    }
}

class MockPrefsForLifecycle : SakhiPreferences(null) {
    override val countdownSecondsFlow: Flow<Int> get() = flowOf(5)
    override val messageTemplateFlow: Flow<String> get() = flowOf("TEST")
    override val includeLocationAccuracyFlow: Flow<Boolean> get() = flowOf(true)
    override val aiEscalationEnabledFlow: Flow<Boolean> get() = flowOf(true)
    override val escalationSensitivityFlow: Flow<String> get() = flowOf("BALANCED")
    override val policeNumberFlow: Flow<String> get() = flowOf("112")
    override val liveTrackingEnabledFlow: Flow<Boolean> get() = flowOf(true)
    override val liveTrackingUrlFlow: Flow<String> get() = flowOf("http://10.0.2.2:3000")
    override val periodicSmsEnabledFlow: Flow<Boolean> get() = flowOf(false)
    override val autoCallEscalationEnabledFlow: Flow<Boolean> get() = flowOf(false)
    override val escalationPhoneNumberFlow: Flow<String> get() = flowOf("")
    override val escalationPolicyFlow: Flow<String> get() = flowOf("CALL_ON_RISK_ESCALATION")
    override val emergencySpeechMonitoringFlow: Flow<Boolean> get() = flowOf(false)
    override val includeSpeechInSmsFlow: Flow<Boolean> get() = flowOf(false)
    override val externalAudioLanguageFlow: Flow<String> get() = flowOf("en")
}

class MockLiveTrackingForLifecycle : LiveTrackingClient {
    var cancelSessionCalled = false
    var lastCancelledSessionId: String? = null
    var lastCancelledWriterKey: String? = null
    var lastCancelledReason: String? = null

    override suspend fun createSession(baseUrl: String, initialLocation: SakhiLocation?): Result<LiveTrackingSession> {
        return Result.success(
            LiveTrackingSession(
                sessionId = "mock-session-1234",
                trackingToken = "mock-reader-token-5678",
                writerKey = "mock-writer-key-9999",
                trackingUrl = "$baseUrl/track/mock-session-1234?token=mock-reader-token-5678",
                expiresAt = System.currentTimeMillis() + 7200000L
            )
        )
    }

    override suspend fun uploadLocation(baseUrl: String, sessionId: String, writerKey: String, location: SakhiLocation): Result<Boolean> {
        return Result.success(true)
    }

    override suspend fun cancelSession(baseUrl: String, sessionId: String, writerKey: String, reason: String): Result<Boolean> {
        cancelSessionCalled = true
        lastCancelledSessionId = sessionId
        lastCancelledWriterKey = writerKey
        lastCancelledReason = reason
        return Result.success(true)
    }

    override suspend fun testConnection(baseUrl: String): Result<Boolean> {
        return Result.success(true)
    }
}

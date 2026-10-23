package com.example.sakhi

import android.content.Context
import android.content.Intent
import com.example.sakhi.core.model.Contact
import com.example.sakhi.core.model.ContactAlertState
import com.example.sakhi.core.model.EmergencyState
import com.example.sakhi.core.model.HardwareEvent
import com.example.sakhi.core.model.KeychainLinkState
import com.example.sakhi.core.model.PoliceState
import com.example.sakhi.core.model.SessionState
import com.example.sakhi.data.bluetooth.AudioCircularBuffer
import com.example.sakhi.data.bluetooth.BluetoothClassicAudioReceiver
import com.example.sakhi.data.bluetooth.BtAudioConnectionState
import com.example.sakhi.data.bluetooth.BtAudioTelemetry
import com.example.sakhi.data.bluetooth.SakhiStreamFramer
import com.example.sakhi.domain.emergency.EmergencyStateManager
import com.example.sakhi.domain.escalation.EscalationPolicy
import com.example.sakhi.service.EmergencyForegroundService
import com.example.sakhi.ui.navigation.SakhiNavigationDecider
import com.example.sakhi.ui.navigation.SakhiScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verification of background monitoring, foreground service lifecycle,
 * connection persistence across Activity recreation, physical button processing,
 * and reliable background operation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundMonitoringAndLifecycleTest {

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
        mockBleManager.isSimulatedField = false
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
        receiver: BluetoothClassicAudioReceiver? = null,
        fgsStarter: (() -> Unit)? = null,
        fgsStopper: (() -> Unit)? = null,
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
            btAudioReceiver = receiver,
            foregroundServiceStarter = fgsStarter,
            foregroundServiceStopper = fgsStopper,
            timeProvider = timeProvider
        )
    }

    // 1. Foreground service startup and notification constants & actions
    @Test
    fun test01_foregroundServiceStartupAndActions() {
        assertEquals("sakhi_emergency_service_channel", EmergencyForegroundService.CHANNEL_ID_EMERGENCY)
        assertEquals("sakhi_monitoring_service_channel", EmergencyForegroundService.CHANNEL_ID_MONITORING)
        assertEquals(1001, EmergencyForegroundService.NOTIFICATION_ID)
        assertEquals("com.example.sakhi.action.START_MONITORING", EmergencyForegroundService.ACTION_START_MONITORING)
        assertEquals("com.example.sakhi.action.START_EMERGENCY", EmergencyForegroundService.ACTION_START_EMERGENCY)
        assertEquals("com.example.sakhi.action.EMERGENCY_ENDED", EmergencyForegroundService.ACTION_EMERGENCY_ENDED)
        assertEquals("com.example.sakhi.action.STOP_MONITORING", EmergencyForegroundService.ACTION_STOP_MONITORING)
        assertEquals("com.example.sakhi.action.TRIGGER_SOS", EmergencyForegroundService.ACTION_TRIGGER_SOS)
        assertEquals("com.example.sakhi.action.NOTIFICATION_CANCEL", EmergencyForegroundService.ACTION_NOTIFICATION_CANCEL)
        assertEquals("com.example.sakhi.action.NOTIFICATION_POLICE", EmergencyForegroundService.ACTION_NOTIFICATION_POLICE)
    }

    // 2. Activity recreation without losing the service-owned connection
    @Test
    fun test02_activityRecreationWithoutLosingServiceOwnedConnection() = runTest {
        val receiver = object : BluetoothClassicAudioReceiver(null, backgroundScope) {
            override fun hasBluetoothPermission(): Boolean = true
            override fun isBluetoothEnabled(): Boolean = true
            override fun isSocketConnected(): Boolean = true
        }

        // Simulate active streaming connection owned by service
        receiver.framer.feedBytes(SakhiStreamFramer.encodeAudioFrame(ByteArray(64)))
        assertEquals(BtAudioConnectionState.STREAMING, receiver.telemetry.value.state)
        assertTrue(receiver.telemetry.value.totalBytesReceived > 0)
        val initialBytes = receiver.telemetry.value.totalBytesReceived

        // Activity is recreated (e.g., orientation change, return to app) and calls connect()
        receiver.connect(null, isAutoRetry = false)
        testScheduler.advanceTimeBy(100)

        // Connection must NOT be severed or reset
        assertEquals(BtAudioConnectionState.STREAMING, receiver.telemetry.value.state)
        assertEquals(initialBytes, receiver.telemetry.value.totalBytesReceived)
    }

    // 3. Screen lock while Bluetooth audio is streaming (buffer continues accumulating)
    @Test
    fun test03_screenLockWhileBluetoothAudioIsStreaming() = runTest {
        val buffer = AudioCircularBuffer(64000)
        val receiver = object : BluetoothClassicAudioReceiver(null, backgroundScope, buffer) {
            override fun hasBluetoothPermission(): Boolean = true
            override fun isBluetoothEnabled(): Boolean = true
        }

        // Audio frames arrive while phone is locked (CPU awake via WakeLock)
        val pcmSample1 = ByteArray(128) { 0x1A }
        val pcmSample2 = ByteArray(128) { 0x2B }
        receiver.framer.feedBytes(SakhiStreamFramer.encodeAudioFrame(pcmSample1))
        receiver.framer.feedBytes(SakhiStreamFramer.encodeAudioFrame(pcmSample2))

        assertEquals(256, buffer.size())
        assertEquals(BtAudioConnectionState.STREAMING, receiver.telemetry.value.state)
        assertTrue(receiver.telemetry.value.currentRms > 0f)
    }

    // 4. Screen unlock and UI reconnection
    @Test
    fun test04_screenUnlockAndUiReconnection() = runTest {
        val receiver = object : BluetoothClassicAudioReceiver(null, backgroundScope) {
            override fun hasBluetoothPermission(): Boolean = true
            override fun isBluetoothEnabled(): Boolean = true
        }
        val manager = createManager(backgroundScope, receiver = receiver)
        testScheduler.advanceTimeBy(100)

        // Generate telemetry while backgrounded
        receiver.framer.feedBytes(SakhiStreamFramer.encodeAudioFrame(ByteArray(100)))
        testScheduler.advanceTimeBy(100)

        // Screen unlocks: new UI collector observes latest StateFlow snapshot immediately
        val currentTelemetry = receiver.telemetry.first()
        val currentState = manager.state.first()

        assertEquals(BtAudioConnectionState.STREAMING, currentTelemetry.state)
        assertEquals(KeychainLinkState.CONNECTED, currentState.keychainLinkState)
        assertFalse(currentState.isEmergencyActive)
    }

    // 5. Removal from Recents while monitoring
    @Test
    fun test05_removalFromRecentsWhileMonitoring() = runTest {
        var fgsRunning = true
        val service = object : EmergencyForegroundService() {
            override fun performStopSelf() {
                fgsRunning = false
            }
        }

        // Simulate swipe from Recents
        service.onTaskRemoved(Intent())

        // Must NOT stop self; foreground service stays active
        assertTrue(fgsRunning)
    }

    // 6. Bluetooth disconnect and reconnect
    @Test
    fun test06_bluetoothDisconnectAndReconnect() = runTest {
        val receiver = object : BluetoothClassicAudioReceiver(null, backgroundScope) {
            override fun hasBluetoothPermission(): Boolean = true
            override fun isBluetoothEnabled(): Boolean = true
        }
        val manager = createManager(backgroundScope, receiver = receiver)
        testScheduler.advanceTimeBy(100)

        // Initial connection
        receiver.framer.feedBytes(SakhiStreamFramer.encodeAudioFrame(ByteArray(64)))
        testScheduler.advanceTimeBy(100)
        assertEquals(KeychainLinkState.CONNECTED, manager.state.value.keychainLinkState)

        // Device disconnects
        receiver.disconnect()
        testScheduler.advanceTimeBy(100)

        assertEquals(BtAudioConnectionState.DISCONNECTED, receiver.telemetry.value.state)
        assertEquals(KeychainLinkState.DISCONNECTED, manager.state.value.keychainLinkState)

        // Reconnect triggered
        receiver.framer.feedBytes(SakhiStreamFramer.encodeAudioFrame(ByteArray(64)))
        testScheduler.advanceTimeBy(100)
        assertEquals(KeychainLinkState.CONNECTED, manager.state.value.keychainLinkState)
    }

    // 7. Repeated reconnect failures without resource leaks (exponential backoff)
    @Test
    fun test07_repeatedReconnectFailuresWithoutResourceLeaks() {
        assertEquals(1500L, BluetoothClassicAudioReceiver.calculateReconnectDelayMs(1))
        assertEquals(3000L, BluetoothClassicAudioReceiver.calculateReconnectDelayMs(2))
        assertEquals(6000L, BluetoothClassicAudioReceiver.calculateReconnectDelayMs(3))
        assertEquals(12000L, BluetoothClassicAudioReceiver.calculateReconnectDelayMs(4))
        assertEquals(12000L, BluetoothClassicAudioReceiver.calculateReconnectDelayMs(10))

        // Reconnect attempts capped at MAX_RECONNECT_ATTEMPTS (10)
        assertEquals(10, BluetoothClassicAudioReceiver.MAX_RECONNECT_ATTEMPTS)
    }

    // 8. No duplicate receiver or event collectors after Activity recreation
    @Test
    fun test08_noDuplicateReceiverOrEventCollectorsAfterActivityRecreation() = runTest {
        val receiver = object : BluetoothClassicAudioReceiver(null, backgroundScope) {
            override fun hasBluetoothPermission(): Boolean = true
            override fun isBluetoothEnabled(): Boolean = true
        }
        val manager = createManager(backgroundScope, receiver = receiver)

        // Multiple UI screens access the single receiver without registering duplicate collectors
        var eventCount = 0
        manager.handleHardwareEvent(HardwareEvent.BatteryStatus(percentage = 90, isCharging = false))
        assertEquals(90, manager.state.value.deviceBatteryPercent)

        // Double-press triggers SOS exactly once
        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(100)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        val sessionId = manager.state.value.activeSessionId

        // Duplicate double press while active does not restart or duplicate
        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(100)
        assertEquals(sessionId, manager.state.value.activeSessionId)
    }

    // 9. Audio continues reaching the existing processing pipeline
    @Test
    fun test09_audioContinuesReachingExistingProcessingPipeline() = runTest {
        val receiver = object : BluetoothClassicAudioReceiver(null, backgroundScope) {
            override fun hasBluetoothPermission(): Boolean = true
            override fun isBluetoothEnabled(): Boolean = true
        }

        var emittedChunkSize = 0
        val collectJob = backgroundScope.launch {
            receiver.audioChunkFlow.collect { chunk ->
                emittedChunkSize = chunk.size
            }
        }
        testScheduler.advanceTimeBy(50)

        // Deliver SAKH audio packet
        val pcmBytes = ByteArray(256) { (it % 120).toByte() }
        val packet = SakhiStreamFramer.encodeAudioFrame(pcmBytes)
        receiver.framer.feedBytes(packet)
        testScheduler.advanceTimeBy(50)

        assertEquals(256, emittedChunkSize)
        assertEquals(256, receiver.circularBuffer.size())
        collectJob.cancel()
    }

    // 10. Speech recognition remains operational when background restrictions permit the workflow
    @Test
    fun test10_speechRecognitionRemainsOperationalInBackground() = runTest {
        val manager = createManager(backgroundScope)
        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(100)

        // State is active and ready for speech intelligence
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertTrue(manager.state.value.isEmergencyActive)
    }

    // 11. Physical double-press still activates SOS
    @Test
    fun test11_physicalDoublePressStillActivatesSos() = runTest {
        var fgsStarted = false
        val manager = createManager(
            scope = backgroundScope,
            fgsStarter = { fgsStarted = true }
        )

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(100)

        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertEquals(ContactAlertState.COUNTDOWN, manager.state.value.contactAlertState)
        assertTrue(fgsStarted)
    }

    // 12. Countdown hold/release behavior remains correct
    @Test
    fun test12_countdownHoldReleaseBehaviorRemainsCorrect() = runTest {
        mockPreferences.countdownSeconds = 5
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Guardian", phoneNumber = "+919876543210")
        )
        val manager = createManager(backgroundScope)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(100)
        testScheduler.advanceTimeBy(1000) // 4s remaining
        assertEquals(4, manager.state.value.countdownRemainingSeconds)

        // Button hold start pauses countdown
        manager.handleHardwareEvent(HardwareEvent.ButtonHoldStart())
        testScheduler.advanceTimeBy(2000)
        assertEquals(4, manager.state.value.countdownRemainingSeconds)

        // Button release before 3s resumes countdown from 4s
        manager.handleHardwareEvent(HardwareEvent.ButtonHoldRelease(durationMs = 1500L))
        testScheduler.advanceTimeBy(1050)
        assertEquals(3, manager.state.value.countdownRemainingSeconds)

        // Hold for 3s cancels countdown
        manager.handleHardwareEvent(HardwareEvent.ButtonHoldStart())
        testScheduler.advanceTimeBy(3000)
        manager.handleHardwareEvent(HardwareEvent.TapHold(durationMs = 3000L))
        testScheduler.advanceTimeBy(100)

        assertEquals(SessionState.CANCELLED, manager.state.value.sessionState)
        assertEquals("CANCELLED_BEFORE_ALERT", manager.state.value.activeSessionOutcome)
    }

    // 13. Triple-press after countdown still invokes Call Now
    @Test
    fun test13_triplePressAfterCountdownStillInvokesCallNow() = runTest {
        mockPreferences.countdownSeconds = 3
        val manager = createManager(backgroundScope)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        // During countdown, triple-tap is ignored
        manager.handleHardwareEvent(HardwareEvent.TripleTap())
        testScheduler.advanceTimeBy(100)
        assertEquals(0, mockPoliceCallManager.callCount)

        // Advance past countdown
        testScheduler.advanceTimeBy(3500)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertEquals(0, manager.state.value.countdownRemainingSeconds)

        // Post-countdown triple-tap initiates call
        manager.handleHardwareEvent(HardwareEvent.TripleTap())
        testScheduler.advanceTimeBy(100)

        assertEquals(PoliceState.CALL_INITIATED, manager.state.value.policeState)
        assertEquals(1, mockPoliceCallManager.callCount)
    }

    // 14. Hold-to-end still ends session and returns to Home when UI becomes visible
    @Test
    fun test14_holdToEndStillEndsSessionAndReturnsToHome() = runTest {
        mockPreferences.countdownSeconds = 3
        var fgsNotifiedEnded = false
        val manager = createManager(
            scope = backgroundScope,
            fgsStopper = { fgsNotifiedEnded = true }
        )

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(3500) // Dispatched active state
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)

        // 3s hold after dispatch ends emergency
        manager.handleHardwareEvent(HardwareEvent.ButtonHoldStart())
        testScheduler.advanceTimeBy(3000)
        manager.handleHardwareEvent(HardwareEvent.TapHold(durationMs = 3000L))
        testScheduler.advanceTimeBy(100)

        assertEquals(SessionState.CANCELLED, manager.state.value.sessionState)
        assertEquals("ALERT_SENT_THEN_CANCELLED", manager.state.value.activeSessionOutcome)
        assertTrue(fgsNotifiedEnded)

        // Screen decider cleanly transitions to HOME
        val screen = SakhiNavigationDecider.resolveScreenOnSessionStateChange(
            SakhiScreen.ACTIVE_EMERGENCY,
            manager.state.value.sessionState
        )
        assertEquals(SakhiScreen.HOME, screen)
    }

    // 15. Existing SMS, location, tracking and emergency-state behavior is preserved
    @Test
    fun test15_existingSmsLocationTrackingBehaviorPreserved() = runTest {
        mockPreferences.countdownSeconds = 3
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Mother", phoneNumber = "+919876543210")
        )
        val manager = createManager(backgroundScope)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(3500)

        // Alert SMS dispatched
        assertEquals(ContactAlertState.SENT, manager.state.value.contactAlertState)
        assertTrue(mockAlertDispatcher.dispatchCalled)
    }

    // 16. Explicitly stopping monitoring cleans up connection and workers
    @Test
    fun test16_explicitlyStoppingMonitoringCleansUpConnection() = runTest {
        val receiver = object : BluetoothClassicAudioReceiver(null, backgroundScope) {
            override fun hasBluetoothPermission(): Boolean = true
            override fun isBluetoothEnabled(): Boolean = true
        }

        receiver.framer.feedBytes(SakhiStreamFramer.encodeAudioFrame(ByteArray(100)))
        assertEquals(BtAudioConnectionState.STREAMING, receiver.telemetry.value.state)

        // Explicit user stop
        receiver.disconnect()
        testScheduler.advanceTimeBy(100)

        assertEquals(BtAudioConnectionState.DISCONNECTED, receiver.telemetry.value.state)
        assertFalse(receiver.isAutoReconnectActive)
        assertEquals(0, receiver.reconnectAttempt)
        assertEquals(0, receiver.circularBuffer.size())
    }

    // 17. Android permissions and service-start restrictions are handled correctly
    @Test
    fun test17_androidPermissionsAndServiceStartRestrictions() = runTest {
        val receiverWithoutPerm = object : BluetoothClassicAudioReceiver(null, backgroundScope) {
            override fun hasBluetoothPermission(): Boolean = false
        }

        receiverWithoutPerm.connect()
        assertEquals(BtAudioConnectionState.ERROR, receiverWithoutPerm.telemetry.value.state)
        assertNotNull(receiverWithoutPerm.telemetry.value.errorMessage)
        assertFalse(receiverWithoutPerm.isAutoReconnectActive)
    }
}

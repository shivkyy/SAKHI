package com.example.sakhi

import com.example.sakhi.core.model.Contact
import com.example.sakhi.core.model.ContactAlertState
import com.example.sakhi.core.model.HardwareEvent
import com.example.sakhi.core.model.PoliceState
import com.example.sakhi.core.model.SessionState
import com.example.sakhi.domain.emergency.EmergencyStateManager
import com.example.sakhi.domain.escalation.EscalationPolicy
import com.example.sakhi.ui.navigation.SakhiNavigationDecider
import com.example.sakhi.ui.navigation.SakhiScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Direct verification of physical button triple-tap Call Now lifecycle
 * and return-to-home navigation across all termination paths.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripleTapAndReturnHomeTest {

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

    // 1. Triple-tap before SOS -> ignored, no call
    @Test
    fun test01_tripleTapBeforeSosIgnoredNoCall() = runTest {
        val manager = createManager(backgroundScope)
        assertEquals(SessionState.IDLE, manager.state.value.sessionState)
        assertEquals(PoliceState.NOT_REQUESTED, manager.state.value.policeState)

        manager.handleHardwareEvent(HardwareEvent.TripleTap())
        testScheduler.advanceTimeBy(100)

        assertEquals(SessionState.IDLE, manager.state.value.sessionState)
        assertEquals(PoliceState.NOT_REQUESTED, manager.state.value.policeState)
        assertEquals(0, mockPoliceCallManager.callCount)
        assertFalse(mockPoliceCallManager.callInitiated)
    }

    // 2. Triple-tap during countdown -> ignored, countdown continues, no call
    @Test
    fun test02_tripleTapDuringCountdownIgnoredCountdownContinuesNoCall() = runTest {
        mockPreferences.countdownSeconds = 5
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Guardian", phoneNumber = "+919876543210")
        )
        val manager = createManager(backgroundScope)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(100)
        testScheduler.advanceTimeBy(1000) // 1s into 5s countdown: 4s remaining

        assertEquals(4, manager.state.value.countdownRemainingSeconds)
        assertEquals(ContactAlertState.COUNTDOWN, manager.state.value.contactAlertState)

        // Triple-tap during countdown
        manager.handleHardwareEvent(HardwareEvent.TripleTap())
        testScheduler.advanceTimeBy(100)

        // Call must NOT be initiated; countdown continues
        assertEquals(0, mockPoliceCallManager.callCount)
        assertEquals(PoliceState.NOT_REQUESTED, manager.state.value.policeState)
        assertEquals(4, manager.state.value.countdownRemainingSeconds)

        // Advance remainder of countdown
        testScheduler.advanceTimeBy(4000)
        assertEquals(ContactAlertState.SENT, manager.state.value.contactAlertState)
        assertEquals(0, manager.state.value.countdownRemainingSeconds)
    }

    // 3. Triple-tap immediately after countdown completes -> Call Now initiated
    @Test
    fun test03_tripleTapImmediatelyAfterCountdownCompletesInitiatesCall() = runTest {
        mockPreferences.countdownSeconds = 3
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Guardian", phoneNumber = "+919876543210")
        )
        val manager = createManager(backgroundScope)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        // Advance past countdown into ACTIVE state
        testScheduler.advanceTimeBy(3500)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertEquals(0, manager.state.value.countdownRemainingSeconds)

        // Immediate triple-tap post-countdown
        manager.handleHardwareEvent(HardwareEvent.TripleTap())
        testScheduler.advanceTimeBy(100)

        assertEquals(PoliceState.CALL_INITIATED, manager.state.value.policeState)
        assertEquals(1, mockPoliceCallManager.callCount)
        assertTrue(mockPoliceCallManager.callInitiated)
    }

    // 4. Triple-tap 10 seconds after countdown completes -> Call Now initiated
    @Test
    fun test04_tripleTapTenSecondsAfterCountdownCompletesInitiatesCall() = runTest {
        mockPreferences.countdownSeconds = 3
        val manager = createManager(backgroundScope)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(3500) // Countdown completes
        testScheduler.advanceTimeBy(10000) // 10 seconds later

        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)

        manager.handleHardwareEvent(HardwareEvent.TripleTap())
        testScheduler.advanceTimeBy(100)

        assertEquals(PoliceState.CALL_INITIATED, manager.state.value.policeState)
        assertEquals(1, mockPoliceCallManager.callCount)
    }

    // 5. Repeated triple-tap (with debounce) -> Call Now initiated each time
    @Test
    fun test05_repeatedTripleTapWithDebounceInitiatesCallEachTime() = runTest {
        mockPreferences.countdownSeconds = 3
        var currentTime = 10000L
        val manager = createManager(backgroundScope, timeProvider = { currentTime })

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(3500)
        currentTime += 3500

        // First call
        manager.handleHardwareEvent(HardwareEvent.TripleTap())
        testScheduler.advanceTimeBy(100)
        assertEquals(1, mockPoliceCallManager.callCount)

        // Legitimate second triple-tap after 1500ms (> 1000ms debounce)
        currentTime += 1500
        testScheduler.advanceTimeBy(1500)
        manager.handleHardwareEvent(HardwareEvent.TripleTap())
        testScheduler.advanceTimeBy(100)
        assertEquals(2, mockPoliceCallManager.callCount)

        // Legitimate third triple-tap after another 2000ms
        currentTime += 2000
        testScheduler.advanceTimeBy(2000)
        manager.handleHardwareEvent(HardwareEvent.TripleTap())
        testScheduler.advanceTimeBy(100)
        assertEquals(3, mockPoliceCallManager.callCount)
    }

    // 6. Triple-tap rapid duplicates (< 1000 ms) -> debounced to one call
    @Test
    fun test06_tripleTapRapidDuplicatesDebouncedToOneCall() = runTest {
        mockPreferences.countdownSeconds = 3
        var currentTime = 10000L
        val manager = createManager(backgroundScope, timeProvider = { currentTime })

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(3500)
        currentTime += 3500

        // Initial triple-tap
        manager.handleHardwareEvent(HardwareEvent.TripleTap())
        testScheduler.advanceTimeBy(50)
        assertEquals(1, mockPoliceCallManager.callCount)

        // Rapid duplicate 1 (100ms later)
        currentTime += 100
        testScheduler.advanceTimeBy(100)
        manager.handleHardwareEvent(HardwareEvent.TripleTap())
        testScheduler.advanceTimeBy(50)
        assertEquals(1, mockPoliceCallManager.callCount)

        // Rapid duplicate 2 (400ms later; 500ms total from first)
        currentTime += 400
        testScheduler.advanceTimeBy(400)
        manager.handleHardwareEvent(HardwareEvent.TripleTap())
        testScheduler.advanceTimeBy(50)
        assertEquals(1, mockPoliceCallManager.callCount)
    }

    // 7. Triple-tap after session cancelled -> ignored, no call
    @Test
    fun test07_tripleTapAfterSessionCancelledIgnoredNoCall() = runTest {
        mockPreferences.countdownSeconds = 5
        val manager = createManager(backgroundScope)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(1000)

        // Cancel during countdown
        manager.handleTapHold(originatedInCountdown = true)
        testScheduler.advanceTimeBy(100)
        assertEquals(SessionState.CANCELLED, manager.state.value.sessionState)

        // Triple tap after cancellation
        manager.handleHardwareEvent(HardwareEvent.TripleTap())
        testScheduler.advanceTimeBy(100)

        assertEquals(0, mockPoliceCallManager.callCount)
        assertEquals(PoliceState.NOT_REQUESTED, manager.state.value.policeState)
    }

    // 8. Triple-tap after session ended -> ignored, no call
    @Test
    fun test08_tripleTapAfterSessionEndedIgnoredNoCall() = runTest {
        mockPreferences.countdownSeconds = 3
        val manager = createManager(backgroundScope)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(3500) // Active session
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)

        // User safely ends session
        manager.endSession()
        testScheduler.advanceTimeBy(100)
        assertEquals(SessionState.ENDED, manager.state.value.sessionState)

        // Triple tap after ending
        manager.handleHardwareEvent(HardwareEvent.TripleTap())
        testScheduler.advanceTimeBy(100)

        assertEquals(0, mockPoliceCallManager.callCount)
    }

    // 9. Countdown cancellation via UI -> session cancelled, navigate to Home
    @Test
    fun test09_countdownCancellationViaUiReturnsToHome() = runTest {
        mockPreferences.countdownSeconds = 5
        val manager = createManager(backgroundScope)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(1000)

        var screen = SakhiScreen.ACTIVE_EMERGENCY
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertEquals(ContactAlertState.COUNTDOWN, manager.state.value.contactAlertState)

        // User taps Cancel button in UI (calls handleTapHold and onNavigateBack)
        manager.handleTapHold(originatedInCountdown = true)
        testScheduler.advanceTimeBy(50)

        screen = SakhiNavigationDecider.resolveScreenOnSessionStateChange(screen, manager.state.value.sessionState)
        assertEquals(SessionState.CANCELLED, manager.state.value.sessionState)
        assertEquals(SakhiScreen.HOME, screen)
    }

    // 10. Countdown cancellation via 3s hold -> session cancelled, navigate to Home
    @Test
    fun test10_countdownCancellationVia3sHoldReturnsToHome() = runTest {
        mockPreferences.countdownSeconds = 5
        val manager = createManager(backgroundScope)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(1000)

        var screen = SakhiScreen.ACTIVE_EMERGENCY

        // 3-second hardware hold on physical keychain during countdown
        manager.handleHardwareEvent(HardwareEvent.ButtonHoldStart())
        testScheduler.advanceTimeBy(3000)
        manager.handleHardwareEvent(HardwareEvent.TapHold(durationMs = 3000L))
        testScheduler.advanceTimeBy(100)

        screen = SakhiNavigationDecider.resolveScreenOnSessionStateChange(screen, manager.state.value.sessionState)
        assertEquals(SessionState.CANCELLED, manager.state.value.sessionState)
        assertEquals(SakhiScreen.HOME, screen)
    }

    // 11. Active session ended via UI -> session ended, navigate to Home
    @Test
    fun test11_activeSessionEndedViaUiReturnsToHome() = runTest {
        mockPreferences.countdownSeconds = 3
        val manager = createManager(backgroundScope)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(3500)

        var screen = SakhiScreen.ACTIVE_EMERGENCY
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)

        // User taps "YES, I AM SAFE" in UI
        manager.endSession()
        testScheduler.advanceTimeBy(100)

        screen = SakhiNavigationDecider.resolveScreenOnSessionStateChange(screen, manager.state.value.sessionState)
        assertEquals(SessionState.ENDED, manager.state.value.sessionState)
        assertEquals(SakhiScreen.HOME, screen)
    }

    // 12. Active session ended via 3s hold -> session ended, navigate to Home
    @Test
    fun test12_activeSessionEndedVia3sHoldReturnsToHome() = runTest {
        mockPreferences.countdownSeconds = 3
        val manager = createManager(backgroundScope)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(3500)

        var screen = SakhiScreen.ACTIVE_EMERGENCY
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)

        // 3-second hardware hold after alert dispatch
        manager.handleHardwareEvent(HardwareEvent.ButtonHoldStart())
        testScheduler.advanceTimeBy(3000)
        manager.handleHardwareEvent(HardwareEvent.TapHold(durationMs = 3000L))
        testScheduler.advanceTimeBy(100)

        screen = SakhiNavigationDecider.resolveScreenOnSessionStateChange(screen, manager.state.value.sessionState)
        assertEquals(SessionState.CANCELLED, manager.state.value.sessionState)
        assertEquals(SakhiScreen.HOME, screen)
    }

    // 13. Navigation state is clean after return to Home
    @Test
    fun test13_navigationStateIsCleanAfterReturnToHome() = runTest {
        mockPreferences.countdownSeconds = 3
        val manager = createManager(backgroundScope)

        // Transition: HOME -> ACTIVE_EMERGENCY -> HOME
        var screen = SakhiScreen.HOME
        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(100)

        screen = SakhiNavigationDecider.resolveScreenOnSessionStateChange(screen, manager.state.value.sessionState)
        assertEquals(SakhiScreen.ACTIVE_EMERGENCY, screen)

        // End session
        manager.endSession()
        testScheduler.advanceTimeBy(100)

        screen = SakhiNavigationDecider.resolveScreenOnSessionStateChange(screen, manager.state.value.sessionState)
        assertEquals(SakhiScreen.HOME, screen)
        assertFalse(manager.state.value.isEmergencyActive)

        // Further state queries while on Home
        val resolvedAgain = SakhiNavigationDecider.resolveScreenOnSessionStateChange(screen, manager.state.value.sessionState)
        assertEquals(SakhiScreen.HOME, resolvedAgain)
    }

    // 14. Subsequent double-press on Home triggers a new SOS session
    @Test
    fun test14_subsequentDoublePressOnHomeTriggersNewSosSession() = runTest {
        mockPreferences.countdownSeconds = 3
        val manager = createManager(backgroundScope)

        // First session: start and end
        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(3500)
        val firstSessionId = manager.state.value.activeSessionId
        manager.endSession()
        testScheduler.advanceTimeBy(100)
        var screen = SakhiNavigationDecider.resolveScreenOnSessionStateChange(SakhiScreen.ACTIVE_EMERGENCY, manager.state.value.sessionState)
        assertEquals(SakhiScreen.HOME, screen)

        // Subsequent double press from Home triggers new session
        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(100)

        screen = SakhiNavigationDecider.resolveScreenOnSessionStateChange(screen, manager.state.value.sessionState)
        assertEquals(SakhiScreen.ACTIVE_EMERGENCY, screen)
        assertTrue(manager.state.value.isEmergencyActive)
        val secondSessionId = manager.state.value.activeSessionId
        assertTrue(secondSessionId != null && secondSessionId != firstSessionId)
    }

    // 15. Triple-tap in the new SOS session (post-countdown) triggers Call Now
    @Test
    fun test15_tripleTapInNewSosSessionPostCountdownTriggersCallNow() = runTest {
        mockPreferences.countdownSeconds = 3
        val manager = createManager(backgroundScope)

        // Session 1: run and terminate
        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(3500)
        manager.endSession()
        testScheduler.advanceTimeBy(100)
        assertEquals(0, mockPoliceCallManager.callCount)

        // Session 2: activate new session
        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(100)

        // During countdown in session 2, triple-tap ignored
        manager.handleHardwareEvent(HardwareEvent.TripleTap())
        testScheduler.advanceTimeBy(100)
        assertEquals(0, mockPoliceCallManager.callCount)

        // Advance past countdown into session 2 active state
        testScheduler.advanceTimeBy(3500)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertEquals(0, manager.state.value.countdownRemainingSeconds)

        // Triple-tap post-countdown in session 2 triggers Call Now
        manager.handleHardwareEvent(HardwareEvent.TripleTap())
        testScheduler.advanceTimeBy(100)

        assertEquals(PoliceState.CALL_INITIATED, manager.state.value.policeState)
        assertEquals(1, mockPoliceCallManager.callCount)
    }
}

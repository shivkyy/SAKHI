package com.example.sakhi

import com.example.sakhi.core.model.Contact
import com.example.sakhi.core.model.ContactAlertState
import com.example.sakhi.core.model.SessionState
import com.example.sakhi.core.model.TimelineEventType
import com.example.sakhi.data.alerts.AndroidAlertDispatcher
import com.example.sakhi.data.location.AndroidLocationProvider
import com.example.sakhi.data.location.LocationProvider
import com.example.sakhi.data.location.SakhiLocation
import com.example.sakhi.domain.emergency.EmergencyStateManager
import com.example.sakhi.domain.escalation.EscalationPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationSubsystemRepairTest {

    private lateinit var mockBleManager: MockBleManager
    private lateinit var mockLocationProvider: TestMockLocationProvider
    private lateinit var mockAlertDispatcher: MockAlertDispatcher
    private lateinit var mockAudioPipeline: MockAudioPipeline
    private lateinit var mockAssessmentEngine: MockAssessmentEngine
    private lateinit var escalationPolicy: EscalationPolicy
    private lateinit var mockPoliceCallManager: MockPoliceCallManager
    private lateinit var mockContactRepository: MockContactRepository
    private lateinit var mockSessionHistoryRepository: MockSessionHistoryRepository
    private lateinit var mockPreferences: MockPreferences
    private lateinit var mockLiveTrackingClient: MockLiveTrackingClient

    private var virtualTime = 1_000_000L

    class TestMockLocationProvider : LocationProvider {
        val locFlow = MutableStateFlow<SakhiLocation?>(null)
        override val currentLocation: StateFlow<SakhiLocation?> = locFlow.asStateFlow()
        var startUpdatesCalled = false
        var stopUpdatesCalled = false
        var immediateFreshCalled = false

        override fun startLocationUpdates() {
            startUpdatesCalled = true
        }

        override fun stopLocationUpdates() {
            stopUpdatesCalled = true
        }

        override fun requestImmediateFreshLocation() {
            immediateFreshCalled = true
        }

        override suspend fun getBestLocation(): SakhiLocation? = locFlow.value
    }

    @Before
    fun setup() {
        mockBleManager = MockBleManager()
        mockLocationProvider = TestMockLocationProvider()
        mockAlertDispatcher = MockAlertDispatcher()
        mockAudioPipeline = MockAudioPipeline()
        mockAssessmentEngine = MockAssessmentEngine()
        escalationPolicy = EscalationPolicy()
        mockPoliceCallManager = MockPoliceCallManager()
        mockContactRepository = MockContactRepository()
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Friend", phoneNumber = "+919876543210", smsEnabled = true)
        )
        mockSessionHistoryRepository = MockSessionHistoryRepository()
        mockPreferences = MockPreferences()
        mockPreferences.countdownSeconds = 3
        mockLiveTrackingClient = MockLiveTrackingClient()
        virtualTime = 1_000_000L
    }

    private fun createManager(scope: CoroutineScope): EmergencyStateManager {
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
            timeProvider = { virtualTime }
        )
    }

    @Test
    fun testSakhiLocation_dynamicFreshnessCalculation() {
        val now = 1_000_000L

        // Fresh: 10s old
        val freshLoc = SakhiLocation(
            latitude = 28.6139,
            longitude = 77.2090,
            accuracy = 15f,
            timestamp = now - 10_000L,
            isStale = false
        )
        assertTrue(freshLoc.isFresh(now))
        assertFalse(freshLoc.isStale(now))

        // Stale: 61s old
        val staleLoc = SakhiLocation(
            latitude = 28.6139,
            longitude = 77.2090,
            accuracy = 15f,
            timestamp = now - 61_000L,
            isStale = false // Constructed with false, but dynamic check must detect staleness
        )
        assertFalse(staleLoc.isFresh(now))
        assertTrue(staleLoc.isStale(now))
        val refreshed = staleLoc.withFreshness(now)
        assertTrue(refreshed.isStale)
        assertEquals("LOCATION STALE", refreshed.accuracyStatus)

        // Invalid: future timestamp
        val futureLoc = SakhiLocation(
            latitude = 28.6139,
            longitude = 77.2090,
            accuracy = 15f,
            timestamp = now + 20_000L,
            isStale = false
        )
        assertFalse(futureLoc.isFresh(now))
        assertTrue(futureLoc.isStale(now))

        // Invalid: non-positive timestamp
        val zeroLoc = SakhiLocation(
            latitude = 28.6139,
            longitude = 77.2090,
            accuracy = 15f,
            timestamp = 0L,
            isStale = false
        )
        assertFalse(zeroLoc.isFresh(now))
        assertTrue(zeroLoc.isStale(now))
    }

    @Test
    fun testIsBetterLocation_rulesAndFreshnessTransition() {
        val now = 1_000_000L

        // Stale current location replaced by fresh new location
        val staleTime = now - 120_000L // 2 min old
        val freshTime = now - 5_000L   // 5s old
        val isBetter = AndroidLocationProvider.isBetterLocation(
            newTime = freshTime,
            newAccuracy = 50f, // worse accuracy
            newProvider = "network",
            currentTime = staleTime,
            currentAccuracy = 10f, // better accuracy but stale!
            currentProvider = "gps",
            now = now
        )
        assertTrue("Fresh location must always supersede stale location", isBetter)

        // Future clock glitch rejected
        val futureGlitch = AndroidLocationProvider.isBetterLocation(
            newTime = now + 15_000L,
            newAccuracy = 5f,
            newProvider = "gps",
            currentTime = now - 1000L,
            currentAccuracy = 20f,
            currentProvider = "gps",
            now = now
        )
        assertFalse("Future timestamp glitch must be rejected", futureGlitch)

        // Zero timestamp rejected
        val zeroTimeGlitch = AndroidLocationProvider.isBetterLocation(
            newTime = 0L,
            newAccuracy = 5f,
            newProvider = "gps",
            currentTime = now - 1000L,
            currentAccuracy = 20f,
            currentProvider = "gps",
            now = now
        )
        assertFalse("Zero timestamp must be rejected", zeroTimeGlitch)

        // Significantly newer location accepted
        val newerAccurate = AndroidLocationProvider.isBetterLocation(
            newTime = now - 2_000L,
            newAccuracy = 8f,
            newProvider = "gps",
            currentTime = now - 10_000L,
            currentAccuracy = 15f,
            currentProvider = "gps",
            now = now
        )
        assertTrue("More accurate and newer fix accepted", newerAccurate)
    }

    @Test
    fun testCoordinateValidation() {
        assertFalse("Null Island (0,0) rejected", AndroidLocationProvider.isValidCoordinate(0.0, 0.0))
        assertFalse("NaN latitude rejected", AndroidLocationProvider.isValidCoordinate(Double.NaN, 77.2090))
        assertFalse("Infinite longitude rejected", AndroidLocationProvider.isValidCoordinate(28.6139, Double.POSITIVE_INFINITY))
        assertFalse("Lat > 90 rejected", AndroidLocationProvider.isValidCoordinate(91.0, 77.2090))
        assertFalse("Lng > 180 rejected", AndroidLocationProvider.isValidCoordinate(28.6139, 185.0))
        assertTrue("Valid coordinates accepted", AndroidLocationProvider.isValidCoordinate(28.6139, 77.2090))
    }

    @Test
    fun testEmergencyActivation_startsLocationUpdatesImmediately() = runTest {
        val manager = createManager(backgroundScope)
        manager.handleDoublePress()
        testScheduler.advanceTimeBy(500)
        assertTrue("Location updates must be started immediately upon SOS", mockLocationProvider.startUpdatesCalled)
    }

    @Test
    fun testEmergencyCountdownExpiry_withStaleLocation_dispatchesWithoutStalling() = runTest {
        val manager = createManager(backgroundScope)
        // Configure stale cached location
        val staleLoc = SakhiLocation(
            latitude = 28.6139,
            longitude = 77.2090,
            accuracy = 25f,
            timestamp = virtualTime - 120_000L,
            isStale = true
        )
        mockLocationProvider.locFlow.value = staleLoc

        manager.handleDoublePress()
        advanceTimeBy(3500) // 3-second countdown expires

        // Dispatch must have succeeded
        assertTrue(mockAlertDispatcher.dispatchCalled)
        assertEquals(ContactAlertState.SENT, manager.state.value.contactAlertState)

        // Timeline must record stale location acquisition
        val staleEvent = manager.state.value.timeline.firstOrNull { it.title == "Stale Location Acquired" }
        assertNotNull("Must log Stale Location Acquired when dispatching with stale cache", staleEvent)
        assertTrue(staleEvent!!.detail.contains("stale fix"))
    }

    @Test
    fun testFreshLocationArrivingLater_transitionsStateAndLogsTimeline() = runTest {
        val manager = createManager(backgroundScope)
        // Initial state: stale location
        val staleLoc = SakhiLocation(
            latitude = 28.6139,
            longitude = 77.2090,
            accuracy = 30f,
            timestamp = virtualTime - 90_000L,
            isStale = true
        )
        mockLocationProvider.locFlow.value = staleLoc

        manager.handleDoublePress()
        advanceTimeBy(3500) // countdown expires and dispatches

        assertTrue(manager.state.value.isLocationStale)

        // Now fresh GPS fix arrives 10 seconds later
        virtualTime += 10_000L
        val freshLoc = SakhiLocation(
            latitude = 28.6145,
            longitude = 77.2095,
            accuracy = 8f,
            timestamp = virtualTime,
            isStale = false
        )
        mockLocationProvider.locFlow.value = freshLoc
        advanceTimeBy(100)

        // State must transition to fresh and high accuracy
        assertFalse("isLocationStale must become false upon fresh fix", manager.state.value.isLocationStale)
        assertTrue("isLocationHighAccuracy must be true for 8m fresh fix", manager.state.value.isLocationHighAccuracy)
        assertEquals(28.6145, manager.state.value.latitude!!, 0.0001)

        // Timeline must record Fresh Location Acquired
        val freshEvent = manager.state.value.timeline.firstOrNull { it.title == "Fresh Location Acquired" }
        assertNotNull("Must log Fresh Location Acquired when fresh GPS lock established", freshEvent)
        assertTrue(freshEvent!!.detail.contains("GPS lock established"))
    }

    @Test
    fun testSessionDurationTicker_dynamicallyFlipsStaleAfter60s() = runTest {
        val manager = createManager(backgroundScope)
        // Start with a fresh location
        val initialLoc = SakhiLocation(
            latitude = 28.6139,
            longitude = 77.2090,
            accuracy = 10f,
            timestamp = virtualTime,
            isStale = false
        )
        mockLocationProvider.locFlow.value = initialLoc

        manager.handleDoublePress()
        advanceTimeBy(100)
        assertFalse(manager.state.value.isLocationStale)
        assertTrue(manager.state.value.isLocationHighAccuracy)

        // Advance wall-clock virtual time by 65 seconds without new location fixes
        virtualTime += 65_000L
        advanceTimeBy(2000L) // ticker ticks every 1 second

        // Ticker must dynamically evaluate staleness against current time
        assertTrue("Location must dynamically become stale after 60s without a fix", manager.state.value.isLocationStale)
        assertFalse("High accuracy badge must be cleared when location becomes stale", manager.state.value.isLocationHighAccuracy)
    }

    @Test
    fun testAlertDispatcher_periodicSmsFormatting_indicatesStaleness() {
        // 1. Fresh location
        val freshLoc = SakhiLocation(
            latitude = 28.6139,
            longitude = 77.2090,
            accuracy = 12f,
            timestamp = 1_000_000L,
            isStale = false
        )
        val freshMsg = AndroidAlertDispatcher.formatPeriodicUpdateMessage(
            location = freshLoc,
            liveTrackingUrl = "https://track.sakhi.app/demo",
            updateNumber = 1,
            speechExcerpt = null,
            assessmentLabel = null
        )
        assertTrue(freshMsg.contains("±12m"))
        assertFalse(freshMsg.contains("(stale)"))

        // 2. Stale location
        val staleLoc = SakhiLocation(
            latitude = 28.6139,
            longitude = 77.2090,
            accuracy = 15f,
            timestamp = 800_000L,
            isStale = true
        )
        val staleMsg = AndroidAlertDispatcher.formatPeriodicUpdateMessage(
            location = staleLoc,
            liveTrackingUrl = "https://track.sakhi.app/demo",
            updateNumber = 2,
            speechExcerpt = null,
            assessmentLabel = null
        )
        assertTrue(staleMsg.contains("±15m (stale)"))
    }

    @Test
    fun testTapHoldCancellation_stopsLocationUpdates() = runTest {
        val manager = createManager(backgroundScope)
        manager.handleDoublePress()
        testScheduler.advanceTimeBy(500)
        assertTrue(mockLocationProvider.startUpdatesCalled)

        // Cancel via tap-and-hold
        manager.handleTapHold()
        testScheduler.advanceTimeBy(100)
        assertTrue("Cancellation via tap-hold must stop location updates to release hardware", mockLocationProvider.stopUpdatesCalled)
    }

    @Test
    fun testGpsClockJitter_acceptedAsFreshWithinTolerance() {
        val now = 1_000_000L

        // GPS clock 500ms ahead of phone clock (normal jitter)
        val jitterLoc = SakhiLocation(
            latitude = 28.6139,
            longitude = 77.2090,
            accuracy = 10f,
            timestamp = now + 500L,
            isStale = false
        )
        assertTrue("Slight GPS clock jitter ahead of wall-clock must be accepted as fresh", jitterLoc.isFresh(now))
        assertFalse(jitterLoc.isStale(now))

        // Large future clock glitch (> 10s ahead) must be rejected
        val glitchLoc = SakhiLocation(
            latitude = 28.6139,
            longitude = 77.2090,
            accuracy = 10f,
            timestamp = now + 15_000L,
            isStale = false
        )
        assertFalse("Future clock anomaly >10s must be marked stale/invalid", glitchLoc.isFresh(now))
        assertTrue(glitchLoc.isStale(now))
    }

    @Test
    fun testPeriodicLoop_doesNotUploadStaleCoordinatesToLiveTracking() = runTest {
        mockPreferences.liveTrackingEnabled = true
        mockPreferences.periodicSmsInterval = 15
        val manager = createManager(backgroundScope)

        // Stale location fix (2 minutes old)
        val staleLoc = SakhiLocation(
            latitude = 28.6139,
            longitude = 77.2090,
            accuracy = 25f,
            timestamp = virtualTime - 120_000L,
            isStale = true
        )
        mockLocationProvider.locFlow.value = staleLoc

        manager.handleDoublePress()
        testScheduler.advanceTimeBy(3500) // Countdown expires, initial SMS sent

        // Advance 20 seconds for periodic cycle
        testScheduler.advanceTimeBy(20_000L)

        // Uploads must NOT contain the stale location
        assertTrue("Stale location must not be uploaded as a live position update", mockLiveTrackingClient.uploadedLocations.isEmpty())

        // Now provide a fresh fix
        virtualTime += 20_000L
        val freshLoc = SakhiLocation(
            latitude = 28.6140,
            longitude = 77.2091,
            accuracy = 10f,
            timestamp = virtualTime,
            isStale = false
        )
        mockLocationProvider.locFlow.value = freshLoc
        testScheduler.advanceTimeBy(100) // Fresh location collector handles immediate upload

        assertEquals("Fresh location must be uploaded immediately", 1, mockLiveTrackingClient.uploadedLocations.size)
        assertEquals(28.6140, mockLiveTrackingClient.uploadedLocations[0].latitude, 0.0001)
    }
}

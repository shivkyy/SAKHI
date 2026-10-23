package com.example.sakhi

import com.example.sakhi.core.model.Contact
import com.example.sakhi.core.model.ContactAlertState
import com.example.sakhi.core.model.HardwareEvent
import com.example.sakhi.core.model.PoliceState
import com.example.sakhi.core.model.SessionState
import com.example.sakhi.data.bluetooth.BluetoothClassicAudioReceiver
import com.example.sakhi.data.bluetooth.SakhiStreamFramer
import com.example.sakhi.domain.emergency.EmergencyStateManager
import com.example.sakhi.domain.escalation.EscalationPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Comprehensive verification of the ESP32 physical keychain button integration
 * covering all 15 test conditions specified in the SAKHI hardware button protocol.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhysicalButtonIntegrationTest {

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

    // 1. Double press while idle activates SOS and begins countdown
    @Test
    fun test1_doublePressWhileIdleActivatesSosAndBeginsCountdown() = runTest {
        mockPreferences.countdownSeconds = 5
        val manager = createManager(backgroundScope)
        assertEquals(SessionState.IDLE, manager.state.value.sessionState)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(100)

        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertEquals(ContactAlertState.COUNTDOWN, manager.state.value.contactAlertState)
        assertEquals(5, manager.state.value.countdownRemainingSeconds)
    }

    // 2. Double press while activating is deduplicated / ignored
    @Test
    fun test2_doublePressWhileActivatingIsDeduplicated() = runTest {
        mockPreferences.countdownSeconds = 5
        val manager = createManager(backgroundScope)

        manager.handleDoublePress()
        testScheduler.advanceTimeBy(50)
        val sessionId = manager.state.value.activeSessionId

        // Second double-press while activating/counting down
        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(50)

        assertEquals(sessionId, manager.state.value.activeSessionId)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertEquals(ContactAlertState.COUNTDOWN, manager.state.value.contactAlertState)
    }

    // 3. Double press while active is deduplicated / ignored
    @Test
    fun test3_doublePressWhileActiveIsDeduplicated() = runTest {
        mockPreferences.countdownSeconds = 3
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Guardian", phoneNumber = "+919876543210")
        )
        val manager = createManager(backgroundScope)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(100)
        val initialSessionId = manager.state.value.activeSessionId

        // Advance past countdown into dispatched active state
        testScheduler.advanceTimeBy(3500)
        assertEquals(ContactAlertState.SENT, manager.state.value.contactAlertState)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)

        // Another double press while active
        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(100)

        // Session ID and state must remain identical (not restarted)
        assertEquals(initialSessionId, manager.state.value.activeSessionId)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
    }

    // 4. Triple press while idle is ignored
    @Test
    fun test4_triplePressWhileIdleIsIgnored() = runTest {
        val manager = createManager(backgroundScope)
        assertEquals(SessionState.IDLE, manager.state.value.sessionState)
        assertEquals(PoliceState.NOT_REQUESTED, manager.state.value.policeState)

        manager.handleHardwareEvent(HardwareEvent.TripleTap())
        testScheduler.advanceTimeBy(100)

        assertEquals(SessionState.IDLE, manager.state.value.sessionState)
        assertEquals(PoliceState.NOT_REQUESTED, manager.state.value.policeState)
        assertEquals(0, mockPoliceCallManager.callCount)
    }

    // 5. Triple press while active (post-countdown) triggers Call Now
    @Test
    fun test5_triplePressWhileActiveTriggersCallNow() = runTest {
        mockPreferences.countdownSeconds = 5
        val manager = createManager(backgroundScope)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        // Advance past countdown into ACTIVE session
        testScheduler.advanceTimeBy(5500)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertEquals(0, manager.state.value.countdownRemainingSeconds)

        manager.handleHardwareEvent(HardwareEvent.TripleTap())
        testScheduler.advanceTimeBy(100)

        assertEquals(PoliceState.CALL_INITIATED, manager.state.value.policeState)
        assertEquals(1, mockPoliceCallManager.callCount)
    }

    // 6. Press-down during countdown pauses countdown immediately
    @Test
    fun test6_pressDownDuringCountdownPausesCountdownImmediately() = runTest {
        mockPreferences.countdownSeconds = 5
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Mother", phoneNumber = "+919876543210")
        )
        val manager = createManager(backgroundScope)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(100)

        // Advance 1s into countdown (4s remaining)
        testScheduler.advanceTimeBy(1000)
        assertEquals(4, manager.state.value.countdownRemainingSeconds)

        // Press down button (HOLD_START)
        manager.handleHardwareEvent(HardwareEvent.ButtonHoldStart())

        // Advance time by 2.5s while held
        testScheduler.advanceTimeBy(2500)

        // Countdown must be paused: remaining time must not have decremented to 0
        assertEquals(4, manager.state.value.countdownRemainingSeconds)
        // Contacts must NOT have been alerted
        assertEquals(ContactAlertState.COUNTDOWN, manager.state.value.contactAlertState)
        assertFalse(mockAlertDispatcher.dispatchCalled)
    }

    // 7. Hold release before 3s during countdown resumes countdown with exact remaining time
    @Test
    fun test7_holdReleaseBefore3sResumesCountdownWithExactRemainingTime() = runTest {
        mockPreferences.countdownSeconds = 5
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Mother", phoneNumber = "+919876543210")
        )
        val manager = createManager(backgroundScope)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(100)

        // Advance 1s: 4s remaining
        testScheduler.advanceTimeBy(1000)
        assertEquals(4, manager.state.value.countdownRemainingSeconds)

        // Press down and hold for 1.5 seconds (less than 3s)
        manager.handleHardwareEvent(HardwareEvent.ButtonHoldStart())
        testScheduler.advanceTimeBy(1500)
        assertEquals(4, manager.state.value.countdownRemainingSeconds)

        // Release button (HOLD_RELEASE)
        manager.handleHardwareEvent(HardwareEvent.ButtonHoldRelease(durationMs = 1500L))
        testScheduler.advanceTimeBy(50)

        // Countdown resumes from remaining 4s (NOT resetting to 5s)
        testScheduler.advanceTimeBy(1050)
        assertEquals(3, manager.state.value.countdownRemainingSeconds)

        // Advance remaining 3s to let countdown complete
        testScheduler.advanceTimeBy(3500)
        assertEquals(ContactAlertState.SENT, manager.state.value.contactAlertState)
        assertTrue(mockAlertDispatcher.dispatchCalled)
    }

    // 8. Hold for 3s during countdown cancels pending alert (reason: CANCELLED_BEFORE_ALERT)
    @Test
    fun test8_holdFor3sDuringCountdownCancelsPendingAlert() = runTest {
        mockPreferences.countdownSeconds = 5
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Mother", phoneNumber = "+919876543210")
        )
        val manager = createManager(backgroundScope)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(100)

        // Press down during countdown
        manager.handleHardwareEvent(HardwareEvent.ButtonHoldStart())
        testScheduler.advanceTimeBy(3000)

        // 3-second hold completed (TAP_HOLD / HOLD_COMPLETE)
        manager.handleHardwareEvent(HardwareEvent.TapHold(durationMs = 3000L))
        testScheduler.advanceTimeBy(100)

        assertEquals(SessionState.CANCELLED, manager.state.value.sessionState)
        assertEquals(ContactAlertState.CANCELLED, manager.state.value.contactAlertState)
        assertEquals("CANCELLED_BEFORE_ALERT", manager.state.value.activeSessionOutcome)
        assertFalse(mockAlertDispatcher.dispatchCalled)
    }

    // 9. Hold for 3s after alert dispatch ends active emergency session (reason: ALERT_SENT_THEN_CANCELLED)
    @Test
    fun test9_holdFor3sAfterAlertDispatchEndsActiveSession() = runTest {
        mockPreferences.countdownSeconds = 3
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Mother", phoneNumber = "+919876543210")
        )
        val manager = createManager(backgroundScope)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(100)

        // Let countdown expire and dispatch SMS
        testScheduler.advanceTimeBy(3500)
        assertEquals(ContactAlertState.SENT, manager.state.value.contactAlertState)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)

        // Hold button for 3s after dispatch
        manager.handleHardwareEvent(HardwareEvent.ButtonHoldStart())
        testScheduler.advanceTimeBy(3000)
        manager.handleHardwareEvent(HardwareEvent.TapHold(durationMs = 3000L))
        testScheduler.advanceTimeBy(100)

        assertEquals(SessionState.CANCELLED, manager.state.value.sessionState)
        assertEquals("ALERT_SENT_THEN_CANCELLED", manager.state.value.activeSessionOutcome)
        assertEquals(ContactAlertState.SENT, manager.state.value.contactAlertState)
    }

    // 10. Hold release after alert dispatch before 3s does NOT end active emergency session
    @Test
    fun test10_holdReleaseAfterAlertDispatchBefore3sDoesNotEndActiveSession() = runTest {
        mockPreferences.countdownSeconds = 3
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Mother", phoneNumber = "+919876543210")
        )
        val manager = createManager(backgroundScope)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(3500)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)

        // Hold for 1.5s then release
        manager.handleHardwareEvent(HardwareEvent.ButtonHoldStart())
        testScheduler.advanceTimeBy(1500)
        manager.handleHardwareEvent(HardwareEvent.ButtonHoldRelease(durationMs = 1500L))
        testScheduler.advanceTimeBy(100)

        // Must remain ACTIVE
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)
        assertEquals(ContactAlertState.SENT, manager.state.value.contactAlertState)
    }

    // 11. Gestures are assigned to origin state (hold initiated during countdown resolves as countdown cancel)
    @Test
    fun test11_gesturesAssignedToOriginStateCrossBoundary() = runTest {
        mockPreferences.countdownSeconds = 2
        mockContactRepository.testContacts = listOf(
            Contact(id = 1, name = "Mother", phoneNumber = "+919876543210")
        )
        val manager = createManager(backgroundScope)

        manager.handleHardwareEvent(HardwareEvent.DoublePress())
        testScheduler.advanceTimeBy(1000) // 1 second left

        // Hold started while countdown was active
        manager.handleHardwareEvent(HardwareEvent.ButtonHoldStart())
        // Hold takes 3 seconds (which without pause would cross the 2s total duration)
        testScheduler.advanceTimeBy(3000)
        manager.handleHardwareEvent(HardwareEvent.TapHold(durationMs = 3000L))
        testScheduler.advanceTimeBy(100)

        // Origin was countdown, so must resolve as CANCELLED_BEFORE_ALERT
        assertEquals("CANCELLED_BEFORE_ALERT", manager.state.value.activeSessionOutcome)
        assertEquals(ContactAlertState.CANCELLED, manager.state.value.contactAlertState)
        assertFalse(mockAlertDispatcher.dispatchCalled)
    }

    // 12. SPP framing multiplexing: audio chunks and control frames are correctly separated
    @Test
    fun test12_sppFramingMultiplexingAudioAndControlSeparation() {
        val audioChunks = mutableListOf<ByteArray>()
        val controlEvents = mutableListOf<HardwareEvent>()

        val framer = SakhiStreamFramer(
            onAudioChunk = { audioChunks.add(it) },
            onControlEvent = { controlEvents.add(it) }
        )

        val audioPayload1 = ByteArray(256) { (it % 100).toByte() }
        val audioPayload2 = ByteArray(256) { (it % 50).toByte() }
        val audioPacket1 = SakhiStreamFramer.encodeAudioFrame(audioPayload1)
        val controlPacket = SakhiStreamFramer.encodeControlFrame(SakhiStreamFramer.OPCODE_DOUBLE_PRESS)
        val audioPacket2 = SakhiStreamFramer.encodeAudioFrame(audioPayload2)

        // Feed combined stream
        val combined = audioPacket1 + controlPacket + audioPacket2
        framer.feedBytes(combined)

        assertEquals(2, audioChunks.size)
        assertArrayEquals(audioPayload1, audioChunks[0])
        assertArrayEquals(audioPayload2, audioChunks[1])

        assertEquals(1, controlEvents.size)
        assertTrue(controlEvents[0] is HardwareEvent.DoublePress)
    }

    // 13. Audio chunk framing does not alter PCM byte stream (no inserted characters, correct 16-bit alignment)
    @Test
    fun test13_audioFramingPreservesPcmBytesAnd16BitAlignment() {
        val receivedPcm = mutableListOf<ByteArray>()
        val framer = SakhiStreamFramer(
            onAudioChunk = { receivedPcm.add(it) },
            onControlEvent = {}
        )

        // Generate 512 bytes of 16-bit PCM test pattern
        val originalPcm = ByteArray(512) { i -> ((i * 3) and 0xFF).toByte() }
        val frame1 = SakhiStreamFramer.encodeAudioFrame(originalPcm, 0, 256)
        val frame2 = SakhiStreamFramer.encodeAudioFrame(originalPcm, 256, 256)

        framer.feedBytes(frame1)
        framer.feedBytes(frame2)

        assertEquals(2, receivedPcm.size)
        // Verify even length (16-bit alignment)
        assertEquals(0, receivedPcm[0].size % 2)
        assertEquals(0, receivedPcm[1].size % 2)

        val reconstructed = receivedPcm[0] + receivedPcm[1]
        assertArrayEquals(originalPcm, reconstructed)
    }

    // 14. Corrupted control frame (bad checksum or magic) is discarded without crashing
    @Test
    fun test14_corruptedControlFrameIsDiscardedSafely() {
        val receivedEvents = mutableListOf<HardwareEvent>()
        val framer = SakhiStreamFramer(
            onAudioChunk = {},
            onControlEvent = { receivedEvents.add(it) }
        )

        // 1. Packet with bad magic
        val badMagic = byteArrayOf('X'.code.toByte(), 'X'.code.toByte(), 'X'.code.toByte(), 'X'.code.toByte(), 0x02, 0x00, 0x01, 0x01, 0x00)
        framer.feedBytes(badMagic)
        assertEquals(0, receivedEvents.size)

        // 2. Packet with corrupted checksum
        val goodPacket = SakhiStreamFramer.encodeControlFrame(SakhiStreamFramer.OPCODE_DOUBLE_PRESS)
        val corruptedChecksumPacket = goodPacket.copyOf()
        corruptedChecksumPacket[corruptedChecksumPacket.size - 1] = (corruptedChecksumPacket.last() + 1).toByte()

        framer.feedBytes(corruptedChecksumPacket)
        assertEquals(0, receivedEvents.size)

        // 3. Followed by a valid packet -> must parse successfully
        val validPacket = SakhiStreamFramer.encodeControlFrame(SakhiStreamFramer.OPCODE_TRIPLE_TAP)
        framer.feedBytes(validPacket)
        assertEquals(1, receivedEvents.size)
        assertTrue(receivedEvents[0] is HardwareEvent.TripleTap)
    }

    // 15. Unframed raw PCM stream fallback continues to deliver audio to Vosk recognizers
    @Test
    fun test15_unframedRawPcmFallbackDeliversAudioWithAlignment() {
        val receivedChunks = mutableListOf<ByteArray>()
        val framer = SakhiStreamFramer(
            onAudioChunk = { receivedChunks.add(it) },
            onControlEvent = {}
        )

        // Feed odd number of raw bytes (no magic)
        val rawPcm1 = byteArrayOf(1, 2, 3, 4, 5)
        framer.feedBytes(rawPcm1)

        // Must emit even 4 bytes, holding 1 byte in buffer
        assertEquals(1, receivedChunks.size)
        assertEquals(4, receivedChunks[0].size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), receivedChunks[0])

        // Feed next 3 bytes
        val rawPcm2 = byteArrayOf(6, 7, 8)
        framer.feedBytes(rawPcm2)

        // Total remaining = 1 (from previous) + 3 = 4 bytes
        assertEquals(2, receivedChunks.size)
        assertEquals(4, receivedChunks[1].size)
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), receivedChunks[1])
    }

    // 16. Leading garbage bytes before a framed packet are discarded rather than emitted as corrupted audio
    @Test
    fun test16_framedStream_discardsLeadingGarbageBeforeMagic() {
        val receivedChunks = mutableListOf<ByteArray>()
        val receivedEvents = mutableListOf<HardwareEvent>()
        val framer = SakhiStreamFramer(
            onAudioChunk = { receivedChunks.add(it) },
            onControlEvent = { receivedEvents.add(it) }
        )

        // 3 bytes of garbage followed by a valid audio frame
        val garbage = byteArrayOf(0x55, 0xAA.toByte(), 0x12)
        val audioData = byteArrayOf(10, 20, 30, 40)
        val framedAudio = SakhiStreamFramer.encodeAudioFrame(audioData)
        val combined = garbage + framedAudio

        framer.feedBytes(combined)

        // The garbage must NOT be emitted as audio chunks; only the valid framed audio payload
        assertEquals(1, receivedChunks.size)
        assertArrayEquals(audioData, receivedChunks[0])
    }
}

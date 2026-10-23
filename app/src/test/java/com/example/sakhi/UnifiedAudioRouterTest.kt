package com.example.sakhi

import com.example.sakhi.data.audio.ActiveAudioSource
import com.example.sakhi.data.audio.IPhoneMicrophoneAudioSource
import com.example.sakhi.data.audio.PhoneAudioState
import com.example.sakhi.data.audio.PhoneAudioTelemetry
import com.example.sakhi.data.audio.RouterOperationalState
import com.example.sakhi.data.audio.UnifiedAudioRouter
import com.example.sakhi.data.bluetooth.BluetoothClassicAudioReceiver
import com.example.sakhi.data.bluetooth.BtAudioConnectionState
import com.example.sakhi.data.bluetooth.BtAudioTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedAudioRouterTest {

    private class FakeBtAudioReceiver(scope: CoroutineScope) : BluetoothClassicAudioReceiver(scope = scope) {
        fun setTelemetryState(telemetry: BtAudioTelemetry) {
            _telemetry.value = telemetry
        }

        fun emitTestChunk(chunk: ByteArray) {
            _audioChunkFlow.tryEmit(chunk)
        }
    }

    private class FakePhoneMicrophoneAudioSource : IPhoneMicrophoneAudioSource {
        private val _telemetry = MutableStateFlow(PhoneAudioTelemetry())
        override val telemetry: StateFlow<PhoneAudioTelemetry> = _telemetry.asStateFlow()

        private val _audioChunkFlow = MutableSharedFlow<ByteArray>(
            replay = 0,
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        override val audioChunkFlow: SharedFlow<ByteArray> = _audioChunkFlow.asSharedFlow()

        override var isCapturing: Boolean = false
        var startCaptureCalls = 0
        var stopCaptureCalls = 0
        var releaseCalls = 0

        fun setTelemetry(telemetry: PhoneAudioTelemetry) {
            _telemetry.value = telemetry
            isCapturing = telemetry.isCapturing
        }

        fun emitTestChunk(chunk: ByteArray) {
            _audioChunkFlow.tryEmit(chunk)
        }

        override fun startCapture() {
            startCaptureCalls++
            if (_telemetry.value.state != PhoneAudioState.PERMISSION_DENIED &&
                _telemetry.value.state != PhoneAudioState.INITIALIZATION_FAILED &&
                _telemetry.value.state != PhoneAudioState.RECORDING_FAILED
            ) {
                isCapturing = true
                _telemetry.value = _telemetry.value.copy(
                    state = PhoneAudioState.RECORDING,
                    isCapturing = true
                )
            }
        }

        override fun stopCapture() {
            stopCaptureCalls++
            isCapturing = false
            _telemetry.value = _telemetry.value.copy(
                state = PhoneAudioState.STOPPED,
                isCapturing = false
            )
        }

        override fun release() {
            releaseCalls++
            stopCapture()
        }
    }

    @Test
    fun testInitialSelection_whenEsp32IsStreaming_selectsEsp32() = runTest {
        var currentTime = 1000L
        val fakeBt = FakeBtAudioReceiver(backgroundScope)
        fakeBt.setTelemetryState(
            BtAudioTelemetry(
                state = BtAudioConnectionState.STREAMING,
                isReceivingData = true,
                lastDataReceivedTimestamp = currentTime
            )
        )

        val fakePhone = FakePhoneMicrophoneAudioSource()

        val router = UnifiedAudioRouter(
            esp32AudioReceiver = fakeBt,
            phoneAudioSource = fakePhone,
            scope = backgroundScope,
            timeProvider = { currentTime }
        )

        router.start()

        assertEquals(ActiveAudioSource.ESP32_BLUETOOTH, router.activeSource)
        assertEquals(RouterOperationalState.ROUTING_ESP32, router.telemetry.value.operationalState)
        assertFalse("Phone microphone should not be capturing when ESP32 is active", fakePhone.isCapturing)

        router.stop()
    }

    @Test
    fun testImmediateFailover_uponBluetoothDisconnect() = runTest {
        var currentTime = 1000L
        val fakeBt = FakeBtAudioReceiver(backgroundScope)
        fakeBt.setTelemetryState(
            BtAudioTelemetry(
                state = BtAudioConnectionState.STREAMING,
                isReceivingData = true,
                lastDataReceivedTimestamp = currentTime
            )
        )

        val fakePhone = FakePhoneMicrophoneAudioSource()

        val router = UnifiedAudioRouter(
            esp32AudioReceiver = fakeBt,
            phoneAudioSource = fakePhone,
            scope = backgroundScope,
            timeProvider = { currentTime }
        )

        router.start()
        assertEquals(ActiveAudioSource.ESP32_BLUETOOTH, router.activeSource)

        // Simulate immediate Bluetooth disconnect
        fakeBt.setTelemetryState(
            BtAudioTelemetry(
                state = BtAudioConnectionState.DISCONNECTED,
                isReceivingData = false,
                lastDataReceivedTimestamp = currentTime
            )
        )

        router.evaluateSourceSelection()

        assertEquals(ActiveAudioSource.PHONE_MICROPHONE, router.activeSource)
        assertEquals(RouterOperationalState.FAILOVER_ACTIVE, router.telemetry.value.operationalState)
        assertTrue("Phone mic should start capturing upon disconnect failover", fakePhone.isCapturing)
        assertEquals(1, fakePhone.startCaptureCalls)

        router.stop()
    }

    @Test
    fun testImmediateFailover_uponBluetoothError() = runTest {
        var currentTime = 1000L
        val fakeBt = FakeBtAudioReceiver(backgroundScope)
        fakeBt.setTelemetryState(
            BtAudioTelemetry(
                state = BtAudioConnectionState.STREAMING,
                isReceivingData = true,
                lastDataReceivedTimestamp = currentTime
            )
        )

        val fakePhone = FakePhoneMicrophoneAudioSource()

        val router = UnifiedAudioRouter(
            esp32AudioReceiver = fakeBt,
            phoneAudioSource = fakePhone,
            scope = backgroundScope,
            timeProvider = { currentTime }
        )

        router.start()

        // Transition to ERROR
        fakeBt.setTelemetryState(
            BtAudioTelemetry(
                state = BtAudioConnectionState.ERROR,
                isReceivingData = false,
                errorMessage = "RFCOMM socket broken"
            )
        )

        router.evaluateSourceSelection()

        assertEquals(ActiveAudioSource.PHONE_MICROPHONE, router.activeSource)
        assertEquals(RouterOperationalState.FAILOVER_ACTIVE, router.telemetry.value.operationalState)
        assertTrue(fakePhone.isCapturing)

        router.stop()
    }

    @Test
    fun testStalledStreamTimeout_switchesToPhoneMic() = runTest {
        var currentTime = 1000L
        val fakeBt = FakeBtAudioReceiver(backgroundScope)
        fakeBt.setTelemetryState(
            BtAudioTelemetry(
                state = BtAudioConnectionState.STREAMING,
                isReceivingData = true,
                lastDataReceivedTimestamp = 1000L
            )
        )

        val fakePhone = FakePhoneMicrophoneAudioSource()

        val router = UnifiedAudioRouter(
            esp32AudioReceiver = fakeBt,
            phoneAudioSource = fakePhone,
            scope = backgroundScope,
            starvationTimeoutMs = 2500L,
            timeProvider = { currentTime }
        )

        router.start()
        assertEquals(ActiveAudioSource.ESP32_BLUETOOTH, router.activeSource)

        // Advance time by 2000 ms (< 2500 ms timeout)
        currentTime = 3000L
        router.evaluateSourceSelection()
        assertEquals("Still within timeout, should remain on ESP32", ActiveAudioSource.ESP32_BLUETOOTH, router.activeSource)

        // Advance time to 3600 ms (> 2500 ms timeout)
        currentTime = 3600L
        router.evaluateSourceSelection()

        assertEquals("Starvation timeout exceeded: must fail over to phone mic", ActiveAudioSource.PHONE_MICROPHONE, router.activeSource)
        assertEquals(RouterOperationalState.FAILOVER_ACTIVE, router.telemetry.value.operationalState)
        assertTrue(router.telemetry.value.lastSwitchReason!!.contains("starved", ignoreCase = true))
        assertTrue(fakePhone.isCapturing)

        router.stop()
    }

    @Test
    fun testStableStreamRecovery_switchesBackToEsp32() = runTest {
        var currentTime = 1000L
        val fakeBt = FakeBtAudioReceiver(backgroundScope)
        fakeBt.setTelemetryState(
            BtAudioTelemetry(
                state = BtAudioConnectionState.DISCONNECTED,
                isReceivingData = false
            )
        )

        val fakePhone = FakePhoneMicrophoneAudioSource()

        val router = UnifiedAudioRouter(
            esp32AudioReceiver = fakeBt,
            phoneAudioSource = fakePhone,
            scope = backgroundScope,
            stableRecoveryDurationMs = 500L,
            timeProvider = { currentTime },
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        router.start()
        assertEquals(ActiveAudioSource.PHONE_MICROPHONE, router.activeSource)
        assertTrue(fakePhone.isCapturing)

        // ESP32 reconnects and begins streaming
        fakeBt.setTelemetryState(
            BtAudioTelemetry(
                state = BtAudioConnectionState.STREAMING,
                isReceivingData = true,
                lastDataReceivedTimestamp = currentTime
            )
        )

        // First chunk arrived at time = 1000L
        fakeBt.emitTestChunk(ByteArray(320))
        testScheduler.advanceTimeBy(50)
        assertEquals("Should not switch back on 1st chunk immediately", ActiveAudioSource.PHONE_MICROPHONE, router.activeSource)

        // Advance time beyond stableRecoveryDurationMs (500L) and emit 2nd chunk
        currentTime = 1600L
        fakeBt.setTelemetryState(
            BtAudioTelemetry(
                state = BtAudioConnectionState.STREAMING,
                isReceivingData = true,
                lastDataReceivedTimestamp = currentTime
            )
        )
        fakeBt.emitTestChunk(ByteArray(320))
        testScheduler.advanceTimeBy(50)

        // Now stream is stable -> should fail back to ESP32
        assertEquals(ActiveAudioSource.ESP32_BLUETOOTH, router.activeSource)
        assertEquals(RouterOperationalState.ROUTING_ESP32, router.telemetry.value.operationalState)
        assertFalse("Phone mic must stop capturing when failing back to ESP32", fakePhone.isCapturing)
        assertEquals(1, fakePhone.stopCaptureCalls)

        router.stop()
    }

    @Test
    fun testMutualExclusion_neverEmitsSimultaneousStreams() = runTest {
        var currentTime = 1000L
        val fakeBt = FakeBtAudioReceiver(backgroundScope)
        fakeBt.setTelemetryState(
            BtAudioTelemetry(
                state = BtAudioConnectionState.STREAMING,
                isReceivingData = true,
                lastDataReceivedTimestamp = currentTime
            )
        )

        val fakePhone = FakePhoneMicrophoneAudioSource()

        val router = UnifiedAudioRouter(
            esp32AudioReceiver = fakeBt,
            phoneAudioSource = fakePhone,
            scope = backgroundScope,
            timeProvider = { currentTime },
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        val receivedChunks = mutableListOf<ByteArray>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            router.routedAudioFlow.collect { chunk ->
                receivedChunks.add(chunk)
            }
        }

        router.start()
        assertEquals(ActiveAudioSource.ESP32_BLUETOOTH, router.activeSource)

        // 1. ESP32 is active:
        val espChunk = byteArrayOf(1, 2, 3, 4)
        val phoneChunk1 = byteArrayOf(9, 9, 9, 9)

        fakeBt.emitTestChunk(espChunk)
        fakePhone.emitTestChunk(phoneChunk1)
        testScheduler.advanceTimeBy(50)

        assertEquals("Only ESP32 chunk should be routed", 1, receivedChunks.size)
        assertArrayEquals(espChunk, receivedChunks[0])

        // 2. Failover to Phone Mic:
        fakeBt.setTelemetryState(
            BtAudioTelemetry(
                state = BtAudioConnectionState.DISCONNECTED,
                isReceivingData = false
            )
        )
        router.evaluateSourceSelection()
        assertEquals(ActiveAudioSource.PHONE_MICROPHONE, router.activeSource)

        val phoneChunk2 = byteArrayOf(5, 6, 7, 8)
        val staleEspChunk = byteArrayOf(0, 0, 0, 0)

        fakePhone.emitTestChunk(phoneChunk2)
        fakeBt.emitTestChunk(staleEspChunk) // Should be dropped because activeSource is PHONE_MICROPHONE and not yet stable
        testScheduler.advanceTimeBy(50)

        assertEquals(2, receivedChunks.size)
        assertArrayEquals(phoneChunk2, receivedChunks[1])

        router.stop()
        collectJob.cancel()
    }

    @Test
    fun testAllSourcesUnavailable_whenEsp32StalledAndPhoneMicFails() = runTest {
        val fakeBt = FakeBtAudioReceiver(backgroundScope)
        fakeBt.setTelemetryState(
            BtAudioTelemetry(
                state = BtAudioConnectionState.DISCONNECTED,
                isReceivingData = false,
                errorMessage = "Device out of range"
            )
        )

        val fakePhone = FakePhoneMicrophoneAudioSource()
        fakePhone.setTelemetry(
            PhoneAudioTelemetry(
                state = PhoneAudioState.PERMISSION_DENIED,
                errorMessage = "RECORD_AUDIO permission denied"
            )
        )

        val router = UnifiedAudioRouter(
            esp32AudioReceiver = fakeBt,
            phoneAudioSource = fakePhone,
            scope = backgroundScope,
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        router.start()

        val telemetry = router.telemetry.value
        assertEquals(RouterOperationalState.ALL_SOURCES_UNAVAILABLE, telemetry.operationalState)
        assertNotNull(telemetry.failureReason)
        assertTrue(telemetry.failureReason!!.contains("Phone mic failed"))

        router.stop()
    }

    @Test
    fun testTransitionNotification_firesCallback() = runTest {
        var currentTime = 1000L
        val fakeBt = FakeBtAudioReceiver(backgroundScope)
        fakeBt.setTelemetryState(
            BtAudioTelemetry(
                state = BtAudioConnectionState.STREAMING,
                isReceivingData = true,
                lastDataReceivedTimestamp = currentTime
            )
        )

        val fakePhone = FakePhoneMicrophoneAudioSource()
        val transitions = mutableListOf<Pair<ActiveAudioSource, ActiveAudioSource>>()

        val router = UnifiedAudioRouter(
            esp32AudioReceiver = fakeBt,
            phoneAudioSource = fakePhone,
            scope = backgroundScope,
            timeProvider = { currentTime },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            onSourceTransition = { from, to ->
                transitions.add(Pair(from, to))
            }
        )

        router.start()
        // Disconnect BT
        fakeBt.setTelemetryState(
            BtAudioTelemetry(state = BtAudioConnectionState.DISCONNECTED, isReceivingData = false)
        )
        router.evaluateSourceSelection()

        assertEquals(2, transitions.size)
        assertEquals(Pair(ActiveAudioSource.NONE, ActiveAudioSource.ESP32_BLUETOOTH), transitions[0])
        assertEquals(Pair(ActiveAudioSource.ESP32_BLUETOOTH, ActiveAudioSource.PHONE_MICROPHONE), transitions[1])

        router.stop()
        assertEquals(3, transitions.size)
        assertEquals(Pair(ActiveAudioSource.PHONE_MICROPHONE, ActiveAudioSource.NONE), transitions[2])
    }

    @Test
    fun testTelemetry_tracksChunkTimestampAndDataArriving() = runTest {
        var currentTime = 2000L
        val fakeBt = FakeBtAudioReceiver(backgroundScope)
        fakeBt.setTelemetryState(
            BtAudioTelemetry(
                state = BtAudioConnectionState.STREAMING,
                isReceivingData = true,
                lastDataReceivedTimestamp = currentTime
            )
        )

        val fakePhone = FakePhoneMicrophoneAudioSource()

        val router = UnifiedAudioRouter(
            esp32AudioReceiver = fakeBt,
            phoneAudioSource = fakePhone,
            scope = backgroundScope,
            timeProvider = { currentTime },
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        router.start()
        assertEquals(ActiveAudioSource.ESP32_BLUETOOTH, router.activeSource)
        assertNull(router.telemetry.value.lastAudioChunkTimestamp)
        assertFalse(router.telemetry.value.isDataCurrentlyArriving)

        // Emit a chunk
        fakeBt.emitTestChunk(byteArrayOf(1, 2, 3, 4))
        testScheduler.advanceTimeBy(50)

        // Timestamp should be recorded and isDataCurrentlyArriving should be true
        assertEquals(2000L, router.telemetry.value.lastAudioChunkTimestamp)
        assertTrue(router.telemetry.value.isDataCurrentlyArriving)
        assertFalse(router.telemetry.value.isSwitching)

        // Advance time by 2000ms (> 1500ms inactivity window)
        currentTime = 4000L
        fakeBt.setTelemetryState(
            BtAudioTelemetry(
                state = BtAudioConnectionState.STREAMING,
                isReceivingData = true,
                lastDataReceivedTimestamp = currentTime
            )
        )
        router.evaluateSourceSelection()
        assertFalse("Data should no longer be considered currently arriving after 2000ms inactivity", router.telemetry.value.isDataCurrentlyArriving)

        router.stop()
    }
}

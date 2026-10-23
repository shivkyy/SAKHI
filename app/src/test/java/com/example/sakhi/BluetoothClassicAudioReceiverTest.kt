package com.example.sakhi

import com.example.sakhi.data.bluetooth.AudioCircularBuffer
import com.example.sakhi.data.bluetooth.BluetoothClassicAudioReceiver
import com.example.sakhi.data.bluetooth.BtAudioConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothClassicAudioReceiverTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun testAudioCircularBuffer_writeAndRead() {
        val buffer = AudioCircularBuffer(capacity = 100)
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

        val written = buffer.write(data)
        assertEquals(10, written)
        assertEquals(10, buffer.size())

        val dest = ByteArray(10)
        val read = buffer.read(dest)
        assertEquals(10, read)
        assertEquals(0, buffer.size())
        assertArrayEquals(data, dest)
    }

    @Test
    fun testAudioCircularBuffer_wraparoundAndBoundedCapacity() {
        // Create a small 10-byte buffer
        val buffer = AudioCircularBuffer(capacity = 10)

        // Write 6 bytes: 1..6
        buffer.write(byteArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals(6, buffer.size())

        // Write 6 more bytes: 7..12. Total written exceeds capacity 10.
        buffer.write(byteArrayOf(7, 8, 9, 10, 11, 12))
        // Capacity is bounded at 10 bytes
        assertEquals(10, buffer.size())

        // Snapshot should contain the 10 most recent bytes: 3..12
        val snapshot = buffer.snapshot()
        assertEquals(10, snapshot.size)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 12), snapshot)

        // Read out all 10 bytes
        val out = ByteArray(10)
        val readCount = buffer.read(out)
        assertEquals(10, readCount)
        assertEquals(0, buffer.size())
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 12), out)
    }

    @Test
    fun testAudioCircularBuffer_clear() {
        val buffer = AudioCircularBuffer(capacity = 50)
        buffer.write(byteArrayOf(1, 2, 3))
        assertEquals(3, buffer.size())

        buffer.clear()
        assertEquals(0, buffer.size())
        assertEquals(0, buffer.snapshot().size)
    }

    @Test
    fun testComputePcmRms_silence() {
        val receiver = BluetoothClassicAudioReceiver(scope = testScope)
        val silence = ByteArray(512) { 0 }

        val rms = receiver.computePcmRms(silence, silence.size)
        assertEquals(0f, rms, 0.0001f)
    }

    @Test
    fun testComputePcmRms_audioEnergy() {
        val receiver = BluetoothClassicAudioReceiver(scope = testScope)

        // Synthetic 16-bit PCM samples with low amplitude
        val quietAudio = ByteArray(256)
        for (i in quietAudio.indices step 2) {
            val sample: Short = 300
            quietAudio[i] = (sample.toInt() and 0xFF).toByte()
            quietAudio[i + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        // Synthetic 16-bit PCM samples with higher amplitude
        val loudAudio = ByteArray(256)
        for (i in loudAudio.indices step 2) {
            val sample: Short = 8000
            loudAudio[i] = (sample.toInt() and 0xFF).toByte()
            loudAudio[i + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val quietRms = receiver.computePcmRms(quietAudio, quietAudio.size)
        val loudRms = receiver.computePcmRms(loudAudio, loudAudio.size)

        assertTrue("Quiet audio should have RMS > 0", quietRms > 0f)
        assertTrue("Loud audio should have higher RMS than quiet audio", loudRms > quietRms)
        assertTrue("Loud audio RMS must be <= 1.0f", loudRms <= 1.0f)
    }

    @Test
    fun testProcessInputStream_buffersBytesAndUpdatesTelemetry() = runTest {
        val receiver = BluetoothClassicAudioReceiver(scope = testScope)

        // Generate synthetic audio payload (512 bytes = 256 samples of 16-bit PCM)
        val streamData = ByteArray(512) { (it % 64).toByte() }
        val inputStream = ByteArrayInputStream(streamData)

        receiver.processInputStream(inputStream)

        // Verify total bytes received matches the stream size
        val telemetry = receiver.telemetry.value
        assertEquals(512L, telemetry.totalBytesReceived)
        // Stream completed and closed, so final state transitions to DISCONNECTED
        assertEquals(BtAudioConnectionState.DISCONNECTED, telemetry.state)
        assertFalse(telemetry.isReceivingData)

        // Verify buffered data in circular buffer
        assertEquals(512, receiver.circularBuffer.size())
        assertArrayEquals(streamData, receiver.getBufferedPcmData())
    }

    @Test
    fun testDisconnect_resetsState() = runTest {
        val receiver = BluetoothClassicAudioReceiver(scope = testScope)

        receiver.disconnect()

        val telemetry = receiver.telemetry.value
        assertEquals(BtAudioConnectionState.DISCONNECTED, telemetry.state)
        assertFalse(telemetry.isReceivingData)
        assertEquals(0f, telemetry.currentRms, 0.0001f)
    }

    private class TestableBtReceiver(scope: kotlinx.coroutines.CoroutineScope) : BluetoothClassicAudioReceiver(scope = scope) {
        var connectCalls = 0
        var lastConnectAddress: String? = null
        var lastIsAutoRetry: Boolean = false

        override fun isBluetoothEnabled(): Boolean = true
        override fun hasBluetoothPermission(): Boolean = true

        override fun connect(targetAddress: String?, isAutoRetry: Boolean) {
            connectCalls++
            lastConnectAddress = targetAddress
            lastIsAutoRetry = isAutoRetry
            if (!isAutoRetry) {
                isAutoReconnectActive = true
                reconnectAttempt = 0
            }
        }
    }

    @Test
    fun testAutoReconnect_delayCalculationAndBoundedCap() {
        assertEquals(1500L, BluetoothClassicAudioReceiver.calculateReconnectDelayMs(1))
        assertEquals(3000L, BluetoothClassicAudioReceiver.calculateReconnectDelayMs(2))
        assertEquals(6000L, BluetoothClassicAudioReceiver.calculateReconnectDelayMs(3))
        assertEquals(12000L, BluetoothClassicAudioReceiver.calculateReconnectDelayMs(4))
        assertEquals(12000L, BluetoothClassicAudioReceiver.calculateReconnectDelayMs(5))
        assertEquals(12000L, BluetoothClassicAudioReceiver.calculateReconnectDelayMs(10))
    }

    @Test
    fun testAutoReconnect_scheduleAndRetryLimit() = runTest {
        val receiver = TestableBtReceiver(scope = testScope)
        assertFalse(receiver.isAutoReconnectActive)
        assertEquals(0, receiver.reconnectAttempt)

        receiver.connect("00:11:22:33:44:55")
        assertTrue(receiver.isAutoReconnectActive)

        // 1st retry
        receiver.scheduleAutoReconnect()
        assertEquals(1, receiver.reconnectAttempt)
        assertTrue(receiver.telemetry.value.isAutoReconnecting)
        assertEquals(1, receiver.telemetry.value.reconnectAttempt)

        // Subsequent retries up to MAX_RECONNECT_ATTEMPTS (10)
        for (i in 2..10) {
            receiver.scheduleAutoReconnect()
            assertEquals(i, receiver.reconnectAttempt)
            assertTrue(receiver.telemetry.value.isAutoReconnecting)
            assertEquals(i, receiver.telemetry.value.reconnectAttempt)
        }

        // 11th call must stop auto-reconnect
        receiver.scheduleAutoReconnect()
        assertFalse(receiver.isAutoReconnectActive)
        assertFalse(receiver.telemetry.value.isAutoReconnecting)
        assertTrue(receiver.telemetry.value.errorMessage?.contains("stopped after 10 attempts") == true)
    }

    @Test
    fun testAutoReconnect_cancelAndDisconnectResetsActiveFlag() = runTest {
        val receiver = TestableBtReceiver(scope = testScope)
        receiver.connect("AA:BB:CC:DD:EE:FF")
        assertTrue(receiver.isAutoReconnectActive)

        receiver.scheduleAutoReconnect()
        assertEquals(1, receiver.reconnectAttempt)

        receiver.cancelAutoReconnect()
        assertFalse(receiver.isAutoReconnectActive)
        assertEquals(0, receiver.reconnectAttempt)
        assertFalse(receiver.telemetry.value.isAutoReconnecting)

        // Reconnect and test disconnect() cancellation
        receiver.connect("AA:BB:CC:DD:EE:FF")
        assertTrue(receiver.isAutoReconnectActive)
        receiver.disconnect()
        assertFalse(receiver.isAutoReconnectActive)
        assertEquals(0, receiver.reconnectAttempt)
    }
}

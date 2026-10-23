package com.example.sakhi

import android.media.AudioRecord
import com.example.sakhi.data.audio.AudioRecordAdapter
import com.example.sakhi.data.audio.PhoneAudioState
import com.example.sakhi.data.audio.PhoneMicrophoneAudioSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneMicrophoneAudioSourceTest {

    private class FakeAudioRecordAdapter(
        override var state: Int = AudioRecord.STATE_INITIALIZED,
        override var recordingState: Int = AudioRecord.RECORDSTATE_RECORDING,
        private val dataToRead: ByteArray? = null,
        private val errorCodeOnRead: Int? = null,
        private var maxReads: Int = 1
    ) : AudioRecordAdapter {
        var startRecordingCalled = 0
        var stopCalled = 0
        var releaseCalled = 0
        var readCalls = AtomicInteger(0)

        override fun startRecording() {
            startRecordingCalled++
            recordingState = AudioRecord.RECORDSTATE_RECORDING
        }

        override fun stop() {
            stopCalled++
            recordingState = AudioRecord.RECORDSTATE_STOPPED
        }

        override fun release() {
            releaseCalled++
        }

        override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
            readCalls.incrementAndGet()
            if (errorCodeOnRead != null) {
                return errorCodeOnRead
            }
            if (dataToRead != null && maxReads > 0) {
                maxReads--
                val bytesToCopy = minOf(dataToRead.size, sizeInBytes)
                System.arraycopy(dataToRead, 0, audioData, offsetInBytes, bytesToCopy)
                return bytesToCopy
            }
            return 0
        }
    }

    private class TestablePhoneMicrophoneAudioSource(
        scope: kotlinx.coroutines.CoroutineScope,
        var permissionGranted: Boolean = true,
        var minBufferSize8k: Int = 1024,
        var minBufferSize16k: Int = 2048,
        var fakeAdapter: FakeAudioRecordAdapter? = null,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
    ) : PhoneMicrophoneAudioSource(
        context = null,
        scope = scope,
        targetSampleRate = DEFAULT_TARGET_SAMPLE_RATE,
        dispatcher = dispatcher,
        adapterFactory = { sampleRate, _, _, _ ->
            fakeAdapter ?: FakeAudioRecordAdapter()
        }
    ) {
        override fun hasRecordAudioPermission(): Boolean = permissionGranted

        override fun getMinBufferSize(sampleRate: Int): Int {
            return if (sampleRate == 8000) minBufferSize8k else minBufferSize16k
        }

        override fun createAdapter(sampleRate: Int, minBufferSize: Int): AudioRecordAdapter? {
            return fakeAdapter ?: FakeAudioRecordAdapter()
        }
    }

    @Test
    fun testPermissionDenied_transitionsToPermissionDeniedStateAndDoesNotRecord() = runTest {
        val source = TestablePhoneMicrophoneAudioSource(
            scope = backgroundScope,
            permissionGranted = false
        )

        source.startCapture()

        val telemetry = source.telemetry.value
        assertEquals(PhoneAudioState.PERMISSION_DENIED, telemetry.state)
        assertFalse(source.isCapturing)
        assertNotNull(telemetry.errorMessage)
        assertTrue(telemetry.errorMessage!!.contains("permission", ignoreCase = true))
    }

    @Test
    fun testInitializationFailure_whenSampleRatesNotSupported() = runTest {
        val source = TestablePhoneMicrophoneAudioSource(
            scope = backgroundScope,
            permissionGranted = true,
            minBufferSize8k = -1,
            minBufferSize16k = -1
        )

        source.startCapture()

        val telemetry = source.telemetry.value
        assertEquals(PhoneAudioState.INITIALIZATION_FAILED, telemetry.state)
        assertFalse(source.isCapturing)
        assertTrue(telemetry.errorMessage!!.contains("sample rates", ignoreCase = true))
    }

    @Test
    fun testInitializationFailure_whenAdapterStateUninitialized() = runTest {
        val uninitializedAdapter = FakeAudioRecordAdapter(state = AudioRecord.STATE_UNINITIALIZED)
        val source = TestablePhoneMicrophoneAudioSource(
            scope = backgroundScope,
            permissionGranted = true,
            fakeAdapter = uninitializedAdapter
        )

        source.startCapture()

        val telemetry = source.telemetry.value
        assertEquals(PhoneAudioState.INITIALIZATION_FAILED, telemetry.state)
        assertFalse(source.isCapturing)
        assertEquals(1, uninitializedAdapter.releaseCalled)
    }

    @Test
    fun testNative8kRecording_capturesAndEmits8kChunks() = runTest {
        // Prepare 4 bytes of 8 kHz mono 16-bit PCM (2 samples: 1000, 3000)
        val pcmData = ByteArray(4)
        val s0: Short = 1000
        val s1: Short = 3000
        pcmData[0] = (s0.toInt() and 0xFF).toByte()
        pcmData[1] = ((s0.toInt() shr 8) and 0xFF).toByte()
        pcmData[2] = (s1.toInt() and 0xFF).toByte()
        pcmData[3] = ((s1.toInt() shr 8) and 0xFF).toByte()

        val fakeAdapter = FakeAudioRecordAdapter(dataToRead = pcmData)
        val source = TestablePhoneMicrophoneAudioSource(
            scope = backgroundScope,
            fakeAdapter = fakeAdapter,
            minBufferSize8k = 1024,
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        val receivedChunks = mutableListOf<ByteArray>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            source.audioChunkFlow.collect { chunk ->
                receivedChunks.add(chunk)
            }
        }

        source.startCapture()
        testScheduler.advanceTimeBy(100)

        assertTrue("Should have started capturing", source.isCapturing)
        val telemetry = source.telemetry.value
        assertEquals(PhoneAudioState.RECORDING, telemetry.state)
        assertEquals(8000, telemetry.sampleRate)
        assertTrue("Native sample rate should be true", telemetry.isNativeSampleRate)
        assertTrue("Should have read bytes", telemetry.totalBytesRead > 0)
        assertTrue("RMS should be positive", telemetry.currentRms > 0f)

        assertTrue("audioChunkFlow should have received chunks", receivedChunks.isNotEmpty())
        assertEquals(4, receivedChunks[0].size)
        assertArrayEquals(pcmData, receivedChunks[0])

        source.stopCapture()
        assertFalse("Should stop capturing", source.isCapturing)
        assertEquals(PhoneAudioState.STOPPED, source.telemetry.value.state)
        collectJob.cancel()
    }

    @Test
    fun testFallback16kRecording_downsamplesTo8kAccurately() = runTest {
        // 8k buffer size fails (-1), 16k buffer size succeeds (2048)
        // 8 bytes of 16 kHz mono 16-bit PCM (4 samples: 1000, 3000, 4000, 6000)
        val input16k = ByteArray(8)
        val samples = shortArrayOf(1000, 3000, 4000, 6000)
        for (i in samples.indices) {
            val idx = i * 2
            input16k[idx] = (samples[i].toInt() and 0xFF).toByte()
            input16k[idx + 1] = ((samples[i].toInt() shr 8) and 0xFF).toByte()
        }

        val fakeAdapter = FakeAudioRecordAdapter(dataToRead = input16k)
        val source = TestablePhoneMicrophoneAudioSource(
            scope = backgroundScope,
            fakeAdapter = fakeAdapter,
            minBufferSize8k = -1, // Native 8k fails
            minBufferSize16k = 2048, // 16k fallback succeeds
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        val receivedChunks = mutableListOf<ByteArray>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            source.audioChunkFlow.collect { chunk ->
                receivedChunks.add(chunk)
            }
        }

        source.startCapture()
        testScheduler.advanceTimeBy(100)

        val telemetry = source.telemetry.value
        assertEquals(PhoneAudioState.RECORDING, telemetry.state)
        assertEquals(16000, telemetry.sampleRate)
        assertFalse("Native sample rate should be false (downsampled fallback)", telemetry.isNativeSampleRate)

        assertTrue(receivedChunks.isNotEmpty())
        val chunk = receivedChunks[0]
        // 8 bytes @ 16 kHz downsampled 2:1 must yield exactly 4 bytes @ 8 kHz
        assertEquals(4, chunk.size)

        val outS0 = (((chunk[1].toInt() and 0xFF) shl 8) or (chunk[0].toInt() and 0xFF)).toShort()
        val outS1 = (((chunk[3].toInt() and 0xFF) shl 8) or (chunk[2].toInt() and 0xFF)).toShort()

        // (1000 + 3000) / 2 = 2000
        assertEquals(2000.toShort(), outS0)
        // (4000 + 6000) / 2 = 5000
        assertEquals(5000.toShort(), outS1)

        source.stopCapture()
        collectJob.cancel()
    }

    @Test
    fun testDownsample16kTo8k_algorithmVerification() {
        // Direct algorithmic unit test
        val input16k = ByteArray(8)
        val s = shortArrayOf(200, 400, -600, -800)
        for (i in s.indices) {
            input16k[i * 2] = (s[i].toInt() and 0xFF).toByte()
            input16k[i * 2 + 1] = ((s[i].toInt() shr 8) and 0xFF).toByte()
        }

        val out8k = PhoneMicrophoneAudioSource.downsample16kTo8k(input16k)
        assertEquals(4, out8k.size)

        val res0 = (((out8k[1].toInt() and 0xFF) shl 8) or (out8k[0].toInt() and 0xFF)).toShort()
        val res1 = (((out8k[3].toInt() and 0xFF) shl 8) or (out8k[2].toInt() and 0xFF)).toShort()

        assertEquals(300.toShort(), res0)
        assertEquals((-700).toShort(), res1)

        // Odd length handling
        val oddInput = ByteArray(7)
        val outOdd = PhoneMicrophoneAudioSource.downsample16kTo8k(oddInput)
        assertEquals("Truncates to nearest even pair (4 bytes input -> 2 bytes output)", 2, outOdd.size)
    }

    @Test
    fun testRecordingFailure_whenReadReturnsErrorCode() = runTest {
        val fakeAdapter = FakeAudioRecordAdapter(errorCodeOnRead = AudioRecord.ERROR_INVALID_OPERATION)
        val source = TestablePhoneMicrophoneAudioSource(
            scope = backgroundScope,
            fakeAdapter = fakeAdapter,
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        source.startCapture()
        testScheduler.advanceTimeBy(100)

        val telemetry = source.telemetry.value
        assertEquals(PhoneAudioState.RECORDING_FAILED, telemetry.state)
        assertFalse(source.isCapturing)
        assertTrue(telemetry.errorMessage!!.contains("-3"))
    }

    @Test
    fun testRepeatedStartStopAndRelease_cleanLifecycle() = runTest {
        val fakeAdapter = FakeAudioRecordAdapter(dataToRead = ByteArray(64), maxReads = 10)
        val source = TestablePhoneMicrophoneAudioSource(
            scope = backgroundScope,
            fakeAdapter = fakeAdapter,
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        // Cycle 1
        source.startCapture()
        assertTrue(source.isCapturing)
        source.stopCapture()
        assertFalse(source.isCapturing)

        // Cycle 2
        source.startCapture()
        assertTrue(source.isCapturing)
        source.stopCapture()
        assertFalse(source.isCapturing)

        // Release
        source.release()
        assertEquals(PhoneAudioState.IDLE, source.telemetry.value.state)
        assertEquals(0L, source.telemetry.value.totalBytesRead)
    }
}

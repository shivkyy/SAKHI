package com.example.sakhi

import com.example.sakhi.data.audio.LibSpeexDsp
import com.example.sakhi.data.audio.SpeexNoiseSuppressor
import com.example.sakhi.data.audio.SpeexOperationalState
import com.example.sakhi.data.audio.SpeexTelemetry
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeexNoiseSuppressorTest {

    private class FakeLibSpeexDsp(
        var shouldFailInit: Boolean = false,
        var shouldThrowOnInit: Boolean = false,
        var failOnRunAfterN: Int? = null,
        var returnNegativeOnRun: Boolean = false
    ) : LibSpeexDsp {
        var initCount = 0
        var destroyCount = 0
        var runCount = 0
        val ctlCalls = mutableMapOf<Int, Int>()
        var lastFrameProcessed: ShortArray? = null
        val fakePointer = Pointer(12345L)

        override fun speex_preprocess_state_init(frame_size: Int, sampling_rate: Int): Pointer? {
            initCount++
            if (shouldThrowOnInit) throw RuntimeException("Simulated native init exception")
            if (shouldFailInit) return null
            return fakePointer
        }

        override fun speex_preprocess_state_destroy(st: Pointer) {
            destroyCount++
        }

        override fun speex_preprocess_run(st: Pointer, x: ShortArray): Int {
            runCount++
            if (failOnRunAfterN != null && runCount > failOnRunAfterN!!) {
                throw RuntimeException("Simulated native run failure on frame $runCount")
            }
            if (returnNegativeOnRun) {
                return -1
            }
            lastFrameProcessed = x.clone()
            // Test transformation: halve each sample
            for (i in x.indices) {
                x[i] = (x[i] / 2).toShort()
            }
            return 0
        }

        override fun speex_preprocess_ctl(st: Pointer, request: Int, ptr: IntArray): Int {
            ctlCalls[request] = ptr[0]
            return 0
        }
    }

    @Test
    fun testDisabledByDefault_providesExactBitForBitPassthroughAndReportsOff() {
        val fakeLib = FakeLibSpeexDsp()
        val suppressor = SpeexNoiseSuppressor(injectedLib = fakeLib)

        assertFalse("Suppression must be OFF by default", suppressor.isEnabled)
        assertEquals(SpeexOperationalState.OFF, suppressor.operationalState)
        assertEquals(SpeexOperationalState.OFF, suppressor.telemetry.value.state)
        assertEquals(0L, suppressor.telemetry.value.framesSubmitted)
        assertEquals(0L, suppressor.telemetry.value.framesProcessed)
        assertEquals(0L, suppressor.telemetry.value.processingFailures)
        assertEquals(0L, suppressor.telemetry.value.framesForwardedToVosk)
        assertNull(suppressor.telemetry.value.errorMessage)

        val input = ByteArray(512) { (it % 127).toByte() }
        val output = suppressor.process(input)

        // Exact passthrough: identical reference or identical contents, zero runs
        assertArrayEquals(input, output)
        assertEquals(0, fakeLib.runCount)
        assertEquals(0, fakeLib.initCount)
    }

    @Test
    fun testEnableSuppression_transitionsToInitializingThenActiveProcessingOnlyAfterFirstFrame() {
        val fakeLib = FakeLibSpeexDsp()
        val suppressor = SpeexNoiseSuppressor(injectedLib = fakeLib)

        // 1. Enable suppression
        suppressor.isEnabled = true

        // Must report INITIALIZING, NOT ACTIVE_PROCESSING yet
        assertEquals(SpeexOperationalState.INITIALIZING, suppressor.operationalState)
        assertEquals(SpeexOperationalState.INITIALIZING, suppressor.telemetry.value.state)
        assertEquals(0L, suppressor.telemetry.value.framesProcessed)

        // 2. Submit partial chunk (100 bytes < 320 bytes)
        val partialChunk = ByteArray(100) { 1 }
        val out1 = suppressor.process(partialChunk)
        assertEquals(0, out1.size)
        // Must still be INITIALIZING because 0 complete frames have succeeded
        assertEquals(SpeexOperationalState.INITIALIZING, suppressor.operationalState)
        assertEquals(0L, suppressor.telemetry.value.framesProcessed)

        // 3. Submit remaining chunk to complete a 20 ms frame (250 bytes, total 350 bytes >= 320)
        val chunk2 = ByteArray(250) { 2 }
        val out2 = suppressor.process(chunk2)
        assertEquals(320, out2.size)

        // NOW it must transition to ACTIVE_PROCESSING
        assertEquals(SpeexOperationalState.ACTIVE_PROCESSING, suppressor.operationalState)
        assertEquals(SpeexOperationalState.ACTIVE_PROCESSING, suppressor.telemetry.value.state)
        assertEquals(1L, suppressor.telemetry.value.framesSubmitted)
        assertEquals(1L, suppressor.telemetry.value.framesProcessed)
        assertEquals(0L, suppressor.telemetry.value.processingFailures)
        assertEquals(1L, suppressor.telemetry.value.framesForwardedToVosk)
        assertNull(suppressor.telemetry.value.errorMessage)
    }

    @Test
    fun testMissingNativeLibrary_transitionsToFallbackUnavailableAndBypasses() {
        val suppressor = SpeexNoiseSuppressor(injectedLib = null, resolveDefaultLib = false)
        assertFalse("Should report native library not available", suppressor.isNativeAvailable)

        suppressor.isEnabled = true
        assertEquals(SpeexOperationalState.FALLBACK_UNAVAILABLE, suppressor.operationalState)
        assertEquals(SpeexOperationalState.FALLBACK_UNAVAILABLE, suppressor.telemetry.value.state)
        assertEquals("Native library libspeexdsp.so not available", suppressor.telemetry.value.errorMessage)

        val input = ByteArray(640) { (it % 127).toByte() }
        val output = suppressor.process(input)

        // Must safely fall back to passthrough without throwing
        assertArrayEquals(input, output)
        assertEquals(SpeexOperationalState.FALLBACK_UNAVAILABLE, suppressor.operationalState)
    }

    @Test
    fun testNativeInitializationFailure_transitionsToFallbackUnavailable() {
        val fakeLib = FakeLibSpeexDsp(shouldFailInit = true)
        val suppressor = SpeexNoiseSuppressor(injectedLib = fakeLib)

        suppressor.isEnabled = true
        assertEquals(SpeexOperationalState.FALLBACK_UNAVAILABLE, suppressor.operationalState)
        assertEquals("Failed to initialize native SpeexDSP state", suppressor.telemetry.value.errorMessage)

        // Process audio in fallback mode
        val input = ByteArray(320) { 55 }
        val output = suppressor.process(input)
        assertArrayEquals(input, output)
        assertEquals(SpeexOperationalState.FALLBACK_UNAVAILABLE, suppressor.operationalState)
    }

    @Test
    fun testNativeInitializationException_transitionsToFallbackUnavailable() {
        val fakeLib = FakeLibSpeexDsp(shouldThrowOnInit = true)
        val suppressor = SpeexNoiseSuppressor(injectedLib = fakeLib)

        suppressor.isEnabled = true
        assertEquals(SpeexOperationalState.FALLBACK_UNAVAILABLE, suppressor.operationalState)
        assertNotNull(suppressor.telemetry.value.errorMessage)
    }

    @Test
    fun testRuntimeProcessingFailure_transitionsToFallbackUnavailableAndOutputsRawPCM() {
        // Frame 1 succeeds, frame 2 throws RuntimeException
        val fakeLib = FakeLibSpeexDsp(failOnRunAfterN = 1)
        val suppressor = SpeexNoiseSuppressor(injectedLib = fakeLib)
        suppressor.isEnabled = true

        // Frame 1: succeeds
        val frame1 = ByteArray(320) { 10 }
        val out1 = suppressor.process(frame1)
        assertEquals(320, out1.size)
        assertEquals(SpeexOperationalState.ACTIVE_PROCESSING, suppressor.operationalState)
        assertEquals(1L, suppressor.telemetry.value.framesProcessed)
        assertEquals(0L, suppressor.telemetry.value.processingFailures)

        // Frame 2: throws exception
        val frame2 = ByteArray(320) { 20 }
        val out2 = suppressor.process(frame2)
        assertEquals(320, out2.size)

        // Must transition to FALLBACK_UNAVAILABLE and increment failure counter
        assertEquals(SpeexOperationalState.FALLBACK_UNAVAILABLE, suppressor.operationalState)
        assertEquals(1L, suppressor.telemetry.value.processingFailures)
        assertEquals(2L, suppressor.telemetry.value.framesSubmitted)
        assertEquals(1L, suppressor.telemetry.value.framesProcessed)
        assertEquals(1L, suppressor.telemetry.value.framesForwardedToVosk)
        assertNotNull(suppressor.telemetry.value.errorMessage)

        // Output of frame 2 must be raw input fallback (not lost, exact input preserved)
        assertArrayEquals(frame2, out2)

        // Subsequent frame 3: safe bypass without crash
        val frame3 = ByteArray(320) { 30 }
        val out3 = suppressor.process(frame3)
        assertArrayEquals(frame3, out3)
        assertEquals(SpeexOperationalState.FALLBACK_UNAVAILABLE, suppressor.operationalState)
    }

    @Test
    fun testRuntimeProcessingNegativeCode_transitionsToFallbackUnavailable() {
        val fakeLib = FakeLibSpeexDsp(returnNegativeOnRun = true)
        val suppressor = SpeexNoiseSuppressor(injectedLib = fakeLib)
        suppressor.isEnabled = true

        val frame = ByteArray(320) { 7 }
        val out = suppressor.process(frame)
        assertEquals(320, out.size)

        assertEquals(SpeexOperationalState.FALLBACK_UNAVAILABLE, suppressor.operationalState)
        assertEquals(1L, suppressor.telemetry.value.processingFailures)
        assertEquals(0L, suppressor.telemetry.value.framesProcessed)
        // Fallback raw frame returned
        assertArrayEquals(frame, out)
    }

    @Test
    fun testDisablingSuppression_cleanlyReturnsToOffAndResetsActiveCounters() {
        val fakeLib = FakeLibSpeexDsp()
        val suppressor = SpeexNoiseSuppressor(injectedLib = fakeLib)
        suppressor.isEnabled = true

        // Process 3 frames
        suppressor.process(ByteArray(320 * 3))
        assertEquals(SpeexOperationalState.ACTIVE_PROCESSING, suppressor.operationalState)
        assertEquals(3L, suppressor.telemetry.value.framesProcessed)
        assertEquals(3L, suppressor.telemetry.value.framesForwardedToVosk)

        // Disable suppression
        suppressor.isEnabled = false

        // Cleanly returns to OFF and resets active counters
        assertEquals(SpeexOperationalState.OFF, suppressor.operationalState)
        assertEquals(0L, suppressor.telemetry.value.framesSubmitted)
        assertEquals(0L, suppressor.telemetry.value.framesProcessed)
        assertEquals(0L, suppressor.telemetry.value.processingFailures)
        assertEquals(0L, suppressor.telemetry.value.framesForwardedToVosk)
        assertNull(suppressor.telemetry.value.errorMessage)
    }

    @Test
    fun testFallbackWhenNativeLibraryAbsent_returnsExactPassthrough() {
        val suppressor = SpeexNoiseSuppressor(injectedLib = null, resolveDefaultLib = false)
        assertFalse("Should report native library not available", suppressor.isNativeAvailable)

        suppressor.isEnabled = true
        val input = ByteArray(640) { (it % 127).toByte() }
        val output = suppressor.process(input)

        // Must safely fall back to passthrough without throwing
        assertArrayEquals(input, output)
    }

    @Test
    fun testInitializationParameters_configuresDenoiseAgcOffVadOffDereverbOffAndSuppressionLevel() {
        val fakeLib = FakeLibSpeexDsp()
        val suppressor = SpeexNoiseSuppressor(suppressionDbfs = -15, injectedLib = fakeLib)

        suppressor.isEnabled = true

        assertEquals(1, fakeLib.initCount)
        assertEquals(1, fakeLib.ctlCalls[LibSpeexDsp.SPEEX_PREPROCESS_SET_DENOISE])
        assertEquals(0, fakeLib.ctlCalls[LibSpeexDsp.SPEEX_PREPROCESS_SET_AGC])
        assertEquals(0, fakeLib.ctlCalls[LibSpeexDsp.SPEEX_PREPROCESS_SET_VAD])
        assertEquals(0, fakeLib.ctlCalls[LibSpeexDsp.SPEEX_PREPROCESS_SET_DEREVERB])
        assertEquals(-15, fakeLib.ctlCalls[LibSpeexDsp.SPEEX_PREPROCESS_SET_NOISE_SUPPRESS])
    }

    @Test
    fun testVariableSizedChunkFraming_preservesContinuous20msFramesWithoutLossOrReordering() {
        val fakeLib = FakeLibSpeexDsp()
        val suppressor = SpeexNoiseSuppressor(injectedLib = fakeLib)
        suppressor.isEnabled = true

        // 1. Send 100 bytes (< 320 bytes frame) -> returns empty ByteArray
        val chunk1 = ByteArray(100) { 1 }
        val out1 = suppressor.process(chunk1)
        assertEquals(0, out1.size)
        assertEquals(0, fakeLib.runCount)

        // 2. Send 200 bytes (total 300 < 320) -> returns empty ByteArray
        val chunk2 = ByteArray(200) { 2 }
        val out2 = suppressor.process(chunk2)
        assertEquals(0, out2.size)
        assertEquals(0, fakeLib.runCount)

        // 3. Send 50 bytes (total 350 >= 320) -> emits 1 frame (320 bytes), keeps 30 bytes remainder
        val chunk3 = ByteArray(50) { 3 }
        val out3 = suppressor.process(chunk3)
        assertEquals(320, out3.size)
        assertEquals(1, fakeLib.runCount)

        // Verify the 160 samples in the frame correspond to chunk1 (100 bytes = 50 samples)
        // followed by chunk2 (200 bytes = 100 samples) and chunk3 (first 20 bytes = 10 samples)
        val processed = fakeLib.lastFrameProcessed
        assertNotNull(processed)
        assertEquals(160, processed!!.size)

        // First sample should be from chunk1: bytes [1, 1] -> 0x0101 = 257
        val expectedSample0 = ((1 shl 8) or 1).toShort()
        assertEquals(expectedSample0, processed[0])

        // Sample 50 should be from chunk2: bytes [2, 2] -> 0x0202 = 514
        val expectedSample50 = ((2 shl 8) or 2).toShort()
        assertEquals(expectedSample50, processed[50])

        // Sample 150 should be from chunk3: bytes [3, 3] -> 0x0303 = 771
        val expectedSample150 = ((3 shl 8) or 3).toShort()
        assertEquals(expectedSample150, processed[150])
    }

    @Test
    fun testSampleCountConservation_overContinuousStream() {
        val fakeLib = FakeLibSpeexDsp()
        val suppressor = SpeexNoiseSuppressor(injectedLib = fakeLib)
        suppressor.isEnabled = true

        // Stream 50 chunks of 1000 bytes each (50,000 bytes = 25,000 samples)
        var totalOutputBytes = 0
        val chunkSize = 1000
        val numChunks = 50
        val totalInputBytes = chunkSize * numChunks

        for (i in 0 until numChunks) {
            val chunk = ByteArray(chunkSize) { ((i + it) % 127).toByte() }
            val out = suppressor.process(chunk)
            totalOutputBytes += out.size
        }

        // 50,000 / 320 = 156 frames * 320 = 49,920 bytes
        assertEquals(156, fakeLib.runCount)
        assertEquals(49920, totalOutputBytes)

        // Remainder = 50000 - 49920 = 80 bytes (40 samples)
        val flushed = suppressor.flush()
        assertEquals(80, flushed.size)
        totalOutputBytes += flushed.size

        // Total input bytes must exactly equal total output bytes (100% sample conservation)
        assertEquals(totalInputBytes, totalOutputBytes)

        // Verify telemetry counters
        assertEquals(156L, suppressor.telemetry.value.framesSubmitted)
        assertEquals(156L, suppressor.telemetry.value.framesProcessed)
        assertEquals(0L, suppressor.telemetry.value.processingFailures)
        assertEquals(156L, suppressor.telemetry.value.framesForwardedToVosk)
        assertEquals(SpeexOperationalState.ACTIVE_PROCESSING, suppressor.operationalState)
    }

    @Test
    fun testSampleValueTransformation_modifiesSamplesAccurately() {
        val fakeLib = FakeLibSpeexDsp()
        val suppressor = SpeexNoiseSuppressor(injectedLib = fakeLib)
        suppressor.isEnabled = true

        // Create 1 frame of known samples: 1000, 2000, 3000, 4000 ...
        val frameBytes = ByteArray(320)
        for (i in 0 until 160) {
            val s = (i * 100).toShort()
            frameBytes[i * 2] = (s.toInt() and 0xFF).toByte()
            frameBytes[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }

        val out = suppressor.process(frameBytes)
        assertEquals(320, out.size)

        // Read out processed samples; fakeLib halves them: s / 2
        for (i in 0 until 160) {
            val low = out[i * 2].toInt() and 0xFF
            val high = out[i * 2 + 1].toInt()
            val sample = ((high shl 8) or low).toShort()
            val expected = ((i * 100) / 2).toShort()
            assertEquals(expected, sample)
        }
    }

    @Test
    fun testReset_clearsAccumulatorAndFilter() {
        val fakeLib = FakeLibSpeexDsp()
        val suppressor = SpeexNoiseSuppressor(injectedLib = fakeLib)
        suppressor.isEnabled = true

        // Feed 100 bytes (partial frame)
        suppressor.process(ByteArray(100))
        assertEquals(0, fakeLib.runCount)

        // Reset
        suppressor.reset()
        assertTrue("Destroy should be called on reset", fakeLib.destroyCount >= 1)

        // Feed exactly 320 bytes
        val out = suppressor.process(ByteArray(320))
        // Should process exactly 1 frame without being offset by the previous 100 bytes
        assertEquals(320, out.size)
        assertEquals(1, fakeLib.runCount)
    }

    @Test
    fun testToggleOff_resetsStateAndRevertsToPassthrough() {
        val fakeLib = FakeLibSpeexDsp()
        val suppressor = SpeexNoiseSuppressor(injectedLib = fakeLib)
        suppressor.isEnabled = true
        assertEquals(1, fakeLib.initCount)

        suppressor.isEnabled = false
        assertEquals(1, fakeLib.destroyCount)

        // In disabled state, input is passed through directly
        val raw = ByteArray(500) { 42 }
        val out = suppressor.process(raw)
        assertArrayEquals(raw, out)
    }

    @Test
    fun testFlushWhenEmpty_returnsEmptyByteArray() {
        val fakeLib = FakeLibSpeexDsp()
        val suppressor = SpeexNoiseSuppressor(injectedLib = fakeLib)
        suppressor.isEnabled = true

        val flushed = suppressor.flush()
        assertEquals(0, flushed.size)
    }
}

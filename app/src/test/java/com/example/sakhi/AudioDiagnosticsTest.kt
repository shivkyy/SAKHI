package com.example.sakhi

import com.example.sakhi.data.audio.PcmAudioDiagnostics
import com.example.sakhi.data.bluetooth.AudioCircularBuffer
import com.example.sakhi.data.bluetooth.BluetoothClassicAudioReceiver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AudioDiagnosticsTest {

    @Test
    fun testWavHeader_structureAndFormat() {
        val sampleRate = 8000
        val pcmSize = 80000 // 5 seconds of 16-bit mono PCM = 40,000 samples = 80,000 bytes
        val header = PcmAudioDiagnostics.createWavHeader(
            pcmDataSize = pcmSize,
            sampleRate = sampleRate,
            channels = 1,
            bitsPerSample = 16
        )

        // 1. Exact 44-byte canonical RIFF header length
        assertEquals(44, header.size)

        // 2. Validate header fields via parseWavHeader
        val parsed = PcmAudioDiagnostics.parseWavHeader(header)
        assertNotNull(parsed)
        assertTrue("Must be RIFF container", parsed!!.isRiff)
        assertEquals(pcmSize + 36, parsed.fileSizeMinus8)
        assertTrue("Must be WAVE format", parsed.isWave)
        assertTrue("Must have 'fmt ' subchunk", parsed.isFmt)
        assertEquals(16, parsed.subchunk1Size) // Standard PCM subchunk size
        assertEquals(1.toShort(), parsed.audioFormat) // 1 = Linear PCM
        assertEquals(1.toShort(), parsed.numChannels) // 1 = Mono
        assertEquals(sampleRate, parsed.sampleRate) // 8000 Hz
        assertEquals(16000, parsed.byteRate) // 8000 * 1 * (16 / 8) = 16000
        assertEquals(2.toShort(), parsed.blockAlign) // 1 * (16 / 8) = 2
        assertEquals(16.toShort(), parsed.bitsPerSample) // 16 bits
        assertTrue("Must have 'data' subchunk", parsed.isData)
        assertEquals(pcmSize, parsed.dataSize)
    }

    @Test
    fun testWavBytes_enforces16BitSampleAlignment() {
        // Create an odd number of bytes: 173 bytes = 86 complete 16-bit samples + 1 dangling byte
        val oddPcm = ByteArray(173) { (it % 100).toByte() }
        val wavBytes = PcmAudioDiagnostics.createWavBytes(oddPcm, sampleRate = 8000)

        // Total WAV length must be 44 (header) + 172 (even PCM length, 1 dangling byte truncated)
        assertEquals(44 + 172, wavBytes.size)

        val parsed = PcmAudioDiagnostics.parseWavHeader(wavBytes)
        assertNotNull(parsed)
        assertEquals(172, parsed!!.dataSize)
        assertEquals(172 + 36, parsed.fileSizeMinus8)
    }

    @Test
    fun testDurationCalculation_exact5Seconds() {
        // 40,000 samples @ 8000 Hz = 80,000 bytes = exactly 5.0 seconds
        val pcm5s = ByteArray(80000)
        val metrics = PcmAudioDiagnostics.analyzePcm16(pcm5s, sampleRate = 8000)

        assertEquals(40000, metrics.sampleCount)
        assertEquals(5.0f, metrics.durationSeconds, 0.001f)
    }

    @Test
    fun testAudioMetrics_silence() {
        val silencePcm = ByteArray(1600) { 0 } // 800 zero samples (0.1s)
        val metrics = PcmAudioDiagnostics.analyzePcm16(silencePcm, sampleRate = 8000)

        assertEquals(800, metrics.sampleCount)
        assertEquals(0, metrics.peakAmplitude)
        assertEquals(0.0f, metrics.peakPercent, 0.001f)
        assertEquals(-96.0f, metrics.rmsDbfs, 0.001f) // Digital silence
        assertEquals(0, metrics.clippingCount)
        assertEquals(0.0f, metrics.clippingPercent, 0.001f)
        assertEquals(0.0f, metrics.dcOffset, 0.001f)
    }

    @Test
    fun testAudioMetrics_fullScaleClippedSignal() {
        // Generate full-scale positive values (+32767)
        val fullScale = ByteArray(200)
        for (i in fullScale.indices step 2) {
            val s: Short = 32767
            fullScale[i] = (s.toInt() and 0xFF).toByte()
            fullScale[i + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }

        val metrics = PcmAudioDiagnostics.analyzePcm16(fullScale, sampleRate = 8000)
        assertEquals(100, metrics.sampleCount)
        assertEquals(32767, metrics.peakAmplitude)
        assertTrue("Peak percent must be ~100%", metrics.peakPercent > 99.9f)
        assertEquals(0.0f, metrics.rmsDbfs, 0.05f) // 32767 is 0 dBFS
        assertEquals(100, metrics.clippingCount)
        assertEquals(100.0f, metrics.clippingPercent, 0.01f)
        assertEquals(32767.0f, metrics.dcOffset, 0.1f)
    }

    @Test
    fun testAudioMetrics_halfScaleSquareWaveAndDCOffset() {
        // Generate alternating +16384 and -16384 (DC offset = 0, Peak = 16384 = 50% FS, RMS = -6.02 dBFS)
        val squareWave = ByteArray(400)
        for (i in 0 until 400 step 4) {
            // Sample 0: +16384
            val s0: Short = 16384
            squareWave[i] = (s0.toInt() and 0xFF).toByte()
            squareWave[i + 1] = ((s0.toInt() shr 8) and 0xFF).toByte()
            // Sample 1: -16384
            val s1: Short = -16384
            squareWave[i + 2] = (s1.toInt() and 0xFF).toByte()
            squareWave[i + 3] = ((s1.toInt() shr 8) and 0xFF).toByte()
        }

        val metrics = PcmAudioDiagnostics.analyzePcm16(squareWave, sampleRate = 8000)
        assertEquals(200, metrics.sampleCount)
        assertEquals(16384, metrics.peakAmplitude)
        assertEquals(50.0f, metrics.peakPercent, 0.01f)
        // 16384 is exactly half of 32768, which is 20 * log10(0.5) = -6.02 dBFS
        assertEquals(-6.02f, metrics.rmsDbfs, 0.05f)
        assertEquals(0, metrics.clippingCount)
        assertEquals(0.0f, metrics.dcOffset, 0.01f)
    }

    @Test
    fun testAudioCircularBuffer_snapshotRecentGuaranteesEvenByteSamples() {
        val circularBuffer = AudioCircularBuffer(capacity = 1000)
        val data = ByteArray(100) { (it + 1).toByte() }
        circularBuffer.write(data)

        // Request 30 bytes from recent buffer
        val snapshot30 = circularBuffer.snapshotRecent(30)
        assertEquals(30, snapshot30.size)
        // Must contain the last 30 bytes: 71..100
        assertEquals(71.toByte(), snapshot30[0])
        assertEquals(100.toByte(), snapshot30[29])

        // Request an ODD maxBytes: 15 bytes
        val snapshotOdd = circularBuffer.snapshotRecent(15)
        // Must truncate to even 14 bytes (7 complete 16-bit samples)
        assertEquals(14, snapshotOdd.size)
        assertEquals(87.toByte(), snapshotOdd[0])
        assertEquals(100.toByte(), snapshotOdd[13])
    }

    @Test
    fun testBluetoothReceiver_oddByteStreamPreserves16BitAlignment() = runTest {
        val receiver = BluetoothClassicAudioReceiver(scope = backgroundScope)
        val receivedChunks = mutableListOf<ByteArray>()

        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            receiver.audioChunkFlow.collect { chunk ->
                receivedChunks.add(chunk)
            }
        }

        // 1. Simulate an ODD read from RFCOMM socket (5 bytes: [10, 20, 30, 40, 50])
        val oddData1 = byteArrayOf(10, 20, 30, 40, 50)
        receiver.processInputStream(ByteArrayInputStream(oddData1))

        // Only 4 bytes (2 complete samples) must be emitted; byte 50 held as leftover
        assertEquals(1, receivedChunks.size)
        assertEquals(4, receivedChunks[0].size)
        assertArrayEquals(byteArrayOf(10, 20, 30, 40), receivedChunks[0])

        // 2. Simulate next read (3 bytes: [60, 70, 80])
        // Leftover byte 50 + 60, 70, 80 = 4 bytes (2 complete samples: [50, 60, 70, 80])
        val oddData2 = byteArrayOf(60, 70, 80)
        receiver.processInputStream(ByteArrayInputStream(oddData2))

        assertEquals(2, receivedChunks.size)
        assertEquals(4, receivedChunks[1].size)
        assertArrayEquals(byteArrayOf(50, 60, 70, 80), receivedChunks[1])

        collectJob.cancel()
    }
}

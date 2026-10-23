package com.example.sakhi.data.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.example.sakhi.data.bluetooth.BluetoothClassicAudioReceiver
import com.example.sakhi.data.bluetooth.BtAudioConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Objective acoustic metrics computed from raw 16-bit linear PCM audio.
 */
data class AudioDiagnosticMetrics(
    val sampleCount: Int,
    val durationSeconds: Float,
    val peakAmplitude: Int,
    val peakPercent: Float,
    val rmsDbfs: Float,
    val clippingCount: Int,
    val clippingPercent: Float,
    val dcOffset: Float,
    val dcOffsetPercent: Float,
    val filePath: String?,
    val fileSize: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Parsed fields from a standard 44-byte RIFF/WAVE header.
 */
data class WavHeaderInfo(
    val isRiff: Boolean,
    val fileSizeMinus8: Int,
    val isWave: Boolean,
    val isFmt: Boolean,
    val subchunk1Size: Int,
    val audioFormat: Short,
    val numChannels: Short,
    val sampleRate: Int,
    val byteRate: Int,
    val blockAlign: Short,
    val bitsPerSample: Short,
    val isData: Boolean,
    val dataSize: Int
)

/**
 * Reactive states for the diagnostic recording workflow.
 */
sealed class AudioDiagnosticState {
    object Idle : AudioDiagnosticState()
    data class Recording(
        val elapsedMs: Long,
        val targetMs: Long,
        val capturedSamples: Int,
        val targetSamples: Int
    ) : AudioDiagnosticState()
    data class Completed(
        val metrics: AudioDiagnosticMetrics,
        val wavFile: File
    ) : AudioDiagnosticState()
    data class Error(val message: String) : AudioDiagnosticState()
}

/**
 * Diagnostic Audio Utilities:
 * - Creates 44-byte canonical RIFF/WAVE files from raw 16-bit PCM.
 * - Computes truthful acoustic metrics: Peak, RMS (dBFS), Clipping %, DC offset, sample count.
 * - Coordinates exact 5.0-second live recordings from the connected ESP32 microphone.
 */
object PcmAudioDiagnostics {

    private const val TAG = "PcmAudioDiagnostics"

    /**
     * Builds a standard 44-byte RIFF/WAVE header for linear PCM audio.
     */
    fun createWavHeader(
        pcmDataSize: Int,
        sampleRate: Int = 8000,
        channels: Short = 1,
        bitsPerSample: Short = 16
    ): ByteArray {
        val totalDataLen = pcmDataSize + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()

        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk descriptor
        buffer.put('R'.code.toByte())
        buffer.put('I'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.putInt(totalDataLen)
        buffer.put('W'.code.toByte())
        buffer.put('A'.code.toByte())
        buffer.put('V'.code.toByte())
        buffer.put('E'.code.toByte())

        // "fmt " sub-chunk
        buffer.put('f'.code.toByte())
        buffer.put('m'.code.toByte())
        buffer.put('t'.code.toByte())
        buffer.put(' '.code.toByte())
        buffer.putInt(16) // Subchunk1Size for PCM
        buffer.putShort(1) // AudioFormat 1 = Linear PCM
        buffer.putShort(channels)
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign)
        buffer.putShort(bitsPerSample)

        // "data" sub-chunk
        buffer.put('d'.code.toByte())
        buffer.put('a'.code.toByte())
        buffer.put('t'.code.toByte())
        buffer.put('a'.code.toByte())
        buffer.putInt(pcmDataSize)

        return header
    }

    /**
     * Combines a canonical 44-byte WAV header with raw 16-bit PCM bytes.
     * Guarantees even-byte length (complete 16-bit samples).
     */
    fun createWavBytes(
        pcmData: ByteArray,
        sampleRate: Int = 8000,
        channels: Short = 1,
        bitsPerSample: Short = 16
    ): ByteArray {
        val evenLength = pcmData.size - (pcmData.size % 2)
        val header = createWavHeader(evenLength, sampleRate, channels, bitsPerSample)
        val result = ByteArray(44 + evenLength)
        System.arraycopy(header, 0, result, 0, 44)
        System.arraycopy(pcmData, 0, result, 44, evenLength)
        return result
    }

    /**
     * Saves raw 16-bit PCM bytes into a valid canonical WAV file on disk.
     */
    fun createWavFile(
        pcmData: ByteArray,
        sampleRate: Int = 8000,
        outputFile: File
    ): File {
        val evenLength = pcmData.size - (pcmData.size % 2)
        outputFile.parentFile?.mkdirs()

        FileOutputStream(outputFile).use { fos ->
            val header = createWavHeader(evenLength, sampleRate)
            fos.write(header)
            fos.write(pcmData, 0, evenLength)
            fos.flush()
        }
        return outputFile
    }

    /**
     * Parses and validates a standard 44-byte WAV header.
     */
    fun parseWavHeader(wavBytes: ByteArray): WavHeaderInfo? {
        if (wavBytes.size < 44) return null
        val buffer = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)

        val riff = String(ByteArray(4) { buffer.get() })
        val fileSizeMinus8 = buffer.getInt()
        val wave = String(ByteArray(4) { buffer.get() })
        val fmt = String(ByteArray(4) { buffer.get() })
        val subchunk1Size = buffer.getInt()
        val audioFormat = buffer.getShort()
        val numChannels = buffer.getShort()
        val sampleRate = buffer.getInt()
        val byteRate = buffer.getInt()
        val blockAlign = buffer.getShort()
        val bitsPerSample = buffer.getShort()
        val data = String(ByteArray(4) { buffer.get() })
        val dataSize = buffer.getInt()

        return WavHeaderInfo(
            isRiff = riff == "RIFF",
            fileSizeMinus8 = fileSizeMinus8,
            isWave = wave == "WAVE",
            isFmt = fmt == "fmt ",
            subchunk1Size = subchunk1Size,
            audioFormat = audioFormat,
            numChannels = numChannels,
            sampleRate = sampleRate,
            byteRate = byteRate,
            blockAlign = blockAlign,
            bitsPerSample = bitsPerSample,
            isData = data == "data",
            dataSize = dataSize
        )
    }

    /**
     * Calculates acoustic metrics from 16-bit signed little-endian PCM samples:
     * - Sample Count & Duration
     * - Peak Amplitude & % of full scale (32768)
     * - RMS Amplitude in dBFS
     * - Clipping Count & %
     * - DC Offset & %
     */
    fun analyzePcm16(
        pcmData: ByteArray,
        sampleRate: Int = 8000,
        filePath: String? = null,
        fileSize: Long = 0L
    ): AudioDiagnosticMetrics {
        val evenLength = pcmData.size - (pcmData.size % 2)
        val sampleCount = evenLength / 2

        if (sampleCount <= 0) {
            return AudioDiagnosticMetrics(
                sampleCount = 0,
                durationSeconds = 0f,
                peakAmplitude = 0,
                peakPercent = 0f,
                rmsDbfs = -96.0f,
                clippingCount = 0,
                clippingPercent = 0f,
                dcOffset = 0f,
                dcOffsetPercent = 0f,
                filePath = filePath,
                fileSize = fileSize
            )
        }

        var peak = 0
        var sumSquares = 0.0
        var sum = 0.0
        var clippingCount = 0

        for (i in 0 until evenLength step 2) {
            // Little-endian signed 16-bit PCM sample
            val s = (((pcmData[i + 1].toInt() and 0xFF) shl 8) or (pcmData[i].toInt() and 0xFF)).toShort()
            val absS = if (s == Short.MIN_VALUE) 32768 else kotlin.math.abs(s.toInt())

            if (absS > peak) {
                peak = absS
            }
            if (absS >= 32767) {
                clippingCount++
            }
            sum += s.toDouble()
            sumSquares += (s.toDouble() * s.toDouble())
        }

        val duration = sampleCount.toFloat() / sampleRate
        val meanSquare = sumSquares / sampleCount
        val rawRms = sqrt(meanSquare)
        val rmsDbfs = if (rawRms > 0.0) {
            (20.0 * log10(rawRms / 32768.0)).toFloat().coerceIn(-96f, 0f)
        } else {
            -96.0f
        }

        val dcOffset = (sum / sampleCount).toFloat()
        val peakPercent = (peak.toFloat() / 32768f) * 100f
        val clippingPercent = (clippingCount.toFloat() / sampleCount) * 100f
        val dcOffsetPercent = (dcOffset / 32768f) * 100f

        return AudioDiagnosticMetrics(
            sampleCount = sampleCount,
            durationSeconds = duration,
            peakAmplitude = peak,
            peakPercent = peakPercent,
            rmsDbfs = rmsDbfs,
            clippingCount = clippingCount,
            clippingPercent = clippingPercent,
            dcOffset = dcOffset,
            dcOffsetPercent = dcOffsetPercent,
            filePath = filePath,
            fileSize = fileSize
        )
    }
}

/**
 * Diagnostic Recording Manager:
 * Manages live 5.0-second PCM recordings from the connected ESP32 microphone over Bluetooth SPP.
 * Emits reactive states (Idle, Recording, Completed, Error) to the Compose UI.
 */
class AudioDiagnosticRecorder(
    private val context: Context?,
    private val audioReceiver: BluetoothClassicAudioReceiver?,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AudioDiagnosticRecorder"
        const val DEFAULT_SAMPLE_RATE = 8000
        const val DEFAULT_DURATION_SECONDS = 5
        // 5 seconds @ 8000 Hz, 16-bit mono = 40,000 samples = 80,000 bytes
        const val TARGET_SAMPLES = DEFAULT_SAMPLE_RATE * DEFAULT_DURATION_SECONDS
        const val TARGET_BYTES = TARGET_SAMPLES * 2
    }

    private val _state = MutableStateFlow<AudioDiagnosticState>(AudioDiagnosticState.Idle)
    val state: StateFlow<AudioDiagnosticState> = _state.asStateFlow()

    private var activeRecordingJob: Job? = null

    /**
     * Records exactly 5 seconds of live PCM audio from the connected ESP32 microphone.
     */
    fun recordLive(durationSeconds: Int = DEFAULT_DURATION_SECONDS) {
        val receiver = audioReceiver
        if (receiver == null) {
            _state.value = AudioDiagnosticState.Error("Bluetooth audio receiver is not available.")
            return
        }

        val telemetry = receiver.telemetry.value
        val isConnected = telemetry.state == BtAudioConnectionState.CONNECTED ||
                          telemetry.state == BtAudioConnectionState.STREAMING

        if (!isConnected) {
            _state.value = AudioDiagnosticState.Error("ESP32 is not connected. Tap 'Connect to SAKHI_AUDIO' first.")
            return
        }

        activeRecordingJob?.cancel()
        activeRecordingJob = scope.launch(Dispatchers.Default) {
            val targetSamples = durationSeconds * DEFAULT_SAMPLE_RATE
            val targetBytes = targetSamples * 2
            val pcmAccumulator = ByteArrayOutputStream(targetBytes)

            _state.value = AudioDiagnosticState.Recording(
                elapsedMs = 0L,
                targetMs = (durationSeconds * 1000).toLong(),
                capturedSamples = 0,
                targetSamples = targetSamples
            )

            val startTime = System.currentTimeMillis()
            val maxTimeoutMs = (durationSeconds * 1000L) + 2000L // 2s grace period for socket buffering

            try {
                // Collect live chunks from audioChunkFlow until targetBytes is reached
                val chunkCollector = launch {
                    receiver.audioChunkFlow.collect { chunk ->
                        if (!isActive) return@collect
                        synchronized(pcmAccumulator) {
                            if (pcmAccumulator.size() < targetBytes) {
                                val needed = targetBytes - pcmAccumulator.size()
                                val bytesToWrite = minOf(chunk.size, needed)
                                pcmAccumulator.write(chunk, 0, bytesToWrite)
                            }
                        }
                    }
                }

                // Progress update ticker
                while (isActive && (System.currentTimeMillis() - startTime) < maxTimeoutMs) {
                    val currentBytes = synchronized(pcmAccumulator) { pcmAccumulator.size() }
                    val currentSamples = currentBytes / 2
                    val elapsed = System.currentTimeMillis() - startTime

                    _state.value = AudioDiagnosticState.Recording(
                        elapsedMs = elapsed,
                        targetMs = (durationSeconds * 1000).toLong(),
                        capturedSamples = currentSamples,
                        targetSamples = targetSamples
                    )

                    if (currentBytes >= targetBytes) {
                        break
                    }
                    delay(100)
                }

                chunkCollector.cancel()

                val rawPcm = synchronized(pcmAccumulator) { pcmAccumulator.toByteArray() }
                val evenLength = rawPcm.size - (rawPcm.size % 2)

                if (evenLength < 1600) { // Less than 0.1 second of audio captured
                    _state.value = AudioDiagnosticState.Error("Insufficient audio received. Ensure ESP32 is powered and streaming.")
                    return@launch
                }

                val finalPcm = if (evenLength > targetBytes) rawPcm.copyOf(targetBytes) else rawPcm.copyOf(evenLength)

                // Save to app external files dir or internal files dir
                val baseDir = context?.getExternalFilesDir(null) ?: context?.filesDir ?: File("/sdcard")
                val wavFile = File(baseDir, "sakhi_esp32_mic_diagnostic.wav")

                PcmAudioDiagnostics.createWavFile(finalPcm, DEFAULT_SAMPLE_RATE, wavFile)

                val metrics = PcmAudioDiagnostics.analyzePcm16(
                    pcmData = finalPcm,
                    sampleRate = DEFAULT_SAMPLE_RATE,
                    filePath = wavFile.absolutePath,
                    fileSize = wavFile.length()
                )

                _state.value = AudioDiagnosticState.Completed(metrics, wavFile)
                Log.i(TAG, "5-second WAV capture complete: ${wavFile.absolutePath} (${metrics.sampleCount} samples, Peak: ${metrics.peakAmplitude}, RMS: ${metrics.rmsDbfs} dBFS)")

            } catch (e: Exception) {
                Log.e(TAG, "Error during live diagnostic recording", e)
                _state.value = AudioDiagnosticState.Error("Recording failed: ${e.message}")
            }
        }
    }

    /**
     * Grabs the most recent 5 seconds of audio already in the circular buffer without waiting.
     */
    fun snapshotFromBuffer(durationSeconds: Int = DEFAULT_DURATION_SECONDS) {
        val receiver = audioReceiver
        if (receiver == null) {
            _state.value = AudioDiagnosticState.Error("Bluetooth audio receiver is not available.")
            return
        }

        val targetSamples = durationSeconds * DEFAULT_SAMPLE_RATE
        val targetBytes = targetSamples * 2
        val snapshotBytes = receiver.circularBuffer.snapshotRecent(targetBytes)

        if (snapshotBytes.size < 1600) {
            _state.value = AudioDiagnosticState.Error("Buffer does not contain enough audio yet. Connect ESP32 and stream for 5 seconds.")
            return
        }

        try {
            val baseDir = context?.getExternalFilesDir(null) ?: context?.filesDir ?: File("/sdcard")
            val wavFile = File(baseDir, "sakhi_esp32_mic_diagnostic.wav")

            PcmAudioDiagnostics.createWavFile(snapshotBytes, DEFAULT_SAMPLE_RATE, wavFile)

            val metrics = PcmAudioDiagnostics.analyzePcm16(
                pcmData = snapshotBytes,
                sampleRate = DEFAULT_SAMPLE_RATE,
                filePath = wavFile.absolutePath,
                fileSize = wavFile.length()
            )

            _state.value = AudioDiagnosticState.Completed(metrics, wavFile)
            Log.i(TAG, "Buffer snapshot WAV capture complete: ${wavFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving buffer snapshot WAV", e)
            _state.value = AudioDiagnosticState.Error("Failed to save snapshot: ${e.message}")
        }
    }

    /**
     * Exports the recorded WAV file using Android's system share sheet (AirDrop, Gmail, Files, etc.).
     */
    fun shareWavFile(activityContext: Context, file: File) {
        if (!file.exists()) {
            _state.value = AudioDiagnosticState.Error("WAV file does not exist at: ${file.absolutePath}")
            return
        }

        try {
            val uri: Uri = FileProvider.getUriForFile(
                activityContext,
                "${activityContext.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "SAKHI ESP32 Microphone Diagnostic Recording")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "SAKHI ESP32 INMP441 Microphone Diagnostic Recording (5s WAV, 8000Hz 16-bit Mono PCM).\nFile: ${file.name}"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            activityContext.startActivity(
                Intent.createChooser(shareIntent, "Export SAKHI Diagnostic WAV to Mac / Drive")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share WAV file", e)
            _state.value = AudioDiagnosticState.Error("Failed to open share sheet: ${e.message}")
        }
    }

    fun reset() {
        activeRecordingJob?.cancel()
        activeRecordingJob = null
        _state.value = AudioDiagnosticState.Idle
    }
}

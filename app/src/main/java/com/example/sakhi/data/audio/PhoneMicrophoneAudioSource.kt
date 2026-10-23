package com.example.sakhi.data.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Operational states of the phone microphone audio capture source.
 */
enum class PhoneAudioState {
    IDLE,
    STARTING,
    RECORDING,
    PERMISSION_DENIED,
    INITIALIZATION_FAILED,
    RECORDING_FAILED,
    STOPPED
}

/**
 * Telemetry snapshot for the phone microphone capture source.
 */
data class PhoneAudioTelemetry(
    val state: PhoneAudioState = PhoneAudioState.IDLE,
    val sampleRate: Int = 0,
    val channelCount: Int = 1,
    val audioFormat: String = "PCM_16BIT",
    val isNativeSampleRate: Boolean = true,
    val isCapturing: Boolean = false,
    val totalBytesRead: Long = 0L,
    val currentRms: Float = 0f,
    val errorMessage: String? = null
)

/**
 * Abstraction for AudioRecord operations to enable deterministic unit testing without Android hardware.
 */
interface AudioRecordAdapter {
    val state: Int
    val recordingState: Int
    fun startRecording()
    fun stop()
    fun release()
    fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int
}

/**
 * Interface contract for the phone microphone audio capture source.
 */
interface IPhoneMicrophoneAudioSource {
    val audioChunkFlow: SharedFlow<ByteArray>
    val telemetry: StateFlow<PhoneAudioTelemetry>
    val isCapturing: Boolean

    fun startCapture()
    fun stopCapture()
    fun release()
}

/**
 * Production phone microphone PCM capture source using Android AudioRecord.
 *
 * Guarantees output of mono, signed 16-bit little-endian PCM at 8,000 Hz, matching the
 * ESP32 Bluetooth Classic SPP stream format.
 *
 * Requests 8,000 Hz natively. If the physical hardware does not support 8,000 Hz, it falls
 * back to 16,000 Hz and performs decimation downsampling (16 kHz -> 8 kHz) before emission.
 *
 * Thread-safe, non-leaking, and verifies permission and hardware state before reporting active.
 */
open class PhoneMicrophoneAudioSource(
    private val context: Context? = null,
    private val scope: CoroutineScope,
    val targetSampleRate: Int = DEFAULT_TARGET_SAMPLE_RATE,
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val adapterFactory: ((sampleRate: Int, channelConfig: Int, audioFormat: Int, minBufferSize: Int) -> AudioRecordAdapter?)? = null
) : IPhoneMicrophoneAudioSource {

    companion object {
        const val DEFAULT_TARGET_SAMPLE_RATE = 8000
        const val FALLBACK_SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /**
         * Downsamples 16 kHz mono 16-bit little-endian PCM to 8 kHz mono 16-bit LE PCM
         * by averaging adjacent sample pairs (2:1 boxcar decimation).
         */
        fun downsample16kTo8k(input16k: ByteArray, length: Int = input16k.size): ByteArray {
            val validLen = length - (length % 4)
            if (validLen <= 0) return ByteArray(0)
            val sampleCount8k = validLen / 4
            val output8k = ByteArray(sampleCount8k * 2)

            for (i in 0 until sampleCount8k) {
                val inIdx0 = i * 4
                val inIdx1 = inIdx0 + 2
                val s0 = ((input16k[inIdx0 + 1].toInt() shl 8) or (input16k[inIdx0].toInt() and 0xFF)).toShort()
                val s1 = ((input16k[inIdx1 + 1].toInt() shl 8) or (input16k[inIdx1].toInt() and 0xFF)).toShort()
                val avg = ((s0.toInt() + s1.toInt()) / 2).toShort()

                val outIdx = i * 2
                output8k[outIdx] = (avg.toInt() and 0xFF).toByte()
                output8k[outIdx + 1] = ((avg.toInt() shr 8) and 0xFF).toByte()
            }
            return output8k
        }

        /**
         * Calculates Root Mean Square (RMS) volume level assuming 16-bit signed LE PCM.
         * Returns a normalized float between 0.0f and 1.0f (perceptually scaled).
         */
        fun computePcmRms(data: ByteArray, length: Int): Float {
            val sampleCount = length / 2
            if (sampleCount <= 0) return 0f

            var sumSquares = 0.0
            for (i in 0 until length - 1 step 2) {
                val sample = (((data[i + 1].toInt() and 0xFF) shl 8) or (data[i].toInt() and 0xFF)).toShort()
                sumSquares += (sample * sample).toDouble()
            }
            val meanSquare = sumSquares / sampleCount
            val rawRms = sqrt(meanSquare).toFloat()
            return sqrt((rawRms / 16384f).coerceIn(0f, 1f))
        }
    }

    private val lock = Any()

    private val _telemetry = MutableStateFlow(PhoneAudioTelemetry())
    override val telemetry: StateFlow<PhoneAudioTelemetry> = _telemetry.asStateFlow()

    private val _audioChunkFlow = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val audioChunkFlow: SharedFlow<ByteArray> = _audioChunkFlow.asSharedFlow()

    override val isCapturing: Boolean
        get() = _telemetry.value.isCapturing

    private var audioRecordAdapter: AudioRecordAdapter? = null
    private var recordingJob: Job? = null

    /**
     * Checks if the app has RECORD_AUDIO permission.
     */
    open fun hasRecordAudioPermission(): Boolean {
        val ctx = context ?: return true
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Creates an AudioRecordAdapter.
     */
    @SuppressLint("MissingPermission")
    protected open fun createAdapter(sampleRate: Int, minBufferSize: Int): AudioRecordAdapter? {
        if (adapterFactory != null) {
            return adapterFactory.invoke(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT, minBufferSize)
        }
        return try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize * 2
            )
            object : AudioRecordAdapter {
                override val state: Int get() = record.state
                override val recordingState: Int get() = record.recordingState
                override fun startRecording() = record.startRecording()
                override fun stop() {
                    try { record.stop() } catch (_: Exception) {}
                }
                override fun release() {
                    try { record.release() } catch (_: Exception) {}
                }
                override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int =
                    record.read(audioData, offsetInBytes, sizeInBytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Determines minimum buffer size for a given sample rate.
     */
    protected open fun getMinBufferSize(sampleRate: Int): Int {
        return try {
            AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * Starts phone microphone capture.
     * Guaranteed thread-safe, idempotent, and non-blocking.
     */
    override fun startCapture() {
        synchronized(lock) {
            if (_telemetry.value.isCapturing) return

            _telemetry.update {
                it.copy(
                    state = PhoneAudioState.STARTING,
                    errorMessage = null
                )
            }

            // 1. Verify Permission
            if (!hasRecordAudioPermission()) {
                _telemetry.update {
                    it.copy(
                        state = PhoneAudioState.PERMISSION_DENIED,
                        isCapturing = false,
                        errorMessage = "RECORD_AUDIO permission not granted"
                    )
                }
                return
            }

            // 2. Negotiate Sample Rate (prefer native 8000 Hz, fallback to 16000 Hz with downsampling)
            var actualSampleRate = targetSampleRate
            var isNative = true
            var minBufSize = getMinBufferSize(actualSampleRate)

            if (minBufSize <= 0) {
                // Try fallback sample rate
                actualSampleRate = FALLBACK_SAMPLE_RATE
                isNative = false
                minBufSize = getMinBufferSize(actualSampleRate)
            }

            if (minBufSize <= 0) {
                _telemetry.update {
                    it.copy(
                        state = PhoneAudioState.INITIALIZATION_FAILED,
                        isCapturing = false,
                        errorMessage = "AudioRecord does not support required sample rates (8000Hz or 16000Hz)"
                    )
                }
                return
            }

            // 3. Initialize AudioRecord
            val adapter = createAdapter(actualSampleRate, minBufSize)
            if (adapter == null || adapter.state != AudioRecord.STATE_INITIALIZED) {
                adapter?.release()
                _telemetry.update {
                    it.copy(
                        state = PhoneAudioState.INITIALIZATION_FAILED,
                        isCapturing = false,
                        errorMessage = "AudioRecord failed to initialize (state=${adapter?.state})"
                    )
                }
                return
            }

            audioRecordAdapter = adapter

            try {
                adapter.startRecording()
                if (adapter.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("AudioRecord failed to transition to RECORDSTATE_RECORDING (state=${adapter.recordingState})")
                }
            } catch (e: Exception) {
                stopCaptureInternal()
                _telemetry.update {
                    it.copy(
                        state = PhoneAudioState.INITIALIZATION_FAILED,
                        isCapturing = false,
                        errorMessage = "AudioRecord startRecording failed: ${e.message}"
                    )
                }
                return
            }

            _telemetry.update {
                it.copy(
                    state = PhoneAudioState.RECORDING,
                    sampleRate = actualSampleRate,
                    channelCount = 1,
                    audioFormat = "PCM_16BIT",
                    isNativeSampleRate = isNative,
                    isCapturing = true,
                    errorMessage = null
                )
            }

            // 4. Launch Background Reading Loop
            recordingJob = scope.launch(dispatcher) {
                processCaptureLoop(adapter, isNative)
            }
        }
    }

    /**
     * Reads audio frames in a background loop, normalizes to 8000 Hz 16-bit PCM,
     * computes RMS telemetry, and emits chunks.
     */
    private suspend fun processCaptureLoop(adapter: AudioRecordAdapter, isNative: Boolean) {
        val rawBufferSize = 640 // 20 ms at 16 kHz or 40 ms at 8 kHz (multiple of 16-bit samples)
        val readBuffer = ByteArray(rawBufferSize)
        var totalBytes = _telemetry.value.totalBytesRead

        try {
            while (scope.isActive && _telemetry.value.isCapturing) {
                val bytesRead = adapter.read(readBuffer, 0, readBuffer.size)

                if (bytesRead < 0) {
                    // AudioRecord error code
                    synchronized(lock) {
                        _telemetry.update {
                            it.copy(
                                state = PhoneAudioState.RECORDING_FAILED,
                                isCapturing = false,
                                errorMessage = "AudioRecord.read failed with error code $bytesRead"
                            )
                        }
                    }
                    break
                }

                if (bytesRead > 0) {
                    // Guarantee strictly even length (complete 16-bit samples)
                    val evenLength = bytesRead - (bytesRead % 2)
                    if (evenLength > 0) {
                        // Normalize to 8000 Hz mono 16-bit LE PCM
                        val pcm8kChunk = if (isNative) {
                            if (evenLength == readBuffer.size) {
                                readBuffer.copyOf()
                            } else {
                                readBuffer.copyOf(evenLength)
                            }
                        } else {
                            downsample16kTo8k(readBuffer, evenLength)
                        }

                        if (pcm8kChunk.isNotEmpty()) {
                            totalBytes += pcm8kChunk.size
                            val rms = computePcmRms(pcm8kChunk, pcm8kChunk.size)

                            _audioChunkFlow.tryEmit(pcm8kChunk)

                            _telemetry.update {
                                it.copy(
                                    totalBytesRead = totalBytes,
                                    currentRms = rms
                                )
                            }
                        }
                    }
                    kotlinx.coroutines.yield()
                } else {
                    kotlinx.coroutines.delay(10)
                }
            }
        } catch (_: Exception) {
            // Unhandled loop error
        } finally {
            synchronized(lock) {
                stopCaptureInternal()
            }
        }
    }

    private fun stopCaptureInternal() {
        recordingJob?.cancel()
        recordingJob = null

        audioRecordAdapter?.let { adapter ->
            try { adapter.stop() } catch (_: Exception) {}
            try { adapter.release() } catch (_: Exception) {}
        }
        audioRecordAdapter = null

        _telemetry.update {
            it.copy(
                isCapturing = false,
                currentRms = 0f,
                state = if (it.state == PhoneAudioState.RECORDING || it.state == PhoneAudioState.STARTING) PhoneAudioState.STOPPED else it.state
            )
        }
    }

    /**
     * Stops phone microphone capture and releases AudioRecord resources.
     */
    override fun stopCapture() {
        synchronized(lock) {
            stopCaptureInternal()
        }
    }

    /**
     * Permanently releases the phone microphone capture source.
     */
    override fun release() {
        stopCapture()
        _telemetry.update {
            it.copy(
                state = PhoneAudioState.IDLE,
                isCapturing = false,
                totalBytesRead = 0L,
                currentRms = 0f
            )
        }
    }
}

package com.example.sakhi.data.audio

import android.util.Log
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Arrays

/**
 * Thread-safe operational state for SpeexDSP noise suppression.
 */
enum class SpeexOperationalState {
    /** Suppression disabled; original PCM is passed through directly. */
    OFF,

    /** Suppression requested; native library loaded and initialized, awaiting first audio frame. */
    INITIALIZING,

    /** Native library loaded, state initialized, and processing calls are actively succeeding. */
    ACTIVE_PROCESSING,

    /** Suppression requested, but native loading, initialization, or processing failed. Operating in bypass fallback. */
    FALLBACK_UNAVAILABLE
}

/**
 * Thread-safe operational metrics and telemetry for SpeexDSP noise suppression.
 */
data class SpeexTelemetry(
    val state: SpeexOperationalState = SpeexOperationalState.OFF,
    val framesSubmitted: Long = 0L,
    val framesProcessed: Long = 0L,
    val processingFailures: Long = 0L,
    val framesForwardedToVosk: Long = 0L,
    val errorMessage: String? = null
)

/**
 * Stateful audio noise suppressor wrapping the SpeexDSP C preprocessor.
 *
 * Operates on 8,000 Hz mono 16-bit signed little-endian PCM audio.
 * Enforces continuous 20 ms frames (160 samples = 320 bytes) using an internal
 * accumulator to guarantee zero sample loss, duplication, or reordering across
 * arbitrarily sized incoming Bluetooth PCM chunks.
 *
 * Defaults to OFF (bit-for-bit passthrough).
 * When enabled, applies:
 * - Denoise: ON
 * - AGC: OFF (prevents volume pumping, speech distortion, and background noise elevation)
 * - VAD: OFF (Vosk acoustic model handles VAD)
 * - Dereverb: OFF (prevents phase artifacts)
 * - Noise suppression: -15 dB
 */
class SpeexNoiseSuppressor(
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val frameSizeSamples: Int = DEFAULT_FRAME_SIZE_SAMPLES,
    private val suppressionDbfs: Int = DEFAULT_SUPPRESSION_DBFS,
    injectedLib: LibSpeexDsp? = null,
    resolveDefaultLib: Boolean = (injectedLib == null)
) {
    companion object {
        private const val TAG = "SpeexNoiseSuppressor"

        const val DEFAULT_SAMPLE_RATE = 8000
        const val DEFAULT_FRAME_SIZE_SAMPLES = 160 // 20 ms at 8000 Hz
        const val BYTES_PER_SAMPLE = 2              // 16-bit PCM
        const val DEFAULT_FRAME_BYTES = DEFAULT_FRAME_SIZE_SAMPLES * BYTES_PER_SAMPLE // 320 bytes
        const val DEFAULT_SUPPRESSION_DBFS = -15    // -15 dB attenuation

        private fun safeLogI(tag: String, msg: String) {
            try {
                Log.i(tag, msg)
            } catch (_: Throwable) {}
        }

        private fun safeLogW(tag: String, msg: String) {
            try {
                Log.w(tag, msg)
            } catch (_: Throwable) {}
        }

        private fun safeLogE(tag: String, msg: String, tr: Throwable? = null) {
            try {
                Log.e(tag, msg, tr)
            } catch (_: Throwable) {
                System.err.println("[$tag] ERROR: $msg")
                tr?.printStackTrace()
            }
        }
    }

    private val lock = Any()
    private val lib: LibSpeexDsp? = if (injectedLib != null) {
        injectedLib
    } else if (resolveDefaultLib) {
        LibSpeexDsp.getInstance()
    } else {
        null
    }
    private val frameBytes = frameSizeSamples * BYTES_PER_SAMPLE

    private var preprocessState: Pointer? = null

    // Stateful framing accumulator
    private var buffer = ByteArray(4096)
    private var bufferLen = 0

    // Scratch buffers for conversion
    private val shortFrame = ShortArray(frameSizeSamples)
    private val outputScratch = ByteArray(DEFAULT_FRAME_BYTES)

    // Observable thread-safe operational status and telemetry
    private val _telemetry = MutableStateFlow(SpeexTelemetry(state = SpeexOperationalState.OFF))
    val telemetry: StateFlow<SpeexTelemetry> = _telemetry.asStateFlow()

    val operationalState: SpeexOperationalState
        get() = _telemetry.value.state

    /**
     * Diagnostic toggle for noise suppression.
     * When false: Exact bit-for-bit passthrough of raw incoming PCM and resets active counters.
     * When true: Pre-processes incoming PCM with SpeexDSP denoiser.
     */
    @Volatile
    var isEnabled: Boolean = false
        set(value) {
            synchronized(lock) {
                if (field != value) {
                    field = value
                    safeLogI(TAG, "Noise suppression enabled state changed to: $value")
                    if (value) {
                        if (lib == null) {
                            val msg = "Native library libspeexdsp.so not available"
                            safeLogW(TAG, msg)
                            _telemetry.value = SpeexTelemetry(
                                state = SpeexOperationalState.FALLBACK_UNAVAILABLE,
                                errorMessage = msg
                            )
                        } else {
                            val ok = initNativeStateLocked()
                            if (!ok || preprocessState == null) {
                                val msg = "Failed to initialize native SpeexDSP state"
                                safeLogE(TAG, msg)
                                _telemetry.value = SpeexTelemetry(
                                    state = SpeexOperationalState.FALLBACK_UNAVAILABLE,
                                    errorMessage = msg
                                )
                            } else {
                                _telemetry.value = SpeexTelemetry(
                                    state = SpeexOperationalState.INITIALIZING,
                                    errorMessage = null
                                )
                            }
                        }
                    } else {
                        // When disabled, reset filter state and flush any residual samples
                        destroyNativeStateLocked()
                        bufferLen = 0
                        // Cleanly return to OFF and reset active counters
                        _telemetry.value = SpeexTelemetry(
                            state = SpeexOperationalState.OFF,
                            framesSubmitted = 0L,
                            framesProcessed = 0L,
                            processingFailures = 0L,
                            framesForwardedToVosk = 0L,
                            errorMessage = null
                        )
                    }
                }
            }
        }

    val isNativeAvailable: Boolean
        get() = lib != null

    init {
        if (lib == null) {
            safeLogW(TAG, "LibSpeexDsp is not available on this platform. Noise suppression will operate in bypass mode.")
        }
    }

    /**
     * Processes an incoming 8 kHz mono 16-bit PCM chunk.
     *
     * If noise suppression is disabled or native library is unavailable:
     * Returns the raw chunk directly without modification or delay.
     *
     * If enabled:
     * Accumulates bytes, slices exact 20 ms (320-byte) frames, applies SpeexDSP denoise
     * in-place while preserving filter state across frames, and retains any remaining
     * bytes (< 320) for the subsequent chunk.
     *
     * Only transitions to ACTIVE_PROCESSING after at least one real frame succeeds.
     * If native execution fails, transitions to FALLBACK_UNAVAILABLE and passes through raw PCM.
     *
     * @param rawChunk Raw 16-bit PCM bytes from Bluetooth SPP stream.
     * @return Processed 16-bit PCM bytes (may be empty if fewer than 320 bytes accumulated).
     */
    fun process(rawChunk: ByteArray): ByteArray {
        if (rawChunk.isEmpty()) return rawChunk

        synchronized(lock) {
            if (!isEnabled) {
                // Bypass mode: exact passthrough
                return rawChunk
            }

            if (lib == null) {
                if (_telemetry.value.state != SpeexOperationalState.FALLBACK_UNAVAILABLE) {
                    _telemetry.value = _telemetry.value.copy(
                        state = SpeexOperationalState.FALLBACK_UNAVAILABLE,
                        errorMessage = "Native library libspeexdsp.so not available"
                    )
                }
                return rawChunk
            }

            // If we are already in FALLBACK_UNAVAILABLE due to a runtime processing failure, bypass safely
            if (_telemetry.value.state == SpeexOperationalState.FALLBACK_UNAVAILABLE) {
                return rawChunk
            }

            if (preprocessState == null) {
                val ok = initNativeStateLocked()
                if (!ok || preprocessState == null) {
                    _telemetry.value = _telemetry.value.copy(
                        state = SpeexOperationalState.FALLBACK_UNAVAILABLE,
                        errorMessage = "Failed to initialize native SpeexDSP state"
                    )
                    return rawChunk
                }
            }

            val state = preprocessState!!

            // Ensure accumulator buffer has enough capacity
            ensureCapacityLocked(bufferLen + rawChunk.size)
            System.arraycopy(rawChunk, 0, buffer, bufferLen, rawChunk.size)
            bufferLen += rawChunk.size

            val framesToProcess = bufferLen / frameBytes
            if (framesToProcess == 0) {
                // Not enough samples for a complete 20 ms frame yet; hold in accumulator
                return ByteArray(0)
            }

            val outputTotalBytes = framesToProcess * frameBytes
            val output = ByteArray(outputTotalBytes)

            var frameFailures = 0L
            var frameSuccesses = 0L
            var lastFailureError: String? = null

            for (f in 0 until framesToProcess) {
                val frameOffset = f * frameBytes

                // Convert 320 bytes LE to 160 shorts
                bytesToShortsLe(buffer, frameOffset, frameSizeSamples, shortFrame)

                try {
                    // Process 20 ms frame in-place via native SpeexDSP
                    val ret = lib.speex_preprocess_run(state, shortFrame)
                    if (ret < 0) {
                        frameFailures++
                        lastFailureError = "speex_preprocess_run returned $ret"
                        safeLogE(TAG, "speex_preprocess_run returned negative status: $ret")
                        // Fall back to original raw frame for this slice
                        System.arraycopy(buffer, frameOffset, output, frameOffset, frameBytes)
                    } else {
                        frameSuccesses++
                        // Convert 160 shorts back to 320 bytes LE
                        shortsToBytesLe(shortFrame, frameSizeSamples, output, frameOffset)
                    }
                } catch (e: Throwable) {
                    frameFailures++
                    lastFailureError = e.message ?: "Native processing exception"
                    safeLogE(TAG, "Exception during speex_preprocess_run: ${e.message}", e)
                    // Fall back to original raw frame for this slice
                    System.arraycopy(buffer, frameOffset, output, frameOffset, frameBytes)
                }
            }

            // Shift remaining unaligned bytes to the front of the accumulator
            val remainder = bufferLen - outputTotalBytes
            if (remainder > 0) {
                System.arraycopy(buffer, outputTotalBytes, buffer, 0, remainder)
            }
            bufferLen = remainder

            // Update telemetry counters
            val prev = _telemetry.value
            val newSubmitted = prev.framesSubmitted + framesToProcess.toLong()
            val newProcessed = prev.framesProcessed + frameSuccesses
            val newFailures = prev.processingFailures + frameFailures
            val newForwarded = prev.framesForwardedToVosk + frameSuccesses

            val newState = if (frameFailures > 0) {
                // If processing fails after initialization, do not continue displaying ACTIVE_PROCESSING
                // Destroy native state to prevent further corrupted native pointer crashes
                destroyNativeStateLocked()
                SpeexOperationalState.FALLBACK_UNAVAILABLE
            } else if (newProcessed > 0L) {
                SpeexOperationalState.ACTIVE_PROCESSING
            } else {
                prev.state
            }

            val newErrMsg = if (frameFailures > 0) {
                lastFailureError ?: "Native processing call failed"
            } else {
                prev.errorMessage
            }

            _telemetry.value = SpeexTelemetry(
                state = newState,
                framesSubmitted = newSubmitted,
                framesProcessed = newProcessed,
                processingFailures = newFailures,
                framesForwardedToVosk = newForwarded,
                errorMessage = newErrMsg
            )

            return output
        }
    }

    /**
     * Flushes any remaining bytes in the accumulator.
     * When enabled, zero-pads the partial frame to a complete 20 ms frame, processes it,
     * and returns the exact valid sample slice.
     */
    fun flush(): ByteArray {
        synchronized(lock) {
            if (bufferLen == 0) return ByteArray(0)

            if (!isEnabled || lib == null || preprocessState == null || _telemetry.value.state == SpeexOperationalState.FALLBACK_UNAVAILABLE) {
                val flushed = ByteArray(bufferLen)
                System.arraycopy(buffer, 0, flushed, 0, bufferLen)
                bufferLen = 0
                return flushed
            }

            val state = preprocessState!!
            val remainderBytes = bufferLen
            val validSamples = remainderBytes / BYTES_PER_SAMPLE

            if (validSamples == 0) {
                bufferLen = 0
                return ByteArray(0)
            }

            // Zero-pad partial frame to 160 samples
            Arrays.fill(shortFrame, 0.toShort())
            bytesToShortsLe(buffer, 0, validSamples, shortFrame)

            try {
                lib.speex_preprocess_run(state, shortFrame)
            } catch (e: Throwable) {
                safeLogW(TAG, "Exception flushing SpeexPreprocessState: ${e.message}")
            }

            val flushedBytes = validSamples * BYTES_PER_SAMPLE
            val output = ByteArray(flushedBytes)
            shortsToBytesLe(shortFrame, validSamples, output, 0)

            bufferLen = 0
            return output
        }
    }

    /**
     * Resets accumulator buffer and clears native preprocessor adaptation memory.
     */
    fun reset() {
        synchronized(lock) {
            bufferLen = 0
            if (preprocessState != null && lib != null) {
                destroyNativeStateLocked()
                if (isEnabled) {
                    val ok = initNativeStateLocked()
                    _telemetry.value = _telemetry.value.copy(
                        state = if (ok) SpeexOperationalState.INITIALIZING else SpeexOperationalState.FALLBACK_UNAVAILABLE,
                        errorMessage = if (ok) null else "Failed to reinitialize native SpeexDSP state"
                    )
                }
            }
        }
    }

    /**
     * Releases all native memory allocations.
     */
    fun release() {
        synchronized(lock) {
            bufferLen = 0
            destroyNativeStateLocked()
            _telemetry.value = SpeexTelemetry(
                state = SpeexOperationalState.OFF,
                framesSubmitted = 0L,
                framesProcessed = 0L,
                processingFailures = 0L,
                framesForwardedToVosk = 0L,
                errorMessage = null
            )
        }
    }

    private fun initNativeStateLocked(): Boolean {
        if (lib == null) return false
        destroyNativeStateLocked()

        try {
            val state = lib.speex_preprocess_state_init(frameSizeSamples, sampleRate)
            if (state == null) {
                safeLogE(TAG, "Failed to initialize SpeexPreprocessState ($frameSizeSamples samples @ $sampleRate Hz)")
                return false
            }

            // 1. Denoise: ON (1)
            lib.speex_preprocess_ctl(state, LibSpeexDsp.SPEEX_PREPROCESS_SET_DENOISE, intArrayOf(1))

            // 2. AGC: OFF (0) - Crucial: Prevents volume pumping and clipping
            lib.speex_preprocess_ctl(state, LibSpeexDsp.SPEEX_PREPROCESS_SET_AGC, intArrayOf(0))

            // 3. VAD: OFF (0) - Let Vosk Kaldi engine perform speech detection
            lib.speex_preprocess_ctl(state, LibSpeexDsp.SPEEX_PREPROCESS_SET_VAD, intArrayOf(0))

            // 4. Dereverb: OFF (0) - Avoid phase distortion
            lib.speex_preprocess_ctl(state, LibSpeexDsp.SPEEX_PREPROCESS_SET_DEREVERB, intArrayOf(0))

            // 5. Noise suppression level: -15 dB
            lib.speex_preprocess_ctl(state, LibSpeexDsp.SPEEX_PREPROCESS_SET_NOISE_SUPPRESS, intArrayOf(suppressionDbfs))

            preprocessState = state
            safeLogI(
                TAG,
                "SpeexPreprocessState successfully initialized (frameSize=$frameSizeSamples, " +
                "rate=$sampleRate, denoise=1, agc=0, vad=0, dereverb=0, suppress=${suppressionDbfs}dB)"
            )
            return true
        } catch (e: Throwable) {
            safeLogE(TAG, "Exception initializing SpeexDSP preprocessor: ${e.message}", e)
            preprocessState = null
            return false
        }
    }

    private fun destroyNativeStateLocked() {
        preprocessState?.let { state ->
            try {
                lib?.speex_preprocess_state_destroy(state)
            } catch (e: Throwable) {
                safeLogW(TAG, "Exception destroying SpeexPreprocessState: ${e.message}")
            }
        }
        preprocessState = null
    }

    private fun ensureCapacityLocked(required: Int) {
        if (required > buffer.size) {
            var newCap = buffer.size * 2
            while (newCap < required) {
                newCap *= 2
            }
            val newBuf = ByteArray(newCap)
            System.arraycopy(buffer, 0, newBuf, 0, bufferLen)
            buffer = newBuf
        }
    }

    private fun bytesToShortsLe(bytes: ByteArray, offset: Int, count: Int, shorts: ShortArray) {
        for (i in 0 until count) {
            val byteIndex = offset + (i * 2)
            val b0 = bytes[byteIndex].toInt() and 0xFF
            val b1 = bytes[byteIndex + 1].toInt()
            shorts[i] = ((b1 shl 8) or b0).toShort()
        }
    }

    private fun shortsToBytesLe(shorts: ShortArray, count: Int, bytes: ByteArray, offset: Int) {
        for (i in 0 until count) {
            val s = shorts[i].toInt()
            val byteIndex = offset + (i * 2)
            bytes[byteIndex] = (s and 0xFF).toByte()
            bytes[byteIndex + 1] = ((s shr 8) and 0xFF).toByte()
        }
    }
}

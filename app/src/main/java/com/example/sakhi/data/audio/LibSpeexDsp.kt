package com.example.sakhi.data.audio

import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * JNA dynamic binding interface for official libspeexdsp.so.
 * Provides pure C ABI bindings for the SpeexDSP preprocessor API.
 */
interface LibSpeexDsp : Library {

    /**
     * Initializes a SpeexPreprocessState.
     * @param frame_size Number of samples in a single processing frame (e.g., 160 for 20 ms at 8 kHz).
     * @param sampling_rate Sampling rate in Hz (e.g., 8000).
     * @return Opaque pointer to SpeexPreprocessState, or null if initialization failed.
     */
    fun speex_preprocess_state_init(frame_size: Int, sampling_rate: Int): Pointer?

    /**
     * Destroys and frees all memory associated with a SpeexPreprocessState.
     */
    fun speex_preprocess_state_destroy(st: Pointer)

    /**
     * Preprocesses a single frame of audio in-place.
     * @param st Opaque pointer to SpeexPreprocessState.
     * @param x Audio sample buffer (16-bit signed PCM). Modified in-place.
     * @return 1 if VAD is enabled and voice activity detected, 0 otherwise.
     */
    fun speex_preprocess_run(st: Pointer, x: ShortArray): Int

    /**
     * Controls preprocessor parameters via an ioctl-style request.
     * @param st Opaque pointer to SpeexPreprocessState.
     * @param request One of the SPEEX_PREPROCESS_* request IDs.
     * @param ptr IntArray with parameter value (size >= 1).
     * @return 0 on success, negative value on error.
     */
    fun speex_preprocess_ctl(st: Pointer, request: Int, ptr: IntArray): Int

    companion object {
        private const val TAG = "LibSpeexDsp"

        // Control request constants from <speex/speex_preprocess.h>
        const val SPEEX_PREPROCESS_SET_DENOISE = 0
        const val SPEEX_PREPROCESS_GET_DENOISE = 1
        const val SPEEX_PREPROCESS_SET_AGC = 2
        const val SPEEX_PREPROCESS_GET_AGC = 3
        const val SPEEX_PREPROCESS_SET_VAD = 4
        const val SPEEX_PREPROCESS_GET_VAD = 5
        const val SPEEX_PREPROCESS_SET_AGC_LEVEL = 6
        const val SPEEX_PREPROCESS_GET_AGC_LEVEL = 7
        const val SPEEX_PREPROCESS_SET_DEREVERB = 8
        const val SPEEX_PREPROCESS_GET_DEREVERB = 9
        const val SPEEX_PREPROCESS_SET_DEREVERB_LEVEL = 10
        const val SPEEX_PREPROCESS_GET_DEREVERB_LEVEL = 11
        const val SPEEX_PREPROCESS_SET_DEREVERB_DECAY = 12
        const val SPEEX_PREPROCESS_GET_DEREVERB_DECAY = 13
        const val SPEEX_PREPROCESS_SET_PROB_START = 14
        const val SPEEX_PREPROCESS_GET_PROB_START = 15
        const val SPEEX_PREPROCESS_SET_PROB_CONTINUE = 16
        const val SPEEX_PREPROCESS_GET_PROB_CONTINUE = 17
        const val SPEEX_PREPROCESS_SET_NOISE_SUPPRESS = 18
        const val SPEEX_PREPROCESS_GET_NOISE_SUPPRESS = 19
        const val SPEEX_PREPROCESS_SET_ECHO_SUPPRESS = 20
        const val SPEEX_PREPROCESS_GET_ECHO_SUPPRESS = 21

        @Volatile
        private var instance: LibSpeexDsp? = null
        @Volatile
        private var loadAttempted = false

        /**
         * Lazily retrieves or loads the native LibSpeexDsp instance.
         * Returns null gracefully if native library cannot be loaded (e.g. host JVM test).
         */
        fun getInstance(): LibSpeexDsp? {
            if (!loadAttempted) {
                synchronized(this) {
                    if (!loadAttempted) {
                        loadAttempted = true
                        instance = try {
                            Native.load("speexdsp", LibSpeexDsp::class.java).also {
                                safeLogI(TAG, "Successfully loaded libspeexdsp via JNA")
                            }
                        } catch (t: Throwable) {
                            safeLogW(TAG, "libspeexdsp native library not loaded (${t.message}). Fallback passthrough will be used.")
                            null
                        }
                    }
                }
            }
            return instance
        }

        fun isAvailable(): Boolean = getInstance() != null

        /**
         * Injects a custom instance for unit testing.
         */
        fun setInstanceForTesting(testInstance: LibSpeexDsp?) {
            synchronized(this) {
                instance = testInstance
                loadAttempted = true
            }
        }

        /**
         * Resets instance cache (e.g. between tests).
         */
        fun resetForTesting() {
            synchronized(this) {
                instance = null
                loadAttempted = false
            }
        }

        private fun safeLogI(tag: String, msg: String) {
            try {
                Log.i(tag, msg)
            } catch (_: Throwable) {
                // Ignore in unmocked host JVM tests
            }
        }

        private fun safeLogW(tag: String, msg: String) {
            try {
                Log.w(tag, msg)
            } catch (_: Throwable) {
                // Ignore in unmocked host JVM tests
            }
        }
    }
}

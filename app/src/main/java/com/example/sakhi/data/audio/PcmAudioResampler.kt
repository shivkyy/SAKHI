package com.example.sakhi.data.audio

/**
 * Utility for linear interpolation resampling of 16-bit signed Mono Little-Endian PCM audio.
 * Allows transparent upsampling from 8,000 Hz to 16,000 Hz for ASR acoustic models
 * that mandate 16 kHz input.
 */
object PcmAudioResampler {

    /**
     * Resamples 16-bit signed Mono PCM from 8 kHz to 16 kHz via linear interpolation.
     * Doubles sample count: for each adjacent pair (S_n, S_n+1), inserts intermediate sample (S_n + S_n+1) / 2.
     *
     * @param input Raw 8 kHz 16-bit PCM bytes (Little-Endian).
     * @param offset Start offset in input array.
     * @param length Number of bytes to process (must be even, representing 16-bit samples).
     * @return Resampled 16 kHz 16-bit PCM byte array of length (length * 2).
     */
    fun upsample8kTo16k(input: ByteArray, offset: Int = 0, length: Int = input.size): ByteArray {
        val sampleCount = length / 2
        if (sampleCount <= 0) return ByteArray(0)

        val output = ByteArray(length * 2)

        for (i in 0 until sampleCount) {
            val byteIndex = offset + i * 2
            val s0 = ((input[byteIndex + 1].toInt() and 0xFF) shl 8) or (input[byteIndex].toInt() and 0xFF)
            val s0Short = s0.toShort()

            // Write original sample
            val outIndex0 = i * 4
            output[outIndex0] = (s0Short.toInt() and 0xFF).toByte()
            output[outIndex0 + 1] = ((s0Short.toInt() shr 8) and 0xFF).toByte()

            // Interpolate intermediate sample with next sample if available
            val s1Short = if (i + 1 < sampleCount) {
                val nextByteIndex = offset + (i + 1) * 2
                val s1 = ((input[nextByteIndex + 1].toInt() and 0xFF) shl 8) or (input[nextByteIndex].toInt() and 0xFF)
                s1.toShort()
            } else {
                s0Short
            }

            val sMid = ((s0Short.toInt() + s1Short.toInt()) / 2).toShort()
            output[outIndex0 + 2] = (sMid.toInt() and 0xFF).toByte()
            output[outIndex0 + 3] = ((sMid.toInt() shr 8) and 0xFF).toByte()
        }

        return output
    }
}

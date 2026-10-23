package com.example.sakhi.data.bluetooth

import com.example.sakhi.core.model.HardwareEvent
import java.io.ByteArrayOutputStream

/**
 * Stream framer and parser for Bluetooth Classic SPP communication between ESP32 and Android.
 *
 * Provides multiplexing of 8 kHz 16-bit mono PCM audio and discrete button control events
 * over a single RFCOMM serial stream without corrupting speech recognition audio.
 *
 * Frame Format:
 * +-----------------------------------+------------+---------------+-------------------+----------+
 * | Magic (4 bytes)                   | Type (1 B) | Length (2 B)  | Payload (N bytes) | Checksum |
 * | 'S' 'A' 'K' 'H' (0x53 41 4B 48)   | 0x01 / 0x02| Big-Endian    | Raw PCM / Opcode  | 1 B XOR  |
 * +-----------------------------------+------------+---------------+-------------------+----------+
 */
class SakhiStreamFramer(
    private val onAudioChunk: (ByteArray) -> Unit,
    private val onControlEvent: (HardwareEvent) -> Unit
) {
    companion object {
        val MAGIC = byteArrayOf('S'.code.toByte(), 'A'.code.toByte(), 'K'.code.toByte(), 'H'.code.toByte())
        const val HEADER_SIZE = 7 // 4 magic + 1 type + 2 length
        const val TRAILER_SIZE = 1 // 1 checksum

        const val FRAME_TYPE_AUDIO: Byte = 0x01
        const val FRAME_TYPE_CONTROL: Byte = 0x02

        const val OPCODE_DOUBLE_PRESS: Byte = 0x01
        const val OPCODE_TAP_HOLD: Byte = 0x02
        const val OPCODE_TRIPLE_TAP: Byte = 0x03
        const val OPCODE_BATTERY_STATUS: Byte = 0x04
        const val OPCODE_DEVICE_STATUS: Byte = 0x05
        const val OPCODE_HOLD_START: Byte = 0x06
        const val OPCODE_HOLD_RELEASE: Byte = 0x07

        const val MAX_AUDIO_PAYLOAD = 1024
        const val MAX_CONTROL_PAYLOAD = 32

        /**
         * Encodes a raw 16-bit PCM audio chunk into a framed packet.
         */
        fun encodeAudioFrame(pcmData: ByteArray, offset: Int = 0, length: Int = pcmData.size): ByteArray {
            val len = length.coerceAtMost(MAX_AUDIO_PAYLOAD)
            val packet = ByteArray(HEADER_SIZE + len + TRAILER_SIZE)
            System.arraycopy(MAGIC, 0, packet, 0, 4)
            packet[4] = FRAME_TYPE_AUDIO
            packet[5] = ((len shr 8) and 0xFF).toByte()
            packet[6] = (len and 0xFF).toByte()
            System.arraycopy(pcmData, offset, packet, 7, len)

            var checksum = packet[4].toInt() xor packet[5].toInt() xor packet[6].toInt()
            for (i in 0 until len) {
                checksum = checksum xor packet[7 + i].toInt()
            }
            packet[7 + len] = (checksum and 0xFF).toByte()
            return packet
        }

        /**
         * Encodes a hardware control opcode and optional payload into a framed packet.
         */
        fun encodeControlFrame(opcode: Byte, payload: ByteArray = byteArrayOf()): ByteArray {
            val totalLen = 1 + payload.size
            val packet = ByteArray(HEADER_SIZE + totalLen + TRAILER_SIZE)
            System.arraycopy(MAGIC, 0, packet, 0, 4)
            packet[4] = FRAME_TYPE_CONTROL
            packet[5] = ((totalLen shr 8) and 0xFF).toByte()
            packet[6] = (totalLen and 0xFF).toByte()
            packet[7] = opcode
            if (payload.isNotEmpty()) {
                System.arraycopy(payload, 0, packet, 8, payload.size)
            }

            var checksum = packet[4].toInt() xor packet[5].toInt() xor packet[6].toInt() xor opcode.toInt()
            for (b in payload) {
                checksum = checksum xor b.toInt()
            }
            packet[7 + totalLen] = (checksum and 0xFF).toByte()
            return packet
        }
    }

    private val accumulator = ByteArrayOutputStream(4096)
    private var isFramedProtocolActive = false

    /**
     * Ingests a raw byte array read from the RFCOMM socket stream.
     */
    fun feedBytes(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (length <= 0) return
        accumulator.write(data, offset, length)
        processBuffer()
    }

    private fun processBuffer() {
        var buffer = accumulator.toByteArray()

        while (buffer.isNotEmpty()) {
            // Find magic sequence index
            val magicIdx = findMagic(buffer)

            if (magicIdx == -1) {
                // If we have not detected framed protocol yet, or if excess raw data arrives without magic
                if (!isFramedProtocolActive) {
                    // Raw PCM legacy stream support: output even-length PCM audio
                    val evenLength = buffer.size - (buffer.size % 2)
                    if (evenLength > 0) {
                        val rawAudio = buffer.copyOfRange(0, evenLength)
                        onAudioChunk(rawAudio)
                        val remaining = buffer.copyOfRange(evenLength, buffer.size)
                        accumulator.reset()
                        accumulator.write(remaining)
                    }
                } else {
                    // In framed mode, keep only the last 3 bytes in case magic is split across reads
                    val keepBytes = minOf(buffer.size, 3)
                    val remaining = buffer.copyOfRange(buffer.size - keepBytes, buffer.size)
                    accumulator.reset()
                    accumulator.write(remaining)
                }
                return
            }

            // Magic was found!
            isFramedProtocolActive = true

            // Discard unaligned bytes preceding magic sequence to synchronize cleanly on valid frame boundary
            if (magicIdx > 0) {
                buffer = buffer.copyOfRange(magicIdx, buffer.size)
            }

            // Check if we have at least full header
            if (buffer.size < HEADER_SIZE) {
                // Wait for more bytes
                accumulator.reset()
                accumulator.write(buffer)
                return
            }

            val frameType = buffer[4]
            val payloadLen = ((buffer[5].toInt() and 0xFF) shl 8) or (buffer[6].toInt() and 0xFF)

            // Validate frame type and length
            val isAudio = frameType == FRAME_TYPE_AUDIO && payloadLen in 1..MAX_AUDIO_PAYLOAD
            val isControl = frameType == FRAME_TYPE_CONTROL && payloadLen in 1..MAX_CONTROL_PAYLOAD

            if (!isAudio && !isControl) {
                // Malformed header, discard 1st byte of magic and resume scanning
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }

            val totalFrameSize = HEADER_SIZE + payloadLen + TRAILER_SIZE
            if (buffer.size < totalFrameSize) {
                // Complete frame not yet available in buffer; wait for next read
                accumulator.reset()
                accumulator.write(buffer)
                return
            }

            // Compute and verify checksum
            var expectedChecksum = buffer[4].toInt() xor buffer[5].toInt() xor buffer[6].toInt()
            for (i in 0 until payloadLen) {
                expectedChecksum = expectedChecksum xor buffer[HEADER_SIZE + i].toInt()
            }
            val actualChecksum = buffer[totalFrameSize - 1].toInt() and 0xFF

            if ((expectedChecksum and 0xFF) == actualChecksum) {
                // Frame is verified!
                val payload = buffer.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLen)

                if (frameType == FRAME_TYPE_AUDIO) {
                    onAudioChunk(payload)
                } else if (frameType == FRAME_TYPE_CONTROL) {
                    parseAndDispatchControlEvent(payload)
                }

                // Advance buffer past this frame
                buffer = buffer.copyOfRange(totalFrameSize, buffer.size)
            } else {
                // Checksum failed! Discard first byte of magic to resynchronize
                buffer = buffer.copyOfRange(1, buffer.size)
            }
        }

        accumulator.reset()
    }

    private fun findMagic(buffer: ByteArray): Int {
        if (buffer.size < 4) return -1
        for (i in 0..buffer.size - 4) {
            if (buffer[i] == MAGIC[0] &&
                buffer[i + 1] == MAGIC[1] &&
                buffer[i + 2] == MAGIC[2] &&
                buffer[i + 3] == MAGIC[3]
            ) {
                return i
            }
        }
        return -1
    }

    private fun parseAndDispatchControlEvent(payload: ByteArray) {
        if (payload.isEmpty()) return
        val opcode = payload[0]
        val event: HardwareEvent? = when (opcode) {
            OPCODE_DOUBLE_PRESS -> HardwareEvent.DoublePress()
            OPCODE_TAP_HOLD -> {
                val duration = if (payload.size >= 3) {
                    (((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)).toLong()
                } else {
                    3000L
                }
                HardwareEvent.TapHold(durationMs = duration)
            }
            OPCODE_TRIPLE_TAP -> HardwareEvent.TripleTap()
            OPCODE_HOLD_START -> HardwareEvent.ButtonHoldStart()
            OPCODE_HOLD_RELEASE -> {
                val duration = if (payload.size >= 3) {
                    (((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)).toLong()
                } else {
                    0L
                }
                HardwareEvent.ButtonHoldRelease(durationMs = duration)
            }
            OPCODE_BATTERY_STATUS -> {
                if (payload.size >= 2) {
                    val pct = (payload[1].toInt() and 0xFF).coerceIn(0, 100)
                    val isCharging = if (payload.size >= 3) payload[2] == 1.toByte() else false
                    HardwareEvent.BatteryStatus(pct, isCharging)
                } else null
            }
            OPCODE_DEVICE_STATUS -> {
                val ver = if (payload.size >= 4) {
                    "${payload[1].toInt() and 0xFF}.${payload[2].toInt() and 0xFF}.${payload[3].toInt() and 0xFF}"
                } else "v1.0.0"
                val rssi = if (payload.size >= 5) payload[4].toInt() else -65
                HardwareEvent.DeviceStatus(firmwareVersion = ver, rssi = rssi)
            }
            else -> null
        }

        if (event != null) {
            onControlEvent(event)
        }
    }

    fun reset() {
        accumulator.reset()
        isFramedProtocolActive = false
    }
}

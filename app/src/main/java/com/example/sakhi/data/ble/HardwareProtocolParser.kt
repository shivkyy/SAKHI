package com.example.sakhi.data.ble

import com.example.sakhi.core.model.HardwareEvent

interface IHardwareProtocolParser {
    fun parsePacket(data: ByteArray): HardwareEvent?
    fun encodeVibrationCommand(patternCode: Byte): ByteArray
}

class HardwareProtocolParser : IHardwareProtocolParser {

    override fun parsePacket(data: ByteArray): HardwareEvent? {
        if (data.isEmpty()) return null

        return when (data[0]) {
            HardwareProtocolConfig.OPCODE_DOUBLE_PRESS -> {
                HardwareEvent.DoublePress()
            }
            HardwareProtocolConfig.OPCODE_TAP_HOLD -> {
                val durationMs = if (data.size >= 3) {
                    ((data[1].toLong() and 0xFF) shl 8) or (data[2].toLong() and 0xFF)
                } else {
                    1500L
                }
                HardwareEvent.TapHold(durationMs = durationMs)
            }
            HardwareProtocolConfig.OPCODE_TRIPLE_TAP -> {
                HardwareEvent.TripleTap()
            }
            HardwareProtocolConfig.OPCODE_BATTERY_STATUS -> {
                if (data.size >= 2) {
                    val percentage = (data[1].toInt() and 0xFF).coerceIn(0, 100)
                    val isCharging = if (data.size >= 3) data[2] == 1.toByte() else false
                    HardwareEvent.BatteryStatus(percentage = percentage, isCharging = isCharging)
                } else null
            }
            HardwareProtocolConfig.OPCODE_DEVICE_STATUS -> {
                val versionStr = if (data.size >= 4) {
                    "${data[1].toInt() and 0xFF}.${data[2].toInt() and 0xFF}.${data[3].toInt() and 0xFF}"
                } else {
                    "v1.0.0"
                }
                val rssi = if (data.size >= 5) data[4].toInt() else -65
                HardwareEvent.DeviceStatus(firmwareVersion = versionStr, rssi = rssi)
            }
            else -> null
        }
    }

    override fun encodeVibrationCommand(patternCode: Byte): ByteArray {
        return byteArrayOf(patternCode)
    }
}

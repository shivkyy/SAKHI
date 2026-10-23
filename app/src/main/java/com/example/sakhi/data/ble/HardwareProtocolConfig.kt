package com.example.sakhi.data.ble

import java.util.UUID

object HardwareProtocolConfig {
    // SAKHI 128-bit Base Service and Characteristic UUIDs
    val SERVICE_UUID: UUID = UUID.fromString("0000FE5A-0000-1000-8000-00805F9B34FB")
    val EVENT_CHAR_UUID: UUID = UUID.fromString("0000FE5B-0000-1000-8000-00805F9B34FB")
    val CONTROL_CHAR_UUID: UUID = UUID.fromString("0000FE5C-0000-1000-8000-00805F9B34FB")
    val AUDIO_CHAR_UUID: UUID = UUID.fromString("0000FE5D-0000-1000-8000-00805F9B34FB")

    // Standard Client Characteristic Configuration Descriptor (CCCD)
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Protocol OpCodes (1-byte header)
    const val OPCODE_DOUBLE_PRESS: Byte = 0x01
    const val OPCODE_TAP_HOLD: Byte = 0x02
    const val OPCODE_TRIPLE_TAP: Byte = 0x03
    const val OPCODE_BATTERY_STATUS: Byte = 0x04
    const val OPCODE_DEVICE_STATUS: Byte = 0x05

    // Vibration command codes to write to CONTROL_CHAR
    const val CMD_VIBRATE_ACTIVATION: Byte = 0x11   // 1 long pulse (1000ms)
    const val CMD_VIBRATE_CANCEL: Byte = 0x12       // 2 short pulses (200ms on, 100ms off, 200ms on)
    const val CMD_VIBRATE_POLICE: Byte = 0x13       // 3 short pulses (200ms on, 100ms off, 200ms on, 100ms off, 200ms on)

    // Device Advertisement Name Filter Prefix
    const val DEVICE_NAME_PREFIX = "SAKHI"
}

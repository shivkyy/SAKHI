# SAKHI ESP32-S3 BLE Hardware Protocol Specification

This document defines the Bluetooth Low Energy (BLE) communication protocol between the physical **SAKHI ESP32-S3 Keychain** wearable and the **SAKHI Android Application**.

---

## 1. BLE Service & Characteristic UUIDs

The SAKHI wearable advertises a dedicated primary GATT service with 128-bit custom UUIDs:

| Name | UUID | Type | Properties | Description |
|---|---|---|---|---|
| **SAKHI Emergency Service** | `0000FE5A-0000-1000-8000-00805F9B34FB` | Primary Service | - | Main GATT Service for SAKHI Keychain |
| **Event Characteristic** | `0000FE5B-0000-1000-8000-00805F9B34FB` | Characteristic | `NOTIFY`, `READ` | Streams hardware button & telemetry events to the phone |
| **Control Characteristic** | `0000FE5C-0000-1000-8000-00805F9B34FB` | Characteristic | `WRITE`, `WRITE_NO_RESPONSE` | Receives vibration commands from the phone |
| **Audio Characteristic** | `0000FE5D-0000-1000-8000-00805F9B34FB` | Characteristic | `NOTIFY` | Optional audio stream transport from MEMS microphone |
| **Client Characteristic Configuration (CCCD)** | `00002902-0000-1000-8000-00805F9B34FB` | Descriptor | `READ`, `WRITE` | Written by Android app (`0x0001`) to enable notifications |

---

## 2. Advertisement Packet

- **Device Name**: `SAKHI-XXXX` (Prefix `SAKHI` followed by the last 4 characters of the ESP32 MAC address).
- **Service UUID**: 128-bit Service UUID `0000FE5A-0000-1000-8000-00805F9B34FB` included in 128-bit Complete List of Service UUIDs (`0x07` AD Type).
- **Tx Power**: Advertised at `+3 dBm` or `+6 dBm` for stable range within 10 meters of the phone.

---

## 3. Hardware Event Packet Framing (Wearable -> Android Phone)

Packets sent by the ESP32-S3 over the **Event Characteristic** (`0000FE5B`) use a binary opcode-based format:

```
+------------+-----------------------------------+
| Byte 0     | Bytes 1..N                        |
| OpCode     | Payload                           |
+------------+-----------------------------------+
```

### Supported OpCodes

| OpCode (Hex) | Semantic Event | Payload Format | Description |
|---|---|---|---|
| `0x01` | `DOUBLE_PRESS` | None (Length = 1 byte) | Physical button pressed twice rapidly within 400ms. Activates emergency session. |
| `0x02` | `TAP_HOLD` | `[DurationHighByte, DurationLowByte]` (Length = 3 bytes) | Button held for 1500ms+. Cancels active emergency session if cancellable. |
| `0x03` | `TRIPLE_TAP` | None (Length = 1 byte) | Button pressed three times rapidly while session is active. Manual police request. |
| `0x04` | `BATTERY_STATUS` | `Byte 1: Percent (0..100)`, `Byte 2: IsCharging (0 or 1)` | Telemetry packet sent on connect and periodically (every 60 seconds). |
| `0x05` | `DEVICE_STATUS` | `Byte 1: Major`, `Byte 2: Minor`, `Byte 3: Patch`, `Byte 4: RSSI` | Firmware version and signal quality diagnostics. |

---

## 4. Vibration Control Commands (Android Phone -> Wearable)

When the phone sends haptic feedback commands to the wearable, it writes a 1-byte payload to the **Control Characteristic** (`0000FE5C`):

| Command (Hex) | Semantic Action | Firmware Vibration Pattern |
|---|---|---|
| `0x11` | `CMD_VIBRATE_ACTIVATION` | **1 continuous long pulse**: 1000ms ON, then OFF. |
| `0x12` | `CMD_VIBRATE_CANCEL` | **2 short distinct pulses**: 200ms ON, 100ms OFF, 200ms ON, then OFF. |
| `0x13` | `CMD_VIBRATE_POLICE` | **3 sharp rapid pulses**: 200ms ON, 100ms OFF, 200ms ON, 100ms OFF, 200ms ON. |

---

## 5. ESP32-S3 Firmware Implementation Guide (Arduino / ESP-IDF)

```c
// Example ESP-IDF NimBLE snippet for SAKHI Event Notification
#include "nimble/ble.h"
#include "host/ble_hs.h"

#define OPCODE_DOUBLE_PRESS   0x01
#define OPCODE_TAP_HOLD       0x02
#define OPCODE_TRIPLE_TAP     0x03
#define OPCODE_BATTERY_STATUS 0x04

void send_sakhi_event(uint8_t opcode) {
    struct os_mbuf *om = ble_hs_mbuf_from_flat(&opcode, 1);
    ble_gatts_notify_custom(conn_handle, event_char_val_handle, om);
}
```

---

## 6. Disconnection & Error Handling

1. **Disconnection during Emergency**:
   - If BLE connection drops during an active session, the Android phone preserves the active session, displays an amber status banner, and continues all phone-side functions (GPS tracking, SMS dispatch, police calling, phone microphone capture).
   - The ESP32-S3 restarts advertising immediately with low latency (`Scan_Interval: 32`, `Scan_Window: 32`) to allow instant reconnection.

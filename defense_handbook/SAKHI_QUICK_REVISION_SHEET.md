# SAKHI — FINAL-DAY COMPETITION QUICK REVISION CHEAT SHEET

> **Document ID**: SAKHI-DOC-10  
> **Target Audience**: SAKHI Defense Team (Print or keep on phone for final-minute revision before judging)  
> **Time to Read**: 5 Minutes

---

## 1. ESSENTIAL SPECS & NUMBERS TO MEMORIZE

| Specification | Exact Value to State to Judges | Where It Is in the Code |
| :--- | :--- | :--- |
| **Microphone Sample Rate** | **8,000 Hz** (16-bit signed linear PCM, mono) | `firmware:20` (`#define SAMPLE_RATE 8000`) |
| **Audio Packet Size** | **128 samples = 256 bytes PCM** (264 bytes total frame) | `firmware:21-22` (`PACKET_SAMPLES 128`) |
| **Audio Transmission Rate** | **62.5 packets/sec = 16 kB/s = 128 kbps** | `128 samples / 8000 Hz = 16 ms per packet` |
| **Button Debounce Time** | **30 milliseconds** | `firmware:54` (`BUTTON_DEBOUNCE_MS = 30`) |
| **Multi-Press Double-Tap Window** | **400 milliseconds** | `firmware:55` (`MULTI_PRESS_WINDOW_MS = 400`) |
| **Hold-to-Cancel Duration** | **3,000 milliseconds (3 seconds)** | `firmware:57` (`HOLD_DURATION_MS = 3000`) |
| **Emergency Alert Countdown** | **5 seconds default** (configurable to 3s or 10s) | `CountdownController.kt`, `SakhiPreferences.kt` |
| **Bluetooth Protocol** | **Bluetooth Classic RFCOMM SPP** (Device: `SAKHI_AUDIO`) | UUID `00001101-0000-1000-8000-00805F9B34FB` |
| **Audio Circular Buffer** | **192,000 bytes** (6 seconds of 16 kHz 16-bit audio in RAM) | `BluetoothClassicAudioReceiver.kt:75` |
| **Vosk English Acoustic Model** | **54 MB** (8 kHz telephone Kaldi model) | `app/src/main/assets/model` |
| **Vosk Hindi Acoustic Model** | **78 MB** (16 kHz wideband Kaldi model) | `app/src/main/assets/model-hi` |
| **Total Offline Speech Assets** | **132 MB** (explains why APK is 145 MB Debug / 131 MB Release)| `assets/` directory |
| **Android Unit Tests** | **306 / 306 Passing** across 22 test suites | `./gradlew testDebugUnitTest` |
| **Backend Integration Tests** | **7 / 7 Passing** in 12 ms | `cd backend && node tests/server.test.js` |
| **Android Lint Status** | **0 Errors** (`BUILD SUCCESSFUL in 18s`) | `./gradlew lintDebug` |
| **Cryptographic Security** | **128-bit Session ID, 256-bit Writer Key, 256-bit Reader Token**| `backend/sessionStore.js:65-71` |
| **Local Storage Encryption** | **AndroidKeyStore AES-256-GCM** with 12-byte IV | `KeystoreSecretStorage.kt:38-54` |
| **Hardware Prototype Cost** | **₹838 ($10.07)**; mass manufacturing target **~₹450 ($5.40)**| Bill of Materials (BOM) |
| **Battery Life (Continuous Stream)**| **~5 Hours** on 3.7V 600mAh LiPo (96.4 mA active draw) | De-rated capacity 480 mAh / 96.4 mA |

---

## 2. HARDWARE PINOUT CHEAT SHEET

```
               +--------------------------------------+
               |          ESP32-WROOM-32              |
               |                                      |
 INMP441 WS <--| GPIO 25 (Word Select / LRCLK)        |
INMP441 SCK <--| GPIO 26 (Bit Clock / BCLK)           |
 INMP441 SD -->| GPIO 33 (Serial Data In / DOUT)      |
 INMP441 L/R --| GND (Left Channel Mono)              |
               |                                      |
 BUTTON PIN ---| GPIO 27 (Configured as INPUT_PULLUP) |
 BUTTON GND ---| GND (Pressing connects GPIO 27 to GND)|
               |                                      |
     3.3V IN <-| 3.3V Output from ME6211 LDO Regulator|
      GND IN --| Common Ground (Battery, Mic, ESP32)  |
               +--------------------------------------+
```

---

## 3. PROTOCOL OPCODES (MAGIC: `'S''A''K''H'` / `0x53, 0x41, 0x4B, 0x48`)

- `0x01` (`FRAME_TYPE_AUDIO`): 256 bytes raw PCM audio (Length: `0x01, 0x00`).
- `0x02` (`FRAME_TYPE_CONTROL`): 1 byte opcode (Length: `0x00, 0x01`).
  - `Opcode 0x01`: **Double Press** -> Triggers SOS countdown.
  - `Opcode 0x02`: **3-Second Hold Complete** -> Cancels countdown or ends active emergency.
  - `Opcode 0x03`: **Triple Tap** -> Triggers instant emergency call (post-countdown only).
  - `Opcode 0x06`: **Hold Start** -> Pauses countdown while button is being held down.
  - `Opcode 0x07`: **Hold Release** -> Resumes countdown if button released before 3 seconds.

---

## 4. TOP 25 HIGH-PROBABILITY JUDGE QUESTIONS & QUICK-FIRE ANSWERS

1. **Why an ESP32 instead of an Arduino Uno or Nano?**  
   *Answer*: "The ESP32 has integrated Bluetooth Classic RFCOMM, a dual-core 32-bit processor at 160 MHz, and dedicated hardware I2S DMA peripherals required for digital microphone streaming."
2. **Why Bluetooth Classic instead of BLE?**  
   *Answer*: "Uncompressed 16-bit 8 kHz audio requires 128 kbps. BLE GATT struggles with sustained streaming and drops packets; Bluetooth Classic SPP handles continuous audio reliably."
3. **Why INMP441 digital mic instead of MAX4466 analog mic?**  
   *Answer*: "Digital I2S transmits binary bits over differential clocks, making it immune to the 2.4 GHz RF transmitter noise that causes severe buzzing on analog electret mics."
4. **Why is your APK 145 MB?**  
   *Answer*: "Because it bundles two complete offline Kaldi speech recognition models (54 MB English and 78 MB Hindi) and native JNI binaries, allowing emergency voice detection without internet."
5. **How does the app work if the phone is locked?**  
   *Answer*: "We use Full-Screen Intents on a high-priority notification to wake the screen over the keyguard, and route locked calls via `TelecomManager.placeCall()` to bypass Background Activity Launch restrictions."
6. **What happens if someone double-presses by accident?**  
   *Answer*: "A 5-second countdown begins with haptic feedback. Holding the physical button for 3 seconds cancels the countdown immediately, revoking all remote tracking and sending nothing."
7. **Can background speech like 'I need help with my homework' trigger SOS?**  
   *Answer*: "No. Single words require emergency context modifiers ('help me please', 'call police'). Even if triggered, the 5-second countdown with haptics allows the user to disarm with a 3-second hold."
8. **Why didn't you use Google Cloud Speech or Whisper?**  
   *Answer*: "Cloud APIs fail in basements, underground transit, and rural roads with zero internet, incur per-minute fees, and compromise user privacy. Vosk runs 100% locally on-device."
9. **How do you resample 8 kHz audio to 16 kHz for the Hindi model?**  
   *Answer*: "We implemented a low-overhead linear acoustic resampler in `PcmAudioResampler.kt` that computes midpoints between adjacent samples, taking less than 1.5 ms per frame."
10. **How do you prevent the Hindi model from hallucinating English words?**  
    *Answer*: "Our `BilingualTranscriptArbitrator` uses a Devanagari phonetic dictionary to detect and suppress cross-language phonetic echoes, ensuring exactly one authoritative transcript per utterance."
11. **What happens if Bluetooth disconnects during an emergency?**  
    *Answer*: "The emergency session is preserved on the phone, the audio router automatically switches to the phone's internal microphone, and the app attempts bounded exponential backoff auto-reconnection."
12. **Can an attacker track the user's live location?**  
    *Answer*: "No. Sessions use 128-bit random IDs, viewing requires a 256-bit Reader Token, updating/disarming requires a 256-bit Writer Key, and sessions auto-expire after 24 hours."
13. **What is your battery life on the wearable?**  
    *Answer*: "Approximately 5 hours of continuous audio streaming on a 600 mAh LiPo (drawing ~96.4 mA). For production, we will implement deep sleep with wake-on-interrupt for 8–10 day standby."
14. **Why not connect the battery directly to the 3.3V pin?**  
    *Answer*: "A fully charged LiPo reaches 4.2V, which exceeds the ESP32's maximum rating of 3.6V and would permanently destroy the chip. We regulate it through an ME6211 3.3V LDO."
15. **What happens if GPS is blocked indoors?**  
    *Answer*: "`LocationProvider` falls back to network/cellular triangulation and explicitly appends `(stale)` to the coordinate text in the SMS to avoid misleading emergency contacts."
16. **Why is triple-tap disabled while idle?**  
    *Answer*: "To prevent accidental 112 police calls while the keychain is in a bag. Triple-tap calling is armed strictly after an emergency countdown has expired."
17. **How many contacts can SAKHI alert?**  
    *Answer*: "Up to 5 trusted emergency contacts via direct multipart SMS using Android's native `SmsManager`."
18. **What mapping tool do you use on the tracking website?**  
    *Answer*: "Leaflet.js with OpenStreetMap (OSM) vector tiles and Server-Sent Events (SSE) for real-time live breadcrumb streaming."
19. **How does SAKHI protect audio privacy?**  
    *Answer*: "Zero-disk-storage architecture: audio exists only in transient RAM circular buffers (192,000 bytes) and is immediately overwritten. Raw audio is never saved or uploaded."
20. **What is your automated test coverage?**  
    *Answer*: "306 Android unit tests across 22 test suites and 7 backend integration tests, all passing with zero errors."
21. **What is your Android Lint status?**  
    *Answer*: "Zero errors on `./gradlew lintDebug`."
22. **What was your prototype cost?**  
    *Answer*: "₹838 ($10.07) for the physical prototype; estimated under ₹450 ($5.40) in mass production."
23. **Did you use AI to write this code?**  
    *Answer*: "Our team conceived the product architecture, selected the components, and designed the algorithms. We used AI assistants for boilerplate scaffolding, test automation, and debugging, reviewing and verifying every line ourselves."
24. **Can you demonstrate the app without physical hardware?**  
    *Answer*: "Yes, our built-in Demo Lab test bench allows complete simulation of hardware buttons, Bluetooth drops, and audio phrases directly on screen."
25. **What is your next engineering milestone?**  
    *Answer*: "Fabricating a custom 4-layer rigid-flex PCB, implementing BLE Audio LC3 codec compression, and conducting a 50-user pilot test."

---

## 5. WHAT TO DO WHEN YOU DON'T KNOW AN ANSWER

If a judge asks an obscure technical question or cites an edge case you haven't investigated:

1. **Do NOT guess or fabricate specifications.**
2. **Use this exact phrasing**:
   > *"That is an excellent engineering question. In our current prototype, we focused on verifying the core I2S streaming, dual Vosk arbitration, and lock-screen calling workflows. We have not specifically benchmarked that exact edge case on hardware yet, but our architectural approach would be [brief technical hypothesis], and we have logged it in our Phase 2 validation roadmap."*
3. **Why this works**: Judges respect intellectual honesty, self-awareness, and structured engineering methodology over confident guessing.

# SAKHI — SOURCE CODE & FUNCTION-LEVEL WALKTHROUGH

> **Document ID**: SAKHI-DOC-03  
> **Reference**: Class-by-Class, Function-by-Function Engineering Guide Across All Repository Source Files  
> **Repository Context**: Complete audit of firmware, Android Kotlin codebase, and Node.js backend.

---

## 1. EMBEDDED FIRMWARE LAYER

### File: `firmware/SAKHI_ESP32_AUDIO_BUTTON/SAKHI_ESP32_AUDIO_BUTTON.ino`
- **Location**: [`firmware/SAKHI_ESP32_AUDIO_BUTTON/SAKHI_ESP32_AUDIO_BUTTON.ino`](file:///Users/shivkumaryadav/Documents/SAKHI/firmware/SAKHI_ESP32_AUDIO_BUTTON/SAKHI_ESP32_AUDIO_BUTTON.ino)
- **Lines of Code**: 471 lines
- **Language**: C++ / Arduino / ESP-IDF Framework

#### Key Functions & Implementation Details:

1. **`setup()` (Lines 383–415)**
   - *Inputs/Outputs*: None (`void`).
   - *When Called*: Once at microcontroller boot/reset.
   - *Behavior*: Initializes UART at 115200 baud, sets GPIO 27 as `INPUT_PULLUP`, records baseline button state, calls `initializeMicrophone()` and `initializeBluetooth()`.
   - *Edge Cases*: If microphone or Bluetooth fails initialization, logs `[FATAL]` but does not crash, enabling serial diagnostic reporting.

2. **`initializeMicrophone()` (Lines 250–290)**
   - *Inputs/Outputs*: Returns `bool` (success/failure).
   - *Behavior*: Installs ESP-IDF I2S driver on `I2S_NUM_0`. Configures standard Philips I2S format, 8000 Hz, 32-bit slot, mono-left channel. Sets pin mapping: WS=GPIO 25, SCK=GPIO 26, SD=GPIO 33. Clears DMA buffer.
   - *Likely Judge Question*: *"Why use a 32-bit slot for a 24-bit microphone?"*  
     *Answer*: The INMP441 outputs 24-bit data, but the ESP32 hardware I2S peripheral operates with native 32-bit DMA alignment. We capture 32-bit words and bit-shift right by 16 (`sample = sample >> 16`) to cleanly map the MSB-aligned 24-bit audio to standard 16-bit linear PCM.

3. **`updatePhysicalButton()` (Lines 153–245)**
   - *Inputs/Outputs*: None (`void`), executed every iteration of `loop()`.
   - *Algorithm*:
     - **Debounce**: Requires 30 ms of stable reading (`now - buttonLastRawChangeMs >= BUTTON_DEBOUNCE_MS`) before accepting state change.
     - **Press Down**: On stable HIGH -> LOW transition, starts hold timer (`buttonPressStartMs = now`) and emits `OPCODE_HOLD_START` (`0x06`) to pause phone countdown.
     - **Hold Evaluation**: While held LOW, if `now - buttonPressStartMs >= 3000ms`, emits `OPCODE_HOLD_COMPLETE` (`0x02`) and sets `holdCompleteTriggered = true`.
     - **Release**: On LOW -> HIGH transition, emits `OPCODE_HOLD_RELEASE` (`0x07`). If held $\le 500\text{ ms}$, increments `shortPressCount`. If `shortPressCount >= 3`, instantly emits `OPCODE_TRIPLE_TAP` (`0x03`).
     - **Multi-Press Resolution**: When button is released and `shortPressCount == 2`, if idle for $>400\text{ ms}$, emits `OPCODE_DOUBLE_PRESS` (`0x01`).
   - *Likely Judge Question*: *"How do you prevent a 3-second hold from triggering a single-tap or double-tap on release?"*  
     *Answer*: When the hold threshold (3000 ms) is crossed, `holdCompleteTriggered` is set to `true` and `shortPressCount` is forcefully reset to 0. When the user finally releases the button, the firmware checks `holdCompleteTriggered`, bypasses the short-press counter, and clears the flag.

4. **`streamAudioPacket()` (Lines 312–351)**
   - *Inputs/Outputs*: Returns `bool`.
   - *Behavior*: Calls `i2s_read()` requesting 128 words (512 bytes). Bit-shifts samples and multiplies by `AUDIO_GAIN` (2x) with saturation clamping between -32768 and +32767. Calls `sendAudioFrame()`.
   - *Timing*: Naturally blocks for ~16 ms per 128 samples at 8 kHz, pacing the FreeRTOS loop without artificial `delay()`.

5. **`sendControlEvent(uint8_t opcode)` (Lines 84–105)**
   - *Inputs*: 1-byte opcode (`0x01`, `0x02`, `0x03`, `0x06`, `0x07`).
   - *Frame Structure*: 9 bytes total: `{'S','A','K','H'}, 0x02, 0x00, 0x01, opcode, checksum`.
   - *Checksum*: `0x02 ^ 0x00 ^ 0x01 ^ opcode`.

---

## 2. ANDROID BLUETOOTH & AUDIO PROCESSING SUBSYSTEM

### File: `app/src/main/java/com/example/sakhi/data/bluetooth/BluetoothClassicAudioReceiver.kt`
- **Location**: [`app/src/main/java/com/example/sakhi/data/bluetooth/BluetoothClassicAudioReceiver.kt`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/bluetooth/BluetoothClassicAudioReceiver.kt)
- **Lines of Code**: 597 lines
- **Key Responsibilities**:
  - Manages RFCOMM socket connection to ESP32 device (`SAKHI_AUDIO`).
  - Implements thread-safe bounded `AudioCircularBuffer`.
  - Dispatches raw PCM chunks and control events to Kotlin Flows.
  - Implements bounded exponential backoff auto-reconnection.

#### Core Inner Classes & Functions:
1. **`AudioCircularBuffer` (Lines 75–131)**:
   - *Capacity*: Fixed 192,000 bytes (6 seconds of 16 kHz 16-bit mono audio).
   - *Thread Safety*: Synchronized using an internal `lock: Any`.
   - *Methods*: `write(data, offset, length)`, `read(dest, offset, length)`, `snapshot()`, `snapshotRecent(maxBytes)`.
   - *Protection*: Guarantees bounded memory footprint; write pointer wraps around, overwriting oldest data if buffer is not consumed, preventing OOM crashes.
2. **`connect(targetAddress, isAutoRetry)` (Lines 253–402)**:
   - *Guards*: If already `CONNECTED` or `STREAMING` on an open socket to the same address, ignores redundant calls (prevents tearing down healthy socket during Jetpack Compose recomposition).
   - *Discovery*: Cancels Bluetooth discovery (`cancelDiscovery()`) prior to `socket.connect()` to prevent 50% RF throughput degradation per Android guidelines.
3. **`scheduleAutoReconnect()` (Lines 407–439)**:
   - Bounded backoff: Calculates delay using $\min(1500\text{ ms} \times 2^{\text{attempt}-1},\ 12000\text{ ms})$.
   - Aborts if attempts exceed `MAX_RECONNECT_ATTEMPTS = 10`.

---

### File: `app/src/main/java/com/example/sakhi/data/bluetooth/SakhiStreamFramer.kt`
- **Location**: [`app/src/main/java/com/example/sakhi/data/bluetooth/SakhiStreamFramer.kt`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/bluetooth/SakhiStreamFramer.kt)
- **Key Responsibilities**:
  - Implements stateful byte-by-byte streaming frame parser.
  - States: `SEEKING_MAGIC` -> `READING_TYPE` -> `READING_LENGTH` -> `READING_PAYLOAD` -> `VERIFYING_CHECKSUM`.
  - Enforces complete sample integrity: Guarantees delivered audio chunks have an even number of bytes (complete 16-bit PCM samples).

---

### File: `app/src/main/java/com/example/sakhi/data/audio/UnifiedAudioRouter.kt`
- **Location**: [`app/src/main/java/com/example/sakhi/data/audio/UnifiedAudioRouter.kt`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/audio/UnifiedAudioRouter.kt)
- **Key Responsibilities**:
  - Seamlessly multiplexes between the **ESP32 Bluetooth microphone** and the **built-in phone microphone** (`PhoneMicrophoneAudioSource`).
  - Active audio source switching: If Bluetooth disconnects during an emergency, the router automatically fails over to the phone microphone within 100 ms without interrupting speech recognition.
  - Source Transition Listener: Triggers a state flush on `SpeexNoiseSuppressor` and Kaldi acoustic lattices whenever the audio source switches, preventing transient acoustic popping artifacts from causing false speech triggers.

---

### File: `app/src/main/java/com/example/sakhi/data/audio/SpeexNoiseSuppressor.kt` & `LibSpeexDsp.kt`
- **Location**: [`app/src/main/java/com/example/sakhi/data/audio/SpeexNoiseSuppressor.kt`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/audio/SpeexNoiseSuppressor.kt)
- **Key Responsibilities**:
  - JNI wrapper around the native SpeexDSP C library (`libspeexdsp.so`, compiled for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`).
  - Operates on 8,000 Hz audio frames (160 samples = 320 bytes per 20 ms frame).
  - Integrates:
    - Preprocessor denoiser (suppresses up to -30 dB of stationary background hum).
    - Automatic Gain Control (AGC) with target level 8000 and max gain 30 dB.
    - Voice Activity Detector (VAD).

---

### File: `app/src/main/java/com/example/sakhi/data/audio/PcmAudioResampler.kt`
- **Location**: [`app/src/main/java/com/example/sakhi/data/audio/PcmAudioResampler.kt`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/audio/PcmAudioResampler.kt)
- **Key Responsibilities**:
  - Implements lightweight, real-time linear acoustic resampling from 8,000 Hz to 16,000 Hz (2x upsampling).
  - For each input sample $S_i$ and $S_{i+1}$, computes the midpoint:
    $$S_{\text{mid}} = \frac{S_i + S_{i+1}}{2}$$
  - Enables the 16 kHz Hindi Vosk model to consume the 8 kHz ESP32 audio stream with negligible CPU overhead ($<1.5\text{ ms}$ per frame).

---

## 3. SPEECH RECOGNITION & BILINGUAL ARBITRATION

### File: `app/src/main/java/com/example/sakhi/data/assistant/VoskAudioTranscriptionService.kt`
- **Location**: [`app/src/main/java/com/example/sakhi/data/assistant/VoskAudioTranscriptionService.kt`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/assistant/VoskAudioTranscriptionService.kt)
- **Lines of Code**: 1,234 lines
- **Key Responsibilities**:
  - Manages lifecycle of English and Hindi Vosk models.
  - Enforces `RecognitionSessionType`: `DIAGNOSTIC` (blocks emergency triggers) vs `PRODUCTION_SOS` (authoritative emergency callbacks enabled).
  - Manages recognition loop on `Dispatchers.Default`.
  - Feeds candidate decodes to `BilingualTranscriptArbitrator`.

---

### File: `app/src/main/java/com/example/sakhi/data/assistant/BilingualTranscriptArbitrator.kt`
- **Location**: [`app/src/main/java/com/example/sakhi/data/assistant/BilingualTranscriptArbitrator.kt`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/assistant/BilingualTranscriptArbitrator.kt)
- **Lines of Code**: 773 lines
- **Key Responsibilities**:
  - Evaluates competing candidate transcripts from English and Hindi decoders.
  - Invariant: Never selects a transcript solely because it matches a safety keyword.
  - Evaluates:
    - Average word confidence from Kaldi posteriors (`"conf": 0.92`).
    - Word count & linguistic completeness (penalizes single-word unk artifacts).
    - Cross-language phonetic echo filtering using `ENGLISH_TO_DEVANAGARI_PHONETICS` dictionary.
    - Suppresses duplicate repeats within 3,500 ms.
    - Suppresses conflicting secondary decodes within 1,000 ms.

---

## 4. EMERGENCY STATE & ESCALATION CORE

### File: `app/src/main/java/com/example/sakhi/domain/emergency/EmergencyStateManager.kt`
- **Location**: [`app/src/main/java/com/example/sakhi/domain/emergency/EmergencyStateManager.kt`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/domain/emergency/EmergencyStateManager.kt)
- **Lines of Code**: 1,638 lines
- **Key Responsibilities**:
  - The central authoritative state machine of the SAKHI application.
  - Manages `_state: MutableStateFlow<EmergencyState>`.
  - Workflows handled:
    - `handleDoublePress()`: Activates emergency, starts countdown, initiates live tracking, begins location capture.
    - `handleTapHold()`: Cancels countdown or ends active emergency session; revokes remote tracking session.
    - `handleTripleTap()`: Evaluates manual police escalation; strictly blocked while idle or counting down.
    - `onSpeechSegmentRecognized()`: Ingests authoritative transcripts and evaluates risk escalation.
    - `startPeriodicLocationUpdateLoop()`: Sends periodic 60-second SMS updates and backend location breadcrumbs.

---

### File: `app/src/main/java/com/example/sakhi/domain/escalation/PoliceCallManager.kt`
- **Location**: [`app/src/main/java/com/example/sakhi/domain/escalation/PoliceCallManager.kt`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/domain/escalation/PoliceCallManager.kt)
- **Lines of Code**: 210 lines
- **Key Responsibilities**:
  - Manages telephone call escalation under Android operating system security constraints.
  - Inspects `KeyguardManager.isDeviceLocked()`.
  - If device is locked:
    - Non-emergency contact numbers -> Routed to `TelecomManager.placeCall()`.
    - Emergency helplines ("112", "911") -> Routed to `Intent.ACTION_DIAL`, launching the system emergency dialer over keyguard.

---

### File: `app/src/main/java/com/example/sakhi/service/EmergencyForegroundService.kt`
- **Location**: [`app/src/main/java/com/example/sakhi/service/EmergencyForegroundService.kt`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/service/EmergencyForegroundService.kt)
- **Lines of Code**: 546 lines
- **Key Responsibilities**:
  - Android 14+ typed foreground service: `location | microphone | connectedDevice | phoneCall`.
  - Binds high-priority ongoing notification with emergency countdown status.
  - Attaches Full-Screen Intent (`setFullScreenIntent`) to turn on the screen and present the emergency UI over the keyguard.
  - Returns `START_STICKY` to ensure automatic recovery if killed under extreme memory pressure.

---

## 5. BACKEND & WEB TRACKING SUBSYSTEM

### File: `backend/server.js`
- **Location**: [`backend/server.js`](file:///Users/shivkumaryadav/Documents/SAKHI/backend/server.js)
- **Lines of Code**: 321 lines
- **Technology**: Node.js core HTTP server (Zero external npm framework dependencies like Express, maximizing execution speed and minimizing attack surface).
- **Key Routes**:
  - `POST /api/sessions`: Creates new emergency tracking session.
  - `POST /api/sessions/:id/location`: Appends live coordinates (requires `X-Writer-Key`).
  - `POST /api/sessions/:id/cancel`: Ends session (requires `X-Writer-Key`).
  - `GET /api/sessions/:id`: Returns public tracking state (requires `?token=...`).
  - `GET /api/sessions/:id/stream`: Server-Sent Events (SSE) real-time stream.
  - `GET /track/:id`: Serves Leaflet OSM tracking web application (`track.html`).

---

### File: `backend/sessionStore.js`
- **Location**: [`backend/sessionStore.js`](file:///Users/shivkumaryadav/Documents/SAKHI/backend/sessionStore.js)
- **Lines of Code**: 338 lines
- **Key Responsibilities**:
  - In-memory `Map<sessionId, Session>` backed by persistent JSON file (`sessions.json`).
  - Cryptographic token generation:
    - `id`: 16 bytes random hex (`crypto.randomBytes(16)` = 128 bits).
    - `trackingToken`: 32 bytes random hex (`crypto.randomBytes(32)` = 256 bits).
    - `writerKey`: 32 bytes random hex (`crypto.randomBytes(32)` = 256 bits).
  - Validation: Coordinate bounds check (lat: -90..90, lng: -180..180).
  - History cap: Retains maximum 120 breadcrumbs per session.
  - Event Emitter: Emits `update:<sessionId>` and `ended:<sessionId>` to SSE streams.
  - Automatic purging: Deletes expired sessions older than 24 hours.

# SAKHI — COMPLETE PRODUCT & ENGINEERING DEFENSE HANDBOOK

> **Document ID**: SAKHI-DOC-01  
> **Target Audience**: SAKHI Student Defense Team (4 Members), Product-a-Thon Technical Judges, Evaluators  
> **System Name**: SAKHI ("Friend" / "Companion" in Hindi)  
> **Package**: `com.example.sakhi`  
> **Target OS**: Android 7.0 (API 24) to Android 15/16 (API 36)  
> **Embedded Hardware**: ESP32-WROOM-32 Development Board + INMP441 I2S MEMS Microphone  
> **Communication Protocol**: Bluetooth Classic Serial Port Profile (RFCOMM SPP, UUID: `00001101-0000-1000-8000-00805F9B34FB`)

---

## 1. EXECUTIVE OVERVIEW & PROBLEM DEFINITION

### 1.1 The Critical Problem
Personal safety for women, vulnerable individuals, and solo night workers remains one of the most pressing socio-technical challenges in India and globally. According to official crime statistics (such as the National Crime Records Bureau of India), crimes against women frequently occur in transit, poorly lit public areas, and isolated workspaces.

In real-world threatening situations, traditional safety solutions fail due to three fundamental human and technical factors:
1. **The Reach-for-Phone Dilemma**: If a woman is being stalked, physically assaulted, or intimidated in a cab or street, reaching into a bag, unlocking a smartphone, finding a safety app, and swiping an on-screen SOS button is either physically impossible, escalates the aggressor's violence if noticed, or takes 15–30 seconds—far too long when an attack happens in seconds.
2. **The Voice Hesitation & Incapacitation Factor**: Loud alarms or screaming can provoke immediate violence or choking. Conversely, quiet distress or verbal threats ("मुझे जाने दो", "Stop following me") go completely unnoticed by smartphones locked in pockets.
3. **The Unreliable Infrastructure Trap**: Purely cloud-dependent safety apps fail in basements, underground metros, rural transit corridors, or crowded markets where cellular data drops. Standalone SIM-based hardware trackers require separate monthly cellular subscriptions, suffer from high battery drain, and are frequently abandoned when subscriptions lapse or batteries die after 12 hours.

### 1.2 The SAKHI Solution
**SAKHI** is a hybrid wearable and smartphone safety ecosystem that bridges immediate physical tactile reaction, continuous acoustic distress intelligence, and automated emergency escalation:
- **Discreet Physical Wearable**: An ultra-compact keychain or pendant housing an ESP32-WROOM microcontroller, a physical tactile switch, and an INMP441 digital I2S omnidirectional microphone. It requires zero visual attention to operate.
- **Continuous Ambient Audio Streaming**: Streams 8,000 Hz 16-bit linear PCM audio continuously over an RFCOMM Bluetooth Classic connection to the user's paired smartphone.
- **Dual Offline Speech Recognition**: Employs two on-device Kaldi/Vosk speech recognition engines (English at 8 kHz and Hindi at 16 kHz after linear acoustic resampling) running entirely locally inside an Android Foreground Service without needing an internet connection.
- **Objective Transcript Arbitration**: A mathematical arbitrator evaluates spoken words across both models, suppressing phonetic echoes, cross-talk, and false alarms, and dispatches authoritative transcripts to an emergency risk analyzer.
- **Layered Emergency Workflows**:
  - **Double-Press Physical SOS**: Triggers an immediate 5-second emergency countdown with haptic feedback.
  - **Hold-to-Cancel (3 Seconds)**: Cancels pending countdowns if triggered accidentally, preventing false alarms.
  - **Triple-Tap "Call Now"**: Available after countdown expiry to instantly escalate to emergency services (112) or a primary emergency contact without touching the phone.
  - **Automated Contact Dispatch**: Sends multi-part SMS alerts containing fresh GPS coordinates, accuracy tolerances, and a live web tracking link to up to 5 emergency contacts.
  - **Ephemeral Live Tracking Gateway**: A dedicated Node.js service offering real-time Server-Sent Events (SSE) breadcrumb tracking on an interactive OpenStreetMap interface for family members and police.

---

## 2. TARGET USERS & USE-CASE SCENARIOS

| Persona / User Group | Operating Environment | Primary Threat Model | How SAKHI Protects Them |
| :--- | :--- | :--- | :--- |
| **College Student / Young Professional** | Commuting alone after dark, walking through dimly lit metro stations, taking auto-rickshaws or cabs. | Stalking, verbal harassment, kidnapping, diversion from route. | Keychain is gripped casually in a coat pocket or handbag. A double-press triggers SOS without pulling out the phone; speech monitoring captures cab driver arguments. |
| **Night-Shift Worker (Healthcare, BPO, Hospitality)** | Empty industrial corridors, hospital parking lots, irregular public transit hours. | Sudden ambush, isolated physical assault, intimidation. | Tactile button provides instant, eyes-free trigger. If phone is locked in handbag, Full-Screen Intent wakes screen and initiates escalation call. |
| **Solo Traveler / Jogger** | Parks, trails, suburban roads with intermittent data coverage. | Medical distress, physical attack, severe harassment. | Offline Vosk models detect emergency phrases ("help me", "bachao") even when 4G/5G mobile data is absent; SMS and calling use standard cellular voice/SMS towers. |
| **Domestic Harassment / Vulnerable Individuals** | Indoors, shared living spaces, coercive control situations. | Inability to openly dial police without retaliation. | Quiet physical hold or spoken keywords trigger background alert dispatch to trusted guardians with live location. |

---

## 3. COMPLETE SYSTEM ARCHITECTURE

```
+-------------------------------------------------------------------------------------------------+
|                                  PHYSICAL WEARABLE (ESP32-WROOM)                                |
|                                                                                                 |
|   +-----------------------+     +-------------------------+     +---------------------------+   |
|   | INMP441 I2S Mic       |     | Tactile Pushbutton      |     | 3.7V 600mAh LiPo Battery  |   |
|   | 8kHz 16-bit Mono PCM  |     | GPIO 27 (INPUT_PULLUP)  |     | TP4056 + ME6211 3.3V LDO  |   |
|   +-----------+-----------+     +------------+------------+     +-------------+-------------+   |
|               |                              |                                |                 |
|               v                              v                                v                 |
|   +-----------------------------------------------------------------------------------------+   |
|   | FreeRTOS Task / Arduino Main Loop: Non-blocking button debouncer (30ms) & multi-press   |   |
|   | SAKH Binary Multiplexed Framer: Type 0x01 (Audio: 256B) | Type 0x02 (Control: Opcode)   |   |
|   +------------------------------------------+----------------------------------------------+   |
+----------------------------------------------|--------------------------------------------------+
                                               | Bluetooth Classic RFCOMM SPP
                                               | Device: SAKHI_AUDIO (Baud: 115200 serial)
                                               v
+-------------------------------------------------------------------------------------------------+
|                                    ANDROID SMARTPHONE APP (Kotlin)                              |
|                                                                                                 |
|   +-----------------------------------------------------------------------------------------+   |
|   | EmergencyForegroundService (Sticky Service with location, mic, phoneCall, connectedDev) |   |
|   +------------------------------------------+----------------------------------------------+   |
|                                              |                                                  |
|                      +-----------------------+-----------------------+                          |
|                      v                                               v                          |
|   +-------------------------------------+         +-------------------------------------+       |
|   | BluetoothClassicAudioReceiver       |         | LocationProvider                    |       |
|   | - SakhiStreamFramer (Checksum check)|         | - Fused / GPS / Network Triangulation|      |
|   | - AudioCircularBuffer (192,000 B)   |         | - Location Validation (<30s, acc<50m) |     |
|   +------------------+------------------+         +------------------+------------------+       |
|                      | 8kHz PCM                                      | Lat, Lng, Accuracy       |
|                      v                                               |                          |
|   +-------------------------------------+                            |                          |
|   | UnifiedAudioRouter (ESP32 vs Phone) |                            |                          |
|   | -> SpeexDSP Noise Suppressor (8kHz) |                            |                          |
|   +------------------+------------------+                            |                          |
|                      | Clean 8kHz PCM                                |                          |
|           +----------+----------+                                    |                          |
|           | 8kHz                | Upsampled 16kHz (Linear)           |                          |
|           v                     v                                    |                          |
|   +---------------+     +---------------+                            |                          |
|   | Vosk English  |     | Vosk Hindi    |                            |                          |
|   | Recognizer    |     | Recognizer    |                            |                          |
|   +-------+-------+     +-------+-------+                            |                          |
|           | Text + Conf         | Text + Conf                        |                          |
|           +----------+----------+                                    |                          |
|                      v                                               |                          |
|   +-------------------------------------+                            |                          |
|   | BilingualTranscriptArbitrator       |                            |                          |
|   | - Cross-language echo suppression   |                            |                          |
|   | - Word posterior confidence check   |                            |                          |
|   | - Exactly 1 dispatch per utterance  |                            |                          |
|   +------------------+------------------+                            |                          |
|                      | Authoritative Spoken Transcript               |                          |
|                      v                                               |                          |
|   +------------------------------------------------------------------+----------------------+   |
|   | EmergencyStateManager (Authoritative State Machine)                                     |   |
|   | - Double-Press -> 5s Countdown -> Alert Dispatch                                        |   |
|   | - 3s Hold -> Cancel Session & Revoke Remote Live Tracking                               |   |
|   | - Post-Countdown Triple-Tap -> Instant Police / Emergency Escalation Call              |   |
|   +----+-----------------------------+-------------------------------+-----------------+----+   |
|        |                             |                               |                 |        |
|        v                             v                               v                 v        |
|   +-----------+               +---------------+               +--------------+   +------------+ |
|   | Compose   |               | Alert         |               | PoliceCall   |   | LiveTrack  | |
|   | UI Layer  |               | Dispatcher    |               | Manager      |   | Client     | |
|   | (Dark M3) |               | (Direct SMS)  |               | (TelecomMgr) |   | (HTTP/JSON)| |
|   +-----------+               +-------+-------+               +-------+------+   +-----+------+ |
+---------------------------------------|-------------------------------|----------------|--------+
                                        | Cellular Radio                | Voice Radio    | Internet
                                        v                               v                v
                             +--------------------+            +--------------+  +---------------+
                             | Trusted Contacts   |            | Police (112) |  | Live Tracking |
                             | (Up to 5 SMS)      |            | or Guardian  |  | Node.js / SSE |
                             +--------------------+            +--------------+  +---------------+
```

---

## 4. HARDWARE ENGINEERING DEEP DIVE

### 4.1 Component Selection & Specifications

#### 1. Microcontroller: ESP32-WROOM-32 (Espressif Systems)
- **Architecture**: Xtensa® dual-core 32-bit LX6 microprocessor operating at up to 240 MHz.
- **Memory**: 520 KB internal SRAM, 4 MB external SPI flash.
- **Wireless Interfaces**: Wi-Fi 802.11 b/g/n (up to 150 Mbps) and Bluetooth v4.2 BR/EDR (Bluetooth Classic) & BLE.
- **Why Chosen**:
  - Dual cores allow Core 0 to dedicate its time to I2S DMA audio capture and button debouncing while Core 1 handles the heavy Bluetooth Classic RFCOMM stack.
  - Native Bluetooth Classic support with high-bandwidth SPP profile.
  - Low cost (~₹280–₹350 / $3.50–$4.20) and massive community hardware documentation.
- **Alternative Evaluated**:
  - *ESP32-S3*: Newer, supports USB-OTG and vector instructions for TinyML, but dropped Bluetooth Classic BR/EDR (supports only BLE 5.0). Since BLE struggles with uncompressed 16-bit 8kHz continuous audio streaming (128 kbps), ESP32-WROOM with Bluetooth Classic was chosen for the prototype.
  - *nRF52840*: Excellent BLE ultra-low power, but lacks Bluetooth Classic audio streaming and requires external proprietary codecs (Opus/LC3) requiring custom firmware licenses.

#### 2. Digital Microphone: INMP441 (InvenSense / TDK)
- **Type**: Omnidirectional MEMS (Micro-Electro-Mechanical Systems) microphone with integrated I2S interface.
- **Output Format**: 24-bit I2S data in standard 32-bit slot, mapped to 16-bit PCM by bit-shifting (`>> 16`).
- **Acoustic Specs**: Sensitivity: -26 dBFS at 1 kHz, 94 dB SPL; SNR: 61 dBA; Flat frequency response from 60 Hz to 15 kHz.
- **Electrical**: Supply voltage 1.8V to 3.3V (typically 3.3V), low current consumption (~1.4 mA).
- **Why Digital I2S over Analog (e.g., MAX4466 / MAX9814)**:
  - Analog electret microphones require an external op-amp, an ADC pin on the ESP32, and are notoriously vulnerable to 2.4 GHz RF noise coupling from the ESP32’s Wi-Fi/Bluetooth transmitter (the classic "buzzing" sound).
  - INMP441 converts sound to digital binary directly inside the silicon package. The I2S bus transmits digital bits over differential clocking, making it impervious to RF noise.

#### 3. Power Supply Subsystem
- **Battery**: 3.7 V single-cell Lithium-Polymer (LiPo), 600 mAh capacity, equipped with an integrated Seiko battery protection circuit (over-charge protection at 4.28V, over-discharge cutoff at 2.4V, short-circuit current cutoff at 2.5A).
- **Charger Module**: TP4056 Linear Li-Ion Charger with Micro-USB/USB-C input, pre-set to 500 mA charge current (using a 2.4 kΩ PROG resistor to ensure charging rate does not exceed 1C for the 600 mAh cell).
- **Voltage Regulator**: Low-Dropout (LDO) linear regulator (e.g., ME6211 or AMS1117-3.3).
  - Input: 3.5 V–4.2 V from LiPo.
  - Output: Regulated 3.3 V (up to 500 mA peak output current).
  - Dropout Voltage: 100 mV at 100 mA for ME6211.
- **Peak Current Considerations**:
  - During Bluetooth Classic connection negotiation and RF transmission bursts, the ESP32 draws instantaneous peak currents between 280 mA and 380 mA for 10–50 ms.
  - An input ceramic decoupling capacitor (10 µF) and output tantalum/ceramic capacitor (22 µF) are essential on the 3.3V rail to prevent transient voltage dips below 2.7V, which would trigger the ESP32 Brownout Reset (BOR).

### 4.2 Verified Pin Allocation Table

| Peripheral | Functional Signal | ESP32-WROOM GPIO | Hardware Wiring Notes |
| :--- | :--- | :--- | :--- |
| **INMP441 Mic** | Word Select (WS / LRCLK) | **GPIO 25** | Configured in I2S driver as `ws_io_num` |
| **INMP441 Mic** | Serial Clock (SCK / BCLK) | **GPIO 26** | Configured in I2S driver as `bck_io_num` |
| **INMP441 Mic** | Serial Data (SD / DOUT) | **GPIO 33** | Configured in I2S driver as `data_in_num` |
| **INMP441 Mic** | L/R Channel Select | **GND** | Pulled LOW to select LEFT channel mono |
| **INMP441 Mic** | VDD & GND | **3.3V & GND** | Clean decoupling near VDD pin |
| **Pushbutton** | Contact Pin 1 | **GPIO 27** | Configured as `INPUT_PULLUP` (Internal 45 kΩ pullup) |
| **Pushbutton** | Contact Pin 2 | **GND** | Pressing button connects GPIO 27 to Ground (Active-LOW) |
| **Vibration Motor** | Gate / Base Trigger | **GPIO 14** *(Planned)* | Driven via N-channel MOSFET (AO3400) + flyback diode |

---

## 5. FIRMWARE & COMMUNICATION PROTOCOL DEEP DIVE

### 5.1 Program Structure (`SAKHI_ESP32_AUDIO_BUTTON.ino`)
The firmware is built using the Arduino-ESP32 core utilizing FreeRTOS primitives.

1. **`setup()` Sequence**:
   - Initializes Serial debug monitor at 115,200 baud.
   - Configures GPIO 27 as `INPUT_PULLUP` and reads initial baseline state.
   - Calls `initializeMicrophone()`: Configures ESP-IDF I2S driver (`i2s_driver_install`, `i2s_set_pin`, zero DMA buffers).
   - Calls `initializeBluetooth()`: Starts `BluetoothSerial` with broadcast name `"SAKHI_AUDIO"`.
2. **`loop()` Sequence**:
   - `updatePhysicalButton()`: Evaluates the debounced state machine, hold timers, and multi-tap counters.
   - Evaluates Bluetooth client connection (`SerialBT.hasClient()`).
   - If connected, invokes `streamAudioPacket()`: Reads 128 samples from I2S DMA, maps to 16-bit PCM, wraps into a binary frame, and writes to `SerialBT`.
   - If disconnected, executes low-power delay (10 ms) to yield CPU to the RTOS IDLE task.

### 5.2 Button State Machine & Timing Discipline
Distinguishing short taps, double presses, triple taps, and long holds on a single physical switch without blocking audio transmission requires strict state-machine discipline:

```
[Button Pin HIGH (Released)]
           |
   (Pressed: reading == LOW) -> Debounce 30ms -> Stable LOW
           |
           +---> buttonPressStartMs = now; holdCompleteTriggered = false;
           |     sendControlEvent(OPCODE_HOLD_START: 0x06); // Pause phone countdown
           |
           |---> [While Button Held LOW]
           |     If (now - buttonPressStartMs >= 3000ms):
           |         holdCompleteTriggered = true;
           |         sendControlEvent(OPCODE_HOLD_COMPLETE: 0x02); // Cancel / End session
           |
           +---> (Released: reading == HIGH) -> Debounce 30ms -> Stable HIGH
                 |
                 +---> If holdCompleteTriggered:
                 |         Reset holdCompleteTriggered; shortPressCount = 0;
                 |
                 +---> Else (Released before 3000ms):
                           sendControlEvent(OPCODE_HOLD_RELEASE: 0x07); // Resume countdown
                           If (pressDuration <= 500ms): // Valid short tap
                               shortPressCount++;
                               buttonLastReleaseMs = now;
                               If (shortPressCount >= 3):
                                   sendControlEvent(OPCODE_TRIPLE_TAP: 0x03); // Call Now
                                   shortPressCount = 0;
                           Else: // 500ms < duration < 3000ms
                               shortPressCount = 0; // Rejected ambiguous press

[While Button Released HIGH & shortPressCount > 0]
   If (now - buttonLastReleaseMs > 400ms): // Multi-press window expired
       If (shortPressCount == 2):
           sendControlEvent(OPCODE_DOUBLE_PRESS: 0x01); // Trigger SOS
       shortPressCount = 0;
```

### 5.3 Custom Binary Framing Protocol (`SAKH`)
To stream both high-throughput continuous audio and low-latency hardware control events across a single serial RFCOMM pipe without mutual corruption, the firmware multiplexes packets using a 4-byte synchronization magic word:

#### 1. Audio Packet (Total 264 Bytes)
```
+--------------------+------------+------------------+-----------------------+----------+
| Magic 'S''A''K''H' | Type: 0x01 | Length: 0x01,0x00| Audio PCM (256 Bytes) | Checksum |
| (4 Bytes: 0x53..48)| (1 Byte)   | (2 Bytes = 256)  | (128 samples x 16-bit)| (1 Byte) |
+--------------------+------------+------------------+-----------------------+----------+
```
- **Throughput**: 128 samples at 8,000 Hz = 16 ms of audio per packet.  
  Transmission rate: 62.5 packets per second $\times$ 264 bytes $\approx$ 16,500 bytes/sec (~132 kbps).

#### 2. Control Event Packet (Total 9 Bytes)
```
+--------------------+------------+------------------+-----------------------+----------+
| Magic 'S''A''K''H' | Type: 0x02 | Length: 0x00,0x01| OpCode (1 Byte)       | Checksum |
| (4 Bytes: 0x53..48)| (1 Byte)   | (2 Bytes = 1)    | (e.g., 0x01, 0x02...) | (1 Byte) |
+--------------------+------------+------------------+-----------------------+----------+
```

#### 3. Supported OpCodes
- `0x01` (`OPCODE_DOUBLE_PRESS`): Triggers 5-second countdown on Android phone.
- `0x02` (`OPCODE_HOLD_COMPLETE`): Triggers cancellation (during countdown) or ends active session.
- `0x03` (`OPCODE_TRIPLE_TAP`): Initiates immediate call to emergency escalation contact (post-countdown).
- `0x06` (`OPCODE_HOLD_START`): Instructs Android to pause countdown ticking while button is being held.
- `0x07` (`OPCODE_HOLD_RELEASE`): Resumes countdown ticking if released prior to 3 seconds.

#### 4. XOR Checksum
Calculated over Type, Length (High/Low), and Payload:
$$\text{Checksum} = \text{Type} \oplus \text{Len}_{H} \oplus \text{Len}_{L} \oplus \bigoplus_{i=0}^{N-1} \text{Payload}[i]$$
Any corrupted packet caused by RF interference is silently rejected by Android's `SakhiStreamFramer`.

---

## 6. SPEECH RECOGNITION & BILINGUAL ARBITRATION

### 6.1 The Speech Processing Stack

```
   Raw 8 kHz 16-bit PCM (from ESP32 or Phone Mic)
                     |
                     v
   [SpeexDSP Noise Suppressor] (Native C library via JNI)
   - 8 kHz frame processing (160 samples / 20 ms)
   - Suppresses stationary ambient background noise
   - Automatic Gain Control (AGC) prevents loud distortion
                     |
         +-----------+-----------+
         | 8 kHz                 | Upsampled to 16 kHz
         v                       v
   [English Recognizer]    [PcmAudioResampler]
   - Vosk 8 kHz Model      - Linear Interpolation
   - Vocabulary Grammar    - 8000 Hz -> 16000 Hz
   - Distress Keywords           |
         |                       v
         |                 [Hindi Recognizer]
         |                 - Vosk 16 kHz Model
         |                 - Open Devanagari Vocabulary
         |                 - Hindi Distress Keywords
         |                       |
         +-----------+-----------+
                     | Candidate Transcripts + Confidence
                     v
   [BilingualTranscriptArbitrator]
   - Word posterior probability validation
   - Completeness & multi-word coherence bonus
   - Competing cross-language phonetic echo suppression
   - Duplicate suppression window (3500 ms)
   - Conflicting decode window (1000 ms)
                     |
                     v
   Exactly ONE Authoritative Spoken Segment Dispatched
```

### 6.2 Offline Vosk Engine Rationale
- **Zero Internet Dependency**: Voice distress detection works in remote roads, parking garages, and cell-dead zones.
- **Zero Privacy Invasiveness**: Audio is streamed into local RAM and fed directly into the Kaldi acoustic decoder. Audio bytes are never saved to disk, never uploaded to a cloud server, and never logged.
- **Bundled Models**:
  - English: `app/src/main/assets/model` (54 MB, 8 kHz acoustic model).
  - Hindi: `app/src/main/assets/model-hi` (78 MB, 16 kHz acoustic model).

### 6.3 Disambiguation & Arbitrator Invariants
The `BilingualTranscriptArbitrator` strictly enforces five critical invariants:
1. **No Artificial Keyword Preference**: A transcript is NEVER chosen simply because it contains the word "help" or "bachao". It must win on acoustic posterior confidence and completeness.
2. **Grounding**: Real word-level posterior confidences from Vosk JSON (`"conf": 0.94`) are evaluated. If Vosk does not output confidence, no synthetic values are invented.
3. **Cross-Language False Echo Suppression**: When a user shouts in English ("Help me please"), the Hindi model often outputs phonetically similar nonsense Hindi words ("हेल्प मी"). The arbitrator identifies English phonetic homophones in Devanagari and suppresses the echo.
4. **Single Dispatch**: Guarantees exactly one authoritative transcript is delivered to the `EmergencyRiskAnalyzer` per utterance, preventing duplicate emergency triggers.

---

## 7. EMERGENCY LIFECYCLE, SMS, CALLING & LOCK-SCREEN HARDENING

### 7.1 Authoritative State Machine (`EmergencyStateManager.kt`)
The emergency state machine enforces strict separation between session progression, contact SMS delivery, and police escalation:

```
                  +-----------------------------------+
                  |           IDLE STATE              |
                  +-----------------+-----------------+
                                    |
          Double-Press Event        |   (Triple-Tap strictly IGNORED)
                                    v
                  +-----------------------------------+
                  |         ACTIVATING STATE          |
                  |  - 5-Second Countdown Active      |
                  |  - Haptic: 1 Long Pulse           |
                  |  - GPS Acquisition Initiated      |
                  |  - Live Tracking Session Created  |
                  +---------+---------------+---------+
                            |               |
       Hold Button 3s       |               | Countdown Ticks to 0
                            v               v
            +-------------------+       +-----------------------------------+
            | CANCELLED STATE   |       |          ACTIVE STATE             |
            | - "CANCELLED_     |       | - Emergency SMS Dispatched        |
            |    BEFORE_ALERT"  |       | - Live Tracking URL Active        |
            | - Remote Revoked  |       | - Periodic 60s Location Loop      |
            | - Haptic: 2 Pulses|       | - Triple-Tap Calling NOW ARMED    |
            +-------------------+       +---------+-----------------+-------+
                                                  |                 |
                             Triple-Tap Event     |                 | Hold Button 3s
                                                  v                 v
                                        +-------------------+   +-------------------+
                                        | POLICE ESCALATION |   | CANCELLED STATE   |
                                        | - Call Placed     |   | - "ALERT_SENT_    |
                                        | - Lock-Screen     |   |    THEN_CANCELLED"|
                                        |   Dialer Launched |   | - Follow-up Safe  |
                                        +-------------------+   |   SMS Dispatched  |
                                                                +-------------------+
```

### 7.2 Lock-Screen Emergency Calling Resolution
On Android 10+ (API 29–36), calling `startActivity(Intent(ACTION_CALL))` from a background service when the screen is locked is dropped by Android's Background Activity Launch (BAL) restrictions. Furthermore, calling `"112"` directly via `ACTION_CALL` throws a `SecurityException` because emergency helpline numbers require `android.permission.CALL_PRIVILEGED` (restricted exclusively to system dialers).

**SAKHI's Triple Hardening Resolution**:
1. **Device Lock Detection**: [`PoliceCallManager.kt`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/domain/escalation/PoliceCallManager.kt) checks `KeyguardManager.isDeviceLocked()`.
2. **`TelecomManager` API**: When locked, non-emergency contact calls are placed directly using `TelecomManager.placeCall()`, which operates via Android's native telecom subsystem and bypasses BAL restrictions.
3. **Emergency Helpline Routing**: If the target number is `"112"` or `"911"`, SAKHI recognizes it via `PhoneNumberUtils.isEmergencyNumber()` and launches the system emergency dialer using `Intent.ACTION_DIAL`, ensuring carrier compliance.
4. **Keyguard Penetration**: `MainActivity` specifies `showWhenLocked="true"` and `turnScreenOn="true"`, while `EmergencyForegroundService` attaches a Full-Screen Intent to the emergency notification, turning on the phone display over the keyguard.

---

## 8. BACKEND LIVE TRACKING ARCHITECTURE

### 8.1 Backend Implementation (`backend/`)
- **Technology**: Node.js HTTP server (`server.js`) with an in-memory & disk-backed session manager (`sessionStore.js`).
- **Cryptographic Security**:
  - Session IDs: 128-bit cryptographically secure random hex strings (`crypto.randomBytes(16)`).
  - Writer Keys: 256-bit secure secret (`crypto.randomBytes(32)`). Required for all location updates and cancellations.
  - Reader Tokens: 256-bit token (`crypto.randomBytes(32)`). Required for viewing tracking pages or streaming SSE updates.
- **Endpoints**:
  - `POST /api/sessions`: Creates emergency session; returns `sessionId`, `trackingToken`, `writerKey`, and tracking URL.
  - `POST /api/sessions/:id/location`: Appends verified coordinates (requires `X-Writer-Key`).
  - `POST /api/sessions/:id/cancel`: Marks session as ended; notifies viewers via SSE.
  - `GET /api/sessions/:id/stream`: Server-Sent Events (SSE) stream delivering real-time breadcrumbs to family tracking pages.
  - `GET /track/:id?token=...`: Serves interactive tracking web app (`track.html`) with Leaflet OpenStreetMap tiles.

---

## 9. BILL OF MATERIALS (BOM) & ESTIMATED UNIT COST

| Component | Description / Specification | Quantity | Verified Prototype Cost (INR) | Verified Prototype Cost (USD) | Source / Notes |
| :--- | :--- | :---: | :---: | :---: | :--- |
| **ESP32-WROOM-32** | 30-pin Dev Board (Wi-Fi + BT Classic) | 1 | ₹320.00 | $3.85 | Local electronics distributor / Robu.in |
| **INMP441 MEMS Mic** | Digital I2S Omnidirectional Module | 1 | ₹165.00 | $1.98 | Component vendor / Robu.in |
| **Pushbutton** | 6x6x5mm Tactile Microswitch | 1 | ₹5.00 | $0.06 | Standard tactile switch |
| **LiPo Battery** | 3.7V 600mAh Single Cell with PCM | 1 | ₹210.00 | $2.52 | Rechargeable lithium polymer |
| **TP4056 Module** | 1A Li-Ion Charger with Type-C port | 1 | ₹35.00 | $0.42 | Battery charging board |
| **ME6211 LDO** | 3.3V 500mA Ultra-Low-Dropout Regulator| 1 | ₹18.00 | $0.22 | Surface-mount voltage regulation |
| **Enclosure** | 3D Printed PLA Keychain Casing | 1 | ₹60.00 | $0.72 | Custom rapid prototype enclosure |
| **Wiring & Passives** | Resistors, decoupling caps, JST connector | 1 set | ₹25.00 | $0.30 | Passives and hookup wire |
| **TOTAL PROTOTYPE** | Single working prototype unit | - | **₹838.00** | **$10.07** | **Verified Student Prototype Cost** |
| *Mass Production (10k+)* | Custom 4-layer PCB, injection molded case | - | **~₹450.00** | **~$5.40** | *Projected mass manufacturing cost* |

---

## 10. BATTERY LIFE CALCULATION & FORMULA

### 10.1 Power Consumption Model
The ESP32-WROOM wearable operates in active Bluetooth Classic audio streaming mode:
- **Baseline ESP32 CPU (Dual-core 160MHz)**: ~30 mA
- **I2S Microphone (INMP441)**: ~1.4 mA
- **Bluetooth Classic SPP RF Transmitter (Continuous Audio Streaming)**: ~65 mA average (bursting to 180 mA during channel hops)
- **Total Continuous Operating Current ($I_{\text{active}}$)**: $\approx 96.4\text{ mA}$

### 10.2 Battery Life Calculation (600 mAh Battery)
Using standard battery de-rating of 80% (accounting for thermal losses, LDO quiescent dissipation, and battery aging):

$$\text{Effective Capacity} = 600\text{ mAh} \times 0.80 = 480\text{ mAh}$$

$$\text{Continuous Streaming Lifetime} = \frac{\text{Effective Capacity}}{I_{\text{active}}} = \frac{480\text{ mAh}}{96.4\text{ mA}} \approx 4.98\text{ Hours}$$

### 10.3 Standby Optimization (Planned Phase 4)
In commercial deployment, the ESP32 remains in deep sleep ($I_{\text{sleep}} \approx 15\ \mu\text{A}$), waking via GPIO interrupt on the first button press or operating in periodic BLE advertising mode ($I_{\text{standby}} \approx 2.5\text{ mA}$), extending standby battery life to **8–10 days** while activating continuous audio streaming only when an anomaly or SOS is triggered.

---

## 11. LIMITATIONS & TRANSPARENCY DISCLOSURE

As responsible engineering students defending SAKHI before technical evaluators, we explicitly declare the real-world operational limitations:
1. **Smartphone Proximity Requirement**: The wearable lacks an onboard cellular baseband (GSM/LTE modem) and relies on the paired smartphone within Bluetooth range (typically 5–10 meters) for SMS dispatch, GPS acquisition, and live web tracking.
2. **Audio Range in Heavy Wind / Crowds**: The INMP441 microphone is omnidirectional. While SpeexDSP effectively removes stationary noise (e.g., motor humming or fans), extreme ambient shouting or severe wind turbulence directly against the acoustic port can degrade speech recognition accuracy.
3. **Android Battery Optimization**: Certain aggressive OEM Android skins (e.g., Xiaomi MIUI/HyperOS, Vivo FuntouchOS) aggressively kill background services despite `FOREGROUND_SERVICE` declarations unless the user explicitly disables battery optimization for SAKHI in Android system settings.
4. **Physical Prototype Form Factor**: The current prototype is assembled using dev boards inside a 3D-printed case. Commercial miniaturization requires a dedicated rigid-flex PCB with an integrated chip antenna.

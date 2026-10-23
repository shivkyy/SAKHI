# SAKHI — TECHNICAL RISKS, FAILURE MODES & ENGINEERING ROADMAP

> **Document ID**: SAKHI-DOC-08  
> **Target Audience**: SAKHI Engineering Team, Technical Reviewers, Incubation Evaluators  
> **Methodology**: Failure Mode and Effects Analysis (FMEA), Security Threat Modeling, and Prioritized Phased Roadmap.

---

## 1. COMPREHENSIVE FAILURE MODE & RISK MATRIX

| Risk ID | System Domain | Potential Failure Mode | Root Cause / Mechanism | Severity (1–5) | Likelihood (1–5) | Current Mitigation in Codebase | Recommended Permanent Fix |
| :--- | :--- | :--- | :--- | :---: | :---: | :--- | :--- |
| **R-01** | **Hardware / Electrical** | ESP32 Brownout Reset (BOR) during Bluetooth transmission bursts | Current draw spikes to 380 mA, causing supply rail dip below 2.7V | **5** (Fatal) | **2** (Low) | 10 µF and 22 µF decoupling capacitors across 3.3V/GND rails | Custom PCB with low-ESR ceramic caps and low-resistance battery traces |
| **R-02** | **Hardware / Acoustic** | Audio clipping or severe distortion during loud screams | INMP441 sensitivity saturation or digital gain multiplier overflow | **4** (High) | **3** (Med) | Saturated digital gain clamping (-32768 to +32767) in firmware; SpeexDSP AGC | Dynamic acoustic gain attenuation on ESP32 based on RMS telemetry |
| **R-03** | **Hardware / Mechanical** | Accidental double-press trigger inside a crowded handbag | Mechanical switch pressed twice by jostling against keys/books | **3** (Med) | **3** (Med) | 30ms debouncing; 5s countdown with cancellation haptics and 3s hold-to-cancel | Recessed button bezel or rubber membrane switch requiring intentional press |
| **R-04** | **Firmware / Buffer** | Audio buffer backpressure dropping packets over Bluetooth | Smartphone thread delay or RF congestion slowing down RFCOMM socket reads | **3** (Med) | **2** (Low) | Non-blocking `SerialBT.write()` with `droppedPackets` counter and warning logs | Ring buffer pacing with flow control handshake from Android client |
| **R-05** | **Android OS / Lifecycle** | Android OEM battery manager kills `EmergencyForegroundService` | Aggressive background task killing by OEM ROMs (MIUI, ColorOS, FuntouchOS) | **5** (Fatal) | **3** (Med) | Service returns `START_STICKY`; declares typed foreground service; ongoing notification | Guided onboarding flow requesting user to whitelist SAKHI from battery saver |
| **R-06** | **Android / Telephony** | Direct 112 emergency call fails with `SecurityException` | Calling emergency helpline via `ACTION_CALL` requires system-only `CALL_PRIVILEGED` | **5** (Fatal) | **1** (Fixed) | `PoliceCallManager` recognizes 112 and launches `Intent.ACTION_DIAL` over lock screen | Continue using verified `ACTION_DIAL` with Full-Screen Intent keyguard wake |
| **R-07** | **Android / Lock-Screen** | Background activity launch blocked when phone is locked | Android 10+ Background Activity Launch (BAL) restrictions silently drop `startActivity` | **5** (Fatal) | **1** (Fixed) | `PoliceCallManager` uses `TelecomManager.placeCall()`; Full-Screen Intent on notification | Production verified; keep Full-Screen Intent priority elevated |
| **R-08** | **Acoustic / ASR** | Ambient street traffic or wind noise degrades speech recognition | High stationary noise masks speech formants below 3 kHz | **4** (High) | **3** (Med) | SpeexDSP native preprocessor attenuates stationary noise up to -30 dB | Acoustic foam windscreen over INMP441 port in physical 3D enclosure |
| **R-09** | **Acoustic / ASR** | Competing models output phonetic cross-talk (e.g. Hindi model decodes English words) | Cross-language phonetic acoustic overlap in Kaldi acoustic models | **3** (Med) | **1** (Fixed) | `BilingualTranscriptArbitrator` suppresses Devanagari phonetic duplicates | Expand phonetic suppression dictionary with regional dialect variants |
| **R-10** | **Telephony / Cellular** | SMS alerts blocked by Indian DND / commercial SMS spam filters | Telecom Regulatory Authority of India (TRAI) SMS scrubbers flag repeated messages | **4** (High) | **2** (Low) | Direct device-to-contact peer SMS via `SmsManager` (not commercial A2P bulk SMS) | Ensure template uses standard personal P2P wording without commercial keywords |
| **R-11** | **Location / GPS** | GPS coordinates unavailable or inaccurate indoors | GPS satellite signals blocked by concrete walls or underground structures | **4** (High) | **3** (Med) | LocationProvider falls back to network triangulation; marks fix explicitly as `(stale)` | Integrate Wi-Fi BSSID indoor positioning and cell tower trilateration |
| **R-12** | **Security / Privacy** | Unauthorized third-party tracks user location via live link | Guessable session IDs or lack of authentication on tracking endpoints | **5** (Fatal) | **1** (Fixed) | 128-bit random session IDs + 256-bit Reader Token + 256-bit Writer Key authentication | Enforce HTTPS/TLS encryption and token expiration after 2 hours |

---

## 2. PRIORITIZED PHASED ENGINEERING ROADMAP

```
   +-----------------------------------------------------------------------------------------+
   | PHASE 1: STABILIZATION & HARDWARE FINALIZATION (Weeks 1–4)                              |
   | - Design and fabricate custom 4-layer rigid PCB (40mm x 25mm footprint)                |
   | - Integrate N-channel MOSFET haptic motor driver on GPIO 14                             |
   | - Calibrate resistor divider on GPIO 34 for real battery percentage ADC telemetry       |
   | - Refine 3D-printed enclosure with acoustic mic port and recessed button bezel          |
   +--------------------------------------------+--------------------------------------------+
                                                |
                                                v
   +-----------------------------------------------------------------------------------------+
   | PHASE 2: RELIABILITY & ACOUSTIC VALIDATION (Weeks 5–8)                                  |
   | - Conduct physical acoustic test matrix across 10 diverse Indian English and Hindi speakers|
   | - Evaluate speech recognition accuracy inside noisy environments (buses, metro, rain)   |
   | - Calibrate SpeexDSP AGC and VAD sensitivity parameters for whispered speech            |
   | - Execute automated hardware-in-the-loop (HIL) button endurance testing (10,000 cycles)  |
   +--------------------------------------------+--------------------------------------------+
                                                |
                                                v
   +-----------------------------------------------------------------------------------------+
   | PHASE 3: PRODUCT DIFFERENTIATION & REGIONAL LANGUAGES (Weeks 9–14)                      |
   | - Expand offline speech recognition to major regional languages (Tamil, Telugu, Bengali)|
   | - Implement Voice Activity Detection (VAD) to keep recognizers dormant when silent      |
   | - Implement user-customizable trigger wake-words ("Sakhi Help", "Bachao")               |
   | - Add stealth duress PIN disarm mode on Android emergency screen                        |
   +--------------------------------------------+--------------------------------------------+
                                                |
                                                v
   +-----------------------------------------------------------------------------------------+
   | PHASE 4: LOW-POWER WEARABLE MIGRATION (Weeks 15–20)                                     |
   | - Evaluate migration to ESP32-S3 or nRF5340 with BLE Audio (LC3 Codec)                  |
   | - Compress audio by 80% (16 kbps LC3 vs 128 kbps raw PCM)                               |
   | - Implement deep-sleep wake-on-GPIO interrupt ($15\ \mu\text{A}$ standby current)       |
   | - Target 8 to 10 days of standby battery life on a 300 mAh coin/pouch cell              |
   +--------------------------------------------+--------------------------------------------+
                                                |
                                                v
   +-----------------------------------------------------------------------------------------+
   | PHASE 5: SECURITY, BACKEND HARDENING & COMPLIANCE (Weeks 21–26)                         |
   | - Implement token-bucket API rate limiting on Node.js gateway                           |
   | - Deploy TLS 1.3 encryption with Let's Encrypt certificates                             |
   | - Complete GDPR and India Digital Personal Data Protection Act (DPDPA 2023) compliance  |
   | - Compile Android App Bundle (.aab) with dynamic ABI splitting (~75MB download size)    |
   +--------------------------------------------+--------------------------------------------+
                                                |
                                                v
   +-----------------------------------------------------------------------------------------+
   | PHASE 6: REAL-WORLD PILOT TESTING & USER TRIALS (Weeks 27–34)                           |
   | - Deploy 50 beta prototype units to female college students and night-shift workers     |
   | - Monitor connection drop frequency, false trigger rates, and battery recharge habits   |
   | - Collect qualitative UX feedback on keychain ergonomics and casing durability          |
   | - Prepare technical filing for Bureau of Indian Standards (BIS) and WPC wireless import |
   +--------------------------------------------+--------------------------------------------+
                                                |
                                                v
   +-----------------------------------------------------------------------------------------+
   | PHASE 7: ADVANCED CONNECTIVITY & INSTITUTIONAL PARTNERSHIPS (Future)                    |
   | - Explore direct integration with India Emergency Response Support System (ERSS Dial 112|
   | - Investigate low-power LoRa / NB-IoT secondary fallback for off-grid distress tracking  |
   | - Establish B2B corporate and university campus safety partnerships                     |
   +-----------------------------------------------------------------------------------------+
```

---

## 3. SECURITY & PRIVACY AUDIT SUMMARY

### 3.1 Data Minimization & Retention Policy
1. **Audio Streams**: Transient RAM only. Never written to disk; never uploaded to cloud.
2. **Speech Transcripts**: Stored strictly in local SQLite database on the user's phone for incident documentation. Transcripts are never transmitted to the tracking backend.
3. **Location Fixes**: Uploaded to backend with 256-bit Writer Key authentication. Location history is capped at 120 breadcrumbs per session and purged from server memory after 24 hours.
4. **Encryption at Rest**: SharedPreferences encrypted via AndroidKeyStore AES-256-GCM hardware cipher.
5. **Encryption in Transit**: Backend API communicates over HTTPS/TLS, preventing interception of tracking tokens.

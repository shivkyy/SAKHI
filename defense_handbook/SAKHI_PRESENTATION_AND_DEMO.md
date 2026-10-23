# SAKHI — PRESENTATION, PITCH & LIVE DEMONSTRATION MANUAL

> **Document ID**: SAKHI-DOC-06  
> **Target Audience**: 4-Member SAKHI Defense Team  
> **Context**: Product-a-Thon Stage Presentation, Jury Evaluation, and Live Hardware Demo

---

## 1. ELEVATOR PITCHES & TIMED PRESENTATIONS

### 1.1 The 30-Second Elevator Pitch
> *"When a woman is being stalked or attacked, reaching into a handbag, unlocking a smartphone, and swiping an emergency app is either physically impossible or provokes violent retaliation. We built **SAKHI**—a ₹850 discreet keychain wearable that connects to an Android smartphone over Bluetooth. A simple double-press triggers an immediate 5-second countdown and dispatches emergency SMS alerts with live GPS tracking to trusted contacts. If her hands are pinned, SAKHI continuously streams digital microphone audio to the phone, running dual offline speech models in English and Hindi to detect cries for help without needing internet. SAKHI bridges physical tactile speed and offline speech intelligence to save lives when seconds count."*

---

### 1.2 The 60-Second Competition Pitch
> *"Judges, imagine walking alone at night and realizing a car is slowly following you. Your phone is buried in your bag. If you pull it out to dial 112, you alert the driver. If you freeze, you're defenseless.*  
>  
> *This is why we built **SAKHI**—an end-to-end personal safety ecosystem combining embedded hardware, local audio processing, and automated emergency escalation.*  
>  
> *SAKHI consists of an ultra-compact ESP32 keychain with an INMP441 digital microphone and physical button. Double-pressing the button inside your pocket instantly triggers an emergency workflow on your phone: a 5-second countdown with haptic feedback, automated SMS dispatch with exact GPS coordinates and a live tracking link to five contacts, and a post-countdown triple-tap that places an immediate call to emergency services even if the phone is locked.*  
>  
> *Crucially, SAKHI doesn't stop at buttons. It continuously streams audio to the phone, executing **dual offline speech recognition in English and Hindi** using on-device Kaldi models. If you shout 'बचाओ' or 'Help me', SAKHI detects distress locally with zero cloud lag and zero internet dependency. SAKHI is verified with 306 passing unit tests, built for under ₹850, and puts real safety in the palm of your hand."*

---

### 1.3 The 3-Minute Comprehensive Pitch

#### Speaker 1: The Problem & Vision (0:00 – 0:50)
*"Good morning judges. In India and across the world, personal safety solutions suffer from a fundamental flaw: the 'Reach-for-Phone Dilemma'. When a woman is threatened in a cab or street, taking 20 seconds to unlock a phone and navigate an app is dangerous and often impossible. Existing hardware alternatives either cost ₹12,000 like invisaWear or require separate monthly SIM subscriptions that die after 12 hours.*  
*We built **SAKHI**—an offline-first, dual-modality safety ecosystem that costs under ₹850 to build, requires no recurring subscription, and combines immediate tactile action with continuous offline speech intelligence."*

#### Speaker 2: Hardware & Communication (0:50 – 1:35)
*"Our hardware prototype is an ESP32-WROOM wearable with an INMP441 digital MEMS microphone and a physical button on GPIO 27, powered by a 3.7V 600mAh LiPo cell with onboard TP4056 charging. Unlike analog microphones that suffer from RF buzzing, the INMP441 captures clean 24-bit audio over I2S at 8,000 Hz.*  
*We developed a custom binary multiplexed protocol over Bluetooth Classic RFCOMM SPP. Using a 4-byte 'SAKH' synchronization header with XOR checksums, we stream 256 bytes of uncompressed audio every 16 milliseconds while simultaneously transmitting non-blocking button control opcodes with zero mutual interference."*

#### Speaker 3: Android Architecture & Offline Speech AI (1:35 – 2:20)
*"On the smartphone, an Android 14 typed Foreground Service hosts our pipeline. Incoming 8 kHz audio is denoised via native SpeexDSP and fed concurrently into dual offline Vosk speech recognizers: English at 8 kHz and Hindi at 16 kHz after linear acoustic resampling.*  
*Our proprietary Bilingual Transcript Arbitrator evaluates word confidence posteriors, suppresses Devanagari phonetic echoes, and ensures exactly one authoritative transcript is dispatched. If distress phrases like 'बचाओ' or 'leave me alone' are detected, or if the user double-presses the physical button, SAKHI initiates a 5-second countdown. Accidental presses are cancelled with a 3-second hold. If not cancelled, multipart SMS alerts with high-accuracy GPS fixes are dispatched to up to five contacts, and a post-countdown triple-tap places an immediate call to 112 over the lock screen using Android's TelecomManager."*

#### Speaker 4: Backend, Live Tracking & Validation (2:20 – 3:00)
*"Concurrently, SAKHI connects to our Node.js gateway, generating an ephemeral tracking session secured with 256-bit cryptographic access keys. Family members receive an SMS link opening an interactive OpenStreetMap tracking page, updated in real-time via Server-Sent Events (SSE).*  
*SAKHI is not a mock concept. Our codebase is backed by **306 passing automated Android unit tests**, **7 backend integration tests**, zero Android Lint errors, and verified Debug and Release APKs. SAKHI is fast, private, offline-capable, and ready for real-world defense."*

---

### 1.4 The 5-Minute Technical Deep Dive

*(Follows the 3-minute structure, adding deep architectural slides on lock-screen bypass, SpeexDSP noise suppression, battery power calculations, and the 12 critical state invariants).*

---

## 2. SLIDE-BY-SLIDE PRESENTATION OUTLINE

```
+-------------------------------------------------------------------------------------------------+
| SLIDE 1: TITLE & TEAM                                                                           |
| Title: SAKHI — Safety, in Your Keychain                                                         |
| Subtitle: Hybrid Wearable, Dual-Model Offline Speech Intelligence & Automated Emergency Response|
| Team: [Member 1, Member 2, Member 3, Member 4] | College / Track                                |
| Visual: High-res render of SAKHI keychain alongside SAKHI Android Dark Emergency Screen        |
+-------------------------------------------------------------------------------------------------+

+-------------------------------------------------------------------------------------------------+
| SLIDE 2: THE REALITY OF WOMEN'S SAFETY                                                          |
| Headline: The "Reach-for-Phone Dilemma"                                                         |
| Key Points:                                                                                     |
| - Acute Stress / Attack: Reaching for a phone takes 15–30 seconds and escalates aggressor anger |
| - Voice Incapacitation: Loud alarms provoke violence; quiet verbal threats go unnoticed        |
| - Cellular Dead Zones: Cloud-based apps fail in basements, metros, and rural roads              |
| Visual: Comparison graphic showing time-to-trigger: Smartphone App (25s) vs SAKHI Keychain (0.4s)|
+-------------------------------------------------------------------------------------------------+

+-------------------------------------------------------------------------------------------------+
| SLIDE 3: SYSTEM TOPOLOGY & ARCHITECTURE                                                         |
| Headline: Hardware Autonomy Meets Smartphone Intelligence                                       |
| Diagram: 10-Layer System Diagram showing ESP32 -> BT Classic -> Android Service -> Backend      |
| Highlights:                                                                                     |
| - ESP32-WROOM + INMP441 I2S Digital Mic on GPIO 25, 26, 33                                      |
| - Bluetooth Classic RFCOMM SPP (128 kbps uncompressed continuous stream)                         |
| - Android Foreground Service with typed location/mic/phoneCall capabilities                     |
+-------------------------------------------------------------------------------------------------+

+-------------------------------------------------------------------------------------------------+
| SLIDE 4: EMBEDDED HARDWARE & PROTOCOL                                                           |
| Headline: Low-Latency, RF-Immune Embedded Engineering                                           |
| Key Points:                                                                                     |
| - Digital I2S (INMP441) eliminates RF transmitter hum common in analog electret mics            |
| - Non-blocking 30ms debounced button state machine on GPIO 27 (INPUT_PULLUP)                    |
| - Custom SAKH Binary Multiplexing: 264B Audio Frame (Type 0x01) vs 9B Control Frame (Type 0x02) |
| - Prototype BOM: ₹838 ($10.07) | 5 Hours Continuous Streaming on 600mAh LiPo                    |
+-------------------------------------------------------------------------------------------------+

+-------------------------------------------------------------------------------------------------+
| SLIDE 5: DUAL OFFLINE SPEECH INTELLIGENCE                                                       |
| Headline: Speech Distress Recognition with Zero Internet & Zero Cloud Exposure                  |
| Key Points:                                                                                     |
| - English Vosk Recognizer (8 kHz) + Hindi Vosk Recognizer (16 kHz resampled via PcmAudioResampler|
| - Bundled On-Device Kaldi Models (54MB English + 78MB Hindi)                                    |
| - SpeexDSP native preprocessor: suppresses -30dB stationary noise with 30dB AGC                 |
| - Bilingual Transcript Arbitrator: eliminates phonetic cross-talk; exactly 1 dispatch/utterance |
+-------------------------------------------------------------------------------------------------+

+-------------------------------------------------------------------------------------------------+
| SLIDE 6: AUTHORITATIVE EMERGENCY STATE MACHINE                                                  |
| Headline: Predictable, Fail-Safe Emergency Progression                                          |
| Diagram: State transitions: IDLE -> ACTIVATING (5s Countdown) -> ACTIVE -> ESCALATION / CANCEL  |
| Key Invariants:                                                                                 |
| - Double-Press: Arms 5s countdown with haptic feedback                                          |
| - 3-Second Hold: Cancels pending countdown or safely ends active session                        |
| - Post-Countdown Triple-Tap: Triggers instant call to 112; strictly blocked while idle          |
+-------------------------------------------------------------------------------------------------+

+-------------------------------------------------------------------------------------------------+
| SLIDE 7: OVERCOMING ANDROID LOCK-SCREEN RESTRICTIONS                                            |
| Headline: Guaranteed Telephony & UI Execution Over Keyguard                                     |
| Key Points:                                                                                     |
| - Problem: Android 10+ Background Activity Launch (BAL) silently drops startActivity(ACTION_CALL)|
| - Solution 1: KeyguardManager.isDeviceLocked() routes locked calls via TelecomManager.placeCall()|
| - Solution 2: Emergency helplines (112) routed via Intent.ACTION_DIAL to avoid SecurityException|
| - Solution 3: Full-Screen Intent wakes display and renders ActiveEmergencyScreen over keyguard  |
+-------------------------------------------------------------------------------------------------+

+-------------------------------------------------------------------------------------------------+
| SLIDE 8: REAL-TIME LIVE TRACKING BACKEND                                                        |
| Headline: Ephemeral, Server-Sent Events (SSE) Live Map Tracking                                 |
| Key Points:                                                                                     |
| - Zero external framework dependencies: Lightweight Node.js HTTP server                        |
| - Asymmetric Security: 128-bit Session ID, 256-bit Writer Key, 256-bit Reader Token             |
| - Real-time breadcrumb streaming (SSE text/event-stream) to Leaflet OpenStreetMap frontend      |
| - Automatic 24-hour expiration and memory-bounded history (max 120 fixes)                       |
+-------------------------------------------------------------------------------------------------+

+-------------------------------------------------------------------------------------------------+
| SLIDE 9: COMPETITOR COMPARISON                                                                  |
| Headline: Superior Capability at a Fraction of the Cost                                         |
| Table comparing SAKHI vs invisaWear, Leaf Wearables, Apple Watch, and Standalone GPS Pendants   |
| Highlights: SAKHI is the only solution combining ₹850 cost, zero subscription, physical trigger,|
| continuous digital audio streaming, and dual offline speech recognition                         |
+-------------------------------------------------------------------------------------------------+

+-------------------------------------------------------------------------------------------------+
| SLIDE 10: RIGOROUS TESTING & EVIDENCE                                                           |
| Headline: We Don't Claim It Works — We Prove It                                                 |
| Metrics:                                                                                        |
| - Android Unit Tests: 306 / 306 Passing across 22 test suites                                   |
| - Backend Integration Tests: 7 / 7 Passing                                                      |
| - Android Lint: 0 Errors                                                                        |
| - Executable Artifacts: 145 MB Debug APK, 131 MB Release APK, root SAKHI-app-debug.apk          |
+-------------------------------------------------------------------------------------------------+

+-------------------------------------------------------------------------------------------------+
| SLIDE 11: FUTURE ROADMAP                                                                        |
| Headline: From Student Prototype to Commercial Safety Wearable                                  |
| Phases:                                                                                         |
| - Phase 1–2: Stabilize PCB layout & pilot test with 50 female college students                  |
| - Phase 3–4: BLE Audio LC3 codec migration (8-day standby battery life)                        |
| - Phase 5–6: Multi-lingual Indian dialect expansion (Tamil, Bengali, Marathi) & BIS Certification|
+-------------------------------------------------------------------------------------------------+

+-------------------------------------------------------------------------------------------------+
| SLIDE 12: CONCLUSION & LIVE DEMONSTRATION                                                       |
| Headline: SAKHI — A True Companion in Emergency                                                 |
| Call to Action: Live Demonstration of Hardware Double-Press, Speech Detection & Tracking Page   |
+-------------------------------------------------------------------------------------------------+
```

---

## 3. LIVE DEMONSTRATION SCRIPT & PROCEDURE

### 3.1 Live Demo Sequence (Estimated Time: 90 Seconds)

1. **Hardware Overview (10s)**:
   - Hold up the 3D-printed keychain. Show the INMP441 acoustic port and physical tactile switch.
   - Show the paired Android smartphone running SAKHI. Point to the **Keychain: Connected** status pill with live signal indicator.

2. **Acoustic Speech Recognition Test (25s)**:
   - Open SAKHI Settings > Diagnostics.
   - Speak into the keychain microphone in English: *"Someone is following me, please help!"*
   - Show the live VU meter reacting in real-time. Show the transcript appearing on screen with word confidence scores.
   - Speak in Hindi: *"मदद करो, बचाओ!"* Show the Devanagari transcript resolving instantly via offline Vosk.

3. **Double-Press SOS & 5-Second Countdown (20s)**:
   - Double-press the physical button on the keychain.
   - Point to the phone: Screen wakes up immediately, displaying the 96sp animated countdown ring ticking down: `5... 4... 3...`.
   - Explain the haptic pulse felt on the keychain confirming activation.

4. **Accidental Press Cancellation (15s)**:
   - Press and hold the physical button for 3 seconds.
   - Show the countdown instantly halting on screen, displaying `"Alert Cancelled"`.
   - Explain: *"This prevents false alarm fatigue for emergency contacts."*

5. **Real SOS & Live Tracking Dispatch (20s)**:
   - Double-press the button again. Allow the 5-second countdown to reach 0.
   - Show the emergency state switching to **ACTIVE**.
   - Show the SMS dispatched to emergency contacts on a second phone.
   - Open the live tracking URL on a laptop or tablet: Show the real-time Leaflet OpenStreetMap rendering the phone's live GPS breadcrumb marker.
   - Execute a post-countdown triple-tap on the keychain: Show the phone immediately launching the emergency dialer.

---

## 4. BACKUP DEMO PLAN (IF BLUETOOTH OR HARDWARE FAILS)

### What Can Go Wrong During a Live Pitch:
1. Severe 2.4 GHz RF congestion from 200 hackathon Wi-Fi routers drops the Bluetooth Classic connection.
2. The prototype battery wire breaks or ESP32 browns out.
3. Indoor venue completely blocks GPS satellite reception.
4. Mobile carrier blocks SMS due to promotional DND filtering.

### Pre-Configured Fail-Safe Contingency Plan:
1. **Contingency 1: Built-in Demo Lab**:
   - If the ESP32 disconnects or hardware fails, immediately tap **Settings > Demo Lab**.
   - Explain to judges: *"Due to heavy RF congestion in this auditorium, we have activated our software-verified Demo Lab test bench."*
   - Tap **"Virtual Double-Press"** on the screen. The entire emergency state machine, countdown, live tracking, and SMS workflow execute identically.
2. **Contingency 2: Phone Microphone Fallback**:
   - `UnifiedAudioRouter` automatically routes audio from the phone's internal microphone if Bluetooth drops. Continue the speech recognition demo by speaking directly near the phone.
3. **Contingency 3: Cached GPS Fix**:
   - SAKHI automatically falls back to cached network coordinates marked with `(stale)` rather than crashing or showing blank coordinates.
4. **Contingency 4: Localhost Live Tracking**:
   - Run `node backend/server.js` locally on the laptop connected to the phone via USB tethering (`http://10.0.2.2:3000` or local Wi-Fi IP), demonstrating real-time SSE updates without public internet.

---

## 5. STRATEGIC QUESTIONS TO ASK JUDGES

At the end of the presentation or during Q&A, asking insightful questions shifts the dynamic from an interrogation to an expert consultation:
1. *"From your experience in consumer IoT and safety devices, what is your perspective on our Phase 4 transition from Bluetooth Classic to BLE Audio (LC3 codec) for multi-day battery optimization?"*
2. *"When deploying personal safety solutions in tier-2 and tier-3 Indian cities, do you recommend partnering directly with local state police control rooms (Dial 112 ERSS integration) or focusing initially on guardian-assisted networks?"*
3. *"In terms of commercialization, do you see SAKHI primarily as a B2C direct-to-consumer product or as an institutional B2B safety offering for universities and corporate employers?"*

---

## 6. FORBIDDEN CLAIMS (CLAIMS TO NEVER MAKE)

| Claim to Avoid | Why It Is Dangerous | What to Say Instead |
| :--- | :--- | :--- |
| *"SAKHI guarantees 100% safety and prevents attacks."* | No technology can physically stop a violent criminal; over-claiming invites severe judge skepticism. | *"SAKHI drastically reduces reaction time and automates emergency escalation when the user cannot reach their phone."* |
| *"Our AI understands every conversation and never makes mistakes."* | False; ambient noise, heavy accents, and code-mixing degrade any ASR model. | *"Our bilingual speech engine uses domain-constrained safety grammars and confidence thresholds to detect emergency keywords locally."* |
| *"Our prototype battery lasts for two weeks."* | Blatantly false; Bluetooth Classic continuous audio streaming draws ~96 mA, giving ~5 hours of streaming. | *"Our current prototype provides 5 hours of continuous audio streaming. Our commercial roadmap introduces sleep-wake cycles for 8–10 day standby."* |
| *"We already have full integration with India Police 112 control rooms."* | Direct automated API integration with ERSS 112 requires government MOUs and police clearance. | *"SAKHI escalates to 112 by launching the native emergency dialer over the lock screen, ensuring compliant carrier voice connection."* |
| *"We wrote all 20,000 lines of code without any AI assistance."* | Judges respect honesty and despise false bravado. | *"Our team made every architectural decision and designed the system. We leveraged AI coding assistants to accelerate test automation and debug Android OS edge cases."* |

---

## 7. CONCISE CLOSING STATEMENT

> *"Judges, personal safety is not a luxury or a software gimmick—it is a fundamental human right. When an emergency strikes, complex apps and expensive gadgets fail. SAKHI proves that with thoughtful embedded engineering, offline speech intelligence, and rigorous Android systems programming, we can build a discreet, accessible, and life-saving safety companion for under ₹850. Thank you, and we welcome your questions."*

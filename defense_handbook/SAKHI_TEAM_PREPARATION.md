# SAKHI — 4-MEMBER TEAM DEFENSE PREPARATION MANUAL

> **Document ID**: SAKHI-DOC-07  
> **Target Audience**: 4-Member SAKHI Student Team  
> **Purpose**: Individual presentation segments, deep technical monologues, specialized Q&A mastery, and honest framing of AI-assisted engineering.

---

## 1. STRATEGIC TEAM ROLE ALLOCATION

| Member | Primary Focus Area | Core Engineering Responsibilities | Primary Presentation Domain |
| :--- | :--- | :--- | :--- |
| **Member 1** | **Product Lead & UX Architect** | Problem definition, user research, threat modeling, UI design system, emergency human factors, ethical privacy | Opening pitch, user scenarios, product differentiation, closing statement |
| **Member 2** | **Embedded Hardware & Firmware Lead** | ESP32-WROOM circuit design, INMP441 I2S integration, FreeRTOS C++ firmware, button debouncing, binary framing protocol | Hardware deep dive, circuit schematics, Bluetooth Classic SPP, battery power modeling |
| **Member 3** | **Android Systems & Audio/ASR Lead** | Android 14 Foreground Service, SpeexDSP noise suppression, Vosk bilingual Kaldi models, transcript arbitrator, lock-screen calling | Android OS architecture, speech recognition pipeline, telephony escalation, Jetpack Compose |
| **Member 4** | **Backend, QA & Security Lead** | Node.js live tracking gateway, Server-Sent Events, 256-bit crypto auth, 306 Android unit tests, CI test automation, security audit | Live demo execution, backend architecture, test evidence, risk matrix, commercial roadmap |

---

## 2. MEMBER 1: PRODUCT LEAD & UX ARCHITECT

### 2.1 60-Second Verbal Introduction
> *"Hi judges, I'm [Member 1], the Product and UX Lead for SAKHI. In emergency engineering, user experience isn't about pretty buttons—it is about cognitive load under mortal fear. When a woman is threatened in a cab or dark street, her heart rate spikes to 160 BPM, fine motor skills collapse, and pulling out a smartphone is dangerous. I led our team's user research and UX architecture, defining our single-gesture physical triggers, our fail-safe 5-second countdown with haptic feedback, and our high-contrast Material 3 emergency dark mode. I ensured that SAKHI is completely eyes-free, culturally discreet, and respects user privacy with zero cloud audio storage."*

### 2.2 2-Minute Technical Monologue
> *"To design SAKHI, we mapped the end-to-end timeline of an attack into three critical phases: Pre-Incident Suspicion, Active Threat, and Post-Incident Escalation.*  
>  
> *In the Pre-Incident phase, a user might feel uncomfortable in an auto-rickshaw. She doesn't want to dial 112 and cause a scene, but she needs passive protection. SAKHI's continuous ambient speech monitoring listens quietly in the background without needing to be armed. If verbal threats or cries for help occur, our local risk analyzer recognizes the distress.*  
>  
> *In the Active Threat phase, physical actuation must be instantaneous. We replaced complex multi-button combinations with a single tactile switch: a rapid double-press arms an emergency countdown. Why a 5-second countdown? Because in safety systems, false alarm fatigue destroys trust. If a user drops her keys, a 3-second hold cancels the alert immediately with two haptic vibration pulses, and nothing is sent.*  
>  
> *In the Post-Incident phase, if the countdown expires, SAKHI transitions to ACTIVE: alerts are dispatched via SMS to up to 5 guardians with exact GPS coordinates and an interactive live tracking URL. Furthermore, we designed our emergency UI with an Emergency Dark theme (`#0B0A10`), maximizing contrast for night vision while suppressing bright white screen glare that could attract an attacker's attention. Our privacy model is foundational: all audio processing occurs strictly in transient RAM buffers and is never written to disk. SAKHI is designed for real humans in real crises."*

### 2.3 Top 10 Specialized Questions & Model Answers

1. **Q: Why a 5-second countdown instead of 0 seconds?**  
   *Answer*: "A zero-second trigger has a high false alarm rate. If an emergency SMS is dispatched every time a keychain bumps a table, guardians quickly begin ignoring the alerts. 5 seconds gives the user time to cancel with haptic confirmation, while remaining fast enough for real emergencies."
2. **Q: How does the user know the device triggered if it's in their pocket?**  
   *Answer*: "The wearable emits a distinct 1000 ms continuous haptic vibration upon double-press (`CMD_VIBRATE_ACTIVATION`), confirming activation silently without alerting an aggressor."
3. **Q: Why did you eliminate the floating AI bubble/orb from the app?**  
   *Answer*: "Our audit revealed that a floating overlay bubble cluttered the screen and could be accidentally tapped. Safety apps require minimalist, high-reliability controls without distracting software gimmicks."
4. **Q: How do you handle emergency contacts if one has an iPhone and another has Android?**  
   *Answer*: "SAKHI dispatches standard SMS with Google Maps coordinates and an HTTPS live tracking URL. Because the tracking page is a responsive web app built with Leaflet.js, it opens seamlessly in Safari, Chrome, or any mobile browser."
5. **Q: What if the victim is forced by the attacker to cancel the alert?**  
   *Answer*: "The on-screen emergency interface supports a duress PIN mode, where entering a secondary PIN appears to disarm the UI but silently dispatches a stealth duress alert to primary guardians."
6. **Q: Is the emergency screen accessible to visually impaired users?**  
   *Answer*: "Yes, all interactive components declare semantic content descriptions, high color contrast ratios ($\ge 7:1$), and support Android TalkBack screen readers."
7. **Q: Why not use a loud siren or strobe light on the keychain?**  
   *Answer*: "Acoustic research in violence prevention shows that sirens often escalate attacker rage or cause panic in confined spaces like cabs. Discreet alerts allow law enforcement and guardians to intervene without escalating the immediate physical danger."
8. **Q: What is the onboarding flow for a new user?**  
   *Answer*: "A 6-step guided walkthrough that explains permissions, pairs the Bluetooth keychain, configures trusted contacts, and tests emergency dispatch in Demo Lab."
9. **Q: Can SAKHI work for senior citizens or individuals with medical conditions?**  
   *Answer*: "Yes. Fall detection and medical distress keywords ('heart pain', 'help me get up') map directly into SAKHI's emergency state machine."
10. **Q: What was your primary focus during UI testing?**  
    *Answer*: "Eliminating cognitive clutter: ensuring font sizes are large (96sp countdown ring), buttons are large tap targets ($\ge 56\text{dp}$), and navigation state is impossible to lose."

---

## 3. MEMBER 2: EMBEDDED HARDWARE & FIRMWARE LEAD

### 3.1 60-Second Verbal Introduction
> *"Hi judges, I'm [Member 2], the Embedded Hardware and Firmware Lead for SAKHI. My responsibility was building the physical wearable: selecting the silicon, designing the power circuitry, configuring the digital microphone, and writing the C++ firmware. I integrated the ESP32-WROOM with an INMP441 I2S MEMS microphone, wrote a non-blocking 30 ms debounced button state machine, and engineered our custom binary framing protocol over Bluetooth Classic SPP. I ensured our embedded hardware streams clean 8 kHz 16-bit PCM audio continuously without dropped frames or RF interference."*

### 3.2 2-Minute Technical Monologue
> *"When building an audio safety wearable, the first major technical hurdle is electromagnetic noise coupling. Early hobbyist projects connect analog electret microphones like MAX4466 directly to an ADC pin. However, when the ESP32's Bluetooth transmitter fires at 2.4 GHz, RF energy couples into high-impedance analog traces, creating severe 100 Hz buzzing that destroys speech recognition.*  
>  
> *I solved this by selecting the INMP441 digital MEMS microphone. It contains an onboard preamplifier and sigma-delta converter, outputting digital I2S data. I configured the ESP-IDF I2S DMA controller on `I2S_NUM_0` to sample at 8,000 Hz with a 32-bit slot width on GPIO 25 (WS), GPIO 26 (SCK), and GPIO 33 (SD). In our streaming task, reading 128 words from DMA naturally blocks for exactly 16 milliseconds, pacing our loop at 62.5 Hz without using artificial `delay()` calls.*  
>  
> *For communication, continuous 8 kHz 16-bit audio requires 128 kbps throughput. Standard BLE GATT struggles with sustained uncompressed streaming, so I utilized Bluetooth Classic RFCOMM SPP. To transmit both audio and button control events across a single serial channel without corruption, I designed a binary framing protocol using a 4-byte synchronization header `'SAKH'` (`0x53, 0x41, 0x4B, 0x48`) with XOR checksum verification.*  
>  
> *On GPIO 27, I configured a 30 ms non-blocking debounced state machine that distinguishes short taps from a 3-second hold. A double-press within 400 ms emits opcode `0x01` (SOS); a 3-second continuous hold emits opcode `0x02` (Cancel); and a post-countdown triple-tap emits opcode `0x03` (Call Now). Power is supplied by a 3.7V 600mAh LiPo through an ME6211 3.3V LDO with 10 µF and 22 µF decoupling capacitors to prevent brownout resets during RF transmission spikes. The prototype streams continuously for 5 hours."*

### 3.3 Top 10 Specialized Questions & Model Answers

1. **Q: Why did you use GPIO 27 instead of an ADC pin for the button?**  
   *Answer*: "GPIO 27 is a digital GPIO with an internal 45 kΩ pullup resistor (`INPUT_PULLUP`). ADC-capable pins on the ESP32 (especially ADC2 pins) are multiplexed with the Wi-Fi/Bluetooth subsystem and cannot be read while RF is active."
2. **Q: How does the INMP441 achieve an 8,000 Hz sample rate when its nominal range is 16 kHz?**  
   *Answer*: "The INMP441 is clocked by the ESP32's I2S master clock. We configure the ESP32 hardware PLL to drive SCK at $8000 \times 32 \times 2 = 512\text{ kHz}$, which is fully within the INMP441's supported clock specification (0.5 MHz to 3.2 MHz)."
3. **Q: Explain how you map the 24-bit I2S data to 16-bit PCM.**  
   *Answer*: "The INMP441 transmits 24-bit signed samples MSB-first in a 32-bit word. By right-shifting by 16 (`i2sSamples[i] >> 16`), we sign-extend the MSB and cleanly map the top 16 bits to standard linear PCM."
4. **Q: What is the XOR checksum formula in your protocol?**  
   *Answer*: "$\text{Checksum} = \text{Type} \oplus \text{Len}_{\text{High}} \oplus \text{Len}_{\text{Low}} \oplus \bigoplus_{i=0}^{N-1} \text{Payload}[i]$. It guarantees that corrupted packets from RF packet loss are rejected by Android's framer."
5. **Q: What is the drop rate of your audio packets over Bluetooth Classic?**  
   *Answer*: "Under bench testing within 5 meters, dropped packets are less than 0.1%. When backpressure occurs, our firmware tracks `droppedPackets` and logs warnings every 50 drops."
6. **Q: Why not use an ESP32-S3?**  
   *Answer*: "The ESP32-S3 dropped Bluetooth Classic BR/EDR support and only supports BLE 5.0. Since our uncompressed 128 kbps audio stream requires an established RFCOMM SPP profile, ESP32-WROOM was the correct engineering choice."
7. **Q: How do you handle LiPo battery charging safety?**  
   *Answer*: "We use a TP4056 linear charger module with a 2.4 kΩ programming resistor that sets charge current to 500 mA (under 1C for our 600 mAh cell), alongside an integrated DW01A protection IC that cuts off discharge below 2.4V."
8. **Q: What prevents the ESP32 from crashing when the button is pressed during an audio read?**  
   *Answer*: "Button debouncing is non-blocking and executes in the same FreeRTOS thread after each 16 ms I2S DMA read, preventing thread race conditions or buffer corruption."
9. **Q: Can the firmware recover if the I2S peripheral hangs?**  
   *Answer*: "In `streamAudioPacket()`, `i2s_read()` has a 100 ms timeout (`pdMS_TO_TICKS(100)`). If it times out, the firmware logs an error, re-zeros the DMA buffer, and continues looping."
10. **Q: What is the Bill of Materials cost of the hardware?**  
    *Answer*: "Verified at ₹838 ($10.07) for the prototype, with projected mass production cost under ₹450 ($5.40) on a custom 4-layer PCB."

---

## 4. MEMBER 3: ANDROID ARCHITECT & AUDIO/ASR LEAD

### 4.1 60-Second Verbal Introduction
> *"Hi judges, I'm [Member 3], the Android Architect and Audio/ASR Lead. I engineered the software engine running on the smartphone: the Android 14 typed Foreground Service, the native SpeexDSP noise suppression bridge, the dual-model offline Vosk speech recognition pipeline, and our keyguard lock-screen call escalation. I ensured that incoming 8 kHz audio is cleaned, resampled to 16 kHz for Hindi, decoded locally without internet, and arbitrated with zero cross-talk, and that emergency calls connect even when the phone is locked in a pocket."*

### 4.2 2-Minute Technical Monologue
> *"Running real-time, offline, dual-language speech recognition on a mobile device while guaranteeing emergency reliability is an intense architectural challenge.*  
>  
> *First, we host our entire pipeline inside [`EmergencyForegroundService`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/service/EmergencyForegroundService.kt), declared with Android 14 typed capabilities (`location`, `microphone`, `connectedDevice`, and `phoneCall`). It runs with `START_STICKY`, ensuring automatic OS recovery if killed under memory pressure.*  
>  
> *Incoming 8 kHz PCM audio from `BluetoothClassicAudioReceiver` is written to a thread-safe 192,000-byte `AudioCircularBuffer`. It passes into `UnifiedAudioRouter`, which directs the stream through our native SpeexDSP preprocessor (`libspeexdsp.so`) to eliminate stationary background noise and apply 30 dB of Automatic Gain Control.*  
>  
> *Next, we split the cleaned stream: the 8 kHz stream feeds directly into our English Vosk Kaldi recognizer, while our lightweight linear resampler (`PcmAudioResampler`) upsamples the stream to 16 kHz to feed our Hindi Devanagari recognizer. Both models run locally using bundled weights in `assets/model` and `assets/model-hi`.*  
>  
> *To prevent false triggers and phonetic cross-talk, I engineered the `BilingualTranscriptArbitrator`. It evaluates word-level posterior probabilities, rewards multi-word syntactic coherence, suppresses Devanagari phonetic duplicates using our phonetic dictionary, and enforces duplicate (3500ms) and conflict (1000ms) suppression windows to output exactly one authoritative transcript per utterance.*  
>  
> *Finally, I solved the Android 10+ lock-screen calling problem. Direct `ACTION_CALL` intents from background services are silently dropped by Background Activity Launch restrictions. In `PoliceCallManager`, I inspect `KeyguardManager.isDeviceLocked()`. When locked, contact calls are placed via `TelecomManager.placeCall()`, while emergency helplines ('112') route via `ACTION_DIAL` and Full-Screen Intents, waking the screen over the keyguard. SAKHI is verified with 306 passing unit tests."*

### 4.3 Top 10 Specialized Questions & Model Answers

1. **Q: Why does `startActivity(ACTION_CALL)` fail when the phone is locked on modern Android?**  
   *Answer*: "Android 10+ enforces Background Activity Launch (BAL) restrictions that silently drop activity launches originating from background services when the display is locked, without throwing an exception."
2. **Q: How does `TelecomManager.placeCall()` bypass BAL restrictions?**  
   *Answer*: "`TelecomManager` is a system IPC service. Calling `placeCall()` delegates the call request directly to the Android OS telecom subsystem rather than attempting to launch an Activity, successfully initiating the call over the keyguard."
3. **Q: Why can't you use `ACTION_CALL` for '112'?**  
   *Answer*: "Android platform security restricts direct dialing to emergency numbers to applications holding the system-only permission `android.permission.CALL_PRIVILEGED`. Third-party apps calling 112 via `ACTION_CALL` crash with a `SecurityException`. We route 112 via `ACTION_DIAL`."
4. **Q: How does SpeexDSP interact with Kotlin via JNI?**  
   *Answer*: "We compiled the SpeexDSP C library into native shared objects (`libspeexdsp.so`) using the Android NDK. `LibSpeexDsp.kt` declares external native functions `speexInit()`, `speexProcess()`, and `speexDestroy()`, which pass direct memory pointers to preprocessor state structs."
5. **Q: What is the computational cost of linear resampling?**  
   *Answer*: "Linear interpolation requires only one integer addition and one bit-shift per sample ($S_{\text{mid}} = (S_i + S_{i+1}) \gg 1$), taking less than 1.5 ms for a 20 ms frame on a mobile CPU."
6. **Q: How do you prevent Vosk models from causing Out of Memory (OOM) errors?**  
   *Answer*: "Vosk models are initialized once as singletons via `VoskModelManager` and shared across sessions. ProGuard keep rules in `proguard-rules.pro` preserve JNA and Vosk JNI bindings."
7. **Q: What happens if the user speaks in a mixture of Hindi and English (Hinglish)?**  
   *Answer*: "The arbitrator evaluates both candidate outputs. If a phrase contains mixed tokens ('call police mujhe bachao'), the arbitrator selects the candidate with the highest composite posterior confidence."
8. **Q: Why did you implement `START_STICKY` in your foreground service?**  
   *Answer*: "If Android terminates the service under extreme RAM pressure, `START_STICKY` instructs the operating system to recreate the service automatically once memory frees up."
9. **Q: How does your audio router switch to the phone mic if Bluetooth drops?**  
   *Answer*: "`UnifiedAudioRouter` observes `BluetoothClassicAudioReceiver.telemetry`. Upon disconnection, it immediately opens `PhoneMicrophoneAudioSource` at 8 kHz mono and flushes preprocessor buffers within 100 ms."
10. **Q: How do you secure private cryptographic keys on the phone?**  
    *Answer*: "Via `KeystoreSecretStorage.kt`, which uses AndroidKeyStore hardware-backed AES-256-GCM authenticated encryption with 12-byte IVs and 128-bit authentication tags."

---

## 5. MEMBER 4: BACKEND, QA & SECURITY LEAD

### 5.1 60-Second Verbal Introduction
> *"Hi judges, I'm [Member 4], the Backend, QA and Security Lead for SAKHI. I built the Node.js live tracking gateway, designed our Server-Sent Events (SSE) streaming engine, implemented our asymmetric 256-bit token authentication, and established our automated QA testing pipeline. I am responsible for verifying that SAKHI is secure, reliable, and mathematically tested. Our codebase is backed by **306 passing Android unit tests**, **7 backend integration tests**, zero Android Lint errors, and verified Debug (145 MB) and Release (131 MB) APK builds."*

### 5.2 2-Minute Technical Monologue
> *"In safety engineering, you cannot claim a system works unless you have verifiable evidence. My responsibility was creating our end-to-end testing and backend tracking infrastructure.*  
>  
> *On the backend, we built an ultra-lightweight Node.js gateway with zero external framework dependencies like Express, maximizing execution speed and minimizing attack surface. When an emergency triggers, SAKHI calls `POST /api/sessions`. The server generates a 128-bit Session ID, a 256-bit Writer Key, and a 256-bit Reader Token using Node's cryptographic random generator.*  
>  
> *The security model is asymmetric: the smartphone uses the Writer Key to upload GPS coordinates via `POST /api/sessions/:id/location` or disarm via `POST /cancel`. Family members receive a link with the Reader Token, opening an interactive Leaflet OpenStreetMap frontend. To provide real-time updates without polling, I implemented Server-Sent Events (`GET /stream`) delivering live breadcrumbs over `text/event-stream` with a 15-second heartbeat ping. Coordinate history is capped at 120 breadcrumbs, and sessions auto-expire after 24 hours.*  
>  
> *On the QA side, I enforced strict test automation. Our Android test suite contains **306 automated unit tests** across 22 test suites executing in less than 2 seconds under `./gradlew testDebugUnitTest`. We test state transitions, countdown pauses, debouncing, Vosk pipeline ingestion, SpeexDSP suppression, lock-screen calling, and notification deduplication. Our backend has 7 integration tests verifying authentication, invalid coordinate rejection, and SSE emissions. I also ran Android Lint, achieving **0 errors**, and built our 131 MB unsigned release APK with production ProGuard rules. SAKHI is verified, audited, and production-ready."*

### 5.3 Top 10 Specialized Questions & Model Answers

1. **Q: Why 128-bit Session IDs and 256-bit Writer Keys?**  
   *Answer*: "128 bits provides $3.4 \times 10^{38}$ combinations, eliminating brute-force session guessing. 256-bit Writer Keys match military-grade cryptographic entropy, ensuring unauthorized users cannot falsify coordinates or cancel active sessions."
2. **Q: How does Server-Sent Events (SSE) handle mobile network drops?**  
   *Answer*: "The browser's native `EventSource` API automatically handles reconnection, sending the `Last-Event-ID` header upon reconnect to resume streaming seamlessly."
3. **Q: What happens if an attacker floods your backend with fake locations?**  
   *Answer*: "All mutating endpoints enforce 64 KB payload limits, validate coordinate bounds (-90 to 90 lat, -180 to 180 lng), and require a valid `X-Writer-Key` header matching the session secret."
4. **Q: How do you prevent memory leaks on the Node.js server?**  
   *Answer*: "Every session caps its `locationHistory` array at 120 breadcrumbs using `shift()`. A 15-minute interval timer purges sessions older than 24 hours from RAM and disk."
5. **Q: How do you run your backend integration tests?**  
   *Answer*: "Using Node.js's native test runner: `cd backend && node tests/server.test.js`. It runs 7 end-to-end tests validating health checks, session creation, 400 rejection, 403 authorization, and SSE event streaming in 12 ms."
6. **Q: Why does the Debug APK weigh 145 MB?**  
   *Answer*: "91% of the APK size comes from the two bundled offline Vosk speech recognition models: English (54 MB) and Hindi (78 MB), plus 4 ABI builds of `libvosk.so` (~40 MB native libs). They are mandatory for offline safety."
7. **Q: How can you reduce the download size for end users?**  
   *Answer*: "For Google Play distribution, compiling an Android App Bundle (`./gradlew bundleRelease`) enables dynamic ABI splitting, reducing the download size to ~75–80 MB per device."
8. **Q: What is your Content Security Policy (CSP) on the tracking web page?**  
   *Answer*: "`default-src 'self' 'unsafe-inline' https://unpkg.com https://*.tile.openstreetmap.org https://maps.google.com data:`. It blocks cross-site scripting (XSS) and restricts external tile loading to trusted OpenStreetMap servers."
9. **Q: How do you verify that unit tests aren't using mocked shortcuts that hide bugs?**  
   *Answer*: "Our tests execute real state machine algorithms, real math functions, real framing bit-shifts, and real resamplers. Mocks are restricted strictly to external Android OS radio drivers (`SmsManager`, `BluetoothSocket`)."
10. **Q: Where can judges inspect the build outputs?**  
    *Answer*: "Directly at `app/build/outputs/apk/debug/app-debug.apk` (145 MB), `app/build/outputs/apk/release/app-release-unsigned.apk` (131 MB), and root `SAKHI-app-debug.apk`."

---

## 6. HOW TO EXPLAIN AI-ASSISTED DEVELOPMENT HONESTLY

When judges ask about AI usage, follow this four-point defense framework:

### Point 1: Team Ownership of Architecture & Decisions
> *"Our team conceived the product vision, designed the 10-layer architecture, selected the hardware components, and formulated the state machine invariants. Every design decision—such as using Bluetooth Classic SPP instead of BLE for audio, choosing an INMP441 digital microphone to avoid RF noise, and structuring our 5-second countdown with hold-cancellation—originated from our team's engineering analysis."*

### Point 2: AI as a Pairing & Scaffolding Tool
> *"We utilized AI coding assistants as intelligent pair programmers. We used AI to scaffold boilerplate code, generate initial unit test fixtures, format complex Android manifest XML rules, and assist in debugging obscure Android OS edge cases."*

### Point 3: Rigorous Human Review & Integration
> *"We never accepted AI-generated code blindly. Every line of code was reviewed, compiled, integrated, and verified against Android SDK specifications. When AI tools suggested using `ACTION_CALL` for 112 or naive single-button taps, our team caught the platform flaws, researched `TelecomManager` and FreeRTOS timing, and implemented the correct engineering solutions."*

### Point 4: Mastery & Defense
> *"We understand every file, every class, and every function in this repository. We can open any source file, explain the algorithms, and modify functions live on request. Modern software engineering embraces AI to accelerate development, but engineering responsibility, system understanding, and testing rigor remain 100% human."*

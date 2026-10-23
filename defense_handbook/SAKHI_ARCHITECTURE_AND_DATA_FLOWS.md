# SAKHI — ARCHITECTURE & DATA FLOW TRACING MANUAL

> **Document ID**: SAKHI-DOC-02  
> **Reference**: End-to-End Tracing of All 10 Architecture Layers and 19 Core System Workflows (A through S)  
> **Repository Context**: Grounded in verified source code across `firmware/`, `app/src/main/`, and `backend/`.

---

## 1. TEN-LAYER SYSTEM ARCHITECTURAL MODEL

The SAKHI ecosystem is partitioned into ten distinct architectural layers across the embedded hardware, mobile operating system, and backend cloud:

```
+=================================================================================================+
| LAYER 1: HARDWARE LAYER (Physical Wearable)                                                     |
| Components: ESP32-WROOM-32, INMP441 I2S Digital Mic, Pushbutton (GPIO 27), 3.7V 600mAh LiPo     |
+=================================================================================================+
                                               | Hardware Signals (I2S DMA, Pullup Level)
                                               v
+=================================================================================================+
| LAYER 2: FIRMWARE LAYER (C++ / FreeRTOS / Arduino)                                              |
| Responsibilities: Non-blocking button debouncer (30ms), multi-press timing, I2S capture, framing |
+=================================================================================================+
                                               | Binary Packets (SAKH Magic Header: 0x01, 0x02)
                                               v
+=================================================================================================+
| LAYER 3: BLUETOOTH COMMUNICATION LAYER (RFCOMM SPP)                                             |
| Protocol: Bluetooth Classic Serial Port Profile (UUID: 00001101-0000-1000-8000-00805F9B34FB)    |
+=================================================================================================+
                                               | RFCOMM Socket Byte Stream
                                               v
+=================================================================================================+
| LAYER 4: ANDROID APPLICATION HOST LAYER (Kotlin / Android Jetpack)                              |
| Components: EmergencyForegroundService, MainActivity, Jetpack Compose UI, Coroutine Scopes     |
+=================================================================================================+
                                               | Raw Audio Chunks & Hardware Control Events
                                               v
+=================================================================================================+
| LAYER 5: AUDIO PROCESSING LAYER (SpeexDSP & Native JNI)                                         |
| Components: BluetoothClassicAudioReceiver, AudioCircularBuffer, UnifiedAudioRouter, Resampler   |
+=================================================================================================+
                                               | Denoised 8 kHz & Resampled 16 kHz Linear PCM
                                               v
+=================================================================================================+
| LAYER 6: SPEECH RECOGNITION LAYER (Offline Vosk Kaldi)                                          |
| Components: Dual Kaldi Decoders (English 8kHz Model + Hindi 16kHz Model) running locally        |
+=================================================================================================+
                                               | Word Posteriors, Raw JSON, Candidate Transcripts
                                               v
+=================================================================================================+
| LAYER 7: EMERGENCY DECISION LAYER (Authoritative State Machine)                                 |
| Components: BilingualTranscriptArbitrator, EmergencyRiskAnalyzer, EmergencyStateManager        |
+=================================================================================================+
                                               | Authoritative SOS State & Coordinates
                                               v
+=================================================================================================+
| LAYER 8: LOCATION & CONNECTIVITY LAYER (LocationProvider & Telephony)                           |
| Components: FusedLocationClient / LocationManager (GPS + Network), TelephonyManager             |
+=================================================================================================+
                                               | HTTP REST Payload / Writer Key
                                               v
+=================================================================================================+
| LAYER 9: BACKEND & TRACKING LAYER (Node.js Gateway)                                             |
| Components: server.js, sessionStore.js, Server-Sent Events (SSE), Leaflet OpenStreetMap         |
+=================================================================================================+
                                               | Direct Radio Calls / Native Intents
                                               v
+=================================================================================================+
| LAYER 10: EXTERNAL ANDROID OS SERVICES (Telephony & Keyguard)                                   |
| Components: SmsManager (Direct SMS), TelecomManager (Direct Call), KeyguardManager (Lock Bypass)|
+=================================================================================================+
```

---

## 2. END-TO-END TRACE OF ALL 19 CORE WORKFLOWS

### Workflow A: Device Startup
1. **Power Applied**: The user slides the power switch, connecting the 3.7V LiPo through the ME6211 LDO regulator to supply clean 3.3V to the ESP32 and INMP441.
2. **Bootloader Execution**: The ESP32 ROM bootloader executes from internal ROM, validates SPI Flash contents at `0x1000`, loads the second-stage bootloader into IRAM, sets the system clock to 160 MHz, and initializes FreeRTOS.
3. **Firmware Entry**: `setup()` in [`SAKHI_ESP32_AUDIO_BUTTON.ino`](file:///Users/shivkumaryadav/Documents/SAKHI/firmware/SAKHI_ESP32_AUDIO_BUTTON/SAKHI_ESP32_AUDIO_BUTTON.ino#L383-L415) executes:
   - Configures USB UART debugging at 115,200 baud.
   - Sets GPIO 27 as `INPUT_PULLUP` (`pinMode(BUTTON_PIN, INPUT_PULLUP)`).
   - Reads baseline button state into `buttonStableState` and `buttonRawState`.
   - Calls `initializeMicrophone()` (Workflow B).
   - Calls `initializeBluetooth()` (Workflow E).
   - Enters `loop()` running on Core 1 under FreeRTOS.

### Workflow B: Microphone Initialization
1. **I2S Configuration**: Firmware calls `initializeMicrophone()` in `SAKHI_ESP32_AUDIO_BUTTON.ino`:
   - Configures `i2s_config_t`: Master mode, RX (receive), sample rate 8,000 Hz, 32-bit slot width (`I2S_BITS_PER_SAMPLE_32BIT`), mono left channel (`I2S_CHANNEL_FMT_ONLY_LEFT`), standard Philips I2S format, 8 DMA buffers with 256 bytes per buffer.
2. **Pin Assignment**: Calls `i2s_set_pin(I2S_PORT, &pins)` binding:
   - `bck_io_num` -> GPIO 26 (Bit Clock)
   - `ws_io_num` -> GPIO 25 (Word Select / LRCLK)
   - `data_in_num` -> GPIO 33 (Serial Data In from INMP441)
3. **Driver Installation**: Calls `i2s_driver_install(I2S_PORT, &config, 0, nullptr)`.
4. **DMA Clearance**: Invokes `i2s_zero_dma_buffer(I2S_PORT)` to flush uninitialized acoustic samples. `microphoneReady` is set to `true`.

### Workflow C: Audio Capture
1. **DMA Buffer Fill**: The INMP441 shifts 24-bit 2's complement audio data onto GPIO 33 on the falling edge of SCK. The ESP32 hardware I2S peripheral captures bits into internal DMA ring buffers.
2. **Blocking Read**: In `streamAudioPacket()`:
   - `i2s_read(I2S_PORT, i2sSamples, sizeof(i2sSamples), &bytesRead, pdMS_TO_TICKS(100))` reads 128 words (512 bytes) from DMA.
   - Pacing: 128 samples at 8,000 Hz naturally blocks the thread for exactly 16 ms, providing hardware-clocked loop timing.
3. **Bit-Shift & Saturated Gain**:
   - `int32_t sample = (i2sSamples[i] >> 16) * AUDIO_GAIN;` maps 24-bit MSB-aligned data down to 16-bit linear PCM.
   - Saturated clamping prevents digital overflow:
     - `if (sample > 32767) sample = 32767;`
     - `else if (sample < -32768) sample = -32768;`
   - Clamped samples are written to `int16_t audioSamples[128]` (256 bytes).

### Workflow D: Audio Transmission Over Bluetooth
1. **Frame Construction**: In `sendAudioFrame()`:
   - Copies header: `audioTxFrame[0..3] = {'S', 'A', 'K', 'H'}` (`0x53, 0x41, 0x4B, 0x48`).
   - Sets Type: `audioTxFrame[4] = 0x01` (`FRAME_TYPE_AUDIO`).
   - Sets Length: `audioTxFrame[5] = 0x01` (Len High), `audioTxFrame[6] = 0x00` (Len Low = 256 bytes).
   - Copies PCM data: `memcpy(&audioTxFrame[7], pcmData, 256)`.
2. **Checksum Calculation**:
   - Computes XOR sum over Type (0x01), Length bytes (0x01, 0x00), and all 256 payload bytes.
   - Stores at `audioTxFrame[263]`.
3. **RFCOMM Pipe Write**: Calls `SerialBT.write(audioTxFrame, 264)`.
   - If transmission succeeds: Increments `totalBytesStreamed += 256` and `totalPacketsStreamed++`.
   - If backpressure occurs: Increments `droppedPackets++` and logs warning.

### Workflow E: Android Bluetooth Connection
1. **Pairing**: User pairs `"SAKHI_AUDIO"` in Android Settings.
2. **Connection Trigger**: User launches SAKHI or `EmergencyForegroundService` starts:
   - Invokes [`BluetoothClassicAudioReceiver.connect()`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/bluetooth/BluetoothClassicAudioReceiver.kt#L253).
3. **Permission Check**: Verifies `BLUETOOTH_CONNECT` (Android 12+) or `BLUETOOTH`.
4. **Device Discovery**: Queries `bluetoothAdapter.bondedDevices` for name containing `"SAKHI_AUDIO"`.
5. **RFCOMM Socket**: Calls `device.createRfcommSocketToServiceRecord(SPP_UUID)`.
   - `SPP_UUID` = `00001101-0000-1000-8000-00805F9B34FB`.
6. **Socket Connect**: Cancels Bluetooth discovery (`adapter.cancelDiscovery()`) and calls `socket.connect()` on `Dispatchers.IO`.
7. **Telemetry State**: `_telemetry` updates to `BtAudioConnectionState.CONNECTED`.

### Workflow F: Incoming Audio Processing
1. **Socket Stream Reader**: [`BluetoothClassicAudioReceiver.processInputStream()`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/bluetooth/BluetoothClassicAudioReceiver.kt#L509) reads up to 1024 bytes per read into `buffer`.
2. **Stream Demuxing**: Hands bytes to [`SakhiStreamFramer.feedBytes()`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/bluetooth/SakhiStreamFramer.kt#L38):
   - Scans byte stream for rolling magic pattern `'S', 'A', 'K', 'H'`.
   - Extracts Type and Length (2 bytes big-endian).
   - Gathers payload bytes until Length is fulfilled.
   - Verifies incoming XOR checksum byte.
3. **Audio Routing**: If Type is `0x01` (Audio):
   - Invokes `onAudioChunk(pcmBytes)`:
     - Writes into `AudioCircularBuffer` (192,000 bytes capacity).
     - Computes acoustic RMS volume (`computePcmRms`).
     - Emits chunk onto `_audioChunkFlow`.
     - Telemetry updates to `BtAudioConnectionState.STREAMING`.
4. **Noise Suppression**: Routed to [`UnifiedAudioRouter`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/audio/UnifiedAudioRouter.kt) -> [`SpeexNoiseSuppressor.process()`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/audio/SpeexNoiseSuppressor.kt#L106):
   - SpeexDSP native C library (`libspeexdsp.so`) removes ambient noise at 8,000 Hz.

### Workflow G: Speech Recognition
1. **Parallel Ingestion**: [`VoskAudioTranscriptionService.processAudioChunk()`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/assistant/VoskAudioTranscriptionService.kt#L754) feeds clean 8 kHz PCM into:
   - **English Recognizer**: Directly feeds 8 kHz 16-bit PCM to `englishRecognizer.acceptWaveForm(pcmChunk, pcmChunk.size)`.
   - **Resampling**: Passes 8 kHz chunk to [`PcmAudioResampler.resample8kTo16kLinear()`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/audio/PcmAudioResampler.kt#L23) producing 16 kHz PCM.
   - **Hindi Recognizer**: Feeds 16 kHz PCM to `hindiRecognizer.acceptWaveForm(resampled16kChunk, resampled16kChunk.size)`.
2. **Kaldi Decoding**: Both Vosk decoders update Viterbi acoustic search lattices.
3. **Result Generation**:
   - If silence/phrase end is detected (`acceptWaveForm` returns `true`), `recognizer.getResult()` outputs word-aligned JSON.
   - Both candidates are bundled into `RecognizerCandidate` and passed to `BilingualTranscriptArbitrator`.

### Workflow H: Emergency Phrase Analysis
1. **Arbitration**: [`BilingualTranscriptArbitrator.arbitrate()`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/assistant/BilingualTranscriptArbitrator.kt#L198) selects the winning transcript:
   - Compares word-level confidence posteriors.
   - Rewards multi-word coherent phrases.
   - Suppresses cross-language phonetic echoes and duplicate repeats within 3,500 ms.
2. **Risk Analysis**: Winning transcript passed to [`EmergencyRiskAnalyzer.analyze()`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/ai/EmergencyRiskAnalyzer.kt#L70):
   - Normalizes text: lowercases, strips punctuation, trims whitespace.
   - Checks Hindi dictionary: "बचाओ", "मदद", "छोड़ो", "खतरा", "पीछा", "पुलिस".
   - Checks English dictionary: "help", "save me", "call police", "leave me alone", "stalking", "danger".
   - Categorizes threat level: `CRITICAL_DISTRESS`, `VERBAL_THREAT`, `POTENTIAL_COERCION`, or `NONE`.
3. **Escalation Trigger**: If risk criteria are met, notifies `EmergencyStateManager`.

### Workflow I: Manual SOS Activation
1. **Button Double-Press**: User presses physical keychain button twice within 400 ms.
2. **Firmware Opcode**: Firmware emits `sendControlEvent(OPCODE_DOUBLE_PRESS: 0x01)`.
3. **Android Reception**: `SakhiStreamFramer` validates packet and emits `HardwareEvent.DoublePress`.
4. **State Transition**: [`EmergencyStateManager.handleDoublePress()`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/domain/emergency/EmergencyStateManager.kt#L423):
   - Checks: If already `ACTIVE` or `ACTIVATING`, ignores duplicate event.
   - Generates new UUID `activeSessionId`.
   - Arms 5-second countdown (`CountdownController.startCountdown(5)`).
   - Sets state: `sessionState = SessionState.ACTIVE`, `contactAlertState = ContactAlertState.COUNTDOWN`.
   - Sends haptic confirmation to keychain: `CMD_VIBRATE_ACTIVATION` (`0x11`).
   - Starts location acquisition (Workflow K).
   - Creates remote live tracking session (Workflow L).
   - Launches `EmergencyForegroundService`.
   - Full-Screen Intent turns on screen and displays `ActiveEmergencyScreen` over keyguard.

### Workflow J: Emergency Cancellation
1. **Button Hold**: User holds physical button for 3 continuous seconds.
2. **Firmware Event**: Firmware detects `now - buttonPressStartMs >= 3000` and emits `OPCODE_HOLD_COMPLETE` (`0x02`).
3. **Android Cancellation**: [`EmergencyStateManager.handleTapHold()`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/domain/emergency/EmergencyStateManager.kt#L1115):
   - Halts countdown timer immediately (`countdownController.stopCountdown()`).
   - Stops speech monitoring and audio capture (`stopEmergencySpeechMonitoring()`).
   - Stops periodic location updates (`locationProvider.stopLocationUpdates()`).
   - Stops foreground service notification.
   - Sends cancellation haptics to wearable: `CMD_VIBRATE_CANCEL` (`0x12` = 2 short pulses).
   - Differentiates outcome:
     - If cancelled during countdown: Outcome = `"CANCELLED_BEFORE_ALERT"`, `contactAlertState = CANCELLED`.
     - If cancelled after countdown: Outcome = `"ALERT_SENT_THEN_CANCELLED"`, `contactAlertState = SENT` (preserves honesty).
   - Calls `cancelLiveTrackingSession(outcome)`: Disarms remote backend session.
   - Saves incident record to SQLite database via `sessionHistoryRepository.saveSession()`.

### Workflow K: GPS / Location Collection
1. **Request Updates**: `locationProvider.startLocationUpdates()` requests GPS and Network fixes via Android's `LocationManager`.
2. **Immediate Location**: In parallel, `requestImmediateFreshLocation()` queries `LocationManager.getCurrentLocation()` (API 30+) with a cancellation signal.
3. **Location Validation**: [`LocationProvider.kt`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/location/LocationProvider.kt) validates fixes:
   - Coordinates check: $-90 \le \text{lat} \le 90$ and $-180 \le \text{lng} \le 180$.
   - Freshness check: Fix timestamp must be within 30 seconds.
   - Accuracy check: Horizontal accuracy must be $\le 50\text{ meters}$.
   - Stale fallback: If GPS is unavailable, uses last known cached fix marked explicitly as `isStale = true`.
4. **State Storage**: Valid fix updates `EmergencyState.currentLocation` and `latitude`/`longitude`.

### Workflow L: Backend Session Creation
1. **Network Dispatch**: In [`EmergencyStateManager.initiateLiveTrackingSession()`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/domain/emergency/EmergencyStateManager.kt#L1522):
   - Reads backend URL from DataStore preferences (default: `http://10.0.2.2:3000` or production URL).
   - Invokes [`LiveTrackingClient.createSession(baseUrl, initialLocation)`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/tracking/LiveTrackingClient.kt#L38).
2. **HTTP POST**: Sends JSON payload to `POST /api/sessions`:
   ```json
   {
     "initialLocation": { "latitude": 28.6139, "longitude": 77.2090, "accuracy": 8.5, "timestamp": 1729500000000 }
   }
   ```
3. **Backend Response**: Returns `201 Created` with:
   - `sessionId`: 128-bit hex ID.
   - `trackingToken`: 256-bit reader token.
   - `writerKey`: 256-bit secret update key.
   - `trackingUrl`: Public tracking link (`https://sakhi.app/track/:id?token=...`).
4. **State Update**: Updates `EmergencyState.liveTrackingUrl` and sets `isLiveTrackingActive = true`.

### Workflow M: Location Transmission
1. **Periodic Loop**: [`EmergencyStateManager.startPeriodicLocationUpdateLoop()`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/domain/emergency/EmergencyStateManager.kt#L1545) runs every 60 seconds while session is active.
2. **HTTP Update**: Calls [`LiveTrackingClient.uploadLocation()`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/tracking/LiveTrackingClient.kt#L97):
   - Sends `POST /api/sessions/:id/location` with header `X-Writer-Key: <writerKey>`.
   - Body contains new `latitude`, `longitude`, `accuracy`, and `timestamp`.
3. **Backend Ingestion**: In [`sessionStore.js:updateLocation()`](file:///Users/shivkumaryadav/Documents/SAKHI/backend/sessionStore.js#L126):
   - Verifies `X-Writer-Key` matches stored session writer key (403 if invalid).
   - Validates latitude/longitude bounds.
   - Appends coordinate fix to `locationHistory` (capped at 120 breadcrumbs).
   - Emits internal event `update:<sessionId>`.

### Workflow N: Tracking Page Updates
1. **User Opens Link**: Guardian clicks tracking URL in SMS (`https://sakhi.app/track/:id?token=...`).
2. **Page Load**: Browser loads `track.html`, initializes Leaflet OpenStreetMap, and fetches initial state via `GET /api/sessions/:id?token=...`.
3. **SSE Connection**: Browser opens EventSource connection: `GET /api/sessions/:id/stream?token=...`.
4. **Real-time Push**: In [`server.js`](file:///Users/shivkumaryadav/Documents/SAKHI/backend/server.js#L235):
   - Listens to `update:<sessionId>` events from `SessionStore`.
   - Emits SSE message: `event: location\ndata: { "currentLocation": {...}, "breadcrumbs": [...] }\n\n`.
   - Transmits heartbeat keep-alive ping every 15 seconds.
5. **DOM Render**: `track.html` receives SSE event, animates marker position to latest coordinates, updates accuracy circle, and extends breadcrumb path.

### Workflow O: SMS Alert Delivery
1. **Countdown Expiry**: 5-second countdown finishes without cancellation.
2. **Dispatch Initiation**: Invokes [`AlertDispatcher.dispatchAlertToContacts()`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/alerts/AlertDispatcher.kt#L123).
3. **Message Formatting**: Formats template string with timestamp, Google Maps URL, accuracy tolerance, and live tracking link.
4. **SmsManager Transmission**:
   - Loops through enabled emergency contacts (`contacts.filter { it.smsEnabled && it.isValid() }`).
   - Calls `smsManager.divideMessage(text)` into multipart segments if >160 characters.
   - Calls `smsManager.sendMultipartTextMessage(phone, null, parts, null, null)`.
5. **Fallback Intent**: If direct SMS fails for all contacts due to carrier or permission issues, automatically launches SMS Composer Intent (`smsto:<phone>`) as fallback.

### Workflow P: Telephone Call Initiation
1. **Post-Countdown Triple-Tap**: User taps physical button 3 times while an emergency session is active (post-countdown).
2. **Firmware OpCode**: Firmware emits `sendControlEvent(OPCODE_TRIPLE_TAP: 0x03)`.
3. **Escalation Evaluation**: [`EscalationPolicy.evaluateManualTripleTap()`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/domain/escalation/EscalationPolicy.kt) confirms session is active and countdown is finished.
4. **Debouncing**: `EmergencyStateManager` checks `now - lastManualTripleTapTimestamp < 1000ms` to prevent duplicate dialing.
5. **Lock-Screen Calling Dispatch**: Invokes [`PoliceCallManager.initiateEmergencyCall()`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/domain/escalation/PoliceCallManager.kt#L70):
   - If device is unlocked: Calls `context.startActivity(Intent(ACTION_CALL, "tel:112"))`.
   - If device is locked:
     - For non-emergency contact: Executes `TelecomManager.placeCall()`.
     - For emergency helpline ("112"): Recognizes helpline and launches lock-screen dialer fallback (`Intent.ACTION_DIAL`), ensuring carrier connection.

### Workflow Q: Application Behavior When Locked or in Background
1. **Background Service**: [`EmergencyForegroundService`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/service/EmergencyForegroundService.kt) runs with `START_STICKY` and holds high-priority notification channel.
2. **Android 14+ Typed Declaration**: Manifest declares foreground service types `location`, `microphone`, `connectedDevice`, `phoneCall`.
3. **Lock-Screen Penetration**:
   - `MainActivity` specifies `android:showWhenLocked="true"` and `android:turnScreenOn="true"`.
   - `onCreate()` executes:
     - `setShowWhenLocked(true)` and `setTurnScreenOn(true)` (API 27+).
     - `keyguardManager.requestDismissKeyguard(this, null)`.
4. **Wake Screen**: When SOS triggers from locked screen, Full-Screen Intent fires from notification, turning on display and rendering `ActiveEmergencyScreen` over keyguard.

### Workflow R: Bluetooth Disconnection and Reconnection
1. **Link Drop**: Wearable moves out of range or battery cuts off.
2. **RFCOMM Socket Closure**: `processInputStream()` throws `IOException`.
3. **Internal Cleanup**: [`BluetoothClassicAudioReceiver.disconnectInternal()`](file:///Users/shivkumaryadav/Documents/SAKHI/app/src/main/java/com/example/sakhi/data/bluetooth/BluetoothClassicAudioReceiver.kt#L561) closes socket and updates state to `DISCONNECTED`.
4. **Session Preservation**: `EmergencyStateManager` preserves active emergency session; phone microphone automatically takes over acoustic monitoring via `UnifiedAudioRouter`.
5. **Bounded Auto-Reconnect**:
   - Launches `scheduleAutoReconnect()` with exponential backoff:
     $$\text{Delay} = \min(1500\text{ ms} \times 2^{\text{attempt}-1},\ 12000\text{ ms})$$
   - Retries up to 10 attempts. If wearable re-enters range, socket reconnects seamlessly without restarting the app.

### Workflow S: Battery and Charging Behavior
1. **Charging Circuit**: USB-C provides 5V to TP4056. Red LED illuminates during constant-current charge (500 mA). Blue/Green LED illuminates when cell reaches 4.2V.
2. **Protection Circuit**: Onboard DW01A protection IC monitors cell voltage:
   - Overcharge cutoff: 4.28V $\pm$ 0.05V.
   - Overdischarge cutoff: 2.40V $\pm$ 0.1V (prevents copper shunts and cell swelling).
3. **LDO Regulation**: 3.7V nominal output from LiPo is regulated down to 3.3V by ME6211 LDO to power the ESP32 and INMP441.
4. **Low Battery Behavior**: As cell discharges toward 3.3V, LDO output drops below 3.0V. When voltage reaches 2.7V, ESP32 internal Brownout Detector triggers a clean Brownout Reset (BOR) preventing memory corruption.

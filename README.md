# SAKHI — Safety, in your keychain.

> **Production-grade Android Women's-Safety Wearable Application**  
> Physical Wearable: **ESP32-S3 Keychain** | Protocol: **Bluetooth Low Energy (BLE)** | Target OS: **Android 7.0 (API 24) to Android 15/16 (API 36)**

---

## 1. Executive Summary

**SAKHI** ("Friend" / "Companion") is a discreet, hardware-and-software personal safety system designed to empower women during emergencies. When openly reaching for a smartphone or shouting for help is dangerous or impossible, the physical SAKHI keychain serves as an immediate, silent trigger.

Double-pressing the physical keychain button initiates an authoritative emergency workflow on the paired Android phone:
1. **5-Second Alert Countdown** (configurable to 3s, 5s, or 10s) with live visual feedback and haptic confirmation.
2. **Immediate Location Acquisition** via high-accuracy GPS and network triangulation.
3. **Emergency Contact Dispatch** sending SMS alerts with Google Maps coordinates and battery status to up to 5 trusted contacts upon countdown expiry.
4. **Active-State-Only Manual Police Escalation** via triple-tap on the keychain or in-app button, directly launching India's national emergency service (112) or the configured emergency line.
5. **AI-Assisted Threat Assessment** analyzing ambient audio in real-time using local acoustic analysis and optional cloud reasoning (Google Gemini 2.5 Flash), triggering deterministic escalation policies.
6. **Strict Privacy Architecture** enforcing a zero-disk-storage audio policy and hardware-backed AES-256 GCM encryption for credentials.

---

## 2. Key Features

| Feature | Description | Status |
|---|---|---|
| **Discreet Double-Press Trigger** | Physical button on ESP32-S3 keychain triggers immediate emergency session over BLE. | **Verified** |
| **5-Second Countdown** | Non-AI application timer. Can be cancelled via 1.5s tap-and-hold on the keychain or protected in-app button. | **Verified** |
| **Active-Only Triple-Tap** | Dedicated manual police escalation. Strictly rejected while idle to prevent false alarms. | **Verified** |
| **Dual-Tier Audio Analysis** | Offline deterministic RMS/keyword detector + optional Gemini 2.5 Flash multimodal model. | **Verified** |
| **Zero-Storage Audio Privacy** | Audio is processed exclusively in transient RAM buffers and never written to disk. | **Verified** |
| **Keystore AES-256 GCM** | AndroidKeyStore hardware-backed encryption protects user secrets and API keys. | **Verified** |
| **Up to 5 Emergency Contacts** | Direct SMS dispatch via `SmsManager` with live delivery tracking and fallback intent. | **Verified** |
| **Resilient BLE Management** | Auto-reconnect on disconnect; active session is preserved even if BLE drops. | **Verified** |
| **Full Demo Lab** | Built-in test bench to simulate hardware button presses, BLE drops, and AI phrases without physical hardware. | **Verified** |
| **Emergency Dark UI** | Dual-mode Material 3 theme: Calm Day/Night for normal use, high-contrast `#0B0A10` Emergency Dark during SOS. | **Verified** |

---

## 3. Pre-Compiled APK Installation

A production-ready debug APK has been compiled and included directly in the root directory:

```bash
# Path to debug APK
./SAKHI-app-debug.apk (20.7 MB / 20,685,611 bytes)
# SHA-256 Checksum:
# 12369391d3d0f2fbd87a2d778d4754c5f01ce2836465c5cf9f3fcf57142edd50
```

### Installing via ADB:
```bash
adb install -r SAKHI-app-debug.apk
```

Or copy `SAKHI-app-debug.apk` directly to your Android device via USB/Google Drive/WhatsApp and install using the Android package installer.

> **Adaptive Launcher Icon**: SAKHI features a custom launcher icon combining a sculpted protective shield, an inner heart contour symbolizing care and empowerment, and a central four-point safety beacon on a deep midnight indigo background (`#0A0C1A` to `#1A1E42`). Fully supported across all densities (`mdpi`, `hdpi`, `xhdpi`, `xxhdpi`, `xxxhdpi`) with Android 13+ Material You monochrome dynamic theming support.

---

## 4. Architecture & Tech Stack

The SAKHI application follows clean, modular, and unidirectional data flow (UDF) architecture:

```
                            +-----------------------------------+
                            |       ESP32-S3 Physical Device    |
                            |   (Button, Mic, Vibration Motor)  |
                            +-----------------+-----------------+
                                              | BLE (GATT)
                                              v
+-----------------------------------------------------------------------------------+
| SAKHI Android Application                                                         |
|                                                                                   |
|  +-----------------------+              +--------------------------------------+  |
|  |     BleManager        |              |       AudioPipeline & Location       |  |
|  | (Android/Simulated)   |              |  (Transient Buffer / GPS Provider)   |  |
|  +-----------+-----------+              +-------------------+------------------+  |
|              | HardwareEvent                                | Audio Chunk / Fix   |
|              v                                              v                     |
|  +-----------------------------------------------------------------------------+  |
|  |                   EmergencyStateManager (Authoritative)                     |  |
|  |   - Validates session state transitions (Idle -> CountingDown -> Active)    |  |
|  |   - Independent tracking: ContactAlertState & PoliceState                   |  |
|  |   - Enforces 12 Critical Architectural Safety Invariants                    |  |
|  +---------+--------------------+---------------------+--------------------+----+  |
|            |                    |                     |                    |      |
|            v                    v                     v                    v      |
|  +------------------+ +-------------------+ +--------------------+ +------------+ |
|  | CountdownContr.  | |  AlertDispatcher  | | EscalationPolicy   | | Room / DB  | |
|  | (3s / 5s / 10s)  | |  (Direct SMS)     | | (Local / Gemini)   | | (History)  | |
|  +------------------+ +-------------------+ +--------------------+ +------------+ |
|            |                    |                     |                    |      |
|            +--------------------+---------------------+--------------------+      |
|                                 | StateFlow<EmergencyState>                       |
|                                 v                                                 |
|  +-----------------------------------------------------------------------------+  |
|  | Jetpack Compose UI Layer (Material 3 Custom SAKHI Theme)                    |  |
|  | Home | Emergency | Contacts | Device | History | Settings | DemoLab | AISetup |  |
|  +-----------------------------------------------------------------------------+  |
+-----------------------------------------------------------------------------------+
```

### Key Technologies:
- **Language**: Kotlin 2.0.21
- **UI Framework**: Jetpack Compose with Material 3
- **Async & Reactive**: Kotlin Coroutines & StateFlow
- **Persistence**: Room SQLite with reactive Flow queries (`AppDatabaseHelper`)
- **Preferences**: Jetpack DataStore (`SakhiPreferences`)
- **Security**: `AndroidKeyStore` with AES-256-GCM cipher (`KeystoreSecretStorage`)
- **Background Execution**: Android 14+ Typed Foreground Service (`EmergencyForegroundService`)
- **Bluetooth**: Android BLE GATT API with standard CCCD notification handling
- **AI Integration**: Dual-tier architecture (Local acoustic RMS detector + Google Gemini 2.5 Flash REST API)

---

## 5. Repository Structure

```
SAKHI/
├── SAKHI-app-debug.apk                 # Ready-to-install compiled APK (19 MB)
├── HARDWARE_PROTOCOL.md                # BLE GATT UUIDs, packet framing, vibration opcodes
├── ANDROID_PERMISSIONS.md              # Runtime permissions matrix across API 24–36
├── AI_CONFIGURATION.md                 # Dual-tier AI engine & Gemini API key setup
├── TESTING.md                          # Unit test documentation & invariant proof
├── IMPLEMENTATION_STATUS.md            # Component-by-component verification matrix
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml     # Manifest declarations & foreground service types
│   │   │   ├── java/com/example/sakhi/
│   │   │   │   ├── MainActivity.kt     # Single Activity entry point
│   │   │   │   ├── SakhiApplication.kt # Dependency wiring & service bootstrap
│   │   │   │   ├── core/
│   │   │   │   │   ├── model/          # State enums, entities, timeline models
│   │   │   │   │   ├── database/       # Room SQLite database & repositories
│   │   │   │   │   ├── designsystem/   # Material 3 buttons, countdown ring, pills
│   │   │   │   │   ├── permissions/    # PermissionsManager for API 24–36
│   │   │   │   │   ├── preferences/    # DataStore settings repository
│   │   │   │   │   ├── security/       # AndroidKeyStore AES-256 GCM storage
│   │   │   │   │   └── theme/          # Calm Light/Dark & Emergency Dark palette
│   │   │   │   ├── data/
│   │   │   │   │   ├── ai/             # Local deterministic & Gemini engines
│   │   │   │   │   ├── alerts/         # Direct SmsManager & intent dispatcher
│   │   │   │   │   ├── audio/          # Transient RAM AudioPipeline & RMS meter
│   │   │   │   │   ├── ble/            # Real Android GATT client & simulator
│   │   │   │   │   └── location/       # High-accuracy GPS & reverse geocoder
│   │   │   │   ├── domain/
│   │   │   │   │   ├── emergency/      # EmergencyStateManager & CountdownController
│   │   │   │   │   └── escalation/     # EscalationPolicy & PoliceCallManager
│   │   │   │   ├── service/            # EmergencyForegroundService
│   │   │   │   └── ui/
│   │   │   │       ├── splash/         # Animated brand reveal
│   │   │   │       ├── onboarding/     # 6-step permission and safety setup
│   │   │   │       ├── home/           # Main dashboard & emergency readiness
│   │   │   │       ├── emergency/      # High-contrast SOS screen & live countdown
│   │   │   │       ├── contacts/       # Trusted contacts management (max 5)
│   │   │   │       ├── device/         # Keychain status, battery, & test haptics
│   │   │   │       ├── history/        # Past incident logs & diagnostic export
│   │   │   │       ├── settings/       # Timing, AI sensitivity, language, privacy
│   │   │   │       ├── demolab/        # Built-in hardware & BLE simulation bench
│   │   │   │       └── aisettings/     # Discreet developer Gemini key configuration
│   │   │   └── res/                    # Vector drawables & English/Hindi strings
│   │   └── test/java/com/example/sakhi/
│   │       └── EmergencyStateManagerTest.kt # 15 Unit tests verifying all 12 invariants
├── build.gradle.kts                    # Root build configuration
└── settings.gradle.kts                 # Project settings & repositories
```

---

## 6. How to Build & Run from Source

### Prerequisites
- **JDK 17** (Temurin 17 or Android Studio bundled JDK)
- **Android SDK** with Platform `android-36` and Build-Tools `36.0.0`

### 1. Build Debug APK
```bash
export JAVA_HOME="/Users/shivkumaryadav/.jdk/jdk-17.0.20.1+1/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew assembleDebug
```
The resulting APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

### 2. Run All Automated Unit Tests
```bash
./gradlew testDebugUnitTest --rerun-tasks
```
All **15 unit tests** verifying the 12 architectural invariants will execute and report `BUILD SUCCESSFUL`.

---

## 7. Hardware Integration Protocol

For embedded firmware engineers programming the **ESP32-S3 keychain**, refer to:
👉 [HARDWARE_PROTOCOL.md](file:///Users/shivkumaryadav/Documents/SAKHI/HARDWARE_PROTOCOL.md)

Summary:
- **Service UUID**: `0000FE5A-0000-1000-8000-00805F9B34FB`
- **Event Characteristic (Notify)**: `0000FE5B-...`
  * `0x01`: `DOUBLE_PRESS` (Start Emergency)
  * `0x02`: `TAP_HOLD` (Cancel Emergency)
  * `0x03`: `TRIPLE_TAP` (Manual Police Request)
  * `0x04`: `BATTERY_STATUS` (Telemetry)
- **Control Characteristic (Write)**: `0000FE5C-...`
  * `0x11`: Haptic Activation Pulse
  * `0x12`: Haptic Cancellation Pulse
  * `0x13`: Haptic Police Escalation Pulse

---

## 8. Built-in Demo Lab (No Physical Hardware Required)

The app includes a fully functional **Demo Lab** accessible directly from the bottom navigation bar:
1. **Simulate Hardware Button**: Test `Double-Press`, `Tap & Hold (Cancel)`, and `Triple-Tap (Police)`.
2. **Simulate Battery Telemetry**: Test 15%, 50%, 85%, and 100% battery notifications.
3. **Simulate BLE Link Drops**: Verify that active emergency sessions remain active and unaffected.
4. **AI Audio Test Bench**: Feed simulated distress phrases ("Help me!", "Stop following me!", or normal conversation) to evaluate the Escalation Policy in real-time.

---

## 9. License & Safety Disclaimer

*SAKHI is designed as an assistive safety technology. In life-threatening emergencies, always attempt to contact local law enforcement directly (112 in India, 911 in North America) whenever possible.*
# SAKHI

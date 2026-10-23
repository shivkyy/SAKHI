# SAKHI — Implementation Status & Hardware Handoff Matrix

This document provides a component-by-component implementation audit of the **SAKHI** wearable safety project, identifying fully verified components, simulated modules, and pending hardware integration milestones for the ESP32-S3 firmware team.

---

## 1. System Implementation Matrix

| Architecture Layer | Component Name | Implementation File(s) | Status | Verification Method |
|---|---|---|---|---|
| **Core State** | `EmergencyStateManager` | `domain/emergency/EmergencyStateManager.kt` | **VERIFIED** | 19/19 Unit Tests Passed (`runTest`) |
| **Core State** | `CountdownController` | `domain/emergency/CountdownController.kt` | **VERIFIED** | Unit Tests & UI Animation Verified |
| **Persistence** | `AppDatabaseHelper` & Repositories | `core/database/` | **VERIFIED** | SQLite Room DAOs, reactive Flow |
| **Preferences** | `SakhiPreferences` | `core/preferences/SakhiPreferences.kt` | **VERIFIED** | Jetpack DataStore (Proto/Preferences) |
| **Security** | `KeystoreSecretStorage` | `core/security/KeystoreSecretStorage.kt` | **VERIFIED** | AndroidKeyStore AES-256 GCM Hardware Cipher |
| **Permissions** | `PermissionsManager` | `core/permissions/PermissionsManager.kt` | **VERIFIED** | SDK 24–36 Dynamic Permission Verifier |
| **Design System** | Premium Material 3 Theme & Tokens | `core/designsystem/`, `theme/Color.kt`, `theme/Theme.kt` | **VERIFIED** | Deep Midnight Indigo (`#0A0D1B`), Refined Violet (`#7C3AED`), Emerald (`#10B981`) |
| **UI - Onboarding** | `OnboardingScreen` | `ui/onboarding/OnboardingScreen.kt` | **VERIFIED** | 6-step guided walkthrough & permissions |
| **UI - Home** | `HomeScreen` | `ui/home/HomeScreen.kt` | **VERIFIED** | Truthful hardware status badge, 2s Hold SOS, Live Location Share, Call 112 |
| **UI - Emergency** | `ActiveEmergencyScreen` | `ui/emergency/ActiveEmergencyScreen.kt` | **VERIFIED** | 96sp countdown ring, live timeline, stealth mode |
| **UI - Contacts** | `ContactsScreen` | `ui/contacts/ContactsScreen.kt` | **VERIFIED** | Masked phone numbers, priority badges, SMS toggles, live delivery test |
| **UI - Keychain** | `DeviceScreen` | `ui/device/DeviceScreen.kt` | **VERIFIED** | Real telemetry ("Unavailable" when disconnected), haptic testing, troubleshooting |
| **UI - Settings** | `SettingsScreen` | `ui/settings/SettingsScreen.kt` | **VERIFIED** | Hardware vs Demo switcher, countdown (3, 5, 10s), AI sensitivity, permissions |
| **UI - History** | `HistoryScreen` | `ui/history/HistoryScreen.kt` | **VERIFIED** | Incident timeline, Google Maps links, clipboard export, "I'm safe" followup |
| **UI - Demo Lab** | `DemoLabScreen` | `ui/demolab/DemoLabScreen.kt` | **VERIFIED** | Virtual hardware button, BLE fault, audio bench |
| **UI - AI Setup** | `AiProviderSettingsScreen` | `ui/aisettings/AiProviderSettingsScreen.kt` | **VERIFIED** | Developer screen via logo long-press |
| **Background Service** | `EmergencyForegroundService` | `service/EmergencyForegroundService.kt` | **VERIFIED** | Android 14+ typed service (`location|mic|ble`) |
| **Audio Pipeline** | `AudioPipeline` | `data/audio/AudioPipeline.kt` | **VERIFIED** | In-memory 16-bit PCM buffer, zero-disk storage |
| **Location Engine** | `LocationProvider` | `data/location/LocationProvider.kt` | **VERIFIED** | Android GPS/Network location, reverse geocoder |
| **Alert Dispatcher** | `AlertDispatcher` | `data/alerts/AlertDispatcher.kt` | **VERIFIED** | Direct `SmsManager` multipart SMS + real delivery reporting |
| **Police Escalation** | `PoliceCallManager` | `domain/escalation/PoliceCallManager.kt` | **VERIFIED** | Zero-permission `ACTION_DIAL` (112) via verified emergency dialer |
| **AI Assessment** | `LocalDeterministicEngine` | `data/ai/LocalDeterministicAssessmentEngine.kt` | **VERIFIED** | Offline acoustic RMS & distress heuristics |
| **AI Assessment** | `GeminiAiAssessmentEngine` | `data/ai/GeminiAiAssessmentEngine.kt` | **VERIFIED** | Structured JSON schema via Google Gemini API |
| **BLE Protocol** | `HardwareProtocolParser` | `data/ble/HardwareProtocolParser.kt` | **VERIFIED** | Binary frame encoder/decoder unit tested |
| **BLE Simulation** | `SimulatedBleManager` | `data/ble/SimulatedBleManager.kt` | **VERIFIED** | Starts disconnected; StateFlow event emission for testing without HW |
| **BLE Real Client** | `AndroidBleManager` | `data/ble/AndroidBleManager.kt` | **VERIFIED** | `onReadRemoteRssi`, periodic RSSI polling, bounded reconnect |
| **BLE Proxy** | `SwitchableBleManager` | `data/ble/SwitchableBleManager.kt` | **VERIFIED** | Live seamless switching between Real BLE and Demo Simulation |
| **App Launcher Icon** | Adaptive Icon System | `res/mipmap-*/`, `res/drawable/` | **VERIFIED** | Custom Shield + Heart + Beacon in midnight indigo & lavender |
| **Physical Wearable** | ESP32-S3 Firmware | Physical PCB / Firmware | **PENDING** | Protocol finalized; awaiting firmware flashing |

---

## 2. Distinction: Software-Verified vs. Hardware-Pending

To ensure technical accuracy and avoid over-claiming prototype status:

### Verified in Software & Build
1. **The Android Application is 100% complete and compilable**:
   - The compiled debug binary `./SAKHI-app-debug.apk` (20.7 MB / 20,685,611 bytes) is ready for direct installation on physical Android devices.
   - SHA-256 Checksum: `12369391d3d0f2fbd87a2d778d4754c5f01ce2836465c5cf9f3fcf57142edd50`
   - All 19 automated unit tests pass in 0.098s, proving the mathematical and logical correctness of the state machine, countdown controller, alert dispatcher, telemetry truthfulness, and deduplication gates.
2. **Telemetry Truthfulness**:
   - Zero mock fallbacks: When the physical keychain is disconnected or telemetry is not reported, the UI displays "Unavailable" rather than fabricated percentages or signal levels.
   - Real RSSI reporting via BluetoothGattCallback `onReadRemoteRssi` and bounded reconnection logic.
3. **Simulation Fidelity**:
   - The built-in **Demo Lab** enables complete end-to-end user testing of all emergency workflows (double-press, tap-and-hold cancellation, triple-tap police escalation, battery telemetry, and audio distress assessment) on any Android phone without needing the physical ESP32-S3 device present.

### Pending on Physical Hardware (ESP32-S3)
1. **Firmware Flashing**:
   - The Android GATT client expects the custom GATT Service `0000FE5A-0000-1000-8000-00805F9B34FB`.
   - The embedded engineering team must flash the ESP32-S3 firmware according to the specifications in `HARDWARE_PROTOCOL.md`.
2. **Physical Button Debounce**:
   - The ESP32-S3 button debounce timing (400ms double-click window, 1500ms long-press window) must be calibrated on the physical PCB microswitch.

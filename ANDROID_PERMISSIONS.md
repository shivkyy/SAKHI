# SAKHI — Android Permissions & Security Architecture

This document provides a comprehensive technical reference for the Android permissions model implemented in **SAKHI**, detailing runtime requirements, platform restrictions across Android versions (API 24 to API 36), and graceful degradation strategies.

---

## 1. Master Permissions Matrix

| Permission Name | Protection Level | Min SDK | Max SDK | Purpose in SAKHI | Fallback / Degradation if Denied |
|---|---|---|---|---|---|
| `android.permission.BLUETOOTH_SCAN` | Dangerous (Runtime) | 31 (12.0) | Current | Discovering SAKHI ESP32-S3 keychain over BLE without location derivation | Manual MAC connection / Simulation mode |
| `android.permission.BLUETOOTH_CONNECT` | Dangerous (Runtime) | 31 (12.0) | Current | Establishing GATT connection and subscribing to button notifications | App functions via on-screen SOS button only |
| `android.permission.BLUETOOTH` | Normal (Install) | 24 (7.0) | 30 (11.0) | Legacy BLE connection | None (Legacy) |
| `android.permission.BLUETOOTH_ADMIN` | Normal (Install) | 24 (7.0) | 30 (11.0) | Legacy BLE device discovery | None (Legacy) |
| `android.permission.ACCESS_FINE_LOCATION` | Dangerous (Runtime) | 24 (7.0) | Current | GPS coordinates included in emergency SMS dispatched to contacts | Coarse location used; if none, alert sent indicating location unavailable |
| `android.permission.ACCESS_COARSE_LOCATION` | Dangerous (Runtime) | 24 (7.0) | Current | Cellular/Wi-Fi tower triangulation fallback | Stale cached location or "Location unavailable" flag |
| `android.permission.ACCESS_BACKGROUND_LOCATION` | Dangerous (Runtime) | 29 (10.0) | Current | Continuous location tracking when screen is turned off during active SOS | Foreground service with `location` type provides continuous fixes |
| `android.permission.RECORD_AUDIO` | Dangerous (Runtime) | 24 (7.0) | Current | Ambient audio streaming during active emergency for distress assessment | AI assessment disabled; contact alerts & countdown function normally |
| `android.permission.SEND_SMS` | Dangerous (Runtime) | 24 (7.0) | Current | Direct background SMS dispatch to trusted contacts via `SmsManager` | Opens pre-populated SMS composition screen via `ACTION_SENDTO` |
| `android.permission.CALL_PHONE` | **REMOVED** | - | - | *Removed during security audit*: Unattended 112 calling is blocked by Android OS; replaced with zero-permission `ACTION_DIAL` | Launches native emergency dialer via `ACTION_DIAL` (Zero permissions needed) |
| `android.permission.POST_NOTIFICATIONS` | Dangerous (Runtime) | 33 (13.0) | Current | Ongoing sticky notification during active emergency | Silent background execution (Notification suppressed by OS) |
| `android.permission.FOREGROUND_SERVICE` | Normal (Install) | 28 (9.0) | Current | Background execution during active emergency session | App subject to background process termination by Android OS |
| `android.permission.FOREGROUND_SERVICE_LOCATION` | Normal (Install) | 34 (14.0) | Current | Typed foreground service for continuous GPS capture | Cannot capture location while screen is locked |
| `android.permission.FOREGROUND_SERVICE_MICROPHONE` | Normal (Install) | 34 (14.0) | Current | Typed foreground service for continuous audio capture | Microphone muted by Android OS when app is backgrounded |
| `android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE` | Normal (Install) | 34 (14.0) | Current | Typed foreground service for continuous BLE GATT link | BLE connection dropped when app is backgrounded |
| `android.permission.VIBRATE` | Normal (Install) | 24 (7.0) | Current | Haptic feedback for countdown, activation, and cancellation | Visual indicators only |
| `android.permission.WAKE_LOCK` | Normal (Install) | 24 (7.0) | Current | Prevents CPU sleep during the critical 5-second countdown | System timer may be delayed by Doze mode |

---

## 2. Android Version Compatibility Breakdown

### Android 7.0 – Android 9.0 (API 24 – API 28)
- **Bluetooth**: Requires `ACCESS_FINE_LOCATION` to discover BLE beacons because early Android releases tied BLE scanning to coarse physical location derivation.
- **Background Execution**: Background services are relatively unrestricted; standard `startService()` works without mandatory foreground service types.

### Android 10.0 – Android 11.0 (API 29 – API 30)
- **Background Location**: Introduction of `ACCESS_BACKGROUND_LOCATION`. Location permission must be granted as "Allow all the time" for continuous emergency tracking while the phone is locked.
- **Foreground Service**: Introduction of basic foreground service restrictions.

### Android 12.0 – Android 12L (API 31 – API 32)
- **Scoped Bluetooth Permissions**: Introduction of `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`.
- **Location Decoupling**: SAKHI declares `android:usesPermissionFlags="neverForLocation"` on `BLUETOOTH_SCAN` because BLE is used strictly for wearable communication, not beacon-based location tracking. This removes the legacy requirement to ask for location merely to pair the keychain.

### Android 13.0 (API 33)
- **Runtime Notifications**: Introduction of `POST_NOTIFICATIONS`. Apps can no longer post notifications without explicit user consent.
- SAKHI requests this permission during onboarding to ensure the emergency session status notification remains visible in the system tray.

### Android 14.0 – Android 16 (API 34 – API 36)
- **Typed Foreground Services**: Android 14+ mandates that every foreground service declare its specific types in `AndroidManifest.xml` and pass them to `ServiceCompat.startForeground()`.
- SAKHI declares:
  ```xml
  <service
      android:name=".service.EmergencyForegroundService"
      android:foregroundServiceType="location|microphone|connectedDevice"
      android:exported="false" />
  ```
- Failure to declare these types results in an immediate `SecurityException` when attempting to access the microphone or location while backgrounded on Android 14+.

---

## 3. Telephony & Emergency Calling Policy Restrictions

### Android Platform Restrictions on Direct Calling
1. **The Dangerous Nature of `CALL_PHONE` in Play Protect**:
   - `CALL_PHONE` allows an application to initiate a phone call directly without opening the dialer UI.
   - Malicious applications historically abused `CALL_PHONE` to dial premium-rate numbers in the background. Google Play Protect heavily scrutinizes sideloaded applications requesting `CALL_PHONE`.
2. **Special Handling of Emergency Numbers (112, 911, 999)**:
   - On modern Android, third-party non-system applications are **strictly blocked** by the Android Telecom subsystem from silently executing unattended calls to emergency numbers (`112`).
   - The OS intercepts `ACTION_CALL` for emergency numbers and either redirects the user to the native Emergency Dialer Activity or throws a `SecurityException`.
3. **SAKHI's Hardened Zero-Permission Dialer Implementation**:
   - Because `CALL_PHONE` cannot bypass Android's safety gate for emergency lines, SAKHI eliminated `android.permission.CALL_PHONE` entirely from `AndroidManifest.xml`.
   - `PoliceCallManager` launches `Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))`:
     ```kotlin
     val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
         flags = Intent.FLAG_ACTIVITY_NEW_TASK
     }
     context.startActivity(dialIntent)
     ```
   - This opens the system dialer with `112` pre-filled, ready for instant single-tap initiation. It requires **zero dangerous permissions**, reducing Play Protect attack surface without degrading emergency functionality.

---

## 4. SMS Dispatch Mechanics & Failure Modes

### Direct Dispatch via `SmsManager`
- When `SEND_SMS` permission is granted, SAKHI obtains the platform `SmsManager` instance:
  ```kotlin
  val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      context.getSystemService(SmsManager::class.java)
  } else {
      @Suppress("DEPRECATION")
      SmsManager.getDefault()
  }
  ```
- Long messages exceeding 160 characters (such as detailed location URLs) are safely split using `smsManager.divideMessage(message)` and dispatched via `sendMultipartTextMessage()`.
- **PendingIntents** track transmission and delivery reports.

### SMS Fallback Strategy (`ACTION_SENDTO`)
- If `SEND_SMS` is denied or the cellular carrier rejects the SMS:
  - SAKHI constructs an `Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$recipient"))` with `sms_body`.
  - The default messaging app opens with the emergency text pre-filled so the user can send it manually.

---

## 5. Security & Privacy Guarantees

1. **Transient Audio Buffer**:
   - `RECORD_AUDIO` is active **only** while `EmergencyState.sessionState == SessionState.ACTIVE`.
   - The microphone is strictly **muted** during idle state and countdown state.
   - Audio is buffered in an in-memory 16-bit PCM circular buffer. No audio file is ever created or written to external or internal storage.
2. **Hardware-Backed Keystore Storage**:
   - Any sensitive tokens (such as the Gemini API Key) are encrypted using AES-256-GCM via the hardware-backed `AndroidKeyStore`.
   - Keys cannot be extracted from the device even with root access or ADB backup.

# SAKHI — Automated Verification & Testing Report

This document records the automated testing strategy, execution instructions, and mathematical verification of all **12 Core Architectural Safety Invariants** implemented in the SAKHI application.

---

## 1. Automated Test Execution Summary

The entire unit test suite for SAKHI runs locally using JUnit 4, Kotlin Coroutines Test framework (`runTest`, `StandardTestDispatcher`, `backgroundScope`), and mock objects.

### Running the Test Suite
```bash
export JAVA_HOME="/Users/shivkumaryadav/.jdk/jdk-17.0.20.1+1/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew testDebugUnitTest --rerun-tasks
```

### Official Execution Results (Generated 2026-10-12)
```
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 11s
26 actionable tasks: 26 executed
Test Results: 15 passed, 0 failed, 0 skipped, 0 errors
Time taken: 0.144 seconds
```

The XML report is located at:
`app/build/test-results/testDebugUnitTest/TEST-com.example.sakhi.EmergencyStateManagerTest.xml`

---

## 2. Formal Verification of the 12 Architectural Invariants

Every non-negotiable safety invariant specified in the master product requirements is mapped to a dedicated automated unit test in `EmergencyStateManagerTest.kt`:

| Invariant ID | Safety Requirement | Automated Test Name | Result |
|---|---|---|---|
| **Invariant 1** | Triple-tap while `IDLE` must **never** create a police request or launch emergency calls. | `invariant1_tripleTapWhileIdleCannotCreatePoliceRequest` | **PASSED** (0.004s) |
| **Invariant 2** | Triple-tap while `ACTIVE` must create **at most one** police request; duplicate requests are rejected. | `invariant2_tripleTapWhileActiveCreatesAtMostOnePoliceRequest` | **PASSED** (0.004s) |
| **Invariant 3** | The 5-second countdown to contact dispatch must **never wait for AI assessment**. | `invariant3_countdownDoesNotWaitForAi` | **PASSED** (0.012s) |
| **Invariant 4** | Complete failure, timeout, or absence of the AI engine must **never prevent contact alert dispatch**. | `invariant4_aiUnavailableDoesNotPreventAlertDispatch` | **PASSED** (0.003s) |
| **Invariant 5** | Valid cancellation before countdown expiry must **cancel the pending alert** and stop the session. | `invariant5_validCancellationBeforeExpiryCancelsPendingAlert` | **PASSED** (0.004s) |
| **Invariant 6** | Cancellation after alert dispatch must **never pretend to recall a sent SMS**; history accurately reflects `SENT`. | `invariant6_cancellationDoesNotPretendToRecallSentAlert` | **PASSED** (0.004s) |
| **Invariant 7** | Contact alert state requires real SMS dispatcher evidence before transitioning to `SENT`. | `invariant7_contactAlertRequiresRealEvidenceForSent` | **PASSED** (0.001s) |
| **Invariant 8** | Police call states must be strictly distinct: `IDLE` -> `POLICE_REQUESTED` -> `CALL_INITIATED` -> `CALL_CONNECTED`/`CALL_FAILED`. | `invariant8_policeStatesAreDistinct` | **PASSED** (0.004s) |
| **Invariant 9** | Microphone capture must **never start while the system is IDLE**. | `invariant9_microphoneNotStartedWhileIdle` | **PASSED** (0.001s) |
| **Invariant 10** | A BLE disconnection event must **never erase or abort an active emergency session**. | `invariant10_bleDisconnectDoesNotEraseActiveSession` | **PASSED** (0.093s) |
| **Invariant 11** | Malformed, corrupted, or hallucinated AI responses must **never trigger accidental police escalation**. | `invariant11_malformedAiResponseCannotTriggerEscalation` | **PASSED** (0.001s) |
| **Invariant 12** | Simulation and Demo Lab events must **never trigger real SMS dispatches or live police calls**. | `invariant12_simulationEventsCannotTriggerRealEmergencyCommunication` | **PASSED** (0.004s) |

---

## 3. Additional Quality & Protocol Unit Tests

In addition to the 12 invariants, 3 auxiliary unit tests verify system components:

1. **`contactValidation_validatesPhoneLength`**:
   - Verifies phone number sanitization (stripping whitespace, hyphens, and brackets).
   - Validates that numbers must be between 10 and 15 digits according to E.164 standards.
2. **`escalationPolicy_sensitivityModesBehaveAccurately`**:
   - Tests sensitivity transitions between `Conservative` (3 detections needed), `Balanced` (2 detections needed), and `Sensitive` (1 detection needed).
   - Verifies latching behavior and 30-second cooldown mechanics.
3. **`protocolParser_decodesDoublePressAndBattery`**:
   - Encodes and decodes binary BLE byte frames matching the ESP32-S3 specification (`0x01` double press, `0x04` battery packet).
   - Ensures correct payload extraction for percentages and charging flags.

---

## 4. End-to-End Interactive Testing via SAKHI Demo Lab

Because physical ESP32-S3 hardware may not always be connected to the developer machine during testing, SAKHI embeds a full **Hardware and AI Simulator** accessible via the bottom navigation tab **"Demo Lab"**:

```
+-------------------------------------------------------------+
|                     SAKHI DEMO LAB                          |
|                                                             |
|  [ DOUBLE PRESS ]       [ TAP & HOLD ]      [ TRIPLE TAP ]  |
|  (Simulate SOS)        (Simulate Cancel)    (Simulate 112)  |
|                                                             |
|  ---------------------------------------------------------  |
|  BATTERY TELEMETRY SIMULATION                               |
|  [ 15% (Low) ]    [ 50% ]    [ 85% ]    [ 100% (Charging) ] |
|                                                             |
|  ---------------------------------------------------------  |
|  BLE LINK FAULT INJECTION                                   |
|  [ Toggle BLE Disconnect ]                                  |
|                                                             |
|  ---------------------------------------------------------  |
|  AI THREAT ASSESSMENT TEST BENCH                            |
|  [ "Help me!" ]  [ "Stop following me!" ]  [ Normal Talk ]  |
|  [ Inject Custom Audio Evaluation ]                         |
|                                                             |
|  Live Simulation Terminal & Log Output                      |
+-------------------------------------------------------------+
```

### Testing Steps in Demo Lab:
1. **Double-Press Test**:
   - Tap **"Simulate Double Press"**.
   - Verify that the app transitions to `COUNTING_DOWN`, plays a 5-second countdown with ticking haptics, and shows the emergency countdown circle.
2. **Cancellation Test**:
   - While the countdown is running, tap **"Simulate Tap & Hold"**.
   - Verify the countdown aborts, the session cancels, and the app safely returns to `IDLE` state.
3. **Triple-Tap While Idle vs Active**:
   - While idle, tap **"Simulate Triple Tap"**. Verify nothing happens and police state remains `IDLE`.
   - Start an emergency via double press, wait until active, then tap **"Simulate Triple Tap"**. Verify the police state transitions to `POLICE_REQUESTED`.
4. **BLE Disconnection Test**:
   - While an emergency is active, tap **"Simulate BLE Disconnect"**.
   - Verify that an amber warning banner appears showing the keychain is disconnected, but the emergency session **remains active**, and the location tracker and countdown continue uninterrupted.

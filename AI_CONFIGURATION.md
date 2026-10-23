# SAKHI — AI Threat Assessment & Configuration Guide

This document describes the design, architecture, security, and configuration of the **Dual-Tier AI Distress Assessment Engine** within the SAKHI application.

---

## 1. Safety Architecture: The Non-Negotiable Boundaries of AI

In high-stakes personal safety systems, **stochastic models (LLMs) must NEVER be single points of failure**. SAKHI implements strict architectural guardrails to ensure that AI serves as an intelligence amplifier, not a safety bottleneck:

```
+-----------------------------------------------------------------------------------+
| EMERGENCY TIMELINE INDEPENDENCE                                                   |
|                                                                                   |
|  Double-Press  ---> [ 5s Countdown ] --------> [ SMS Alert Sent to Contacts ]     |
|                             |                              |                      |
|                             v                              v                      |
|                     (Timer Runs In App)          (Non-AI Application Code)         |
|                                                                                   |
|  -------------------------------------------------------------------------------  |
|                                                                                   |
|  PARALLEL AI PIPELINE (Completely Decoupled)                                      |
|                                                                                   |
|  Active Session ---> [ Audio Capture ] ---> [ Dual-Tier AI ] ---> [ Policy Gate ] |
|                                                                          |        |
|                                                                          v        |
|                                                                  (Police Escalate)|
+-----------------------------------------------------------------------------------+
```

### Architectural Safety Invariants
1. **Invariant 3**: The 5-second countdown to contact dispatch **never waits for AI assessment**.
2. **Invariant 4**: Complete failure, timeout, or absence of the AI engine **never prevents emergency contact alert dispatch**.
3. **The LLM never directly executes actions**: Gemini returns structured assessment JSON data (`distressCategory`, `confidence`, `urgencyScore`, `keywordsDetected`). The deterministic `EscalationPolicy` evaluates this data against mathematical threshold rules.

---

## 2. Dual-Tier Assessment Architecture

SAKHI combines local offline deterministic heuristic analysis with advanced cloud LLM reasoning:

```
                                  [ Audio Stream ]
                                         |
                                         v
                         +-------------------------------+
                         |      Tier 1: Local Engine     |
                         | - Real-time acoustic RMS      |
                         | - Offline heuristic keywords  |
                         | - Zero latency, 0 network req |
                         +---------------+---------------+
                                         |
                    +--------------------+--------------------+
                    |                                         |
       [ No Cloud Key / Offline ]                   [ Cloud Key Configured ]
                    |                                         |
                    v                                         v
     +-------------------------------+         +-------------------------------+
     | LocalDeterministicEngine      |         | GeminiAiAssessmentEngine      |
     | - Evaluates decibel spikes    |         | - Model: gemini-2.5-flash     |
     | - Detects acoustic anomalies  |         | - Structured JSON evaluation  |
     | - Generates Local Assessment  |         | - Natural distress analysis   |
     +---------------+---------------+         +---------------+---------------+
                     |                                         |
                     +-------------------+---------------------+
                                         | Structured Assessment
                                         v
                         +-------------------------------+
                         |       EscalationPolicy        |
                         |  (Deterministic State Gating) |
                         +---------------+---------------+
                                         | Threshold Met?
                                         v
                         +-------------------------------+
                         |      Police Escalation        |
                         +-------------------------------+
```

### Tier 1: Local Deterministic Engine (`LocalDeterministicAssessmentEngine`)
- **Execution**: 100% on-device inside the Android process.
- **Dependencies**: Zero network, zero API keys, zero cloud dependencies.
- **Heuristics**:
  - Calculates acoustic RMS (Root Mean Square) energy of raw PCM samples.
  - Detects elevated acoustic stress spikes (> 0.70 normalized RMS).
  - Evaluates distress triggers with near-zero latency (< 10ms).
- **Default State**: Active out-of-the-box on every device.

### Tier 2: Cloud LLM Engine (`GeminiAiAssessmentEngine`)
- **Execution**: Secure HTTPS endpoint to Google Gemini Generative Language API.
- **Model**: `gemini-2.5-flash` (or user-selected Gemini model).
- **Prompt Structure**:
  ```json
  System Instruction:
  You are SAKHI's emergency distress assessment engine. You analyze ambient audio transcriptions or acoustic features for indicators of physical distress, assault, stalking, violence, or screams.
  Respond ONLY with valid JSON conforming to this schema:
  {
    "isDistressed": boolean,
    "confidence": float (0.0 - 1.0),
    "urgencyScore": int (1 - 5),
    "distressCategory": "PHYSICAL_ASSAULT" | "VERBAL_THREAT" | "STALKING" | "MEDICAL" | "UNCERTAIN" | "NONE",
    "rationale": string,
    "keywordsDetected": [string]
  }
  ```

---

## 3. Deterministic Escalation Policy

The `EscalationPolicy` acts as an incorruptible gatekeeper between the AI assessment stream and the telephony subsystem.

### Sensitivity Profiles
Users can select their preferred sensitivity mode in **Settings**:

| Policy Mode | Trigger Criteria | Use Case |
|---|---|---|
| **Balanced (Default)** | Requires **2 consecutive** distress detections with confidence $\ge 0.85$, OR 1 critical detection with urgency score $= 5$. | Recommended balance between speed and false-positive prevention. |
| **Sensitive** | Requires **1 detection** with confidence $\ge 0.70$ and urgency $\ge 3$. | High-risk environments (walking through isolated or dimly lit areas). |
| **Conservative** | Requires **3 consecutive** detections with confidence $\ge 0.90$. | Crowded or noisy environments with elevated ambient chatter. |

### Latching & Cooldown Rules
- Once the escalation policy triggers a police escalation, it **latches** into a triggered state for the lifetime of that emergency session.
- A **30-second cooldown** prevents re-escalation or duplicate call launches.

---

## 4. Hardware-Backed API Key Security

To prevent API key extraction from the device:

1. **AndroidKeyStore Hardware Isolation**:
   - The key is encrypted via `KeystoreSecretStorage` using **AES-256 in GCM mode** (`AES/GCM/NoPadding`).
   - The master encryption key is generated inside the hardware-backed Secure Element (SE) / ARM TrustZone.
   - The private key material never leaves the secure hardware enclave.
2. **Zero Plaintext Storage**:
   - Encrypted payloads are stored in private SharedPreferences as Base64-encoded initialization vectors and ciphertexts.
   - The API key is never logged to Logcat, never saved to Room database, and never backed up to Google Drive.

---

## 5. Privacy: Strict Zero-Disk Audio Policy

SAKHI adheres to a strict privacy guarantee: **Audio is never recorded to disk**.

- **Transient In-Memory Buffer**: The `AudioPipeline` uses an in-memory 16-bit PCM circular buffer sized for 10 seconds of 16 kHz mono audio (~320 KB in RAM).
- **Session Lifecycle Bound**:
  - `startCapture()` is called **only** when `EmergencyState.sessionState == SessionState.ACTIVE`.
  - When the emergency session ends (via cancellation or resolution), `stopCapture()` is invoked immediately.
  - The in-memory buffer is **zeroed out** (`buffer.fill(0)`) and garbage-collected.
- No `.wav`, `.mp3`, or audio files of any kind are ever created in the app's cache, files directory, or external storage.

---

## 6. Accessing the Developer AI Settings Screen

To prevent accidental tampering while keeping configuration seamless for evaluators:

1. Launch the SAKHI app and open the **Home Screen**.
2. **Long-press the SAKHI brand mark (shield logo)** at the top of the screen for **3 seconds**.
3. A haptic pulse will fire, and the **AI Provider Settings Screen** will appear.
4. From this screen:
   - Toggle between **Local Deterministic Engine** and **Google Gemini Cloud Engine**.
   - Enter your **Google Gemini API Key**.
   - Select the target model (`gemini-2.5-flash`, `gemini-1.5-pro`, etc.).
   - Tap **"Test AI Connection"** to verify the key against Google's servers.
   - Tap **"Save Configuration"** (the key will be immediately encrypted via AndroidKeyStore).

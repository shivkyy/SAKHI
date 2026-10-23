#include <Arduino.h>
#include <driver/i2s.h>
#include "BluetoothSerial.h"

// ========================================================
// SAKHI: ESP32 + INMP441 Continuous Audio Streaming
// and Physical Keychain Button Multiplexed Framing Protocol
// ========================================================

BluetoothSerial SerialBT;

// INMP441 Microphone Hardware Pins
#define I2S_WS   25
#define I2S_SCK  26
#define I2S_SD   33

#define I2S_PORT I2S_NUM_0

// Audio Format Configuration
#define SAMPLE_RATE 8000
#define PACKET_SAMPLES 128
#define AUDIO_BYTES_PER_PACKET (PACKET_SAMPLES * sizeof(int16_t)) // 256 bytes

// Digital gain multiplier for INMP441 MEMS microphone.
// 2x provides balanced speech sensitivity without digital clipping.
#define AUDIO_GAIN 2

// Protocol Framing Constants
static const uint8_t FRAME_MAGIC[4] = {0x53, 0x41, 0x4B, 0x48}; // 'S', 'A', 'K', 'H'
#define FRAME_TYPE_AUDIO   0x01
#define FRAME_TYPE_CONTROL 0x02

// Control Event OpCodes
#define OPCODE_DOUBLE_PRESS   0x01
#define OPCODE_HOLD_COMPLETE  0x02
#define OPCODE_TRIPLE_TAP     0x03
#define OPCODE_BATTERY_STATUS 0x04
#define OPCODE_DEVICE_STATUS  0x05
#define OPCODE_HOLD_START     0x06
#define OPCODE_HOLD_RELEASE   0x07

// Buffers
int32_t i2sSamples[PACKET_SAMPLES];
int16_t audioSamples[PACKET_SAMPLES];
uint8_t audioTxFrame[4 + 1 + 2 + AUDIO_BYTES_PER_PACKET + 1]; // 264 bytes

bool microphoneReady = false;
bool bluetoothReady = false;
bool clientConnected = false;
bool wasConnected = false;

// Physical Button Configuration (Button wired between GPIO 27 and GND)
constexpr uint8_t BUTTON_PIN = 27;
constexpr unsigned long BUTTON_DEBOUNCE_MS = 30;
constexpr unsigned long MULTI_PRESS_WINDOW_MS = 400; // Window to wait for next press in multi-press
constexpr unsigned long MAX_PRESS_DURATION_MS = 500;  // Max duration for a short press
constexpr unsigned long HOLD_DURATION_MS = 3000;      // 3 continuous seconds for cancellation / end

// Button State Machine Variables
bool buttonRawState = HIGH;
bool buttonStableState = HIGH;
bool buttonIsPressed = false;
bool holdCompleteTriggered = false;
unsigned long buttonLastRawChangeMs = 0;
unsigned long buttonPressStartMs = 0;
unsigned long buttonLastReleaseMs = 0;
uint8_t shortPressCount = 0;

// Statistics & Telemetry
unsigned long totalBytesStreamed = 0;
unsigned long totalPacketsStreamed = 0;
unsigned long streamStartTime = 0;
unsigned long lastTelemetryReport = 0;
unsigned long droppedPackets = 0;

// ========================================================
// Bluetooth Framing Protocol Helpers
// ========================================================

/**
 * Sends a framed control event to the connected Android application.
 * Format: [Magic(4)][Type:0x02][Len:0x00,0x01][OpCode][Checksum]
 */
void sendControlEvent(uint8_t opcode) {
  if (!bluetoothReady || !SerialBT.hasClient()) {
    Serial.printf("[BT] Client not connected; skipped control event 0x%02X\n", opcode);
    return;
  }

  uint8_t packet[9];
  packet[0] = FRAME_MAGIC[0];
  packet[1] = FRAME_MAGIC[1];
  packet[2] = FRAME_MAGIC[2];
  packet[3] = FRAME_MAGIC[3];
  packet[4] = FRAME_TYPE_CONTROL;
  packet[5] = 0x00; // Length High
  packet[6] = 0x01; // Length Low (1 byte payload)
  packet[7] = opcode;

  // Checksum: XOR over Type, Length High, Length Low, and Payload
  packet[8] = packet[4] ^ packet[5] ^ packet[6] ^ packet[7];

  SerialBT.write(packet, sizeof(packet));
  Serial.printf("[BT EVENT] Sent framed control opcode 0x%02X (9 bytes)\n", opcode);
}

/**
 * Sends a framed 256-byte audio packet to the connected Android application.
 * Format: [Magic(4)][Type:0x01][Len:0x01,0x00][PCM 256 bytes][Checksum]
 */
bool sendAudioFrame(const uint8_t *pcmData, size_t len) {
  if (!bluetoothReady || !SerialBT.hasClient() || len == 0) {
    return false;
  }

  audioTxFrame[0] = FRAME_MAGIC[0];
  audioTxFrame[1] = FRAME_MAGIC[1];
  audioTxFrame[2] = FRAME_MAGIC[2];
  audioTxFrame[3] = FRAME_MAGIC[3];
  audioTxFrame[4] = FRAME_TYPE_AUDIO;
  audioTxFrame[5] = (uint8_t)((len >> 8) & 0xFF);
  audioTxFrame[6] = (uint8_t)(len & 0xFF);

  memcpy(&audioTxFrame[7], pcmData, len);

  uint8_t checksum = audioTxFrame[4] ^ audioTxFrame[5] ^ audioTxFrame[6];
  for (size_t i = 0; i < len; i++) {
    checksum ^= audioTxFrame[7 + i];
  }
  audioTxFrame[7 + len] = checksum;

  size_t totalBytesToSend = 7 + len + 1;
  size_t bytesSent = SerialBT.write(audioTxFrame, totalBytesToSend);

  if (bytesSent != totalBytesToSend) {
    droppedPackets++;
    if (droppedPackets % 50 == 1) {
      Serial.printf("[BT WARN] Backpressure detected: sent %u of %u bytes (dropped: %lu)\n",
                    bytesSent, totalBytesToSend, droppedPackets);
    }
    return false;
  }

  totalBytesStreamed += len;
  totalPacketsStreamed++;
  return true;
}

// ========================================================
// Physical Button State Machine
// Non-blocking, debounced, with hold tracking and multi-press disambiguation
// ========================================================
void updatePhysicalButton() {
  const unsigned long now = millis();
  const bool reading = digitalRead(BUTTON_PIN);

  // Debouncing filter
  if (reading != buttonRawState) {
    buttonRawState = reading;
    buttonLastRawChangeMs = now;
  }

  if (reading != buttonStableState &&
      now - buttonLastRawChangeMs >= BUTTON_DEBOUNCE_MS) {
    buttonStableState = reading;

    if (buttonStableState == LOW) {
      // ----------------------------------------------------
      // Button Transition: HIGH -> LOW (Pressed Down)
      // ----------------------------------------------------
      buttonIsPressed = true;
      buttonPressStartMs = now;
      holdCompleteTriggered = false;

      // Notify Android immediately so countdown can be paused during hold evaluation
      sendControlEvent(OPCODE_HOLD_START);
      Serial.println("[BUTTON] Press down: hold tracking started (countdown pause signalled)");
    } else {
      // ----------------------------------------------------
      // Button Transition: LOW -> HIGH (Released)
      // ----------------------------------------------------
      buttonIsPressed = false;
      const unsigned long pressDuration = now - buttonPressStartMs;

      if (holdCompleteTriggered) {
        // 3-second hold was already completed and emitted while held
        holdCompleteTriggered = false;
        shortPressCount = 0;
        Serial.println("[BUTTON] Hold released after completion");
      } else {
        // Button was released before 3000 ms
        // Notify Android to resume countdown if it was paused
        sendControlEvent(OPCODE_HOLD_RELEASE);

        if (pressDuration <= MAX_PRESS_DURATION_MS) {
          // Valid short press
          shortPressCount++;
          buttonLastReleaseMs = now;
          Serial.printf("[BUTTON] Short press #%u recorded (duration: %lu ms)\n",
                        shortPressCount, pressDuration);

          if (shortPressCount >= 3) {
            // Triple press confirmed immediately on 3rd release
            sendControlEvent(OPCODE_TRIPLE_TAP);
            Serial.println("[BUTTON] >>> TRIPLE PRESS DETECTED -> Call Now triggered <<<");
            shortPressCount = 0;
          }
        } else {
          // Held > 500ms but < 3000ms: rejected as short press, not completed as hold
          shortPressCount = 0;
          Serial.printf("[BUTTON] Press rejected: held for %lu ms (neither short tap nor 3s hold)\n",
                        pressDuration);
        }
      }
    }
  }

  // ------------------------------------------------------
  // Ongoing Hold Evaluation (while button remains held LOW)
  // ------------------------------------------------------
  if (buttonIsPressed && !holdCompleteTriggered) {
    if (now - buttonPressStartMs >= HOLD_DURATION_MS) {
      holdCompleteTriggered = true;
      shortPressCount = 0; // Invalidate any pending multi-presses
      sendControlEvent(OPCODE_HOLD_COMPLETE);
      Serial.println("[BUTTON] >>> 3-SECOND HOLD COMPLETE -> Cancellation / End session triggered <<<");
    }
  }

  // ------------------------------------------------------
  // Multi-Press Resolution Timer (when button is released)
  // ------------------------------------------------------
  if (!buttonIsPressed && shortPressCount > 0) {
    if (now - buttonLastReleaseMs > MULTI_PRESS_WINDOW_MS) {
      if (shortPressCount == 2) {
        // Multi-press window expired without 3rd press -> Confirmed DOUBLE PRESS
        sendControlEvent(OPCODE_DOUBLE_PRESS);
        Serial.println("[BUTTON] >>> DOUBLE PRESS DETECTED -> SOS Activation triggered <<<");
      } else if (shortPressCount == 1) {
        Serial.println("[BUTTON] Single short press window expired (no action)");
      }
      shortPressCount = 0;
    }
  }
}

// ========================================================
// Initialize INMP441 I2S Microphone
// ========================================================
bool initializeMicrophone() {
  Serial.println("[MIC] Initializing INMP441 I2S microphone...");

  i2s_config_t config = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
    .sample_rate = SAMPLE_RATE,
    .bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT,
    .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count = 8,
    .dma_buf_len = 256,
    .use_apll = false,
    .tx_desc_auto_clear = false,
    .fixed_mclk = 0
  };

  i2s_pin_config_t pins = {
    .bck_io_num = I2S_SCK,
    .ws_io_num = I2S_WS,
    .data_out_num = I2S_PIN_NO_CHANGE,
    .data_in_num = I2S_SD
  };

  esp_err_t result = i2s_driver_install(I2S_PORT, &config, 0, nullptr);
  if (result != ESP_OK) {
    Serial.printf("[MIC ERROR] Driver install failed: %d\n", result);
    return false;
  }

  result = i2s_set_pin(I2S_PORT, &pins);
  if (result != ESP_OK) {
    Serial.printf("[MIC ERROR] Pin configuration failed: %d\n", result);
    i2s_driver_uninstall(I2S_PORT);
    return false;
  }

  i2s_zero_dma_buffer(I2S_PORT);
  Serial.println("[MIC] I2S initialized successfully (8000 Hz, 32-bit slot, Mono-Left).");
  return true;
}

// ========================================================
// Initialize Bluetooth Classic Serial (RFCOMM SPP)
// ========================================================
bool initializeBluetooth() {
  Serial.println("[BT] Starting Bluetooth Classic Serial (SPP)...");

  if (!SerialBT.begin("SAKHI_AUDIO")) {
    Serial.println("[BT ERROR] Initialization failed.");
    return false;
  }

  Serial.println("[BT] Bluetooth started successfully.");
  Serial.println("[BT] Device Name: SAKHI_AUDIO");
  Serial.println("[BT] Waiting for phone connection...");
  return true;
}

// ========================================================
// Read I2S and Stream One Audio Packet Over Bluetooth
// ========================================================
bool streamAudioPacket() {
  size_t bytesRead = 0;

  // Read exactly PACKET_SAMPLES 32-bit words from I2S DMA.
  // At 8kHz with 128 samples, this block takes ~16ms, pacing the loop naturally.
  esp_err_t result = i2s_read(
    I2S_PORT,
    i2sSamples,
    sizeof(i2sSamples),
    &bytesRead,
    pdMS_TO_TICKS(100)
  );

  if (result != ESP_OK) {
    Serial.printf("[MIC ERROR] i2s_read failed: %d\n", result);
    return false;
  }

  if (bytesRead == 0) {
    return false;
  }

  size_t count = bytesRead / sizeof(int32_t);
  if (count > PACKET_SAMPLES) {
    count = PACKET_SAMPLES;
  }

  // Convert 24-bit MSB-aligned samples inside 32-bit words to 16-bit PCM.
  // Shifting right by 16 correctly sign-extends and maps bits [31..16] to [15..0].
  // Saturated digital gain prevents wrap-around clipping on loud acoustics.
  for (size_t i = 0; i < count; i++) {
    int32_t sample = (i2sSamples[i] >> 16) * AUDIO_GAIN;
    if (sample > 32767) sample = 32767;
    else if (sample < -32768) sample = -32768;
    audioSamples[i] = (int16_t)sample;
  }

  size_t bytesToSend = count * sizeof(int16_t);
  return sendAudioFrame((const uint8_t *)audioSamples, bytesToSend);
}

// ========================================================
// Diagnostic Status Printout
// ========================================================
void printStatus() {
  Serial.println();
  Serial.println("================ SAKHI AUDIO & BUTTON STATUS ================");
  Serial.printf("Microphone (INMP441) : %s\n", microphoneReady ? "READY" : "FAILED");
  Serial.printf("Bluetooth (SPP)      : %s\n", bluetoothReady ? "READY" : "FAILED");
  Serial.printf("Device Name          : SAKHI_AUDIO\n");
  Serial.printf("Phone Connected      : %s\n", SerialBT.hasClient() ? "YES (ACTIVE)" : "NO (IDLE)");
  Serial.printf("Button Pin (GPIO 27) : %s (Raw: %s)\n",
                buttonStableState == LOW ? "PRESSED" : "RELEASED",
                digitalRead(BUTTON_PIN) == LOW ? "LOW" : "HIGH");
  Serial.printf("Sample Rate          : %d Hz\n", SAMPLE_RATE);
  Serial.printf("Format               : 16-bit Signed Linear PCM, Mono, Little-Endian\n");
  Serial.printf("Framing Protocol     : SAKH Binary Multiplexed (Audio + Control)\n");
  Serial.printf("Total Audio Streamed : %lu bytes (%.2f KB)\n", totalBytesStreamed, totalBytesStreamed / 1024.0);
  Serial.printf("Packets Streamed     : %lu\n", totalPacketsStreamed);
  Serial.printf("Dropped Packets      : %lu\n", droppedPackets);
  if (wasConnected && streamStartTime > 0) {
    unsigned long duration = (millis() - streamStartTime) / 1000;
    Serial.printf("Current Session Time : %lu seconds\n", duration);
  }
  Serial.println("=============================================================");
  Serial.println();
}

// ========================================================
// Setup
// ========================================================
void setup() {
  Serial.begin(115200);
  delay(1500);

  pinMode(BUTTON_PIN, INPUT_PULLUP);
  buttonStableState = digitalRead(BUTTON_PIN);
  buttonRawState = buttonStableState;
  buttonLastRawChangeMs = millis();

  Serial.println();
  Serial.println("========================================================");
  Serial.println(" SAKHI ESP32 CONTINUOUS AUDIO & PHYSICAL BUTTON SYSTEM ");
  Serial.println("========================================================");

  microphoneReady = initializeMicrophone();
  if (!microphoneReady) {
    Serial.println("[FATAL] Microphone initialization failed!");
  }

  bluetoothReady = initializeBluetooth();
  if (!bluetoothReady) {
    Serial.println("[FATAL] Bluetooth initialization failed!");
  }

  Serial.println();
  Serial.println("Operating Mode: AUTOMATIC STREAMING & HARDWARE GESTURES");
  Serial.println("- Double Press (within 400ms)  : Activates SOS");
  Serial.println("- 3-Second Hold during Countdown: Cancels pending alert");
  Serial.println("- 3-Second Hold after Alert    : Ends active session");
  Serial.println("- Triple Press (3 short taps)  : Triggers immediate Call Now");
  Serial.println("- Send 'H' in Serial Monitor for diagnostic status.");
  Serial.println();
}

// ========================================================
// Main Loop
// ========================================================
void loop() {
  updatePhysicalButton();

  // Check USB Serial Monitor user commands
  if (Serial.available()) {
    char cmd = Serial.read();
    if (cmd == 'H' || cmd == 'h') {
      printStatus();
    }
  }

  // Monitor Bluetooth Client Connection Lifecycle
  clientConnected = bluetoothReady && SerialBT.hasClient();

  if (clientConnected) {
    // Client just connected
    if (!wasConnected) {
      wasConnected = true;
      streamStartTime = millis();
      lastTelemetryReport = millis();
      i2s_zero_dma_buffer(I2S_PORT); // flush stale samples
      Serial.println();
      Serial.println(">>> [BT] PHONE CONNECTED: Starting continuous audio stream! <<<");
    }

    // Stream one audio packet (128 samples = 256 bytes)
    // i2s_read naturally paces this at ~16ms per iteration (62.5 packets/sec = 16,000 bytes/sec)
    if (microphoneReady) {
      streamAudioPacket();
    }

    // Periodic USB Serial telemetry report every 5 seconds
    if (millis() - lastTelemetryReport >= 5000) {
      lastTelemetryReport = millis();
      unsigned long elapsedSec = (millis() - streamStartTime) / 1000;
      float kbps = (totalBytesStreamed * 8.0f) / (elapsedSec > 0 ? elapsedSec * 1000.0f : 1.0f);
      Serial.printf("[STREAMING] %lu bytes (~%lu s) | Bitrate: %.1f kbps | Packets: %lu\n",
                    totalBytesStreamed, elapsedSec, kbps, totalPacketsStreamed);
    }
  } else {
    // Client just disconnected
    if (wasConnected) {
      wasConnected = false;
      Serial.println();
      Serial.println(">>> [BT] PHONE DISCONNECTED: Audio streaming paused. <<<");
      printStatus();
    }

    // Yield CPU while waiting for phone connection
    delay(10);
  }
}

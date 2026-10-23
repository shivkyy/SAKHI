package com.example.sakhi.ui.device

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SettingsBluetooth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sakhi.R
import com.example.sakhi.core.designsystem.SakhiButton
import com.example.sakhi.core.designsystem.SakhiOutlinedButton
import com.example.sakhi.core.designsystem.StatusPill
import com.example.sakhi.core.designsystem.TruthfulStatusBadge
import com.example.sakhi.core.model.EmergencyState
import com.example.sakhi.core.model.KeychainLinkState
import com.example.sakhi.data.ble.BleManager
import com.example.sakhi.data.bluetooth.BluetoothClassicAudioReceiver
import com.example.sakhi.data.bluetooth.BtAudioConnectionState
import com.example.sakhi.data.bluetooth.BtAudioTelemetry
import com.example.sakhi.theme.SakhiEmergency
import com.example.sakhi.theme.SakhiLavenderAccent
import com.example.sakhi.theme.SakhiPrimary
import com.example.sakhi.theme.SakhiSuccess
import com.example.sakhi.theme.SakhiWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Normal Device/Keychain Screen focused strictly on physical ESP32 hardware and SPP audio connection.
 * All audio, DSP, and speech recognition diagnostics have been relocated to DiagnosticsScreen.kt.
 * Obsolete BLE GATT scanning, haptic testing, and unsupported telemetry have been removed.
 */
@Composable
fun DeviceScreen(
    state: EmergencyState,
    bleManager: BleManager,
    btAudioReceiver: BluetoothClassicAudioReceiver? = null,
    externalTranscriptionService: com.example.sakhi.data.assistant.VoskAudioTranscriptionService? = null,
    voskModelManager: com.example.sakhi.data.assistant.VoskModelManager? = null,
    audioDiagnosticRecorder: com.example.sakhi.data.audio.AudioDiagnosticRecorder? = null,
    onNavigateToDiagnostics: (() -> Unit)? = null
) {
    val telemetry by btAudioReceiver?.telemetry?.collectAsState() ?: remember {
        mutableStateOf(BtAudioTelemetry())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Screen Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.device_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (bleManager.isSimulated) "Demo Mode — Simulated Keychain" else "ESP32 Bluetooth Classic Hardware (SPP)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TruthfulStatusBadge(
                linkState = state.keychainLinkState,
                isSimulated = bleManager.isSimulated
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Hero Keychain Status Card (Truthful SPP connection state)
        KeychainHardwareStatusCard(
            state = state,
            telemetry = telemetry,
            isSimulated = bleManager.isSimulated
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Primary Connection Management Card (Bluetooth Classic SPP)
        KeychainAudioConnectionCard(
            btAudioReceiver = btAudioReceiver,
            onNavigateToDiagnostics = onNavigateToDiagnostics
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Troubleshooting Guide
        KeychainTroubleshootingCard()

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
fun KeychainHardwareStatusCard(
    state: EmergencyState,
    telemetry: BtAudioTelemetry,
    isSimulated: Boolean
) {
    val isConnected = state.keychainLinkState == KeychainLinkState.CONNECTED

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(MaterialTheme.colorScheme.outline)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(
                        if (isConnected) SakhiSuccess.copy(alpha = 0.16f)
                        else SakhiPrimary.copy(alpha = 0.16f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = "Keychain Hardware",
                    tint = if (isConnected) SakhiSuccess else SakhiPrimary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = if (isSimulated) "SAKHI-ESP32 (Simulated)" else (telemetry.deviceName ?: state.deviceName),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (isConnected) (telemetry.deviceAddress ?: state.deviceAddress ?: "SPP Audio Link Active") else "Device not connected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Truthful Stream Telemetry (No fake BLE gauges)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TelemetryRow(
                    label = "Transport",
                    value = if (isSimulated) "Simulated" else "BT Classic SPP"
                )
                TelemetryRow(
                    label = "Audio Format",
                    value = if (isConnected) "${state.audioSampleRateHz} Hz Mono" else "Offline"
                )
                TelemetryRow(
                    label = "Stream State",
                    value = when (telemetry.state) {
                        BtAudioConnectionState.STREAMING -> "Streaming"
                        BtAudioConnectionState.CONNECTED -> "Connected"
                        BtAudioConnectionState.CONNECTING -> "Connecting..."
                        BtAudioConnectionState.ERROR -> "Error"
                        BtAudioConnectionState.DISCONNECTED -> "Offline"
                    }
                )
                TelemetryRow(
                    label = "Last Received",
                    value = if (state.lastSeenTimestamp != null) {
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(state.lastSeenTimestamp))
                    } else "Never"
                )
            }
        }
    }
}

@Composable
fun TelemetryRow(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (value == "Offline" || value == "Never") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Clean, primary connection management card for Bluetooth Classic SPP Audio from the keychain wearable.
 * Elevated to be the main physical device connection interface.
 */
@Composable
fun KeychainAudioConnectionCard(
    btAudioReceiver: BluetoothClassicAudioReceiver?,
    onNavigateToDiagnostics: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val telemetry by btAudioReceiver?.telemetry?.collectAsState() ?: remember {
        mutableStateOf(BtAudioTelemetry())
    }
    val pairedDevices = remember(telemetry.state) {
        btAudioReceiver?.getPairedDevices() ?: emptyList()
    }
    val isConnected = telemetry.state == BtAudioConnectionState.CONNECTED || telemetry.state == BtAudioConnectionState.STREAMING

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(MaterialTheme.colorScheme.outline)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                when (telemetry.state) {
                                    BtAudioConnectionState.STREAMING -> SakhiSuccess.copy(alpha = 0.16f)
                                    BtAudioConnectionState.CONNECTED -> SakhiLavenderAccent.copy(alpha = 0.16f)
                                    BtAudioConnectionState.CONNECTING -> SakhiWarning.copy(alpha = 0.16f)
                                    BtAudioConnectionState.ERROR -> SakhiEmergency.copy(alpha = 0.16f)
                                    BtAudioConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Keychain Audio Stream",
                            tint = when (telemetry.state) {
                                BtAudioConnectionState.STREAMING -> SakhiSuccess
                                BtAudioConnectionState.CONNECTED -> SakhiLavenderAccent
                                BtAudioConnectionState.CONNECTING -> SakhiWarning
                                BtAudioConnectionState.ERROR -> SakhiEmergency
                                BtAudioConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "KEYCHAIN AUDIO LINK (SPP)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.2.sp
                        )
                        Text(
                            text = telemetry.deviceName ?: "ESP32 (SAKHI_AUDIO)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                StatusPill(
                    text = when (telemetry.state) {
                        BtAudioConnectionState.STREAMING -> "STREAMING"
                        BtAudioConnectionState.CONNECTED -> "CONNECTED"
                        BtAudioConnectionState.CONNECTING -> "LINKING..."
                        BtAudioConnectionState.ERROR -> "ERROR"
                        BtAudioConnectionState.DISCONNECTED -> "OFFLINE"
                    },
                    color = when (telemetry.state) {
                        BtAudioConnectionState.STREAMING -> SakhiSuccess
                        BtAudioConnectionState.CONNECTED -> SakhiLavenderAccent
                        BtAudioConnectionState.CONNECTING -> SakhiWarning
                        BtAudioConnectionState.ERROR -> SakhiEmergency
                        BtAudioConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Bluetooth Classic SPP channel for the wearable microphone. Automatically prioritized by the Unified Audio Router during emergency SOS sessions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (isConnected) {
                SakhiOutlinedButton(
                    text = "Disconnect Audio Link",
                    onClick = { btAudioReceiver?.disconnect() },
                    borderColor = SakhiWarning,
                    contentColor = SakhiWarning,
                    height = 42.dp
                )
            } else {
                val sakhiDevices = pairedDevices.filter { it.isSakhiAudio }
                if (sakhiDevices.isNotEmpty()) {
                    sakhiDevices.forEach { dev ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = dev.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = dev.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SakhiButton(
                                text = "Connect",
                                onClick = {
                                    com.example.sakhi.service.EmergencyForegroundService.startMonitoring(context)
                                    btAudioReceiver?.connect(dev.address)
                                },
                                height = 38.dp,
                                modifier = Modifier.width(96.dp)
                            )
                        }
                    }
                } else {
                    SakhiButton(
                        text = if (telemetry.state == BtAudioConnectionState.CONNECTING) "Connecting..." else "Connect Audio (SAKHI_AUDIO)",
                        onClick = {
                            com.example.sakhi.service.EmergencyForegroundService.startMonitoring(context)
                            btAudioReceiver?.connect()
                        },
                        height = 42.dp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Direct button to open Android Bluetooth Settings to pair new devices
            SakhiOutlinedButton(
                text = "Open Bluetooth Settings",
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                },
                height = 42.dp
            )

            if (onNavigateToDiagnostics != null) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Need audio telemetry or speech testing?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onNavigateToDiagnostics) {
                        Text("Diagnostics →", color = SakhiPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun KeychainTroubleshootingCard() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(MaterialTheme.colorScheme.outline)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.HelpOutline,
                    contentDescription = null,
                    tint = SakhiLavenderAccent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "HARDWARE TROUBLESHOOTING",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.2.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            TroubleshootStep(step = "1", text = "Power on the ESP32-WROOM hardware with INMP441 microphone.")
            TroubleshootStep(step = "2", text = "Pair with 'SAKHI_AUDIO' in Android Bluetooth Settings using the button above.")
            TroubleshootStep(step = "3", text = "Ensure phone's Bluetooth and Nearby Devices permissions are enabled.")
            TroubleshootStep(step = "4", text = "Return here and tap 'Connect Audio' to establish the 8 kHz SPP stream.")
        }
    }
}

@Composable
fun TroubleshootStep(step: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(SakhiPrimary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step,
                style = MaterialTheme.typography.labelSmall,
                color = SakhiPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

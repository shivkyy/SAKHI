package com.example.sakhi.ui.diagnostics

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sakhi.SakhiApplication
import com.example.sakhi.core.designsystem.SakhiButton
import com.example.sakhi.core.designsystem.SakhiOutlinedButton
import com.example.sakhi.core.designsystem.StatusPill
import com.example.sakhi.core.model.DistressAssessment
import com.example.sakhi.core.model.DistressCategory
import com.example.sakhi.core.model.EmergencyState
import com.example.sakhi.data.assistant.ExternalSpeechLanguage
import com.example.sakhi.data.assistant.RecognitionSessionType
import com.example.sakhi.data.assistant.VoskAudioTranscriptionService
import com.example.sakhi.data.assistant.VoskModelManager
import com.example.sakhi.data.assistant.VoskModelState
import com.example.sakhi.data.audio.ActiveAudioSource
import com.example.sakhi.data.audio.AudioDiagnosticRecorder
import com.example.sakhi.data.audio.AudioDiagnosticState
import com.example.sakhi.data.audio.RouterOperationalState
import com.example.sakhi.data.audio.SpeexOperationalState
import com.example.sakhi.data.audio.SpeexTelemetry
import com.example.sakhi.data.audio.UnifiedAudioRouter
import com.example.sakhi.data.bluetooth.BluetoothClassicAudioReceiver
import com.example.sakhi.data.bluetooth.BtAudioConnectionState
import com.example.sakhi.data.bluetooth.BtAudioTelemetry
import com.example.sakhi.theme.SakhiEmergency
import com.example.sakhi.theme.SakhiLavenderAccent
import com.example.sakhi.theme.SakhiPrimary
import com.example.sakhi.theme.SakhiSuccess
import com.example.sakhi.theme.SakhiWarning
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated System & Audio Diagnostics Screen for SAKHI.
 *
 * Provides real-time inspection, telemetry, and testing for:
 * 1. Audio Source & UnifiedAudioRouter failover/failback
 * 2. SpeexDSP Noise Suppression operational state & frame counters
 * 3. Offline Bilingual Vosk Speech Recognition (English 8kHz + Hindi 16kHz)
 * 4. Bilingual Transcript Arbitration & Authoritative Winner Selection
 * 5. Diagnostic Distress Assessment (EmergencyRiskAnalyzer)
 * 6. WAV Audio Diagnostic Recorder & Exporter
 * 7. Model and Acoustic Pipeline Health
 *
 * Safety Invariants:
 * - Diagnostic test sessions are STRICTLY isolated: zero calls, SMS, or sirens.
 * - If a PRODUCTION_SOS session is active, diagnostic testing controls are LOCKED.
 * - Exiting this screen cleanly releases diagnostic audio capture resources.
 */
@Composable
fun DiagnosticsScreen(
    app: SakhiApplication,
    onNavigateBack: () -> Unit
) {
    val emergencyState by app.emergencyStateManager.state.collectAsState()

    DiagnosticsScreenContent(
        audioRouter = app.unifiedAudioRouter,
        transcriptionService = app.externalTranscriptionService,
        modelManager = app.voskModelManager,
        audioDiagnosticRecorder = app.audioDiagnosticRecorder,
        btAudioReceiver = app.btAudioReceiver,
        emergencyState = emergencyState,
        onNavigateBack = onNavigateBack
    )
}

@Composable
fun DiagnosticsScreenContent(
    audioRouter: UnifiedAudioRouter,
    transcriptionService: VoskAudioTranscriptionService,
    modelManager: VoskModelManager,
    audioDiagnosticRecorder: AudioDiagnosticRecorder,
    btAudioReceiver: BluetoothClassicAudioReceiver,
    emergencyState: EmergencyState,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 1. Router & Sources Telemetry
    val routerTelemetry by audioRouter.telemetry.collectAsState()
    val btTelemetry by btAudioReceiver.telemetry.collectAsState()
    val phoneTelemetry by audioRouter.phoneAudioSource.telemetry.collectAsState()

    // 2. Transcription Service State
    val sessionType by transcriptionService.sessionType.collectAsState()
    val asrStatus by transcriptionService.statusText.collectAsState()
    val isIsolationEnabled by transcriptionService.isDiagnosticIsolationEnabled.collectAsState()
    val isNoiseSuppressionEnabled by transcriptionService.isNoiseSuppressionEnabled.collectAsState()
    val speexTelemetry by transcriptionService.speexTelemetry.collectAsState()

    // 3. Channels & Arbitration
    val safetyStatus by transcriptionService.safetyStatusText.collectAsState()
    val safetyPartial by transcriptionService.safetyPartialText.collectAsState()
    val safetyFinal by transcriptionService.safetyFinalTranscript.collectAsState()

    val unrestrictedStatus by transcriptionService.unrestrictedStatusText.collectAsState()
    val unrestrictedPartial by transcriptionService.unrestrictedPartialText.collectAsState()
    val unrestrictedFinal by transcriptionService.unrestrictedFinalTranscript.collectAsState()

    val arbitrationTelemetry by transcriptionService.arbitrator.telemetry.collectAsState()
    val modelVerification by transcriptionService.modelVerification.collectAsState()
    val diagnosticAssessment by transcriptionService.diagnosticAssessment.collectAsState()

    // 4. Model States
    val englishModelState by modelManager.modelState.collectAsState()
    val hindiModelState by modelManager.hindiModelState.collectAsState()
    val hindiStatusMessage by modelManager.hindiStatusMessage.collectAsState()
    val hindiDownloadProgress by modelManager.hindiDownloadProgress.collectAsState()

    // 5. WAV Recorder State
    val diagnosticRecorderState by audioDiagnosticRecorder.state.collectAsState()

    // Automatic resource cleanup: Stop diagnostic session when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            if (transcriptionService.sessionType.value == RecognitionSessionType.DIAGNOSTIC) {
                transcriptionService.stopDiagnosticSession()
            }
        }
    }

    val isProductionSosActive = sessionType == RecognitionSessionType.PRODUCTION_SOS || emergencyState.isEmergencyActive
    val isDiagnosticRunning = sessionType == RecognitionSessionType.DIAGNOSTIC

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Top Navigation & Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back to Settings",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SYSTEM & AUDIO DIAGNOSTICS",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Real-time audio routing, SpeexDSP, and bilingual Vosk ASR",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusPill(
                text = when (sessionType) {
                    RecognitionSessionType.PRODUCTION_SOS -> "SOS ACTIVE"
                    RecognitionSessionType.DIAGNOSTIC -> "DIAGNOSTIC"
                    RecognitionSessionType.INACTIVE -> "INACTIVE"
                },
                color = when (sessionType) {
                    RecognitionSessionType.PRODUCTION_SOS -> SakhiEmergency
                    RecognitionSessionType.DIAGNOSTIC -> SakhiLavenderAccent
                    RecognitionSessionType.INACTIVE -> MaterialTheme.colorScheme.outline
                }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Safety Interlock Banner
        DiagnosticSafetyBanner(
            sessionType = sessionType,
            isProductionSosActive = isProductionSosActive,
            activeSessionId = transcriptionService.activeSessionId
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Diagnostic Test Session Control Buttons
        DiagnosticSessionControlsCard(
            sessionType = sessionType,
            isProductionSosActive = isProductionSosActive,
            onStartDiagnosticTest = {
                transcriptionService.startDiagnosticSession { error ->
                    Toast.makeText(context, "ASR Error: $error", Toast.LENGTH_SHORT).show()
                }
            },
            onStopDiagnosticTest = {
                transcriptionService.stopDiagnosticSession()
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // =====================================================================
        // SECTION A: AUDIO SOURCE & UNIFIED ROUTER
        // =====================================================================
        DiagnosticsCard(
            title = "AUDIO SOURCE & ROUTER",
            icon = Icons.Default.Mic
        ) {
            // Prominent Active Microphone Status Banner (Phase D)
            val (micBannerTitle, micBannerColor, micBannerSubtext) = when {
                routerTelemetry.operationalState == RouterOperationalState.ALL_SOURCES_UNAVAILABLE -> Triple(
                    "AUDIO SOURCE ERROR",
                    SakhiEmergency,
                    routerTelemetry.failureReason ?: "All audio input sources unavailable"
                )
                routerTelemetry.isSwitching -> Triple(
                    "SWITCHING AUDIO SOURCE",
                    SakhiWarning,
                    "Transitioning between microphones..."
                )
                routerTelemetry.activeSource == ActiveAudioSource.ESP32_BLUETOOTH -> Triple(
                    "ESP32 MICROPHONE — ACTIVE",
                    SakhiSuccess,
                    "ESP32-WROOM INMP441 Bluetooth Classic SPP (8,000 Hz Mono)"
                )
                routerTelemetry.activeSource == ActiveAudioSource.PHONE_MICROPHONE -> Triple(
                    "PHONE MICROPHONE — ACTIVE",
                    SakhiLavenderAccent,
                    "Internal Phone Microphone AudioRecord (8,000 Hz Mono)"
                )
                else -> Triple(
                    "NO ACTIVE MICROPHONE",
                    MaterialTheme.colorScheme.outline,
                    "Microphone input is stopped or inactive"
                )
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = micBannerColor.copy(alpha = 0.12f),
                border = BorderStroke(1.5.dp, micBannerColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = micBannerTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = micBannerColor
                        )
                        Text(
                            text = micBannerSubtext,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusPill(
                        text = routerTelemetry.operationalState.name,
                        color = micBannerColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Latency & Auto-Reconnect Status Bar
            val lastChunkTs = routerTelemetry.lastAudioChunkTimestamp
            val latencyText = if (lastChunkTs != null && lastChunkTs > 0L) {
                val diff = (System.currentTimeMillis() - lastChunkTs).coerceAtLeast(0)
                if (diff < 1000) "${diff}ms ago" else "${diff / 1000}s ago"
            } else "No audio chunks received"

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Last Audio Chunk: $latencyText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (btTelemetry.isAutoReconnecting) {
                    Text(
                        text = "ESP32 Auto-Reconnect: Attempt ${btTelemetry.reconnectAttempt}/${BluetoothClassicAudioReceiver.MAX_RECONNECT_ATTEMPTS}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SakhiWarning
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // VU Meter for Active Audio Stream (ESP32 or Phone Mic)
            val activeRms = when (routerTelemetry.activeSource) {
                ActiveAudioSource.ESP32_BLUETOOTH -> btTelemetry.currentRms
                ActiveAudioSource.PHONE_MICROPHONE -> phoneTelemetry.currentRms
                ActiveAudioSource.NONE -> 0f
            }
            val isAudioStreaming = when (routerTelemetry.activeSource) {
                ActiveAudioSource.ESP32_BLUETOOTH -> btTelemetry.state == BtAudioConnectionState.STREAMING
                ActiveAudioSource.PHONE_MICROPHONE -> phoneTelemetry.isCapturing
                ActiveAudioSource.NONE -> false
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = if (isAudioStreaming) SakhiSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "LIVE AUDIO LEVEL (RMS)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = if (isAudioStreaming) "${(activeRms * 100).toInt()}%" else "0%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isAudioStreaming) SakhiSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            DiagnosticAudioLevelMeter(
                level = activeRms,
                isStreaming = isAudioStreaming
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Detailed Router Metrics Table
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DiagnosticMetricItem(
                    label = "ESP32 LINK",
                    value = btTelemetry.state.name,
                    subtitle = if (routerTelemetry.isEsp32Usable) "Usable" else "Unusable"
                )
                DiagnosticMetricItem(
                    label = "PHONE MIC",
                    value = routerTelemetry.phoneMicState.name,
                    subtitle = if (routerTelemetry.isPhoneMicUsable) "Available" else "Idle"
                )
                DiagnosticMetricItem(
                    label = "FAILOVERS",
                    value = "${routerTelemetry.switchCount} switches"
                )
                DiagnosticMetricItem(
                    label = "ROUTED BYTES",
                    value = if (routerTelemetry.totalRoutedBytes > 1024) {
                        String.format(Locale.US, "%.1f KB", routerTelemetry.totalRoutedBytes / 1024.0)
                    } else "${routerTelemetry.totalRoutedBytes} B"
                )
            }

            if (routerTelemetry.lastSwitchReason != null) {
                Spacer(modifier = Modifier.height(8.dp))
                val timeStr = routerTelemetry.lastSwitchTimestamp?.let {
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))
                } ?: "N/A"
                Text(
                    text = "Last Switch ($timeStr): ${routerTelemetry.lastSwitchReason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SakhiLavenderAccent,
                    fontSize = 11.sp
                )
            }

            if (routerTelemetry.failureReason != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Capture Failure: ${routerTelemetry.failureReason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SakhiEmergency,
                    fontSize = 11.sp
                )
            }

            // Optional ESP32 Manual Audio Link Button
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (btTelemetry.state == BtAudioConnectionState.DISCONNECTED || btTelemetry.state == BtAudioConnectionState.ERROR) {
                    SakhiOutlinedButton(
                        text = "Connect ESP32 Audio (SPP)",
                        onClick = {
                            com.example.sakhi.service.EmergencyForegroundService.startMonitoring(context)
                            btAudioReceiver.connect()
                        },
                        height = 36.dp
                    )
                } else if (btTelemetry.state == BtAudioConnectionState.CONNECTED || btTelemetry.state == BtAudioConnectionState.STREAMING) {
                    SakhiOutlinedButton(
                        text = "Disconnect ESP32 Audio",
                        onClick = { btAudioReceiver.disconnect() },
                        borderColor = SakhiWarning,
                        contentColor = SakhiWarning,
                        height = 36.dp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // =====================================================================
        // SECTION B: NOISE SUPPRESSION (SPEEXDSP)
        // =====================================================================
        DiagnosticsCard(
            title = "SPEEXDSP NOISE SUPPRESSION",
            icon = Icons.Default.Tune
        ) {
            val speexState = speexTelemetry.state

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Single-Channel Spectral Noise Suppression (8 kHz)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "SpeexDSP C Preprocessor (-15 dB denoise attenuation, AGC disabled). Does not perform dual-microphone Acoustic Echo Cancellation (AEC).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Truthful Noise Suppression Status Banner (Phase E)
            val (speexStatusLabel, speexStatusColor, speexStatusSubtext) = when {
                !isNoiseSuppressionEnabled -> Triple(
                    "NOISE SUPPRESSION: DISABLED",
                    MaterialTheme.colorScheme.outline,
                    "Suppression disabled: Original PCM passes through unprocessed"
                )
                speexState == SpeexOperationalState.ACTIVE_PROCESSING -> Triple(
                    "NOISE SUPPRESSION: ACTIVE",
                    SakhiSuccess,
                    "Native SpeexDSP loaded and actively processing audio frames (-15 dB)"
                )
                speexState == SpeexOperationalState.INITIALIZING -> Triple(
                    "NOISE SUPPRESSION: INITIALIZING",
                    SakhiWarning,
                    "Native library initialized; awaiting first audio frame"
                )
                speexState == SpeexOperationalState.FALLBACK_UNAVAILABLE -> Triple(
                    "NOISE SUPPRESSION: ERROR",
                    SakhiEmergency,
                    speexTelemetry.errorMessage ?: "Native initialization or processing failed; operating in bypass"
                )
                speexState == SpeexOperationalState.OFF -> Triple(
                    "NOISE SUPPRESSION: DISABLED",
                    MaterialTheme.colorScheme.outline,
                    "Original PCM passes through directly"
                )
                else -> Triple(
                    "NOISE SUPPRESSION: BYPASSED / PASSTHROUGH",
                    SakhiWarning,
                    "Operating in passthrough bypass mode"
                )
            }

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = speexStatusColor.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, speexStatusColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = speexStatusLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = speexStatusColor
                        )
                        Text(
                            text = speexStatusSubtext,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusPill(
                        text = if (isNoiseSuppressionEnabled && speexState == SpeexOperationalState.ACTIVE_PROCESSING) "ACTIVE" else "PASSTHROUGH",
                        color = speexStatusColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Enable Noise Suppression Toggle",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = isNoiseSuppressionEnabled,
                    onCheckedChange = { transcriptionService.setNoiseSuppressionEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SakhiSuccess,
                        checkedTrackColor = SakhiSuccess.copy(alpha = 0.3f),
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // SpeexDSP Frame Telemetry
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DiagnosticMetricItem(label = "SUBMITTED", value = "${speexTelemetry.framesSubmitted} f")
                DiagnosticMetricItem(label = "PROCESSED", value = "${speexTelemetry.framesProcessed} f")
                DiagnosticMetricItem(label = "FAILURES", value = "${speexTelemetry.processingFailures}")
                DiagnosticMetricItem(label = "SENT TO VOSK", value = "${speexTelemetry.framesForwardedToVosk} f")
            }

            if (speexTelemetry.errorMessage != null && speexState != SpeexOperationalState.FALLBACK_UNAVAILABLE) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "SpeexDSP Notice: ${speexTelemetry.errorMessage}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SakhiWarning,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // =====================================================================
        // SECTION C: OFFLINE BILINGUAL SPEECH RECOGNITION (VOSK)
        // =====================================================================
        DiagnosticsCard(
            title = "BILINGUAL SPEECH RECOGNITION (VOSK)",
            icon = Icons.Default.RecordVoiceOver
        ) {
            Text(
                text = "Overall Status: $asrStatus",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            // English Recognizer Channel Card
            DiagnosticAsrChannelCard(
                title = "ENGLISH RECOGNIZER CHANNEL (@ 8,000 HZ)",
                subtitle = safetyStatus,
                accentColor = SakhiPrimary,
                partialText = safetyPartial,
                finalText = safetyFinal
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Hindi Recognizer Channel Card
            DiagnosticAsrChannelCard(
                title = "HINDI RECOGNIZER CHANNEL (@ 16,000 HZ RESAMPLED)",
                subtitle = unrestrictedStatus,
                accentColor = SakhiLavenderAccent,
                partialText = unrestrictedPartial,
                finalText = unrestrictedFinal
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Arbitration Results Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .border(1.dp, SakhiPrimary.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AUTHORITATIVE ARBITRATION WINNER",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = SakhiPrimary
                        )
                        if (arbitrationTelemetry.lastAuthoritativeLanguage != null) {
                            StatusPill(
                                text = arbitrationTelemetry.lastAuthoritativeLanguage!!.displayName,
                                color = SakhiPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = if (arbitrationTelemetry.lastAuthoritativeTranscript.isNotBlank())
                            "\"${arbitrationTelemetry.lastAuthoritativeTranscript}\""
                        else "(no authoritative transcript yet)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (arbitrationTelemetry.lastDecisionReason.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Decision: ${arbitrationTelemetry.lastDecisionReason}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        DiagnosticMetricItem(label = "TOTAL ARB", value = "${arbitrationTelemetry.totalArbitrations}")
                        DiagnosticMetricItem(label = "EN WINS", value = "${arbitrationTelemetry.englishWins}")
                        DiagnosticMetricItem(label = "HI WINS", value = "${arbitrationTelemetry.hindiWins}")
                        DiagnosticMetricItem(label = "SUPPRESSED", value = "${arbitrationTelemetry.suppressedDuplicates + arbitrationTelemetry.suppressedConflicts}")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // =====================================================================
        // SECTION D: DIAGNOSTIC DISTRESS ASSESSMENT
        // =====================================================================
        DiagnosticsCard(
            title = "DIAGNOSTIC DISTRESS CLASSIFICATION",
            icon = Icons.Default.Psychology
        ) {
            Text(
                text = "Evaluates recognized speech segments using real emergency rules (EmergencyRiskAnalyzer) without triggering production dispatch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (diagnosticAssessment != null) {
                DiagnosticAssessmentBox(assessment = diagnosticAssessment!!)
            } else {
                Text(
                    text = "(No transcript evaluated yet. Speak into the microphone or start a diagnostic test.)",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Diagnostic Utility Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SakhiOutlinedButton(
                    text = "Finalize Utterance",
                    onClick = { transcriptionService.finalizeUtterance() },
                    height = 38.dp,
                    modifier = Modifier.weight(1f)
                )
                SakhiOutlinedButton(
                    text = "Clear Transcripts",
                    onClick = { transcriptionService.clearTranscripts() },
                    height = 38.dp,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // =====================================================================
        // SECTION E: WAV DIAGNOSTIC RECORDER
        // =====================================================================
        DiagnosticsCard(
            title = "WAV DIAGNOSTIC RECORDER",
            icon = Icons.Default.GraphicEq
        ) {
            Text(
                text = "Capture raw PCM audio into valid 16-bit mono WAV files for inspection on PC or analysis.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            when (val diag = diagnosticRecorderState) {
                is AudioDiagnosticState.Recording -> {
                    val remainingSec = ((diag.targetMs - diag.elapsedMs) / 1000).coerceAtLeast(0)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SakhiEmergency.copy(alpha = 0.10f))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🔴 Recording in progress... (${remainingSec}s remaining)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = SakhiEmergency
                            )
                            SakhiOutlinedButton(
                                text = "Cancel",
                                onClick = { audioDiagnosticRecorder.reset() },
                                height = 32.dp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (diag.capturedSamples.toFloat() / diag.targetSamples.coerceAtLeast(1)).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = SakhiEmergency,
                            trackColor = SakhiEmergency.copy(alpha = 0.2f),
                        )
                    }
                }

                is AudioDiagnosticState.Completed -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SakhiSuccess.copy(alpha = 0.10f))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = SakhiSuccess,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "WAV Recorded: ${diag.wavFile.name} (${diag.metrics.fileSize / 1024} KB)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = SakhiSuccess
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Path: ${diag.metrics.filePath}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SakhiButton(
                                text = "Share / Export WAV",
                                onClick = { audioDiagnosticRecorder.shareWavFile(context, diag.wavFile) },
                                height = 38.dp,
                                modifier = Modifier.weight(1f)
                            )
                            SakhiOutlinedButton(
                                text = "Record Again",
                                onClick = { audioDiagnosticRecorder.reset() },
                                height = 38.dp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                is AudioDiagnosticState.Error -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SakhiEmergency.copy(alpha = 0.12f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = SakhiEmergency,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = diag.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = SakhiEmergency,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { audioDiagnosticRecorder.reset() }) {
                            Text("Dismiss")
                        }
                    }
                }

                AudioDiagnosticState.Idle -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SakhiButton(
                            text = "Record 5s WAV",
                            onClick = { audioDiagnosticRecorder.recordLive(5) },
                            height = 42.dp,
                            modifier = Modifier.weight(1.2f)
                        )
                        SakhiOutlinedButton(
                            text = "Capture Last 5s",
                            onClick = { audioDiagnosticRecorder.snapshotFromBuffer(5) },
                            height = 42.dp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // =====================================================================
        // SECTION F: MODEL AND ACOUSTIC PIPELINE HEALTH
        // =====================================================================
        DiagnosticsCard(
            title = "MODEL & ACOUSTIC HEALTH",
            icon = Icons.Default.Security
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "English Model (vosk-model-small-en-us-0.15)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Acoustic Sample Rate: 8,000 Hz",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusPill(
                    text = englishModelState.name,
                    color = when (englishModelState) {
                        VoskModelState.READY -> SakhiSuccess
                        VoskModelState.ERROR -> SakhiEmergency
                        else -> SakhiWarning
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Hindi Model (vosk-model-small-hi-0.22)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Acoustic Sample Rate: 16,000 Hz (Resampled)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusPill(
                    text = hindiModelState.name,
                    color = when (hindiModelState) {
                        VoskModelState.READY -> SakhiSuccess
                        VoskModelState.ERROR -> SakhiEmergency
                        else -> SakhiWarning
                    }
                )
            }

            if (hindiModelState == VoskModelState.DOWNLOADING) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (hindiDownloadProgress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = hindiStatusMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = SakhiLavenderAccent
                )
            }

            if (modelVerification != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Model Verification: ${modelVerification!!.verificationDetails}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SakhiLavenderAccent,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DiagnosticSafetyBanner(
    sessionType: RecognitionSessionType,
    isProductionSosActive: Boolean,
    activeSessionId: String?
) {
    if (isProductionSosActive) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SakhiEmergency.copy(alpha = 0.12f))
                .border(1.dp, SakhiEmergency.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = SakhiEmergency,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "🚨 PRODUCTION EMERGENCY ACTIVE",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Black,
                        color = SakhiEmergency
                    )
                    Text(
                        text = "Session: ${activeSessionId ?: "ACTIVE"}. Diagnostic controls are locked to protect ongoing SOS dispatch. Telemetry is read-only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 11.sp
                    )
                }
            }
        }
    } else if (sessionType == RecognitionSessionType.DIAGNOSTIC) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SakhiSuccess.copy(alpha = 0.12f))
                .border(1.dp, SakhiSuccess.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = SakhiSuccess,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "🛡️ DIAGNOSTIC ISOLATION: LOCKED ON",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Black,
                        color = SakhiSuccess
                    )
                    Text(
                        text = "Session: $activeSessionId. Speech recognition is active in isolated test mode. Zero emergency calls, SMS messages, or sirens will trigger.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 11.sp
                    )
                }
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = SakhiLavenderAccent,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "DIAGNOSTIC ISOLATION READY",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Tap 'Start Diagnostic Test' to begin audio ingestion and offline bilingual Vosk transcription in safe isolation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticSessionControlsCard(
    sessionType: RecognitionSessionType,
    isProductionSosActive: Boolean,
    onStartDiagnosticTest: () -> Unit,
    onStopDiagnosticTest: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(MaterialTheme.colorScheme.outline)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "DIAGNOSTIC TEST CONTROLS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.1.sp
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (isProductionSosActive) {
                Text(
                    text = "Diagnostic test controls are locked while an emergency session is active.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SakhiEmergency,
                    fontWeight = FontWeight.SemiBold
                )
            } else if (sessionType == RecognitionSessionType.DIAGNOSTIC) {
                SakhiOutlinedButton(
                    text = "Stop Diagnostic Test",
                    onClick = onStopDiagnosticTest,
                    borderColor = SakhiWarning,
                    contentColor = SakhiWarning,
                    height = 46.dp
                )
            } else {
                SakhiButton(
                    text = "Start Diagnostic Test",
                    onClick = onStartDiagnosticTest,
                    height = 46.dp
                )
            }
        }
    }
}

@Composable
fun DiagnosticsCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(MaterialTheme.colorScheme.outline)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = SakhiPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.1.sp
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun DiagnosticMetricItem(label: String, value: String, subtitle: String? = null) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = SakhiLavenderAccent
            )
        }
    }
}

@Composable
fun DiagnosticAudioLevelMeter(
    level: Float,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedLevel by animateFloatAsState(
        targetValue = if (isStreaming) level.coerceIn(0f, 1f) else 0f,
        label = "diag_audio_level_anim"
    )
    val barColor = when {
        animatedLevel > 0.75f -> SakhiEmergency
        animatedLevel > 0.40f -> SakhiWarning
        else -> SakhiSuccess
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = animatedLevel)
                .fillMaxSize()
                .clip(RoundedCornerShape(5.dp))
                .background(barColor)
        )
    }
}

@Composable
fun DiagnosticAsrChannelCard(
    title: String,
    subtitle: String,
    accentColor: Color,
    partialText: String,
    finalText: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                fontSize = 10.sp
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Partial Result
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "STREAMING HYPOTHESIS:",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 8.sp
            )
            Text(
                text = if (partialText.isNotBlank()) "\"$partialText\"" else "(listening...)",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = if (partialText.isBlank()) FontStyle.Italic else FontStyle.Normal,
                fontWeight = if (partialText.isNotBlank()) FontWeight.SemiBold else FontWeight.Normal,
                color = if (partialText.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Final Result
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "DECODED UTTERANCE:",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                fontSize = 8.sp
            )
            Text(
                text = if (finalText.isNotBlank()) "\"$finalText\"" else "(no final utterance yet)",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (finalText.isNotBlank()) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (finalText.isBlank()) FontStyle.Italic else FontStyle.Normal,
                color = if (finalText.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun DiagnosticAssessmentBox(assessment: DistressAssessment) {
    val isDistress = assessment.category == DistressCategory.POSSIBLE_DISTRESS
    val isConcern = assessment.category == DistressCategory.ELEVATED_CONCERN
    val statusColor = when {
        isDistress -> SakhiEmergency
        isConcern -> SakhiWarning
        assessment.category == DistressCategory.UNCERTAIN -> SakhiLavenderAccent
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(statusColor.copy(alpha = 0.08f))
            .border(1.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "REAL-TIME EVALUATION",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    fontSize = 10.sp
                )
                StatusPill(
                    text = assessment.category.name,
                    color = statusColor
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Transcript: \"${assessment.transcript}\"",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (assessment.contextSummary != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = assessment.contextSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Trigger Match: ${if (assessment.triggerPhraseMatch) "YES (Distress)" else "NO"}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (assessment.triggerPhraseMatch) SakhiEmergency else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (assessment.confidence != null) {
                    Text(
                        text = "Confidence: ${(assessment.confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

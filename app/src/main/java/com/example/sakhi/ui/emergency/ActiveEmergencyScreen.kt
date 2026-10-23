package com.example.sakhi.ui.emergency

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CrisisAlert
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sakhi.R
import com.example.sakhi.core.database.ContactRepository
import com.example.sakhi.core.designsystem.CircularCountdownIndicator
import com.example.sakhi.core.designsystem.SakhiButton
import com.example.sakhi.core.designsystem.SakhiOutlinedButton
import com.example.sakhi.core.designsystem.StatusPill
import com.example.sakhi.core.designsystem.TimelineItemRow
import com.example.sakhi.core.model.AiState
import com.example.sakhi.core.model.Contact
import com.example.sakhi.core.model.ContactAlertState
import com.example.sakhi.core.model.DistressCategory
import com.example.sakhi.core.model.EmergencyState
import com.example.sakhi.core.model.KeychainLinkState
import com.example.sakhi.core.model.PoliceState
import com.example.sakhi.domain.emergency.EmergencyStateManager
import com.example.sakhi.theme.SakhiEmergency
import com.example.sakhi.theme.SakhiEmergencyBackground
import com.example.sakhi.theme.SakhiEmergencySurface
import com.example.sakhi.theme.SakhiLavenderAccent
import com.example.sakhi.theme.SakhiSuccess
import com.example.sakhi.theme.SakhiWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActiveEmergencyScreen(
    emergencyStateManager: EmergencyStateManager,
    contactRepository: ContactRepository,
    onNavigateBack: () -> Unit
) {
    val state by emergencyStateManager.state.collectAsState()
    val contacts by contactRepository.contactsFlow.collectAsState(initial = emptyList())
    val autoCallEnabled by emergencyStateManager.preferences.autoCallEscalationEnabledFlow.collectAsState(initial = false)
    val escalationNumberPref by emergencyStateManager.preferences.escalationPhoneNumberFlow.collectAsState(initial = "")
    val escalationPolicyPref by emergencyStateManager.preferences.escalationPolicyFlow.collectAsState(initial = "CALL_ON_RISK_ESCALATION")
    val includeSpeechInSms by emergencyStateManager.preferences.includeSpeechInSmsFlow.collectAsState(initial = false)
    val periodicIntervalSec by emergencyStateManager.preferences.periodicSmsIntervalFlow.collectAsState(initial = 60)
    val context = LocalContext.current

    var showEndSessionConfirm by remember { mutableStateOf(false) }
    var isAiCardExpanded by remember { mutableStateOf(true) }

    val stealthAlpha = if (state.isStealthMode) 0.15f else 1f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SakhiEmergencyBackground)
            .padding(20.dp)
            .alpha(stealthAlpha)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // A. EMERGENCY STATUS HEADER
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(SakhiEmergency)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SOS ACTIVE",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Stealth mode toggle
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.1f))
                            .clickable { emergencyStateManager.toggleStealthMode() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (state.isStealthMode) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Stealth Mode",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (state.isStealthMode) "Stealth ON" else "Stealth",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            // Keychain Disconnection Warning Banner (if lost during emergency)
            if (state.keychainLinkState != KeychainLinkState.CONNECTED) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SakhiWarning),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.Security, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Keychain Disconnected: Phone emergency workflows continue automatically.",
                            color = Color.Black,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // COUNTDOWN VIEW (if countdown active)
            if (state.isCountdownActive) {
                Spacer(modifier = Modifier.height(16.dp))
                CircularCountdownIndicator(
                    remainingSeconds = state.countdownRemainingSeconds,
                    totalSeconds = state.countdownTotalSeconds
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Alert sending to ${contacts.size} contact(s) in ${state.countdownRemainingSeconds}s",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))
                SakhiButton(
                    text = stringResource(R.string.emergency_cancel_button),
                    onClick = {
                        emergencyStateManager.handleTapHold()
                        onNavigateBack()
                    },
                    containerColor = Color.White,
                    contentColor = SakhiEmergency,
                    height = 64.dp
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.emergency_cancel_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
            } else {
                // ACTIVE EMERGENCY INTELLIGENCE DASHBOARD
                val minutes = state.sessionDurationSeconds / 60
                val seconds = state.sessionDurationSeconds % 60
                val durationFormatted = String.format("%02d:%02d", minutes, seconds)

                // A. Emergency Status Card
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = SakhiEmergencySurface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Elapsed Time",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.65f)
                            )
                            Text(
                                text = durationFormatted,
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Black
                            )
                        }
                        StatusPill(text = "SOS ACTIVE", color = SakhiEmergency)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // B. Live Microphone Indicator Card
                LiveMicrophoneCard(state = state)

                Spacer(modifier = Modifier.height(14.dp))

                // C. Mandatory SpeexDSP Noise Suppression Card
                NoiseSuppressionCard(state = state)

                Spacer(modifier = Modifier.height(14.dp))

                // D. Location Status Card
                LocationStatusCard(
                    state = state,
                    periodicIntervalSec = periodicIntervalSec,
                    onOpenMap = {
                        state.googleMapsUrl?.let { url ->
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // E. Emergency Contacts & SMS Card (with spinners & per-contact message counters)
                ContactsDispatchCard(
                    contacts = contacts,
                    contactAlertState = state.contactAlertState,
                    contactStatuses = state.contactAlertStatuses,
                    contactMessageCounts = state.contactMessageCounts,
                    contactLastSendTimestamps = state.contactLastSendTimestamps
                )

                Spacer(modifier = Modifier.height(14.dp))

                // F. Bilingual Speech Intelligence Card (Hindi & English Parallel Vosk Recognizers)
                BilingualSpeechRecognitionCard(
                    state = state,
                    includeSpeechInSms = includeSpeechInSms,
                    isExpanded = isAiCardExpanded,
                    onToggleExpand = { isAiCardExpanded = !isAiCardExpanded }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // E. Escalation Status Card
                EscalationStatusCard(
                    policeState = state.policeState,
                    autoCallEnabled = autoCallEnabled,
                    configuredNumber = state.escalationDestinationNumber ?: escalationNumberPref,
                    policyName = escalationPolicyPref,
                    failureReason = state.escalationFailureReason,
                    onManualPoliceCall = { emergencyStateManager.triggerManualPoliceCall() }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Live Incident Timeline Card
                TimelineCard(timeline = state.timeline)

                Spacer(modifier = Modifier.height(24.dp))

                // Deliberate End Session Button
                SakhiOutlinedButton(
                    text = stringResource(R.string.emergency_end_session),
                    onClick = { showEndSessionConfirm = true },
                    borderColor = SakhiEmergency,
                    contentColor = SakhiEmergency,
                    height = 56.dp
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }

        // End Session Confirmation Dialog
        if (showEndSessionConfirm) {
            AlertDialog(
                onDismissRequest = { showEndSessionConfirm = false },
                title = { Text("End Emergency Session?") },
                text = { Text("Are you safe? Ending the session will stop audio capture, record the session summary, and terminate the background emergency service.") },
                confirmButton = {
                    TextButton(onClick = {
                        showEndSessionConfirm = false
                        emergencyStateManager.endSession()
                        onNavigateBack()
                    }) {
                        Text("YES, I AM SAFE", color = SakhiEmergency, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showEndSessionConfirm = false }) {
                        Text("CANCEL")
                    }
                }
            )
        }
    }
}

@Composable
fun EscalationStatusCard(
    policeState: PoliceState,
    autoCallEnabled: Boolean,
    configuredNumber: String,
    policyName: String,
    failureReason: String?,
    onManualPoliceCall: () -> Unit
) {
    val displayNumber = configuredNumber.ifBlank { "Not configured" }
    val (statusText, statusColor) = when {
        policeState == PoliceState.CONNECTED -> Pair("Connected", SakhiSuccess)
        policeState == PoliceState.CALL_INITIATED -> Pair("Dialer Opened / Call Initiated", SakhiEmergency)
        policeState == PoliceState.REQUESTED -> Pair("Triggered", SakhiWarning)
        policeState == PoliceState.FAILED -> Pair("Failed", SakhiEmergency)
        autoCallEnabled && configuredNumber.isBlank() -> Pair("Not Configured", SakhiWarning)
        else -> Pair("Not Triggered", Color.Gray)
    }

    val policyLabel = when (policyName.uppercase()) {
        "CALL_ON_SOS_START" -> "Call When SOS Starts"
        else -> "Call When Risk Escalates"
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SakhiEmergencySurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Escalation Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                StatusPill(text = statusText, color = statusColor)
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Auto Call Escalation: ${if (autoCallEnabled) "Enabled ($policyLabel)" else "Disabled"}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Escalation Destination: $displayNumber",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f)
            )
            if (!failureReason.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = failureReason,
                    style = MaterialTheme.typography.labelSmall,
                    color = SakhiEmergency
                )
            }

            if (policeState != PoliceState.CALL_INITIATED && policeState != PoliceState.CONNECTED) {
                val backupTarget = configuredNumber.ifBlank { "112" }
                Spacer(modifier = Modifier.height(14.dp))
                SakhiButton(
                    text = "CALL $backupTarget NOW (ONE-TAP BACKUP)",
                    onClick = onManualPoliceCall,
                    containerColor = SakhiEmergency,
                    contentColor = Color.White,
                    height = 50.dp
                )
            }
        }
    }
}

@Composable
fun ContactsDispatchCard(
    contacts: List<Contact>,
    contactAlertState: ContactAlertState,
    contactStatuses: Map<Long, ContactAlertState>,
    contactMessageCounts: Map<Long, Int> = emptyMap(),
    contactLastSendTimestamps: Map<Long, Long> = emptyMap()
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SakhiEmergencySurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Emergency Contacts & SMS (${contacts.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                StatusPill(
                    text = when (contactAlertState) {
                        ContactAlertState.COUNTDOWN, ContactAlertState.NOT_STARTED -> "Queued"
                        ContactAlertState.SENDING -> "Sending"
                        ContactAlertState.SENT -> "Sent"
                        ContactAlertState.DELIVERED -> "Delivery Confirmed"
                        ContactAlertState.HANDED_TO_MESSAGING_APP -> "Handed to App"
                        ContactAlertState.FAILED -> "Failed"
                        ContactAlertState.CANCELLED -> "Cancelled"
                    },
                    color = when (contactAlertState) {
                        ContactAlertState.SENT, ContactAlertState.DELIVERED -> SakhiSuccess
                        ContactAlertState.FAILED -> SakhiEmergency
                        else -> SakhiWarning
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (contacts.isEmpty()) {
                Text(
                    text = "No emergency contacts configured.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SakhiWarning
                )
            }

            contacts.forEach { contact ->
                val cState = contactStatuses[contact.id] ?: contactAlertState
                val msgCount = contactMessageCounts[contact.id] ?: 0
                val lastSendTs = contactLastSendTimestamps[contact.id]

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = contact.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                        val subline = if (lastSendTs != null) {
                            "${contact.phoneNumber} · Last sent ${timeFormatter.format(Date(lastSendTs))}"
                        } else {
                            contact.phoneNumber
                        }
                        Text(
                            text = subline,
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    if (cState == ContactAlertState.SENDING) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = SakhiWarning
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Sending SMS...",
                                color = SakhiWarning,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        val countSuffix = if (msgCount > 0) {
                            " · $msgCount ${if (msgCount == 1) "message" else "messages"}"
                        } else ""

                        val labelText = when (cState) {
                            ContactAlertState.DELIVERED -> "Delivery confirmed$countSuffix"
                            ContactAlertState.SENT -> "Sent$countSuffix"
                            ContactAlertState.FAILED -> if (msgCount > 0) "Failed (Sent $msgCount)" else "Failed"
                            ContactAlertState.HANDED_TO_MESSAGING_APP -> "Handed to App"
                            ContactAlertState.COUNTDOWN, ContactAlertState.NOT_STARTED -> "Queued"
                            ContactAlertState.CANCELLED -> "Cancelled"
                            ContactAlertState.SENDING -> "Sending SMS..."
                        }

                        Text(
                            text = labelText,
                            color = when (cState) {
                                ContactAlertState.DELIVERED, ContactAlertState.SENT -> SakhiSuccess
                                ContactAlertState.FAILED -> SakhiEmergency
                                else -> SakhiWarning
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LiveMicrophoneCard(state: EmergencyState) {
    val (statusLabel, statusColor) = when {
        state.isAudioSwitching -> Pair("SWITCHING AUDIO SOURCE", SakhiWarning)
        state.audioSourceErrorMessage != null -> Pair("AUDIO SOURCE ERROR", SakhiEmergency)
        state.activeAudioSource == "ESP32_BLUETOOTH" -> Pair("ESP32 MICROPHONE — ACTIVE", SakhiSuccess)
        state.activeAudioSource == "PHONE_MICROPHONE" -> Pair("PHONE MICROPHONE — ACTIVE", SakhiLavenderAccent)
        else -> Pair("NO ACTIVE MICROPHONE", SakhiWarning)
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SakhiEmergencySurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Active Microphone",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                StatusPill(text = statusLabel, color = statusColor)
            }

            Spacer(modifier = Modifier.height(10.dp))

            val sourceDesc = when (state.activeAudioSource) {
                "ESP32_BLUETOOTH" -> "Keychain Bluetooth Classic SPP Stream"
                "PHONE_MICROPHONE" -> "Internal Phone Microphone (AudioRecord Failover)"
                else -> "Awaiting Audio Stream"
            }
            Text(
                text = "Source Stream: $sourceDesc",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.Medium
            )

            val pcmArrivalText = if (state.isAudioDataArriving) "Receiving PCM Packets (Continuous)" else "Stream Idle / Awaiting Audio"
            Text(
                text = "Telemetry: $pcmArrivalText · ${state.audioSampleRateHz} Hz mono",
                style = MaterialTheme.typography.labelSmall,
                color = if (state.isAudioDataArriving) SakhiSuccess else Color.White.copy(alpha = 0.65f)
            )

            if (state.btReconnectAttempts > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "ESP32 Bluetooth Auto-Reconnect: Attempt #${state.btReconnectAttempts}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SakhiWarning
                )
            }

            if (!state.audioSourceErrorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Audio Error: ${state.audioSourceErrorMessage}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SakhiEmergency
                )
            }
        }
    }
}

@Composable
fun NoiseSuppressionCard(state: EmergencyState) {
    val (statusLabel, statusColor) = when (state.speexState) {
        "ACTIVE_PROCESSING" -> Pair("ON / PROCESSING AUDIO", SakhiSuccess)
        "INITIALIZING" -> Pair("ON / INITIALIZING", SakhiWarning)
        "FALLBACK_UNAVAILABLE" -> Pair("ERROR / Speech enhancement is unavailable", SakhiEmergency)
        else -> Pair(if (state.isEmergencyActive) "MANDATORY / CONNECTING" else "OFF", SakhiWarning)
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SakhiEmergencySurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Noise Suppression",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                StatusPill(text = statusLabel, color = statusColor)
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "SpeexDSP Spectral Denoiser (-15 dB) is mandatory for production SOS. Applied to both ESP32 and Phone Mic streams. Cannot be disabled.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f)
            )
            if (state.speexFramesProcessed > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Enhanced Audio Frames Processed: ${state.speexFramesProcessed}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.65f)
                )
            }
            if (!state.speexErrorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "DSP Notice: ${state.speexErrorMessage}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SakhiEmergency
                )
            }
        }
    }
}

@Composable
fun LocationStatusCard(
    state: EmergencyState,
    periodicIntervalSec: Int = 60,
    onOpenMap: () -> Unit
) {
    val context = LocalContext.current
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    val (locStatusText, locStatusColor) = when {
        state.latitude == null -> Pair("Acquiring Fix", SakhiWarning)
        state.isLocationStale -> Pair("Location Stale", SakhiWarning)
        state.isLocationHighAccuracy -> Pair("High Accuracy Fix", SakhiSuccess)
        else -> Pair(state.locationAccuracyStatus.takeIf { it.isNotBlank() } ?: "Live GPS", SakhiSuccess)
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SakhiEmergencySurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = locStatusColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Location Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                StatusPill(text = locStatusText, color = locStatusColor)
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (state.latitude != null && state.longitude != null) {
                Text(
                    text = "Coordinates: ${"%.4f".format(state.latitude)}, ${"%.4f".format(state.longitude)}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                val lastUpdateStr = state.locationTimestamp?.let { timeFormatter.format(Date(it)) } ?: "Just now"
                val intervalLabel = if (periodicIntervalSec >= 60 && periodicIntervalSec % 60 == 0) {
                    "${periodicIntervalSec / 60}m"
                } else {
                    "${periodicIntervalSec}s"
                }
                val providerStr = state.locationProviderType?.uppercase() ?: "GPS"
                Text(
                    text = "${state.locationDisplayStatus} · Provider: $providerStr · Updated: $lastUpdateStr · SMS Interval: $intervalLabel",
                    color = if (state.isLocationStale) SakhiWarning else Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall
                )
                state.locationAddress?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = it, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.clickable { onOpenMap() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Open in Google Maps", color = SakhiSuccess, style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(imageVector = Icons.Default.OpenInNew, contentDescription = null, tint = SakhiSuccess, modifier = Modifier.size(16.dp))
                }

                if (!state.liveTrackingUrl.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(state.liveTrackingUrl)).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Live Tracking Active (Open Webpage)", color = SakhiLavenderAccent, style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(imageVector = Icons.Default.OpenInNew, contentDescription = null, tint = SakhiLavenderAccent, modifier = Modifier.size(16.dp))
                    }
                }
            } else {
                Text(
                    text = "Acquiring GPS fix... (Best effort location fallback active)",
                    color = SakhiWarning,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun BilingualSpeechRecognitionCard(
    state: EmergencyState,
    includeSpeechInSms: Boolean = false,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val riskCategory = state.latestAssessment?.category
    val (riskLabel, riskColor) = when (riskCategory) {
        DistressCategory.POSSIBLE_DISTRESS -> Pair("Possible Distress Indicator", SakhiEmergency)
        DistressCategory.ELEVATED_CONCERN -> Pair("Elevated Concern", SakhiWarning)
        DistressCategory.UNCERTAIN -> Pair("Uncertain / Ambiguous Context", SakhiWarning)
        DistressCategory.NO_CLEAR_INDICATOR, DistressCategory.NORMAL -> Pair("No Clear Distress Indicator", SakhiSuccess)
        null -> Pair("Monitoring Speech (Bilingual Hi/En)", SakhiLavenderAccent)
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SakhiEmergencySurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Bilingual Speech Intelligence",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Hindi (16 kHz) & English (8 kHz) Concurrent Vosk Engines",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = Color.White
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 14.dp)) {
                    // Risk Assessment Header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Distress Assessment:",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        StatusPill(text = riskLabel, color = riskColor)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // SECTION A: HINDI DECODING STREAM
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "हिन्दी (Hindi Recognition — 16 kHz)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = SakhiLavenderAccent
                                )
                                Text(
                                    text = state.hindiStatusText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.65f)
                                )
                            }

                            if (state.hindiPartialText.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Live Partial: \"${state.hindiPartialText}\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SakhiWarning,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                )
                            }

                            val latestHindi = state.hindiLatestFinalText.ifBlank {
                                state.hindiTranscriptHistory.lastOrNull()?.text ?: "Awaiting Hindi speech..."
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Latest Final: \"$latestHindi\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )

                            if (state.hindiTranscriptHistory.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Session History (${state.hindiTranscriptHistory.size} segments):",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontWeight = FontWeight.SemiBold
                                )
                                state.hindiTranscriptHistory.takeLast(4).forEach { seg ->
                                    val timeStr = timeFormatter.format(Date(seg.timestamp))
                                    val confStr = if (seg.confidence != null) " [${(seg.confidence * 100).toInt()}%]" else ""
                                    Text(
                                        text = "$timeStr  \"${seg.text}\"$confStr",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // SECTION B: ENGLISH DECODING STREAM
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "English (Indian English en-in — 8 kHz)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = SakhiLavenderAccent
                                )
                                Text(
                                    text = state.englishStatusText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.65f)
                                )
                            }

                            if (state.englishPartialText.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Live Partial: \"${state.englishPartialText}\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SakhiWarning,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                )
                            }

                            val latestEn = state.englishLatestFinalText.ifBlank {
                                state.englishTranscriptHistory.lastOrNull()?.text ?: "Awaiting English speech..."
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Latest Final: \"$latestEn\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )

                            if (state.englishTranscriptHistory.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Session History (${state.englishTranscriptHistory.size} segments):",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontWeight = FontWeight.SemiBold
                                )
                                state.englishTranscriptHistory.takeLast(4).forEach { seg ->
                                    val timeStr = timeFormatter.format(Date(seg.timestamp))
                                    val confStr = if (seg.confidence != null) " [${(seg.confidence * 100).toInt()}%]" else ""
                                    Text(
                                        text = "$timeStr  \"${seg.text}\"$confStr",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                            }
                        }
                    }

                    // Recent detected relevant phrases
                    if (state.recentRelevantPhrases.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Detected Distress Phrases (${state.recentRelevantPhrases.size}):",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = SakhiWarning
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        state.recentRelevantPhrases.takeLast(4).forEach { phrase ->
                            Text(
                                text = "• \"$phrase\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    val smsPrivacyNote = if (includeSpeechInSms) "Speech in SMS: Enabled" else "Speech in SMS: Off (Private)"
                    Text(
                        text = "Vosk Offline Dual ASR · SpeexDSP Enhanced · $smsPrivacyNote",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
fun TimelineCard(timeline: List<com.example.sakhi.core.model.TimelineEvent>) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SakhiEmergencySurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.emergency_timeline),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(10.dp))

            timeline.take(6).forEach { event ->
                TimelineItemRow(event = event)
            }
        }
    }
}

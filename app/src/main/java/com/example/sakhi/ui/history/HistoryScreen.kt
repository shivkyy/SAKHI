package com.example.sakhi.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sakhi.R
import com.example.sakhi.core.database.ContactRepository
import com.example.sakhi.core.database.SessionHistoryRepository
import com.example.sakhi.core.designsystem.SakhiButton
import com.example.sakhi.core.designsystem.SakhiOutlinedButton
import com.example.sakhi.core.designsystem.StatusPill
import com.example.sakhi.core.designsystem.TimelineItemRow
import com.example.sakhi.core.model.ContactAlertState
import com.example.sakhi.core.model.EmergencySession
import com.example.sakhi.core.model.PoliceState
import com.example.sakhi.data.alerts.AlertDispatcher
import com.example.sakhi.theme.SakhiEmergency
import com.example.sakhi.theme.SakhiLavenderAccent
import com.example.sakhi.theme.SakhiPrimary
import com.example.sakhi.theme.SakhiSuccess
import com.example.sakhi.theme.SakhiWarning
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    sessionHistoryRepository: SessionHistoryRepository,
    contactRepository: ContactRepository,
    alertDispatcher: AlertDispatcher
) {
    val sessions by sessionHistoryRepository.sessionsFlow.collectAsState(initial = emptyList())
    val contacts by contactRepository.contactsFlow.collectAsState(initial = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedSession by remember { mutableStateOf<EmergencySession?>(null) }
    var safeMessageFeedback by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.history_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${sessions.size} recorded sessions on device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(SakhiPrimary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = SakhiPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        if (sessions.isEmpty()) {
            EmptyHistoryCard()
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionItemCard(
                        session = session,
                        onClick = { selectedSession = session },
                        onOpenMaps = { lat, lng ->
                            val uri = Uri.parse("https://maps.google.com/?q=$lat,$lng")
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            context.startActivity(intent)
                        }
                    )
                }
            }
        }
    }

    // Incident Details Modal
    selectedSession?.let { session ->
        val dateFormat = SimpleDateFormat("dd MMMM yyyy, HH:mm:ss", Locale.getDefault())
        val formattedStart = dateFormat.format(Date(session.startTime))

        AlertDialog(
            onDismissRequest = { selectedSession = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Incident Details", fontWeight = FontWeight.Bold)
                    StatusPill(
                        text = session.outcome,
                        color = when (session.outcome) {
                            "ENDED_SAFELY" -> SakhiSuccess
                            "POLICE_REQUESTED" -> SakhiEmergency
                            "CANCELLED_BEFORE_ALERT" -> SakhiWarning
                            else -> SakhiLavenderAccent
                        }
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = formattedStart,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    if (session.latitude != null && session.longitude != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                val uri = Uri.parse("https://maps.google.com/?q=${session.latitude},${session.longitude}")
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            }
                        ) {
                            Icon(imageVector = Icons.Default.LocationOn, contentDescription = null, tint = SakhiLavenderAccent, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = session.locationAddress ?: "${"%.4f".format(session.latitude)}, ${"%.4f".format(session.longitude)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = SakhiLavenderAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    if (session.hindiTranscripts.isNotEmpty() || session.englishTranscripts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "RECOGNIZED SPEECH TRANSCRIPTS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (session.hindiTranscripts.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "हिन्दी / Hindi (${session.hindiTranscripts.size}):",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = SakhiLavenderAccent
                            )
                            session.hindiTranscripts.take(5).forEach { seg ->
                                Text(
                                    text = "• \"${seg.text}\"",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        if (session.englishTranscripts.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "English (${session.englishTranscripts.size}):",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = SakhiLavenderAccent
                            )
                            session.englishTranscripts.take(5).forEach { seg ->
                                Text(
                                    text = "• \"${seg.text}\"",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    Text(
                        text = "INCIDENT TIMELINE (${session.timeline.size} events)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    LazyColumn(modifier = Modifier.height(180.dp)) {
                        items(session.timeline) { event ->
                            TimelineItemRow(event = event)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val summary = buildString {
                            appendLine("SAKHI Incident Report")
                            appendLine("Time: $formattedStart")
                            appendLine("Outcome: ${session.outcome}")
                            if (session.latitude != null && session.longitude != null) {
                                appendLine("Location: https://maps.google.com/?q=${session.latitude},${session.longitude}")
                            }
                            appendLine("Contacts Alert: ${session.contactAlertState}")
                            appendLine("Police State: ${session.policeState}")
                            if (session.hindiTranscripts.isNotEmpty()) {
                                appendLine("Hindi Transcripts: ${session.hindiTranscripts.joinToString(" | ") { it.text }}")
                            }
                            if (session.englishTranscripts.isNotEmpty()) {
                                appendLine("English Transcripts: ${session.englishTranscripts.joinToString(" | ") { it.text }}")
                            }
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("SAKHI Incident", summary))
                        Toast.makeText(context, "Incident summary copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Copy Report", fontWeight = FontWeight.Bold, color = SakhiPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedSession = null }) {
                    Text("Close")
                }
            }
        )
    }

    safeMessageFeedback?.let { msg ->
        AlertDialog(
            onDismissRequest = { safeMessageFeedback = null },
            title = { Text("Safe Follow-up", fontWeight = FontWeight.Bold) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { safeMessageFeedback = null }) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
fun SessionItemCard(
    session: EmergencySession,
    onClick: () -> Unit,
    onOpenMaps: (Double, Double) -> Unit
) {
    val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    val formattedTime = dateFormat.format(Date(session.startTime))

    val outcomeColor = when (session.outcome) {
        "ENDED_SAFELY" -> SakhiSuccess
        "POLICE_REQUESTED" -> SakhiEmergency
        "CANCELLED_BEFORE_ALERT" -> SakhiWarning
        else -> SakhiLavenderAccent
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(MaterialTheme.colorScheme.outline)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                StatusPill(
                    text = session.outcome,
                    color = outcomeColor
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Location with clickable map link
            val sessionLat = session.latitude
            val sessionLng = session.longitude
            if (sessionLat != null && sessionLng != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onOpenMaps(sessionLat, sessionLng) }
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = SakhiLavenderAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = session.locationAddress ?: "${"%.4f".format(session.latitude)}, ${"%.4f".format(session.longitude)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SakhiLavenderAccent,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "Open in Maps",
                        tint = SakhiLavenderAccent,
                        modifier = Modifier.size(14.dp)
                    )
                }
            } else {
                Text(
                    text = "Location: Unavailable during session",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val transcriptCount = session.hindiTranscripts.size + session.englishTranscripts.size
                val transcriptNote = if (transcriptCount > 0) " · $transcriptCount speech transcript(s)" else ""
                Text(
                    text = "SMS: ${session.contactAlertState.name}$transcriptNote",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (session.contactAlertState == ContactAlertState.DELIVERED || session.contactAlertState == ContactAlertState.SENT) SakhiSuccess else SakhiWarning
                )
                Text(
                    text = "Tap to view timeline (${session.timeline.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = SakhiPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun EmptyHistoryCard() {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(MaterialTheme.colorScheme.outline)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(SakhiSuccess.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = SakhiSuccess,
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No Incidents Recorded",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "All emergency sessions, test alarms, and cancellations will be logged securely here on your device with complete timestamps and GPS history.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}

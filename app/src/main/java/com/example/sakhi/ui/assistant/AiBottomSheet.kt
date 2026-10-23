package com.example.sakhi.ui.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sakhi.core.designsystem.StatusPill
import com.example.sakhi.core.model.DistressCategory
import com.example.sakhi.data.assistant.AssistantState
import com.example.sakhi.data.assistant.AssistantVisualState
import com.example.sakhi.theme.SakhiEmergency
import com.example.sakhi.theme.SakhiEmergencyContainer
import com.example.sakhi.theme.SakhiLavenderAccent
import com.example.sakhi.theme.SakhiPrimary
import com.example.sakhi.theme.SakhiSuccess
import com.example.sakhi.theme.SakhiWarning

/**
 * SAKHI Intelligence Modal Bottom Sheet.
 * Provides controls for live microphone transcription, transcript management,
 * and explicit safety assessment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiBottomSheet(
    state: AssistantState,
    sheetState: SheetState,
    onDismissRequest: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onClearTranscript: () -> Unit,
    onRunAiAnalysis: () -> Unit,
    onDismissError: () -> Unit,
    onRequestMicrophonePermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header: Title, Sparkle badge, and Close Action
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
                            .background(SakhiPrimary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = SakhiPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "SAKHI Intelligence",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Real-time speech transcription & AI safety check",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close SAKHI Intelligence",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Microphone Status & Audio Source Banner
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = SolidColor(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AUDIO SOURCE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = SakhiLavenderAccent,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Phone Microphone (Foreground)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    val (statusText, statusColor) = when (state.visualState) {
                        AssistantVisualState.IDLE -> "IDLE" to MaterialTheme.colorScheme.onSurfaceVariant
                        AssistantVisualState.LISTENING -> "LISTENING" to SakhiSuccess
                        AssistantVisualState.TRANSCRIBING -> "TRANSCRIBING" to SakhiPrimary
                        AssistantVisualState.PROCESSING -> "ANALYZING" to SakhiLavenderAccent
                        AssistantVisualState.ERROR -> "ATTENTION" to SakhiEmergency
                    }

                    StatusPill(
                        text = statusText,
                        color = statusColor
                    )
                }
            }

            // Error / Permission Banner
            AnimatedVisibility(
                visible = state.errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = SakhiEmergency.copy(alpha = 0.12f)
                        ),
                        border = CardDefaults.outlinedCardBorder().copy(
                            brush = SolidColor(SakhiEmergency.copy(alpha = 0.45f))
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = SakhiEmergency,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = state.errorMessage ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                if (state.requiresPermissionPrompt) {
                                    Button(
                                        onClick = onRequestMicrophonePermission,
                                        colors = ButtonDefaults.buttonColors(containerColor = SakhiEmergency),
                                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text(text = "Grant Mic Permission", style = MaterialTheme.typography.labelMedium)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                }

                                OutlinedButton(
                                    onClick = onDismissError,
                                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text(text = "Dismiss", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Live Transcript Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LIVE TRANSCRIPT",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = SakhiPrimary,
                    letterSpacing = 1.sp
                )

                if (state.fullDisplayTranscript.isNotBlank()) {
                    TextButton(
                        onClick = onClearTranscript,
                        contentPadding = ButtonDefaults.TextButtonContentPadding
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Clear",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Transcript Box
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = SolidColor(
                        if (state.isListeningOrTranscribing) SakhiPrimary.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 160.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    if (state.fullDisplayTranscript.isBlank()) {
                        Text(
                            text = if (state.isListeningOrTranscribing) "Listening for speech... Speak clearly near your device."
                            else "Tap 'Start Listening' to capture speech via your phone microphone.",
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column {
                            if (state.transcript.isNotBlank()) {
                                Text(
                                    text = state.transcript,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (state.partialText.isNotBlank()) {
                                if (state.transcript.isNotBlank()) Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = state.partialText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontStyle = FontStyle.Italic,
                                    color = SakhiLavenderAccent
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Dual Action Controls (Start/Stop Listening & Run AI Analysis)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Control 1: Microphone Start / Stop
                val isMicActive = state.isListeningOrTranscribing
                Button(
                    onClick = {
                        if (isMicActive) onStopListening() else onStartListening()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isMicActive) SakhiEmergency else SakhiPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                ) {
                    Icon(
                        imageVector = if (isMicActive) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isMicActive) "Stop Listening" else "Start Listening",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Control 2: Explicit AI Safety Analysis
                OutlinedButton(
                    onClick = onRunAiAnalysis,
                    enabled = state.fullDisplayTranscript.isNotBlank() && state.visualState != AssistantVisualState.PROCESSING,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                ) {
                    if (state.visualState == AssistantVisualState.PROCESSING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = SakhiPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Evaluating...", style = MaterialTheme.typography.labelLarge)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = null,
                            tint = if (state.fullDisplayTranscript.isNotBlank()) SakhiPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "AI Analysis",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (state.fullDisplayTranscript.isNotBlank()) SakhiPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // AI Safety Analysis Card (Rendered if analysis was run)
            if (state.latestAnalysis != null) {
                Spacer(modifier = Modifier.height(16.dp))
                val analysis = state.latestAnalysis
                val isDistress = analysis.category == DistressCategory.POSSIBLE_DISTRESS
                val isUncertain = analysis.category == DistressCategory.UNCERTAIN || analysis.category == DistressCategory.ELEVATED_CONCERN

                val cardColor = when {
                    isDistress -> SakhiEmergency.copy(alpha = 0.12f)
                    isUncertain -> SakhiWarning.copy(alpha = 0.12f)
                    else -> SakhiSuccess.copy(alpha = 0.12f)
                }
                val borderStroke = when {
                    isDistress -> SakhiEmergency.copy(alpha = 0.6f)
                    isUncertain -> SakhiWarning.copy(alpha = 0.6f)
                    else -> SakhiSuccess.copy(alpha = 0.6f)
                }

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(borderStroke)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = null,
                                    tint = if (isDistress) SakhiEmergency else if (isUncertain) SakhiWarning else SakhiSuccess,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "SAFETY ASSESSMENT RESULT",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }

                            val confPercent = analysis.confidence?.let { "${(it * 100).toInt()}% conf" } ?: "N/A"
                            StatusPill(
                                text = "${analysis.category.name} ($confPercent)",
                                color = if (isDistress) SakhiEmergency else if (isUncertain) SakhiWarning else SakhiSuccess
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = analysis.contextSummary ?: "No assessment summary available.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Evaluated via ${analysis.providerStatus}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // User Control & Privacy Notice Footer
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "🔒 Privacy & Safety Invariant: SAKHI Intelligence operates on your explicit command or during active emergency sessions. Speech excerpts are only included in periodic emergency SMS if explicitly enabled in Settings, and AI evaluations never delay or replace physical SOS dispatch.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

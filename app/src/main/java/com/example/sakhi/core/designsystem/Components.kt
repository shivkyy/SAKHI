package com.example.sakhi.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CrisisAlert
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sakhi.core.model.KeychainLinkState
import com.example.sakhi.core.model.TimelineEvent
import com.example.sakhi.core.model.TimelineEventType
import com.example.sakhi.theme.SakhiEmergency
import com.example.sakhi.theme.SakhiPrimary
import com.example.sakhi.theme.SakhiSuccess
import com.example.sakhi.theme.SakhiWarning
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SakhiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = SakhiPrimary,
    contentColor: Color = Color.White,
    height: Dp = 56.dp,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.4f),
            disabledContentColor = contentColor.copy(alpha = 0.4f)
        ),
        enabled = enabled
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}

@Composable
fun SakhiOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    borderColor: Color = SakhiPrimary,
    contentColor: Color = SakhiPrimary,
    height: Dp = 52.dp,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = contentColor,
            disabledContentColor = contentColor.copy(alpha = 0.4f)
        ),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = SolidColor(if (enabled) borderColor else borderColor.copy(alpha = 0.3f))
        ),
        enabled = enabled
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
    }
}

/**
 * Truthful status badge explicitly distinguishing between:
 * - Real Physical BLE hardware (Connected / Disconnected / Connecting)
 * - Demo Mode Simulated Keychain
 */
@Composable
fun TruthfulStatusBadge(
    linkState: KeychainLinkState,
    isSimulated: Boolean,
    modifier: Modifier = Modifier
) {
    val (label, badgeColor) = when {
        isSimulated -> Pair("Demo Mode (Simulated)", SakhiPrimary)
        linkState == KeychainLinkState.CONNECTED -> Pair("Keychain Connected", SakhiSuccess)
        linkState == KeychainLinkState.CONNECTING -> Pair("Connecting...", SakhiWarning)
        linkState == KeychainLinkState.SCANNING -> Pair("Scanning BLE...", SakhiWarning)
        else -> Pair("Keychain Disconnected", SakhiWarning)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(badgeColor.copy(alpha = 0.16f))
            .border(1.dp, badgeColor.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(badgeColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = badgeColor,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
    }
}

/**
 * Immediate, deterministic double-press emergency SOS button.
 *
 * Rules:
 * 1. First distinct tap arms a 1000 ms confirmation window with animated visual countdown.
 * 2. A second distinct tap within 1000 ms immediately triggers SOS.
 * 3. A single tap does NOT activate SOS.
 * 4. A continuous long hold does NOT count as two presses.
 * 5. Window expires after 1000 ms and returns to default state.
 * 6. Multiple presses (> 2) cannot duplicate-trigger SOS.
 * 7. Ignores presses if emergency is already activating or active.
 */
@Composable
fun DoublePressToActivateSosButton(
    isEmergencyActive: Boolean,
    onActivated: () -> Unit,
    modifier: Modifier = Modifier,
    confirmationWindowMs: Long = 1000L
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val detector = remember { com.example.sakhi.core.gesture.DoublePressGestureDetector(confirmationWindowMs = confirmationWindowMs) }
    var isArmed by remember { mutableStateOf(false) }
    val progress = remember { Animatable(1f) }
    var timeoutJob by remember { mutableStateOf<Job?>(null) }

    // Reset detector if emergency state changes
    LaunchedEffect(isEmergencyActive) {
        if (isEmergencyActive) {
            timeoutJob?.cancel()
            timeoutJob = null
            detector.reset()
            isArmed = false
            progress.snapTo(0f)
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            timeoutJob?.cancel()
            detector.reset()
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (isEmergencyActive) SakhiEmergency.copy(alpha = 0.35f) else SakhiEmergency)
            .semantics {
                role = Role.Button
                contentDescription = when {
                    isEmergencyActive -> "Emergency active. Tap to view live emergency workflow."
                    isArmed -> "Press again within 1 second to confirm emergency SOS alert."
                    else -> "Double press for emergency SOS alert. Dispatches location and SMS to trusted contacts."
                }
            }
            .pointerInput(isEmergencyActive) {
                if (!isEmergencyActive) {
                    detectTapGestures(
                        onPress = {
                            val pressStart = System.currentTimeMillis()
                            val released = tryAwaitRelease()
                            val pressDuration = System.currentTimeMillis() - pressStart

                            if (released) {
                                val activated = detector.recordPress(isEmergencyActive, pressDuration)
                                if (activated) {
                                    timeoutJob?.cancel()
                                    timeoutJob = null
                                    isArmed = false
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onActivated()
                                } else if (detector.isArmed) {
                                    isArmed = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    timeoutJob?.cancel()
                                    timeoutJob = scope.launch {
                                        progress.snapTo(1f)
                                        progress.animateTo(
                                            targetValue = 0f,
                                            animationSpec = tween(
                                                durationMillis = confirmationWindowMs.toInt(),
                                                easing = LinearEasing
                                            )
                                        )
                                        detector.reset()
                                        isArmed = false
                                    }
                                } else {
                                    timeoutJob?.cancel()
                                    timeoutJob = null
                                    isArmed = false
                                    scope.launch { progress.snapTo(0f) }
                                }
                            }
                        }
                    )
                }
            }
    ) {
        // Confirmation progress overlay during active 1000ms window
        if (isArmed && !isEmergencyActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = progress.value * 0.32f))
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CrisisAlert,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = when {
                        isEmergencyActive -> "EMERGENCY ACTIVE"
                        isArmed -> "PRESS AGAIN WITHIN 1s"
                        else -> "DOUBLE PRESS FOR SOS"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Text(
                    text = when {
                        isEmergencyActive -> "Tap to open active emergency workflow"
                        isArmed -> "Confirm SOS alert: 2nd press required"
                        else -> "Dispatches location & SMS to trusted contacts"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }
    }
}

/**
 * Deliberate 2-second hold-to-activate emergency SOS button with animated progress ring.
 * Deprecated in favor of [DoublePressToActivateSosButton].
 */
@Deprecated("Use DoublePressToActivateSosButton instead", ReplaceWith("DoublePressToActivateSosButton(isEmergencyActive, onActivated, modifier)"))
@Composable
fun HoldToActivateSosButton(
    isEmergencyActive: Boolean,
    onActivated: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val progress = remember { Animatable(0f) }
    var isHolding by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (isEmergencyActive) SakhiEmergency.copy(alpha = 0.35f) else SakhiEmergency)
            .pointerInput(isEmergencyActive) {
                if (!isEmergencyActive) {
                    detectTapGestures(
                        onPress = {
                            isHolding = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val job = scope.launch {
                                progress.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(durationMillis = 2000, easing = LinearEasing)
                                )
                                if (isHolding) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onActivated()
                                }
                            }
                            tryAwaitRelease()
                            isHolding = false
                            job.cancel()
                            scope.launch {
                                progress.snapTo(0f)
                            }
                        }
                    )
                }
            }
    ) {
        // Background fill based on hold progress
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = progress.value * 0.28f))
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CrisisAlert,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = when {
                        isEmergencyActive -> "EMERGENCY ACTIVE"
                        isHolding && progress.value > 0f -> "HOLDING... ${(progress.value * 100).toInt()}%"
                        else -> "HOLD 2s FOR SOS ALERT"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Text(
                    text = if (isEmergencyActive) "Tap to open active emergency workflow" else "Dispatches location & SMS to trusted contacts",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }
    }
}

/**
 * Reusable Quick Action card button for fast standalone emergency tools.
 */
@Composable
fun QuickActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(MaterialTheme.colorScheme.outline)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accentColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun CircularCountdownIndicator(
    remainingSeconds: Int,
    totalSeconds: Int,
    modifier: Modifier = Modifier
) {
    val progress = remember { Animatable(1f) }

    LaunchedEffect(remainingSeconds, totalSeconds) {
        val target = if (totalSeconds > 0) remainingSeconds.toFloat() / totalSeconds.toFloat() else 0f
        progress.animateTo(
            targetValue = target,
            animationSpec = tween(durationMillis = 900, easing = LinearEasing)
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(240.dp)
    ) {
        Canvas(modifier = Modifier.size(220.dp)) {
            val strokeWidth = 14.dp.toPx()
            // Track
            drawCircle(
                color = SakhiEmergency.copy(alpha = 0.2f),
                style = Stroke(width = strokeWidth)
            )
            // Animated progress arc
            drawArc(
                color = SakhiEmergency,
                startAngle = -90f,
                sweepAngle = 360f * progress.value,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$remainingSeconds",
                style = MaterialTheme.typography.displayLarge,
                color = Color.White,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "SECONDS",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                letterSpacing = 2.sp
            )
        }
    }
}

@Composable
fun TimelineItemRow(event: TimelineEvent) {
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val formattedTime = timeFormat.format(Date(event.timestamp))

    val dotColor = when (event.type) {
        TimelineEventType.ACTIVATION -> SakhiEmergency
        TimelineEventType.COUNTDOWN -> SakhiWarning
        TimelineEventType.CONTACT_ALERT -> SakhiSuccess
        TimelineEventType.POLICE -> SakhiEmergency
        TimelineEventType.CANCELLATION -> SakhiWarning
        TimelineEventType.BLE -> SakhiPrimary
        TimelineEventType.AI -> Color(0xFFA78BFA)
        else -> Color.Gray
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (event.detail.isNotBlank()) {
                Text(
                    text = event.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formattedTime,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

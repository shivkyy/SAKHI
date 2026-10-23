package com.example.sakhi.ui.assistant

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.sakhi.data.assistant.AssistantState
import com.example.sakhi.data.assistant.AssistantVisualState
import com.example.sakhi.theme.SakhiEmergency
import com.example.sakhi.theme.SakhiLavenderAccent
import com.example.sakhi.theme.SakhiPrimary
import com.example.sakhi.theme.SakhiPrimaryLight
import com.example.sakhi.theme.SakhiPrimaryVariant
import com.example.sakhi.theme.SakhiSuccess
import com.example.sakhi.theme.SakhiWarning

/**
 * Modern, minimal floating AI orb that persists in the shared layout across all main screens.
 * Designed to be accessible, lightweight, and performant (no continuous looping animations when idle).
 */
@Composable
fun FloatingAiOrb(
    state: AssistantState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visualState = state.visualState
    val isListening = visualState == AssistantVisualState.LISTENING
    val isTranscribing = visualState == AssistantVisualState.TRANSCRIBING
    val isProcessing = visualState == AssistantVisualState.PROCESSING
    val isError = visualState == AssistantVisualState.ERROR

    // Animated breathing ring ONLY when actively listening or transcribing
    // Crucial requirement: animations must NOT run when the assistant is IDLE.
    val infiniteTransition = rememberInfiniteTransition(label = "OrbPulseTransition")

    val pulseScale by if (isListening || isTranscribing) {
        infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.14f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "OrbScale"
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(1.0f) }
    }

    val rotationAngle by if (isProcessing) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "OrbRotation"
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    }

    val stateDescription = when (visualState) {
        AssistantVisualState.IDLE -> "SAKHI Intelligence Assistant. Idle. Tap to open voice assistant."
        AssistantVisualState.LISTENING -> "SAKHI Intelligence Assistant. Actively listening to microphone. Tap to open."
        AssistantVisualState.TRANSCRIBING -> "SAKHI Intelligence Assistant. Speech recognized and transcribing. Tap to open."
        AssistantVisualState.PROCESSING -> "SAKHI Intelligence Assistant. AI evaluation running. Tap to open."
        AssistantVisualState.ERROR -> "SAKHI Intelligence Assistant. Attention required: ${state.errorMessage ?: "Error"}. Tap to open."
    }

    Box(
        modifier = modifier
            .size(64.dp)
            .semantics { contentDescription = stateDescription }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 32.dp),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Outer pulsing aura (visible ONLY during active listening/transcribing)
        if (isListening || isTranscribing) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                if (isTranscribing) SakhiLavenderAccent.copy(alpha = 0.45f)
                                else SakhiSuccess.copy(alpha = 0.40f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        // Processing rotating ring
        if (isProcessing) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .rotate(rotationAngle)
                    .border(
                        width = 2.dp,
                        brush = Brush.sweepGradient(
                            listOf(
                                SakhiPrimaryLight,
                                SakhiLavenderAccent,
                                Color.Transparent,
                                SakhiPrimaryLight
                            )
                        ),
                        shape = CircleShape
                    )
            )
        }

        // Core Orb Body
        val orbBrush = when {
            isError -> Brush.linearGradient(
                listOf(SakhiEmergency, Color(0xFF991B1B))
            )
            isListening -> Brush.linearGradient(
                listOf(SakhiSuccess, Color(0xFF047857))
            )
            isTranscribing -> Brush.linearGradient(
                listOf(SakhiPrimary, SakhiLavenderAccent)
            )
            isProcessing -> Brush.linearGradient(
                listOf(SakhiPrimaryVariant, SakhiPrimaryContainerColor())
            )
            else -> Brush.linearGradient(
                listOf(SakhiPrimary, SakhiPrimaryVariant)
            )
        }

        val borderColor: Color = when {
            isError -> SakhiEmergency.copy(alpha = 0.8f)
            isListening -> SakhiSuccess.copy(alpha = 0.8f)
            isTranscribing -> SakhiLavenderAccent.copy(alpha = 0.8f)
            isProcessing -> SakhiPrimaryLight.copy(alpha = 0.6f)
            else -> SakhiPrimaryLight.copy(alpha = 0.45f)
        }

        Box(
            modifier = Modifier
                .size(52.dp)
                .shadow(elevation = 6.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(orbBrush)
                .border(width = 1.5.dp, color = borderColor, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            when (visualState) {
                AssistantVisualState.IDLE -> {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                AssistantVisualState.LISTENING -> {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(25.dp)
                    )
                }
                AssistantVisualState.TRANSCRIBING -> {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(25.dp)
                    )
                }
                AssistantVisualState.PROCESSING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.5.dp
                    )
                }
                AssistantVisualState.ERROR -> {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Live micro status badge on top-end corner
        val badgeColor = when {
            isError -> SakhiEmergency
            isListening -> SakhiSuccess
            isTranscribing -> SakhiLavenderAccent
            isProcessing -> SakhiPrimaryLight
            else -> null
        }

        if (badgeColor != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-4).dp, y = 4.dp)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(badgeColor)
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }
    }
}

@Composable
private fun SakhiPrimaryContainerColor(): Color {
    return MaterialTheme.colorScheme.primaryContainer
}

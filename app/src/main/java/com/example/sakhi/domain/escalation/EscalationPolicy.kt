package com.example.sakhi.domain.escalation

import com.example.sakhi.core.model.DistressAssessment
import com.example.sakhi.core.model.DistressCategory
import com.example.sakhi.core.model.EmergencyState
import com.example.sakhi.core.model.PoliceState
import com.example.sakhi.core.model.SessionState

data class EscalationDecision(
    val shouldRequestPolice: Boolean,
    val reason: String,
    val updatedConsecutiveCount: Int
)

class EscalationPolicy {

    fun evaluateManualTripleTap(state: EmergencyState): EscalationDecision {
        // Condition A & D: Triple-tap before SOS activation or after SOS ends is ignored
        if (state.sessionState != SessionState.ACTIVE && state.sessionState != SessionState.ACTIVATING) {
            return EscalationDecision(
                shouldRequestPolice = false,
                reason = "Ignored: No active SOS session (current state: ${state.sessionState})",
                updatedConsecutiveCount = state.consecutiveDistressCount
            )
        }

        // Condition B: Triple-tap during SOS countdown is ignored
        val isCountdownRunning = state.sessionState == SessionState.ACTIVATING ||
                state.isCountdownActive ||
                state.contactAlertState == com.example.sakhi.core.model.ContactAlertState.COUNTDOWN ||
                state.countdownRemainingSeconds > 0

        if (isCountdownRunning) {
            return EscalationDecision(
                shouldRequestPolice = false,
                reason = "Ignored: SOS countdown in progress (${state.countdownRemainingSeconds}s remaining)",
                updatedConsecutiveCount = state.consecutiveDistressCount
            )
        }

        // Condition C: Countdown has completely finished and session is ACTIVE.
        // Triple-tap initiates Call Now and allows subsequent manual calls (bypasses deduplication).
        return EscalationDecision(
            shouldRequestPolice = true,
            reason = "Manual triple-tap hardware gesture received while SOS session active",
            updatedConsecutiveCount = state.consecutiveDistressCount
        )
    }

    fun evaluateAiAssessment(
        state: EmergencyState,
        assessment: DistressAssessment,
        aiEscalationEnabled: Boolean,
        sensitivity: String
    ): EscalationDecision {
        if (!aiEscalationEnabled) {
            return EscalationDecision(
                shouldRequestPolice = false,
                reason = "AI-assisted police escalation is disabled in settings",
                updatedConsecutiveCount = 0
            )
        }

        if (state.sessionState != SessionState.ACTIVE && state.sessionState != SessionState.ACTIVATING) {
            return EscalationDecision(
                shouldRequestPolice = false,
                reason = "Session is not active",
                updatedConsecutiveCount = 0
            )
        }

        val isCallActive = state.activeCallInProgress ||
                state.policeState == PoliceState.CALL_INITIATED ||
                state.policeState == PoliceState.CONNECTED ||
                state.policeState == PoliceState.REQUESTED

        if (isCallActive) {
            return EscalationDecision(
                shouldRequestPolice = false,
                reason = "Police already requested or call in progress",
                updatedConsecutiveCount = state.consecutiveDistressCount
            )
        }

        val (confidenceThreshold, requiredConsecutive) = when (sensitivity.uppercase()) {
            "SENSITIVE" -> Pair(0.70f, 1)
            "CONSERVATIVE" -> Pair(0.90f, 3)
            else -> Pair(0.80f, 2) // BALANCED
        }

        val isDistress = assessment.category == DistressCategory.POSSIBLE_DISTRESS &&
                ((assessment.confidence ?: 0.5f) >= confidenceThreshold || assessment.triggerPhraseMatch)

        val newCount = if (isDistress) state.consecutiveDistressCount + 1 else 0

        return if (newCount >= requiredConsecutive) {
            EscalationDecision(
                shouldRequestPolice = true,
                reason = "AI verified $newCount consecutive distress assessments satisfying $sensitivity policy (threshold: $confidenceThreshold)",
                updatedConsecutiveCount = newCount
            )
        } else {
            val pendingReason = if (isDistress) {
                "Distress detected ($newCount/$requiredConsecutive qualifying assessments). Waiting for confirmation."
            } else {
                "No distress detected in latest audio evaluation (${assessment.category}). Count reset."
            }
            EscalationDecision(
                shouldRequestPolice = false,
                reason = pendingReason,
                updatedConsecutiveCount = newCount
            )
        }
    }

    fun evaluateSosStartEscalation(
        state: EmergencyState,
        autoCallEnabled: Boolean,
        policy: String,
        destinationNumber: String
    ): EscalationDecision {
        if (!autoCallEnabled) {
            return EscalationDecision(false, "Automatic call escalation is disabled", state.consecutiveDistressCount)
        }
        if (policy.uppercase() != "CALL_ON_SOS_START") {
            return EscalationDecision(false, "Policy is not CALL_ON_SOS_START", state.consecutiveDistressCount)
        }
        if (!state.isEmergencyActive) {
            return EscalationDecision(false, "Emergency session is not active", state.consecutiveDistressCount)
        }
        if (state.callAttemptedInSession || state.policeState != PoliceState.NOT_REQUESTED) {
            return EscalationDecision(false, "Automatic call already attempted in this session", state.consecutiveDistressCount)
        }
        if (!isValidEscalationNumber(destinationNumber)) {
            return EscalationDecision(false, "Escalation phone number is not configured or invalid", state.consecutiveDistressCount)
        }
        return EscalationDecision(
            shouldRequestPolice = true,
            reason = "Escalation policy 'Call When SOS Starts' triggered for $destinationNumber",
            updatedConsecutiveCount = state.consecutiveDistressCount
        )
    }

    fun evaluateRiskEscalation(
        state: EmergencyState,
        assessment: DistressAssessment,
        autoCallEnabled: Boolean,
        policy: String,
        destinationNumber: String,
        sensitivity: String = "BALANCED",
        currentTime: Long = System.currentTimeMillis()
    ): EscalationDecision {
        if (!autoCallEnabled) {
            return EscalationDecision(false, "Automatic call escalation is disabled", 0)
        }
        if (policy.uppercase() != "CALL_ON_RISK_ESCALATION") {
            return EscalationDecision(false, "Policy is not CALL_ON_RISK_ESCALATION", state.consecutiveDistressCount)
        }
        if (!state.isEmergencyActive) {
            return EscalationDecision(false, "Emergency session is not active", 0)
        }
        val isCallActive = state.activeCallInProgress ||
                state.policeState == PoliceState.CALL_INITIATED ||
                state.policeState == PoliceState.CONNECTED ||
                state.policeState == PoliceState.REQUESTED

        if (isCallActive) {
            return EscalationDecision(
                shouldRequestPolice = false,
                reason = "Emergency call currently active or in progress (${state.policeState})",
                updatedConsecutiveCount = state.consecutiveDistressCount
            )
        }

        // Post-call cooldown: enforce 3-second buffer before re-escalation
        val now = currentTime
        val lastEnded = state.lastCallEndedTimestamp
        if (lastEnded != null && (now - lastEnded) < 3000L) {
            return EscalationDecision(
                shouldRequestPolice = false,
                reason = "Call escalation cooling down (${3000 - (now - lastEnded)}ms remaining)",
                updatedConsecutiveCount = state.consecutiveDistressCount
            )
        }

        // Duplicate utterance suppression within 10s of previous call end
        val transcript = assessment.transcript?.trim()
        if (state.lastEscalatedUtterance != null &&
            !transcript.isNullOrEmpty() &&
            state.lastEscalatedUtterance.equals(transcript, ignoreCase = true) &&
            lastEnded != null && (now - lastEnded) < 10000L
        ) {
            return EscalationDecision(
                shouldRequestPolice = false,
                reason = "Duplicate distress utterance already escalated in prior call",
                updatedConsecutiveCount = state.consecutiveDistressCount
            )
        }

        if (!isValidEscalationNumber(destinationNumber)) {
            return EscalationDecision(false, "Escalation phone number is not configured or invalid", state.consecutiveDistressCount)
        }

        // High-risk recognized speech phrase triggers immediate risk escalation
        if (assessment.category == DistressCategory.POSSIBLE_DISTRESS && assessment.triggerPhraseMatch) {
            val newCount = state.consecutiveDistressCount + 1
            return EscalationDecision(
                shouldRequestPolice = true,
                reason = "High-risk distress speech detected ('${assessment.transcript}'). Escalating call to $destinationNumber.",
                updatedConsecutiveCount = newCount
            )
        }

        // Otherwise fall back to sensitivity-based consecutive evaluation
        return evaluateAiAssessment(
            state = state,
            assessment = assessment,
            aiEscalationEnabled = true,
            sensitivity = sensitivity
        )
    }

    fun isValidEscalationNumber(number: String): Boolean {
        val digits = number.filter { it.isDigit() }
        return digits.length >= 3 && digits.length <= 15
    }
}

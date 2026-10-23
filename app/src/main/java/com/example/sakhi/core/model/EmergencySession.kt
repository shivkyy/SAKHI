package com.example.sakhi.core.model

import java.util.UUID

data class EmergencySession(
    val id: String = UUID.randomUUID().toString(),
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val outcome: String = "ACTIVE", // CANCELLED_BEFORE_ALERT, ALERT_SENT, POLICE_REQUESTED, ENDED_SAFELY, FAILED
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracy: Float? = null,
    val locationAddress: String? = null,
    val contactAlertState: ContactAlertState = ContactAlertState.NOT_STARTED,
    val policeState: PoliceState = PoliceState.NOT_REQUESTED,
    val aiState: AiState = AiState.OFF,
    val cancellationTimestamp: Long? = null,
    val timeline: List<TimelineEvent> = emptyList(),
    val liveTrackingUrl: String? = null,
    val hindiTranscripts: List<TranscriptSegment> = emptyList(),
    val englishTranscripts: List<TranscriptSegment> = emptyList()
) {
    val durationMillis: Long
        get() = (endTime ?: System.currentTimeMillis()) - startTime
}

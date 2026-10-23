package com.example.sakhi.core.model

import java.util.UUID

enum class TimelineEventType {
    ACTIVATION,
    COUNTDOWN,
    CONTACT_ALERT,
    POLICE,
    AI,
    LOCATION,
    BLE,
    CANCELLATION,
    SYSTEM
}

data class TimelineEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val title: String,
    val detail: String,
    val type: TimelineEventType
)

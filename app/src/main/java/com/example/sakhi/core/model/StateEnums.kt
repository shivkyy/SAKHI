package com.example.sakhi.core.model

enum class SessionState {
    IDLE,
    ACTIVATING,
    ACTIVE,
    CANCELLED,
    ENDED
}

enum class ContactAlertState {
    NOT_STARTED,
    COUNTDOWN,
    SENDING,
    SENT,
    DELIVERED,
    FAILED,
    CANCELLED,
    HANDED_TO_MESSAGING_APP
}

enum class PoliceState {
    NOT_REQUESTED,
    REQUESTED,
    CALL_INITIATED,
    CONNECTED,
    CALL_ENDED,
    FAILED
}

enum class KeychainLinkState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    LOST
}

enum class AiState {
    OFF,
    LISTENING,
    ASSESSING,
    OFFLINE,
    ERROR
}

enum class DistressCategory {
    NORMAL,
    NO_CLEAR_INDICATOR,
    UNCERTAIN,
    ELEVATED_CONCERN,
    POSSIBLE_DISTRESS
}

enum class CallEscalationPolicy {
    CALL_ON_SOS_START,
    CALL_ON_RISK_ESCALATION
}


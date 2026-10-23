package com.example.sakhi.core.model

import com.example.sakhi.data.assistant.ExternalSpeechLanguage
import java.util.UUID

/**
 * A recognized speech segment in an emergency SOS session.
 * Tracks timestamp, text, language, optional confidence, and finality.
 * Never invents confidence values if not provided by recognizer.
 */
data class TranscriptSegment(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val text: String,
    val language: ExternalSpeechLanguage,
    val confidence: Double? = null,
    val isFinal: Boolean = true
) {
    val languageLabel: String
        get() = if (language == ExternalSpeechLanguage.HINDI) "हिन्दी (Hindi)" else "English"
}

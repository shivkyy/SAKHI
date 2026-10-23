package com.example.sakhi.data.ai

import com.example.sakhi.core.model.DistressAssessment
import com.example.sakhi.core.model.DistressCategory

/**
 * Represents a relevant speech excerpt extracted during emergency monitoring.
 */
data class RelevantPhrase(
    val exactText: String,
    val normalizedKey: String,
    val indicator: DistressCategory,
    val timestamp: Long = System.currentTimeMillis(),
    val includedInSms: Boolean = false
)

/**
 * Structured Bilingual (Hindi, English, Hinglish code-switched) Risk Analysis Pipeline.
 *
 * Implements:
 * A. Transcript normalization (case, punctuation, whitespace, preserving Devanagari & Latin).
 * B. Relevant phrase extraction with exact wording preservation and deduplication.
 * C. Contextual multi-segment evaluation accounting for benign/conflicting context.
 * D. Qualitative indicator categorization (POSSIBLE_DISTRESS, ELEVATED_CONCERN, UNCERTAIN, NO_CLEAR_INDICATOR).
 */
class EmergencyRiskAnalyzer {

    companion object {
        private const val MAX_CONTEXT_SEGMENTS = 10
        private const val MAX_RELEVANT_PHRASES = 15

        // High-priority distress expressions (English, Hinglish, Hindi Devanagari)
        private val DISTRESS_PATTERNS = listOf(
            // English distress phrases
            "help me",
            "please help",
            "someone help",
            "somebody help",
            "save me",
            "leave me alone",
            "stop following me",
            "please stop following",
            "call the police",
            "call police",
            "call 112",
            "call emergency",
            "don't touch me",
            "do not touch me",
            "get away from me",
            "get away",
            "let me go",

            // Romanized Hindi & Code-switched Hinglish distress phrases
            "mujhe bachao",
            "bacha lo",
            "kripya bachao",
            "bachao mujhe",
            "bachao bachao",
            "meri madad karo",
            "koi meri madad karo",
            "kripya madad karo",
            "meri help karo",
            "please meri help karo",
            "koi meri help karo",
            "mera peecha mat karo",
            "peecha mat karo",
            "peechha mat karo",
            "chhod do",
            "mujhe chhod do",
            "chhodo mujhe",
            "police ko bulao",
            "police bulao",
            "haath mat lagao",
            "door raho",
            "jane do",

            // Devanagari Hindi distress phrases
            "मुझे बचाओ",
            "बचा लो",
            "कृपया बचाओ",
            "बचाओ मुझे",
            "बचाओ बचाओ",
            "मेरी मदद करो",
            "कोई मेरी मदद करो",
            "कृपया मदद करो",
            "मेरा पीछा मत करो",
            "पीछा मत करो",
            "छोड़ दो",
            "मुझे छोड़ दो",
            "छोड़ो मुझे",
            "पुलिस को बुलाओ",
            "पुलिस बुलाओ",
            "हाथ मत लगाओ",
            "दूर रहो",
            "जाने दो"
        )

        // Isolated ambiguous vocalizations (must not trigger emergency escalation when standalone)
        private val ISOLATED_AMBIGUOUS_WORDS = setOf(
            "help",
            "police",
            "madad",
            "मदद",
            "madad karo",
            "मदद करो",
            "help karo",
            "bachao",
            "बचाओ"
        )

        // Elevated concern expressions (fear, stalking, unsafe environment)
        private val ELEVATED_CONCERN_PATTERNS = listOf(
            "following me",
            "someone is following",
            "stalking",
            "peecha kar raha",
            "पीछा कर रहा",
            "mujhe dar lag raha",
            "dar lag raha hai",
            "bohot dar lag raha",
            "मुझे डर लग रहा",
            "डर लग रहा है",
            "i am scared",
            "im scared",
            "i feel unsafe",
            "not safe here",
            "safe nahi",
            "surakshit nahi",
            "सुरक्षित नहीं",
            "khatra hai",
            "खतरा है",
            "in danger",
            "threatening me",
            "watching me"
        )

        // Benign everyday phrases containing words like "help" or "madad" to avoid false-positive certainty
        private val BENIGN_CONTEXT_PATTERNS = listOf(
            // English benign contexts
            "carry this bag",
            "carry my bag",
            "help me with my homework",
            "with my homework",
            "help me with this",
            "help me find",
            "help me cook",
            "can you help me cook",
            "can you help me study",
            "just kidding",
            "watching a movie",

            // Hindi & Hinglish benign contexts
            "khana banane",
            "खाना बनाने",
            "khana banane mein",
            "खाना बनाने में",
            "khana banane me",
            "homework mein",
            "होमवर्क में",
            "homework me",
            "homework",
            "होमवर्क",
            "padhne mein",
            "पढ़ने में",
            "padhne me"
        )
    }

    private val recentSegments = ArrayDeque<String>()
    private val extractedPhrases = mutableListOf<RelevantPhrase>()
    private val seenNormalizedKeys = mutableSetOf<String>()

    /**
     * Step A: Normalize transcript while preserving Latin and Devanagari characters.
     */
    fun normalizeTranscript(raw: String): String {
        return raw.trim()
            .lowercase()
            .replace(Regex("[!?,.;:\"'()\\[\\]{}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Resets session context buffer when an emergency session starts or ends.
     */
    @Synchronized
    fun resetSession() {
        recentSegments.clear()
        extractedPhrases.clear()
        seenNormalizedKeys.clear()
    }

    /**
     * Returns the list of deduplicated relevant phrases extracted during the current session.
     */
    @Synchronized
    fun getExtractedPhrases(): List<RelevantPhrase> {
        return extractedPhrases.toList()
    }

    /**
     * Retrieves and marks the latest relevant phrase that has not yet been sent in a periodic SMS.
     * Prevents sending the same phrase repeatedly across multiple location updates.
     */
    @Synchronized
    fun consumeNextPhraseForSms(): RelevantPhrase? {
        val index = extractedPhrases.indexOfFirst { !it.includedInSms }
        if (index == -1) return null
        val phrase = extractedPhrases[index]
        extractedPhrases[index] = phrase.copy(includedInSms = true)
        return phrase
    }

    /**
     * Evaluates a new speech segment in context and extracts relevant phrases.
     */
    @Synchronized
    fun analyzeSegment(
        rawSegment: String,
        timestamp: Long = System.currentTimeMillis()
    ): DistressAssessment {
        val originalTrimmed = rawSegment.trim()
        val normalized = normalizeTranscript(originalTrimmed)

        if (normalized.isBlank() || originalTrimmed == "[Silence / Ambient]") {
            return DistressAssessment(
                category = DistressCategory.NO_CLEAR_INDICATOR,
                confidence = null,
                transcript = originalTrimmed.ifBlank { "[Silence / Ambient]" },
                contextSummary = "Local Heuristic: No speech detected in segment.",
                timestamp = timestamp,
                providerStatus = "LOCAL_HEURISTIC",
                triggerPhraseMatch = false
            )
        }

        val previousSegment = recentSegments.lastOrNull()
        // Maintain bounded context buffer
        if (recentSegments.size >= MAX_CONTEXT_SEGMENTS) {
            recentSegments.removeFirst()
        }
        recentSegments.addLast(normalized)

        val boundaryContext = if (previousSegment != null) "$previousSegment $normalized" else normalized

        // 1. Check benign override first (contextual disambiguation)
        val matchedBenign = BENIGN_CONTEXT_PATTERNS.firstOrNull { pattern ->
            normalized.contains(pattern) ||
                (previousSegment != null && !previousSegment.contains(pattern) && boundaryContext.contains(pattern))
        }
        if (matchedBenign != null) {
            return DistressAssessment(
                category = DistressCategory.NO_CLEAR_INDICATOR,
                confidence = 0.85f,
                transcript = originalTrimmed,
                contextSummary = "Local Heuristic: Benign everyday phrase detected ('$matchedBenign'). No clear distress indicator.",
                timestamp = timestamp,
                providerStatus = "LOCAL_HEURISTIC",
                triggerPhraseMatch = false
            )
        }

        // 2. Check for isolated ambiguous words (e.g. single "help", "police", "madad", "मदद", "bachao", "बचाओ")
        // These isolated words must not trigger emergency escalation on their own unless boundary context completes a distress phrase.
        val hasBoundaryDistress = previousSegment != null &&
            !previousSegment.contains(normalized) &&
            DISTRESS_PATTERNS.any { boundaryContext.contains(it) }

        if (ISOLATED_AMBIGUOUS_WORDS.contains(normalized) && !hasBoundaryDistress) {
            recordRelevantPhraseIfNew(
                exactText = originalTrimmed,
                normalizedKey = normalized,
                indicator = DistressCategory.UNCERTAIN,
                timestamp = timestamp
            )
            return DistressAssessment(
                category = DistressCategory.UNCERTAIN,
                confidence = 0.50f,
                transcript = originalTrimmed,
                contextSummary = "Local Heuristic: Brief or isolated vocalization — context uncertain ('$normalized')",
                timestamp = timestamp,
                providerStatus = "LOCAL_HEURISTIC",
                triggerPhraseMatch = false
            )
        }

        // 3. Check high-priority distress patterns in current segment or split across the boundary with previous segment
        val matchedDistress = DISTRESS_PATTERNS.firstOrNull { pattern ->
            normalized.contains(pattern) ||
                (previousSegment != null && !previousSegment.contains(pattern) && boundaryContext.contains(pattern))
        }

        if (matchedDistress != null) {
            recordRelevantPhraseIfNew(
                exactText = originalTrimmed,
                normalizedKey = normalized,
                indicator = DistressCategory.POSSIBLE_DISTRESS,
                timestamp = timestamp
            )
            return DistressAssessment(
                category = DistressCategory.POSSIBLE_DISTRESS,
                confidence = 0.94f,
                transcript = originalTrimmed,
                contextSummary = "Local Heuristic: Distress indicator recognized ('$matchedDistress')",
                timestamp = timestamp,
                providerStatus = "LOCAL_HEURISTIC",
                triggerPhraseMatch = true
            )
        }

        // 4. Check elevated concern patterns in current segment or split across the boundary with previous segment
        val matchedConcern = ELEVATED_CONCERN_PATTERNS.firstOrNull { pattern ->
            normalized.contains(pattern) ||
                (previousSegment != null && !previousSegment.contains(pattern) && boundaryContext.contains(pattern))
        }

        if (matchedConcern != null) {
            recordRelevantPhraseIfNew(
                exactText = originalTrimmed,
                normalizedKey = normalized,
                indicator = DistressCategory.ELEVATED_CONCERN,
                timestamp = timestamp
            )
            return DistressAssessment(
                category = DistressCategory.ELEVATED_CONCERN,
                confidence = 0.82f,
                transcript = originalTrimmed,
                contextSummary = "Local Heuristic: Elevated concern phrase recognized ('$matchedConcern')",
                timestamp = timestamp,
                providerStatus = "LOCAL_HEURISTIC",
                triggerPhraseMatch = true
            )
        }

        // 5. Check for very short uninformative vocalizations (< 5 chars)
        if (normalized.length < 5) {
            return DistressAssessment(
                category = DistressCategory.UNCERTAIN,
                confidence = 0.50f,
                transcript = originalTrimmed,
                contextSummary = "Local Heuristic: Brief or isolated vocalization — context uncertain",
                timestamp = timestamp,
                providerStatus = "LOCAL_HEURISTIC",
                triggerPhraseMatch = false
            )
        }

        // 6. No clear distress indicator in segment (Note: absence of phrase != user is safe)
        return DistressAssessment(
            category = DistressCategory.NO_CLEAR_INDICATOR,
            confidence = 0.75f,
            transcript = originalTrimmed,
            contextSummary = "Local Heuristic: No specific distress phrase matched in segment (Absence of phrase does not guarantee safety)",
            timestamp = timestamp,
            providerStatus = "LOCAL_HEURISTIC",
            triggerPhraseMatch = false
        )
    }

    private fun recordRelevantPhraseIfNew(
        exactText: String,
        normalizedKey: String,
        indicator: DistressCategory,
        timestamp: Long
    ) {
        if (normalizedKey.isBlank()) return
        // Deduplicate identical normalized phrases
        if (seenNormalizedKeys.contains(normalizedKey)) return

        seenNormalizedKeys.add(normalizedKey)
        if (extractedPhrases.size >= MAX_RELEVANT_PHRASES) {
            extractedPhrases.removeAt(0)
        }
        // Truncate overly long excerpts to keep SMS concise (max 80 chars)
        val conciseExcerpt = if (exactText.length > 80) exactText.take(77) + "..." else exactText
        extractedPhrases.add(
            RelevantPhrase(
                exactText = conciseExcerpt,
                normalizedKey = normalizedKey,
                indicator = indicator,
                timestamp = timestamp,
                includedInSms = false
            )
        )
    }
}

package com.example.sakhi.data.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Word-level timing and confidence metadata extracted from Vosk recognizer JSON output.
 */
data class VoskWord(
    val word: String,
    val conf: Double? = null,
    val start: Double? = null,
    val end: Double? = null
)

/**
 * Candidate transcript produced by a speech recognizer (English or Hindi).
 * Does not invent confidence values if Vosk did not provide them.
 */
data class RecognizerCandidate(
    val text: String,
    val language: ExternalSpeechLanguage,
    val rawJson: String = "",
    val words: List<VoskWord> = emptyList(),
    val averageConfidence: Double? = null,
    val isPartial: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Disambiguation outcome classifying the spoken language profile of the audio utterance.
 */
enum class DisambiguationOutcome {
    ENGLISH_ONLY,
    HINDI_ONLY,
    MIXED_BILINGUAL,
    UNCERTAIN_DROPPED
}

/**
 * Result of arbitrating between English and Hindi speech recognition candidates.
 */
data class ArbitrationDecision(
    val winner: RecognizerCandidate,
    val loser: RecognizerCandidate? = null,
    val reason: String,
    val arbitrationTimestamp: Long = System.currentTimeMillis(),
    val outcome: DisambiguationOutcome = when (winner.language) {
        ExternalSpeechLanguage.ENGLISH -> DisambiguationOutcome.ENGLISH_ONLY
        ExternalSpeechLanguage.HINDI -> DisambiguationOutcome.HINDI_ONLY
    },
    val isMixedSpeech: Boolean = false,
    val preservedEnglish: RecognizerCandidate? = null,
    val preservedHindi: RecognizerCandidate? = null
)

/**
 * Observable telemetry for bilingual transcript arbitration.
 */
data class BilingualArbitrationTelemetry(
    val lastEnglishTranscript: String = "",
    val lastEnglishConfidence: Double? = null,
    val lastHindiTranscript: String = "",
    val lastHindiConfidence: Double? = null,
    val lastAuthoritativeTranscript: String = "",
    val lastAuthoritativeLanguage: ExternalSpeechLanguage? = null,
    val lastDecisionReason: String = "",
    val totalArbitrations: Long = 0L,
    val englishWins: Long = 0L,
    val hindiWins: Long = 0L,
    val suppressedDuplicates: Long = 0L,
    val suppressedConflicts: Long = 0L
)

/**
 * Authoritative bilingual transcript arbitrator for SAKHI.
 *
 * Evaluates candidate transcripts from concurrent English (8 kHz) and Hindi (16 kHz resampled)
 * Vosk recognizers and selects exactly one authoritative transcript per utterance.
 *
 * Invariants:
 * 1. Objectivity: Never automatically selects a transcript solely because it contains a distress keyword.
 * 2. Confidence Grounding: Uses real Vosk word posterior confidence when present; never invents values.
 * 3. Completeness & Quality: Prefers multi-word coherent transcripts over fragmented tokens or [unk] mismatches.
 * 4. Deduplication & Conflict Suppression: Prevents duplicate dispatches and conflicting second decodes of the same audio.
 * 5. Deterministic Tie-Breaking: When confidence and quality metrics are indistinguishable, prefers the native 8 kHz English model.
 * 6. Cross-Language False-Transcript Suppression: Suppresses phonetic hallucination echoes from competing models.
 */
class BilingualTranscriptArbitrator(
    val repeatSuppressionWindowMs: Long = DEFAULT_REPEAT_SUPPRESSION_WINDOW_MS,
    val conflictSuppressionWindowMs: Long = DEFAULT_CONFLICT_SUPPRESSION_WINDOW_MS,
    val confidenceDeltaThreshold: Double = DEFAULT_CONFIDENCE_DELTA_THRESHOLD
) {
    companion object {
        const val DEFAULT_REPEAT_SUPPRESSION_WINDOW_MS = 3500L
        const val DEFAULT_CONFLICT_SUPPRESSION_WINDOW_MS = 1000L
        const val DEFAULT_CONFIDENCE_DELTA_THRESHOLD = 0.15

        val COMMON_ENGLISH_WORDS = setOf(
            "i", "me", "my", "myself", "we", "our", "ours", "us", "you", "your", "yours",
            "he", "him", "his", "she", "her", "hers", "it", "its", "they", "them", "their",
            "theirs", "what", "which", "who", "whom", "this", "that", "these", "those",
            "am", "is", "are", "was", "were", "be", "been", "being", "have", "has", "had",
            "having", "do", "does", "did", "doing", "a", "an", "the", "and", "but", "if",
            "or", "because", "as", "until", "while", "of", "at", "by", "for", "with",
            "about", "against", "between", "into", "through", "during", "before", "after",
            "above", "below", "to", "from", "up", "down", "in", "out", "on", "off", "over",
            "under", "again", "further", "then", "once", "here", "there", "when", "where",
            "why", "how", "all", "any", "both", "each", "few", "more", "most", "other",
            "some", "such", "no", "nor", "not", "only", "own", "same", "so", "than", "too",
            "very", "s", "t", "can", "will", "just", "don", "should", "now",
            "help", "save", "police", "emergency", "danger", "stop", "following", "leave",
            "alone", "touch", "call", "please", "somebody", "someone", "anybody", "anyone",
            "attack", "hurt", "trouble", "scared", "afraid", "away", "right", "going", "go",
            "come", "car", "cab", "auto", "driver", "street", "road", "safe", "test", "good",
            "bad", "need", "want", "let", "talk", "hear", "see", "listen", "run", "fast",
            "tell", "know", "think", "take", "give", "look", "time", "back", "wait", "weather"
        )

        val NATIVE_HINDI_WORDS = setOf(
            "मुझे", "मुझको", "मेरा", "मेरी", "मेरे", "मैं", "हम", "हमारा", "हमारी", "हमारे",
            "तुम", "तुम्हारे", "तुम्हारी", "तुम्हारा", "आप", "आपका", "आपकी", "आपके",
            "वह", "उसका", "उसकी", "उसके", "उसे", "वे", "उनका", "उनकी", "उनके", "उन्हें",
            "यह", "इसका", "इसकी", "इसके", "इसे", "ये", "इनका", "इनकी", "इनके", "इन्हें",
            "कोई", "किसी", "कुछ", "क्या", "कहाँ", "कब", "क्यों", "कैसे", "कौन",
            "मदद", "बचाओ", "बचा", "बचाना", "छोड़", "छोड़ो", "छोड़ो", "जाने", "दो", "दीजिए",
            "रुक", "रुको", "खतरा", "पीछा", "कर", "करो", "करना", "रहा", "रही", "रहे",
            "है", "हैं", "था", "थी", "थे", "हो", "हूँ", "गया", "गई", "गए",
            "जा", "जाओ", "जाना", "आ", "आओ", "आना", "यहाँ", "वहाँ", "इधर", "उधर",
            "घर", "गाड़ी", "रास्ता", "बात", "सब", "नहीं", "मत", "कृपया", "जल्दी",
            "सुनो", "देखो", "चाहिए", "लोग", "भाई", "बहन", "माता", "पिता", "दोस्त",
            "समय", "दिन", "रात", "पानी", "खाना", "काम", "नाम", "जगह", "शहर",
            "हाथ", "मार", "डरो", "डर", "सुरक्षा", "संभालो", "रोक", "रोको",
            "पुलिस", "को", "बुलाओ", "बुलाना", "फोन", "से", "ने", "का", "के", "की", "में", "पर", "तक", "लिए"
        )

        val ENGLISH_TO_DEVANAGARI_PHONETICS: Map<String, Set<String>> = mapOf(
            "i" to setOf("आई", "आय", "आइ"),
            "need" to setOf("नीड", "निड"),
            "help" to setOf("हेल्प", "हलप", "हैलप"),
            "me" to setOf("मी"),
            "someone" to setOf("समवन", "सम्मन"),
            "somebody" to setOf("समबडी", "सम्बडी"),
            "is" to setOf("इज", "इज़"),
            "following" to setOf("फॉलोइंग", "फालोइंग", "फॉलोइङ्ग"),
            "please" to setOf("प्लीज", "प्लीज़", "प्लिज"),
            "save" to setOf("सेव"),
            "call" to setOf("कॉल", "काल"),
            "emergency" to setOf("इमरजेंसी", "इमर्जेंसी", "इमरजेन्सी"),
            "danger" to setOf("डेंजर", "डेन्जर"),
            "stop" to setOf("स्टॉप", "स्टाप"),
            "leave" to setOf("लीव", "लिव"),
            "alone" to setOf("अलोन"),
            "touch" to setOf("टच"),
            "away" to setOf("अवे"),
            "there" to setOf("देयर"),
            "right" to setOf("राइट"),
            "now" to setOf("नाउ"),
            "where" to setOf("व्हेयर", "वेयर"),
            "are" to setOf("आर"),
            "we" to setOf("वी"),
            "going" to setOf("गोइंग"),
            "test" to setOf("टेस्ट"),
            "good" to setOf("गुड"),
            "bad" to setOf("बैड"),
            "weather" to setOf("वेदर"),
            "the" to setOf("द", "दी", "दा"),
            "you" to setOf("यू"),
            "don't" to setOf("डोंट", "डोन्ट"),
            "dont" to setOf("डोंट", "डोन्ट"),
            "let" to setOf("लेट")
        )

        val ALL_PHONETIC_DEVANAGARI_ECHOES: Set<String> =
            ENGLISH_TO_DEVANAGARI_PHONETICS.values.flatten().toSet()

        fun cleanWord(w: String): String {
            return w.lowercase().trim().replace(Regex("[^a-zA-Z\u0900-\u097F0-9]"), "")
        }

        fun isEnglishLexicalWord(w: String): Boolean {
            val cleaned = cleanWord(w)
            return cleaned.isNotEmpty() && COMMON_ENGLISH_WORDS.contains(cleaned)
        }

        fun isNativeHindiWord(w: String): Boolean {
            val cleaned = cleanWord(w)
            return cleaned.isNotEmpty() && NATIVE_HINDI_WORDS.contains(cleaned)
        }

        fun isPhoneticDevanagariEcho(hiWord: String, enWords: List<String>): Boolean {
            val cleanedHi = cleanWord(hiWord)
            if (cleanedHi.isEmpty()) return false
            if (ALL_PHONETIC_DEVANAGARI_ECHOES.contains(cleanedHi)) return true
            for (enW in enWords) {
                val matches = ENGLISH_TO_DEVANAGARI_PHONETICS[cleanWord(enW)]
                if (matches != null && matches.contains(cleanedHi)) {
                    return true
                }
            }
            return false
        }

        fun getAudioInterval(candidate: RecognizerCandidate): Pair<Double, Double>? {
            val starts = candidate.words.mapNotNull { it.start }
            val ends = candidate.words.mapNotNull { it.end }
            if (starts.isEmpty() || ends.isEmpty()) return null
            return Pair(starts.minOrNull() ?: 0.0, ends.maxOrNull() ?: 0.0)
        }

        fun isSameAudioInterval(c1: RecognizerCandidate, c2: RecognizerCandidate): Boolean {
            val int1 = getAudioInterval(c1)
            val int2 = getAudioInterval(c2)
            if (int1 != null && int2 != null) {
                val (s1, e1) = int1
                val (s2, e2) = int2
                val overlap = maxOf(s1, s2) <= (minOf(e1, e2) + 0.30)
                val startClose = Math.abs(s1 - s2) <= 1.20
                return overlap || startClose
            }
            return Math.abs(c1.timestamp - c2.timestamp) <= 1800L
        }

        fun isDistressPhrase(phrase: String): Boolean {
            val lower = phrase.lowercase().trim()
            return lower.contains("help") || lower.contains("save me") ||
                   lower.contains("police") || lower.contains("emergency") ||
                   lower.contains("danger") || lower.contains("bachao") ||
                   lower.contains("बचाओ") || lower.contains("madad") ||
                   lower.contains("मदद") || lower.contains("chhod do") ||
                   lower.contains("छोड़ दो") || lower.contains("leave me") ||
                   lower.contains("touch me") || lower.contains("following me")
        }

        private fun extractWordsAndConfidenceRegex(jsonStr: String): Pair<List<VoskWord>, Double?> {
            val words = mutableListOf<VoskWord>()
            var confSum = 0.0
            var confCount = 0

            val resultBlockMatch = Regex(""""result"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(jsonStr)
            if (resultBlockMatch != null) {
                val block = resultBlockMatch.groupValues[1]
                val itemRegex = Regex("""\{([^}]+)\}""")
                for (match in itemRegex.findAll(block)) {
                    val itemStr = match.groupValues[1]
                    val word = Regex(""""word"\s*:\s*"([^"]+)"""").find(itemStr)?.groupValues?.get(1)?.trim().orEmpty()
                    val conf = Regex(""""conf"\s*:\s*([0-9.]+)""").find(itemStr)?.groupValues?.get(1)?.toDoubleOrNull()
                    val start = Regex(""""start"\s*:\s*([0-9.]+)""").find(itemStr)?.groupValues?.get(1)?.toDoubleOrNull()
                    val end = Regex(""""end"\s*:\s*([0-9.]+)""").find(itemStr)?.groupValues?.get(1)?.toDoubleOrNull()

                    if (word.isNotEmpty()) {
                        words.add(VoskWord(word, conf, start, end))
                        if (conf != null && !conf.isNaN() && conf >= 0.0) {
                            confSum += conf
                            confCount++
                        }
                    }
                }
            }
            val avgConf = if (confCount > 0) (confSum / confCount) else null
            return Pair(words, avgConf)
        }

        /**
         * Safely parses Vosk JSON result into a [RecognizerCandidate].
         * Extracts word list, real confidence scores, and timestamps when present.
         * If Vosk did not produce confidence scores, averageConfidence remains null.
         */
        fun parseVoskJson(
            jsonStr: String,
            language: ExternalSpeechLanguage,
            isPartial: Boolean = false
        ): RecognizerCandidate {
            val trimmed = jsonStr.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
                return RecognizerCandidate(text = "", language = language, isPartial = isPartial)
            }

            return try {
                val root = JSONObject(trimmed)
                val text = if (isPartial) {
                    root.optString("partial", "").trim()
                } else {
                    root.optString("text", "").trim()
                }

                if (text.isEmpty()) {
                    return RecognizerCandidate(text = "", language = language, rawJson = trimmed, isPartial = isPartial)
                }

                val words = mutableListOf<VoskWord>()
                var confSum = 0.0
                var confCount = 0

                val resultArr = root.optJSONArray("result")
                if (resultArr != null) {
                    for (i in 0 until resultArr.length()) {
                        val item = resultArr.optJSONObject(i) ?: continue
                        val w = item.optString("word", "").trim()
                        val conf = if (item.has("conf")) item.optDouble("conf") else null
                        val start = if (item.has("start")) item.optDouble("start") else null
                        val end = if (item.has("end")) item.optDouble("end") else null

                        if (w.isNotEmpty()) {
                            words.add(VoskWord(w, conf, start, end))
                            if (conf != null && !conf.isNaN() && conf >= 0.0) {
                                confSum += conf
                                confCount++
                            }
                        }
                    }
                }

                // If running under mock stub environment where optJSONArray returned null
                val finalWords: List<VoskWord>
                val avgConf: Double?
                if (words.isEmpty() && trimmed.contains("\"result\"")) {
                    val fallback = extractWordsAndConfidenceRegex(trimmed)
                    finalWords = fallback.first
                    avgConf = fallback.second
                } else {
                    finalWords = words
                    avgConf = if (confCount > 0) (confSum / confCount) else null
                }

                RecognizerCandidate(
                    text = text,
                    language = language,
                    rawJson = trimmed,
                    words = finalWords,
                    averageConfidence = avgConf,
                    isPartial = isPartial
                )
            } catch (_: Throwable) {
                // Fallback regex extraction if JSON parser encounters syntax error or Android stub exception
                val textMatch = if (isPartial) {
                    Regex(""""partial"\s*:\s*"([^"]*)"""").find(trimmed)?.groupValues?.get(1).orEmpty()
                } else {
                    Regex(""""text"\s*:\s*"([^"]*)"""").find(trimmed)?.groupValues?.get(1).orEmpty()
                }
                val (fallbackWords, fallbackAvgConf) = extractWordsAndConfidenceRegex(trimmed)
                RecognizerCandidate(
                    text = textMatch.trim(),
                    language = language,
                    rawJson = trimmed,
                    words = fallbackWords,
                    averageConfidence = fallbackAvgConf,
                    isPartial = isPartial
                )
            }
        }
    }

    private var lastDispatchedText: String = ""
    private var lastDispatchedTimestamp: Long = 0L

    var lastAuthoritativeTranscript: String = ""
        private set

    var totalArbitrations: Long = 0L
        private set
    var englishWins: Long = 0L
        private set
    var hindiWins: Long = 0L
        private set
    var suppressedDuplicates: Long = 0L
        private set
    var suppressedConflicts: Long = 0L
        private set

    private val _telemetry = MutableStateFlow(BilingualArbitrationTelemetry())
    val telemetry: StateFlow<BilingualArbitrationTelemetry> = _telemetry.asStateFlow()

    /**
     * Arbitrates between English and Hindi recognizer candidates for the same audio segment.
     * Returns null if neither recognizer produced a valid non-empty transcript.
     */
    fun arbitrate(
        englishCandidate: RecognizerCandidate?,
        hindiCandidate: RecognizerCandidate?
    ): ArbitrationDecision? {
        val decision = computeArbitration(englishCandidate, hindiCandidate)
        if (decision != null) {
            _telemetry.value = _telemetry.value.copy(
                lastEnglishTranscript = englishCandidate?.text.orEmpty(),
                lastEnglishConfidence = englishCandidate?.averageConfidence,
                lastHindiTranscript = hindiCandidate?.text.orEmpty(),
                lastHindiConfidence = hindiCandidate?.averageConfidence,
                lastDecisionReason = decision.reason
            )
        }
        return decision
    }

    private fun computeArbitration(
        englishCandidate: RecognizerCandidate?,
        hindiCandidate: RecognizerCandidate?
    ): ArbitrationDecision? {
        val en = englishCandidate?.takeIf { it.text.isNotBlank() }
        val hi = hindiCandidate?.takeIf { it.text.isNotBlank() }

        if (en == null && hi == null) return null

        if (en != null && hi == null) {
            val isUnk = en.text.contains("[unk]", ignoreCase = true) || en.text.contains("<unk>", ignoreCase = true)
            return if (isUnk && en.words.isEmpty()) {
                ArbitrationDecision(
                    winner = en,
                    loser = null,
                    reason = "Single candidate contains only out-of-vocabulary [unk] token",
                    outcome = DisambiguationOutcome.UNCERTAIN_DROPPED
                )
            } else {
                ArbitrationDecision(
                    winner = en,
                    loser = null,
                    reason = "Only English recognizer produced non-empty transcript",
                    outcome = DisambiguationOutcome.ENGLISH_ONLY
                )
            }
        }

        if (hi != null && en == null) {
            val isUnk = hi.text.contains("[unk]", ignoreCase = true) || hi.text.contains("<unk>", ignoreCase = true)
            return if (isUnk && hi.words.isEmpty()) {
                ArbitrationDecision(
                    winner = hi,
                    loser = null,
                    reason = "Single candidate contains only out-of-vocabulary [unk] token",
                    outcome = DisambiguationOutcome.UNCERTAIN_DROPPED
                )
            } else {
                ArbitrationDecision(
                    winner = hi,
                    loser = null,
                    reason = "Only Hindi recognizer produced non-empty transcript",
                    outcome = DisambiguationOutcome.HINDI_ONLY
                )
            }
        }

        val nonNullEn = en!!
        val nonNullHi = hi!!
        val enText = nonNullEn.text
        val hiText = nonNullHi.text

        // 1. Audio Interval Alignment: Verify whether candidates belong to the same audio utterance
        if (!isSameAudioInterval(nonNullEn, nonNullHi)) {
            val enTime = nonNullEn.words.mapNotNull { it.start }.minOrNull() ?: (nonNullEn.timestamp.toDouble() / 1000.0)
            val hiTime = nonNullHi.words.mapNotNull { it.start }.minOrNull() ?: (nonNullHi.timestamp.toDouble() / 1000.0)
            return if (enTime >= hiTime) {
                ArbitrationDecision(
                    winner = nonNullEn,
                    loser = nonNullHi,
                    reason = "English candidate is from current audio interval; Hindi is delayed older candidate",
                    outcome = DisambiguationOutcome.ENGLISH_ONLY
                )
            } else {
                ArbitrationDecision(
                    winner = nonNullHi,
                    loser = nonNullEn,
                    reason = "Hindi candidate is from current audio interval; English is delayed older candidate",
                    outcome = DisambiguationOutcome.HINDI_ONLY
                )
            }
        }

        // 2. Out-of-vocabulary [unk] penalty: recognizer with [unk] indicates acoustic/lexical failure
        val enHasUnk = enText.contains("[unk]", ignoreCase = true) || enText.contains("<unk>", ignoreCase = true)
        val hiHasUnk = hiText.contains("[unk]", ignoreCase = true) || hiText.contains("<unk>", ignoreCase = true)

        if (enHasUnk && hiHasUnk) {
            return ArbitrationDecision(
                winner = nonNullEn,
                loser = nonNullHi,
                reason = "Both candidates contain out-of-vocabulary [unk] tokens",
                outcome = DisambiguationOutcome.UNCERTAIN_DROPPED
            )
        }
        if (enHasUnk && !hiHasUnk) {
            return ArbitrationDecision(
                winner = nonNullHi,
                loser = nonNullEn,
                reason = "English candidate contains out-of-vocabulary [unk] token",
                outcome = DisambiguationOutcome.HINDI_ONLY
            )
        }
        if (hiHasUnk && !enHasUnk) {
            return ArbitrationDecision(
                winner = nonNullEn,
                loser = nonNullHi,
                reason = "Hindi candidate contains out-of-vocabulary [unk] token",
                outcome = DisambiguationOutcome.ENGLISH_ONLY
            )
        }

        // 3. Word extraction & lexical validity analysis
        val enWords = enText.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val hiWords = hiText.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val enValidCount = enWords.count { isEnglishLexicalWord(it) }
        val hiNativeCount = hiWords.count { isNativeHindiWord(it) }
        val hiEchoCount = hiWords.count { isPhoneticDevanagariEcho(it, enWords) }

        // 4. Cross-language phonetic echo & hallucination detection
        // 4A. Spoken English causing Hindi phonetic transliteration hallucination
        val isHindiPhoneticHallucination = enValidCount > 0 && hiNativeCount == 0 &&
                (hiEchoCount > 0 || (hiWords.isNotEmpty() && hiWords.all { isPhoneticDevanagariEcho(it, enWords) }))

        if (isHindiPhoneticHallucination) {
            return ArbitrationDecision(
                winner = nonNullEn,
                loser = nonNullHi,
                reason = "English hypothesis has valid lexical words; Hindi candidate is phonetic transliteration echo",
                outcome = DisambiguationOutcome.ENGLISH_ONLY
            )
        }

        // 4B. Spoken Hindi causing false English acoustic interpretation
        val isEnglishFalseInterpretation = hiNativeCount > 0 && enValidCount == 0
        if (isEnglishFalseInterpretation) {
            return ArbitrationDecision(
                winner = nonNullHi,
                loser = nonNullEn,
                reason = "Hindi hypothesis has valid native Devanagari words; English candidate rejected as acoustic echo",
                outcome = DisambiguationOutcome.HINDI_ONLY
            )
        }

        // 5. Real confidence comparison (when both provide real confidence scores and delta >= threshold)
        val enConf = nonNullEn.averageConfidence
        val hiConf = nonNullHi.averageConfidence

        if (enConf != null && hiConf != null) {
            if (enConf - hiConf >= confidenceDeltaThreshold) {
                return ArbitrationDecision(
                    winner = nonNullEn,
                    loser = nonNullHi,
                    reason = "English confidence (${"%.2f".format(enConf)}) exceeds Hindi (${"%.2f".format(hiConf)}) by >= $confidenceDeltaThreshold",
                    outcome = DisambiguationOutcome.ENGLISH_ONLY
                )
            }
            if (hiConf - enConf >= confidenceDeltaThreshold) {
                return ArbitrationDecision(
                    winner = nonNullHi,
                    loser = nonNullEn,
                    reason = "Hindi confidence (${"%.2f".format(hiConf)}) exceeds English (${"%.2f".format(enConf)}) by >= $confidenceDeltaThreshold",
                    outcome = DisambiguationOutcome.HINDI_ONLY
                )
            }
        }

        // 6. Transcript completeness / Word count comparison (difference >= 2 words)
        if (enWords.size >= hiWords.size + 2) {
            return ArbitrationDecision(
                winner = nonNullEn,
                loser = nonNullHi,
                reason = "English candidate has significantly higher completeness (${enWords.size} vs ${hiWords.size} words)",
                outcome = DisambiguationOutcome.ENGLISH_ONLY
            )
        }
        if (hiWords.size >= enWords.size + 2) {
            return ArbitrationDecision(
                winner = nonNullHi,
                loser = nonNullEn,
                reason = "Hindi candidate has significantly higher completeness (${hiWords.size} vs ${enWords.size} words)",
                outcome = DisambiguationOutcome.HINDI_ONLY
            )
        }

        // 7. One candidate has verified high confidence while the other has none
        if (enConf != null && hiConf == null && enConf >= 0.70) {
            return ArbitrationDecision(
                winner = nonNullEn,
                loser = nonNullHi,
                reason = "English has verified high confidence (${"%.2f".format(enConf)})",
                outcome = DisambiguationOutcome.ENGLISH_ONLY
            )
        }
        if (hiConf != null && enConf == null && hiConf >= 0.70) {
            return ArbitrationDecision(
                winner = nonNullHi,
                loser = nonNullEn,
                reason = "Hindi has verified high confidence (${"%.2f".format(hiConf)})",
                outcome = DisambiguationOutcome.HINDI_ONLY
            )
        }

        // 8. Genuine code-switching / mixed bilingual speech (e.g. "please मेरी मदद करो")
        val isCodeSwitched = (hiNativeCount > 0 && hiEchoCount > 0 && enValidCount > 0) ||
                (enText.contains("meri", ignoreCase = true) || enText.contains("karo", ignoreCase = true) ||
                 enText.contains("bachao", ignoreCase = true) || enText.contains("madad", ignoreCase = true) ||
                 hiText.contains("help", ignoreCase = true) || hiText.contains("please", ignoreCase = true) ||
                 (enValidCount > 0 && hiNativeCount > 0 && (enWords.size + hiWords.size) >= 4 && isDistressPhrase(enText) == isDistressPhrase(hiText)))
        if (isCodeSwitched) {
            val winner = if (isDistressPhrase(hiText) && !isDistressPhrase(enText)) nonNullHi else nonNullEn
            val loser = if (winner == nonNullEn) nonNullHi else nonNullEn
            return ArbitrationDecision(
                winner = winner,
                loser = loser,
                reason = "Genuine code-switched bilingual speech: preserved English and Hindi segments",
                outcome = DisambiguationOutcome.MIXED_BILINGUAL,
                isMixedSpeech = true,
                preservedEnglish = nonNullEn,
                preservedHindi = nonNullHi
            )
        }

        // 9. Distress phrase preference over non-distress when quality metrics tie
        val enIsDistress = isDistressPhrase(enText)
        val hiIsDistress = isDistressPhrase(hiText)
        if (enIsDistress && !hiIsDistress) {
            return ArbitrationDecision(
                winner = nonNullEn,
                loser = nonNullHi,
                reason = "Tied metrics; English candidate matches distress vocabulary",
                outcome = DisambiguationOutcome.ENGLISH_ONLY
            )
        }
        if (hiIsDistress && !enIsDistress) {
            return ArbitrationDecision(
                winner = nonNullHi,
                loser = nonNullEn,
                reason = "Tied metrics; Hindi candidate matches distress vocabulary",
                outcome = DisambiguationOutcome.HINDI_ONLY
            )
        }

        // 10. Higher confidence if both provide it (even within the threshold)
        if (enConf != null && hiConf != null) {
            if (enConf > hiConf) {
                return ArbitrationDecision(
                    winner = nonNullEn,
                    loser = nonNullHi,
                    reason = "English has higher confidence (${"%.2f".format(enConf)} vs ${"%.2f".format(hiConf)})",
                    outcome = DisambiguationOutcome.ENGLISH_ONLY
                )
            } else if (hiConf > enConf) {
                return ArbitrationDecision(
                    winner = nonNullHi,
                    loser = nonNullEn,
                    reason = "Hindi has higher confidence (${"%.2f".format(hiConf)} vs ${"%.2f".format(enConf)})",
                    outcome = DisambiguationOutcome.HINDI_ONLY
                )
            }
        }

        // 11. Word count comparison when tied within 1 word
        if (enWords.size > hiWords.size) {
            return ArbitrationDecision(
                winner = nonNullEn,
                loser = nonNullHi,
                reason = "English candidate has higher word count (${enWords.size} vs ${hiWords.size})",
                outcome = DisambiguationOutcome.ENGLISH_ONLY
            )
        }
        if (hiWords.size > enWords.size) {
            return ArbitrationDecision(
                winner = nonNullHi,
                loser = nonNullEn,
                reason = "Hindi candidate has higher word count (${hiWords.size} vs ${enWords.size})",
                outcome = DisambiguationOutcome.HINDI_ONLY
            )
        }

        // 12. Character coverage length
        if (enText.length > hiText.length) {
            return ArbitrationDecision(
                winner = nonNullEn,
                loser = nonNullHi,
                reason = "English candidate has longer character coverage (${enText.length} vs ${hiText.length})",
                outcome = DisambiguationOutcome.ENGLISH_ONLY
            )
        }
        if (hiText.length > enText.length) {
            return ArbitrationDecision(
                winner = nonNullHi,
                loser = nonNullEn,
                reason = "Hindi candidate has longer character coverage (${hiText.length} vs ${enText.length})",
                outcome = DisambiguationOutcome.HINDI_ONLY
            )
        }

        // 13. Deterministic tie-breaker: default to native 8 kHz English model
        return ArbitrationDecision(
            winner = nonNullEn,
            loser = nonNullHi,
            reason = "Tied quality metrics; preferring native 8 kHz English model",
            outcome = DisambiguationOutcome.ENGLISH_ONLY
        )
    }

    /**
     * Checks whether an authoritative transcript should be dispatched, enforcing duplicate
     * and conflict suppression windows.
     */
    fun shouldDispatch(
        candidate: RecognizerCandidate,
        currentTime: Long = System.currentTimeMillis()
    ): Boolean {
        val trimmed = candidate.text.trim()
        if (trimmed.isEmpty()) return false

        // Duplicate suppression within repeat window
        if (trimmed.equals(lastDispatchedText, ignoreCase = true) &&
            (currentTime - lastDispatchedTimestamp) < repeatSuppressionWindowMs
        ) {
            suppressedDuplicates++
            _telemetry.value = _telemetry.value.copy(suppressedDuplicates = suppressedDuplicates)
            return false
        }

        val isCurrentDistress = isDistressPhrase(trimmed)
        val wasLastDistress = isDistressPhrase(lastDispatchedText)

        // Conflict suppression within conflict window:
        // Suppresses secondary/delayed interpretations of the exact same audio utterance,
        // EXCEPT when the new candidate is a distress utterance and previous dispatch was non-distress.
        if (lastDispatchedText.isNotEmpty() &&
            !trimmed.equals(lastDispatchedText, ignoreCase = true) &&
            (currentTime - lastDispatchedTimestamp) < conflictSuppressionWindowMs
        ) {
            if (isCurrentDistress && !wasLastDistress) {
                // Do not suppress real distress phrase if previous dispatch was non-distress noise!
            } else {
                suppressedConflicts++
                _telemetry.value = _telemetry.value.copy(suppressedConflicts = suppressedConflicts)
                return false
            }
        }

        return true
    }

    /**
     * Records an authoritative dispatch to maintain deduplication history and telemetry.
     */
    fun recordDispatch(
        candidate: RecognizerCandidate,
        currentTime: Long = System.currentTimeMillis()
    ) {
        lastAuthoritativeTranscript = candidate.text.trim()
        lastDispatchedText = lastAuthoritativeTranscript
        lastDispatchedTimestamp = currentTime
        totalArbitrations++
        if (candidate.language == ExternalSpeechLanguage.ENGLISH) {
            englishWins++
        } else {
            hindiWins++
        }
        _telemetry.value = _telemetry.value.copy(
            lastAuthoritativeTranscript = lastAuthoritativeTranscript,
            lastAuthoritativeLanguage = candidate.language,
            totalArbitrations = totalArbitrations,
            englishWins = englishWins,
            hindiWins = hindiWins
        )
    }

    /**
     * Resets arbitrator history and dispatch timers.
     */
    fun reset() {
        lastAuthoritativeTranscript = ""
        lastDispatchedText = ""
        lastDispatchedTimestamp = 0L
        _telemetry.value = _telemetry.value.copy(
            lastAuthoritativeTranscript = "",
            lastAuthoritativeLanguage = null
        )
    }
}

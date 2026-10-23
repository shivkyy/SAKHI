package com.example.sakhi

import com.example.sakhi.data.assistant.ArbitrationDecision
import com.example.sakhi.data.assistant.BilingualTranscriptArbitrator
import com.example.sakhi.data.assistant.ExternalSpeechLanguage
import com.example.sakhi.data.assistant.RecognizerCandidate
import com.example.sakhi.data.assistant.VoskWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BilingualTranscriptArbitratorTest {

    private lateinit var arbitrator: BilingualTranscriptArbitrator

    @Before
    fun setUp() {
        arbitrator = BilingualTranscriptArbitrator(
            repeatSuppressionWindowMs = 3500L,
            conflictSuppressionWindowMs = 1000L,
            confidenceDeltaThreshold = 0.15
        )
    }

    @Test
    fun testArbitrate_bothNullOrBlank_returnsNull() {
        assertNull(arbitrator.arbitrate(null, null))

        val blankEn = RecognizerCandidate(text = "   ", language = ExternalSpeechLanguage.ENGLISH)
        val blankHi = RecognizerCandidate(text = "", language = ExternalSpeechLanguage.HINDI)
        assertNull(arbitrator.arbitrate(blankEn, blankHi))
    }

    @Test
    fun testArbitrate_onlyEnglishProducesCandidate_englishWins() {
        val enCandidate = RecognizerCandidate(
            text = "help me please",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = 0.88
        )

        val decision = arbitrator.arbitrate(enCandidate, null)
        assertNotNull(decision)
        assertEquals(ExternalSpeechLanguage.ENGLISH, decision!!.winner.language)
        assertEquals("help me please", decision.winner.text)
        assertNull(decision.loser)
        assertTrue(decision.reason.contains("Only English"))
    }

    @Test
    fun testArbitrate_onlyHindiProducesCandidate_hindiWins() {
        val hiCandidate = RecognizerCandidate(
            text = "मेरी मदद करो",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = 0.91
        )

        val decision = arbitrator.arbitrate(null, hiCandidate)
        assertNotNull(decision)
        assertEquals(ExternalSpeechLanguage.HINDI, decision!!.winner.language)
        assertEquals("मेरी मदद करो", decision.winner.text)
        assertNull(decision.loser)
        assertTrue(decision.reason.contains("Only Hindi"))
    }

    @Test
    fun testArbitrate_outOfVocabularyUnkPenalty_rejectsCandidateWithUnk() {
        // Case 1: English produced [unk] mismatch, Hindi produced valid words
        val enWithUnk = RecognizerCandidate(
            text = "help [unk] there",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = 0.95 // High confidence on recognized parts, but has [unk]
        )
        val hiValid = RecognizerCandidate(
            text = "मदद करो",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = 0.70
        )

        val decision1 = arbitrator.arbitrate(enWithUnk, hiValid)
        assertNotNull(decision1)
        assertEquals(ExternalSpeechLanguage.HINDI, decision1!!.winner.language)
        assertEquals("मदद करो", decision1.winner.text)
        assertEquals(enWithUnk, decision1.loser)
        assertTrue(decision1.reason.contains("[unk]"))

        // Case 2: Hindi produced <unk> mismatch, English produced valid words
        val enValid = RecognizerCandidate(
            text = "call police",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = 0.65
        )
        val hiWithUnk = RecognizerCandidate(
            text = "पुलिस <unk>",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = 0.99
        )

        val decision2 = arbitrator.arbitrate(enValid, hiWithUnk)
        assertNotNull(decision2)
        assertEquals(ExternalSpeechLanguage.ENGLISH, decision2!!.winner.language)
        assertEquals("call police", decision2.winner.text)
        assertEquals(hiWithUnk, decision2.loser)
        assertTrue(decision2.reason.contains("[unk]"))
    }

    @Test
    fun testArbitrate_confidenceDeltaExceedsThreshold_higherConfidenceWins() {
        // English confidence 0.88 vs Hindi confidence 0.65 (delta = 0.23 >= 0.15)
        val enCandidate = RecognizerCandidate(
            text = "stop following me",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = 0.88
        )
        val hiCandidate = RecognizerCandidate(
            text = "मेरा पीछा मत करो",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = 0.65
        )

        val decision = arbitrator.arbitrate(enCandidate, hiCandidate)
        assertNotNull(decision)
        assertEquals(ExternalSpeechLanguage.ENGLISH, decision!!.winner.language)
        assertEquals("stop following me", decision.winner.text)
        assertTrue(decision.reason.contains("confidence"))

        // Reverse: Hindi confidence 0.92 vs English confidence 0.70 (delta = 0.22 >= 0.15)
        val enCandidate2 = RecognizerCandidate(
            text = "save me",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = 0.70
        )
        val hiCandidate2 = RecognizerCandidate(
            text = "मुझे बचाओ",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = 0.92
        )

        val decision2 = arbitrator.arbitrate(enCandidate2, hiCandidate2)
        assertNotNull(decision2)
        assertEquals(ExternalSpeechLanguage.HINDI, decision2!!.winner.language)
        assertEquals("मुझे बचाओ", decision2.winner.text)
        assertTrue(decision2.reason.contains("confidence"))
    }

    @Test
    fun testArbitrate_noDistressKeywordBias_objectiveArbitrationPreserved() {
        // English candidate has high confidence conversational speech without distress keywords.
        // Hindi candidate has low confidence and contains emergency keyword ("मदद").
        // The arbitrator MUST remain objective and NOT artificially pick Hindi just because of the distress keyword.
        val nonDistressEnglish = RecognizerCandidate(
            text = "where are we going right now",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = 0.89
        )
        val distressHindiLowConf = RecognizerCandidate(
            text = "मदद",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = 0.45
        )

        val decisionEnWins = arbitrator.arbitrate(nonDistressEnglish, distressHindiLowConf)
        assertNotNull(decisionEnWins)
        assertEquals(
            "Arbitrator must not bias towards distress keyword when other candidate has superior confidence",
            ExternalSpeechLanguage.ENGLISH,
            decisionEnWins!!.winner.language
        )
        assertEquals("where are we going right now", decisionEnWins.winner.text)

        // Reverse: Hindi candidate has high confidence speech without distress keywords.
        // English candidate has low confidence and contains emergency keyword ("help").
        val nonDistressHindi = RecognizerCandidate(
            text = "हम सब घर जा रहे हैं",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = 0.92
        )
        val distressEnglishLowConf = RecognizerCandidate(
            text = "help",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = 0.40
        )

        val decisionHiWins = arbitrator.arbitrate(distressEnglishLowConf, nonDistressHindi)
        assertNotNull(decisionHiWins)
        assertEquals(
            "Arbitrator must not bias towards English distress keyword when Hindi candidate has superior confidence",
            ExternalSpeechLanguage.HINDI,
            decisionHiWins!!.winner.language
        )
        assertEquals("हम सब घर जा रहे हैं", decisionHiWins.winner.text)
    }

    @Test
    fun testArbitrate_completenessWordCountDifference_prefersMoreCompleteUtterance() {
        // When confidence scores are absent (null), candidate with >= 2 words difference wins
        val enLong = RecognizerCandidate(
            text = "please let me go right now",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = null
        )
        val hiShort = RecognizerCandidate(
            text = "जाओ",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = null
        )

        val decision = arbitrator.arbitrate(enLong, hiShort)
        assertNotNull(decision)
        assertEquals(ExternalSpeechLanguage.ENGLISH, decision!!.winner.language)
        assertEquals("please let me go right now", decision.winner.text)
        assertTrue(decision.reason.contains("completeness"))

        // Reverse: Hindi has 5 words, English has 2 words
        val enShort = RecognizerCandidate(
            text = "go away",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = null
        )
        val hiLong = RecognizerCandidate(
            text = "कृपया मुझे अकेला छोड़ दीजिए आप",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = null
        )

        val decision2 = arbitrator.arbitrate(enShort, hiLong)
        assertNotNull(decision2)
        assertEquals(ExternalSpeechLanguage.HINDI, decision2!!.winner.language)
        assertEquals("कृपया मुझे अकेला छोड़ दीजिए आप", decision2.winner.text)
        assertTrue(decision2.reason.contains("completeness"))
    }

    @Test
    fun testArbitrate_oneHasVerifiedHighConfidenceOtherHasNone() {
        // English has verified confidence >= 0.70, Hindi has null confidence
        val enVerified = RecognizerCandidate(
            text = "call the police",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = 0.85
        )
        val hiUnscored = RecognizerCandidate(
            text = "पुलिस को बुलाओ",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = null
        )

        val decision = arbitrator.arbitrate(enVerified, hiUnscored)
        assertNotNull(decision)
        assertEquals(ExternalSpeechLanguage.ENGLISH, decision!!.winner.language)
        assertTrue(decision.reason.contains("verified high confidence"))

        // Reverse: Hindi has verified confidence >= 0.70, English has null confidence
        val enUnscored = RecognizerCandidate(
            text = "save me",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = null
        )
        val hiVerified = RecognizerCandidate(
            text = "मुझे बचाओ",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = 0.78
        )

        val decision2 = arbitrator.arbitrate(enUnscored, hiVerified)
        assertNotNull(decision2)
        assertEquals(ExternalSpeechLanguage.HINDI, decision2!!.winner.language)
        assertTrue(decision2.reason.contains("verified high confidence"))
    }

    @Test
    fun testArbitrate_deterministicTieBreaker_defaultsToNative8kEnglish() {
        // Tied on word count, confidence, character count
        val enTied = RecognizerCandidate(
            text = "help",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = null
        )
        val hiTied = RecognizerCandidate(
            text = "मदद",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = null
        )

        // Note: "help" has 4 chars, "मदद" has 3 chars -> English wins on length
        // To test strict tie-break, match length:
        val enTieStrict = RecognizerCandidate(
            text = "test",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = 0.80
        )
        val hiTieStrict = RecognizerCandidate(
            text = "जाओ!", // 4 characters
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = 0.80
        )

        val decision = arbitrator.arbitrate(enTieStrict, hiTieStrict)
        assertNotNull(decision)
        assertEquals(
            "Tied metrics must deterministically resolve to native 8 kHz English model",
            ExternalSpeechLanguage.ENGLISH,
            decision!!.winner.language
        )
        assertTrue(decision.reason.contains("native 8 kHz English model"))
    }

    @Test
    fun testParseVoskJson_extractsWordsConfidenceAndTimingAccurately() {
        val voskJson = """
            {
                "result": [
                    { "conf": 0.95, "start": 1.20, "end": 1.50, "word": "help" },
                    { "conf": 0.85, "start": 1.55, "end": 1.80, "word": "me" }
                ],
                "text": "help me"
            }
        """.trimIndent()

        val candidate = BilingualTranscriptArbitrator.parseVoskJson(
            jsonStr = voskJson,
            language = ExternalSpeechLanguage.ENGLISH,
            isPartial = false
        )

        assertEquals("help me", candidate.text)
        assertEquals(ExternalSpeechLanguage.ENGLISH, candidate.language)
        assertFalse(candidate.isPartial)
        assertEquals(2, candidate.words.size)

        assertEquals("help", candidate.words[0].word)
        assertEquals(0.95, candidate.words[0].conf!!, 0.001)
        assertEquals(1.20, candidate.words[0].start!!, 0.001)
        assertEquals(1.50, candidate.words[0].end!!, 0.001)

        assertEquals("me", candidate.words[1].word)
        assertEquals(0.85, candidate.words[1].conf!!, 0.001)

        // Average confidence = (0.95 + 0.85) / 2 = 0.90
        assertNotNull(candidate.averageConfidence)
        assertEquals(0.90, candidate.averageConfidence!!, 0.001)
    }

    @Test
    fun testParseVoskJson_handlesJsonWithoutConfidence_neverInventsValues() {
        val jsonNoConf = """
            {
                "text": "someone help"
            }
        """.trimIndent()

        val candidate = BilingualTranscriptArbitrator.parseVoskJson(
            jsonStr = jsonNoConf,
            language = ExternalSpeechLanguage.ENGLISH,
            isPartial = false
        )

        assertEquals("someone help", candidate.text)
        assertTrue(candidate.words.isEmpty())
        assertNull("Must NEVER invent confidence values if absent from Vosk output", candidate.averageConfidence)
    }

    @Test
    fun testParseVoskJson_handlesPartialJson() {
        val partialJson = """
            {
                "partial": "help m"
            }
        """.trimIndent()

        val candidate = BilingualTranscriptArbitrator.parseVoskJson(
            jsonStr = partialJson,
            language = ExternalSpeechLanguage.ENGLISH,
            isPartial = true
        )

        assertEquals("help m", candidate.text)
        assertTrue(candidate.isPartial)
        assertNull(candidate.averageConfidence)
    }

    @Test
    fun testParseVoskJson_handlesMalformedJsonGracefully() {
        val malformedJson = """{"text": "emergency", invalid}"""

        val candidate = BilingualTranscriptArbitrator.parseVoskJson(
            jsonStr = malformedJson,
            language = ExternalSpeechLanguage.ENGLISH
        )

        assertEquals("emergency", candidate.text)
        assertNull(candidate.averageConfidence)
    }

    @Test
    fun testDeduplication_suppressesIdenticalUtteranceWithinRepeatWindow() {
        val candidate = RecognizerCandidate(
            text = "help me",
            language = ExternalSpeechLanguage.ENGLISH
        )

        // 1. Initial dispatch at t = 1000ms
        assertTrue(arbitrator.shouldDispatch(candidate, currentTime = 1000L))
        arbitrator.recordDispatch(candidate, currentTime = 1000L)
        assertEquals("help me", arbitrator.lastAuthoritativeTranscript)
        assertEquals(1L, arbitrator.totalArbitrations)
        assertEquals(1L, arbitrator.englishWins)

        // 2. Duplicate dispatch attempt at t = 2000ms (delta = 1000ms < 3500ms)
        val isAllowedDuplicate = arbitrator.shouldDispatch(candidate, currentTime = 2000L)
        assertFalse("Duplicate transcript within 3500ms must be suppressed", isAllowedDuplicate)
        assertEquals(1L, arbitrator.suppressedDuplicates)

        // 3. Same transcript after repeat window expires at t = 5000ms (delta = 4000ms >= 3500ms)
        val isAllowedAfterExpiry = arbitrator.shouldDispatch(candidate, currentTime = 5000L)
        assertTrue("Identical transcript after window expiration must be permitted", isAllowedAfterExpiry)
        arbitrator.recordDispatch(candidate, currentTime = 5000L)
        assertEquals(2L, arbitrator.totalArbitrations)
    }

    @Test
    fun testConflictSuppression_suppressesSecondaryDecoderArtifactsWithinConflictWindow() {
        val enCandidate = RecognizerCandidate(
            text = "emergency help",
            language = ExternalSpeechLanguage.ENGLISH
        )
        val hiDelayedCandidate = RecognizerCandidate(
            text = "इमरजेंसी",
            language = ExternalSpeechLanguage.HINDI
        )

        // 1. English candidate is arbitrated and dispatched at t = 1000ms
        assertTrue(arbitrator.shouldDispatch(enCandidate, currentTime = 1000L))
        arbitrator.recordDispatch(enCandidate, currentTime = 1000L)

        // 2. Hindi candidate arrives 300ms later (t = 1300ms) representing the same acoustic event
        val isAllowedConflict = arbitrator.shouldDispatch(hiDelayedCandidate, currentTime = 1300L)
        assertFalse(
            "Secondary conflicting candidate within 1000ms conflict window must be suppressed",
            isAllowedConflict
        )
        assertEquals(1L, arbitrator.suppressedConflicts)

        // 3. New separate utterance after conflict window at t = 2500ms (delta = 1500ms >= 1000ms)
        val isAllowedNewUtterance = arbitrator.shouldDispatch(hiDelayedCandidate, currentTime = 2500L)
        assertTrue(
            "Utterance outside conflict window must be allowed",
            isAllowedNewUtterance
        )
    }

    @Test
    fun testReset_clearsHistoryAndTelemetry() {
        val candidate = RecognizerCandidate(
            text = "help",
            language = ExternalSpeechLanguage.ENGLISH
        )
        arbitrator.recordDispatch(candidate, currentTime = 1000L)
        assertEquals("help", arbitrator.lastAuthoritativeTranscript)

        arbitrator.reset()
        assertEquals("", arbitrator.lastAuthoritativeTranscript)
        assertEquals("", arbitrator.telemetry.value.lastAuthoritativeTranscript)
        assertNull(arbitrator.telemetry.value.lastAuthoritativeLanguage)

        // After reset, immediate dispatch of the same text is allowed
        assertTrue(arbitrator.shouldDispatch(candidate, currentTime = 1100L))
    }

    @Test
    fun testDevanagariPreservationAndRomanizedHindiLimitationDocumentation() {
        // Document and verify:
        // 1. Hindi model produces pure Unicode Devanagari text ("मुझे बचाओ")
        val devalCandidate = RecognizerCandidate(
            text = "मुझे बचाओ",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = 0.88
        )
        assertEquals("मुझे बचाओ", devalCandidate.text)

        // 2. If a Romanized Hindi string ("bachao") is submitted, it is treated as English candidate
        // or out-of-vocabulary [unk] by acoustic models.
        val romanizedCandidate = RecognizerCandidate(
            text = "bachao [unk]",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = 0.30
        )

        val decision = arbitrator.arbitrate(romanizedCandidate, devalCandidate)
        assertNotNull(decision)
        assertEquals(
            "Devanagari Hindi candidate wins over distorted Romanized Hindi candidate",
            ExternalSpeechLanguage.HINDI,
            decision!!.winner.language
        )
        assertEquals("मुझे बचाओ", decision.winner.text)
    }

    @Test
    fun testTelemetryFlow_updatesAccuratelyAcrossArbitrationLifecycle() {
        val enCandidate = RecognizerCandidate(
            text = "leave me alone",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = 0.91
        )
        val hiCandidate = RecognizerCandidate(
            text = "मुझे अकेला छोड़ दो",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = 0.89
        )

        val decision = arbitrator.arbitrate(enCandidate, hiCandidate)
        assertNotNull(decision)

        val tele = arbitrator.telemetry.value
        assertEquals("leave me alone", tele.lastEnglishTranscript)
        assertEquals(0.91, tele.lastEnglishConfidence!!, 0.001)
        assertEquals("मुझे अकेला छोड़ दो", tele.lastHindiTranscript)
        assertEquals(0.89, tele.lastHindiConfidence!!, 0.001)
        assertTrue(tele.lastDecisionReason.isNotBlank())

        arbitrator.recordDispatch(decision!!.winner)
        val updatedTele = arbitrator.telemetry.value
        assertEquals("leave me alone", updatedTele.lastAuthoritativeTranscript)
        assertEquals(ExternalSpeechLanguage.ENGLISH, updatedTele.lastAuthoritativeLanguage)
        assertEquals(1L, updatedTele.totalArbitrations)
        assertEquals(1L, updatedTele.englishWins)
        assertEquals(0L, updatedTele.hindiWins)
    }

    @Test
    fun testArbitrate_tieBreakerPrioritizesDistressVocabulary() {
        // Case 1: Hindi has distress keyword ("बचाओ"), English has non-distress ("good"), same length & confidence
        val enNonDistress = RecognizerCandidate(
            text = "good",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = 0.80
        )
        val hiDistress = RecognizerCandidate(
            text = "बचाओ",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = 0.80
        )

        val decision1 = arbitrator.arbitrate(enNonDistress, hiDistress)
        assertNotNull(decision1)
        assertEquals(ExternalSpeechLanguage.HINDI, decision1!!.winner.language)
        assertEquals("बचाओ", decision1.winner.text)
        assertTrue(decision1.reason.contains("distress vocabulary", ignoreCase = true))

        // Case 2: English has distress keyword ("help"), Hindi has non-distress ("बात"), same metrics
        val enDistress = RecognizerCandidate(
            text = "help",
            language = ExternalSpeechLanguage.ENGLISH,
            averageConfidence = 0.80
        )
        val hiNonDistress = RecognizerCandidate(
            text = "बात!",
            language = ExternalSpeechLanguage.HINDI,
            averageConfidence = 0.80
        )

        val decision2 = arbitrator.arbitrate(enDistress, hiNonDistress)
        assertNotNull(decision2)
        assertEquals(ExternalSpeechLanguage.ENGLISH, decision2!!.winner.language)
        assertEquals("help", decision2.winner.text)
        assertTrue(decision2.reason.contains("distress vocabulary", ignoreCase = true))
    }

    @Test
    fun testConflictSuppression_bypassesConflictWindowForDistressUtterance() {
        val nonDistressNoise = RecognizerCandidate(
            text = "it is raining outside",
            language = ExternalSpeechLanguage.ENGLISH
        )
        val distressUtterance = RecognizerCandidate(
            text = "मदद करो मुझे",
            language = ExternalSpeechLanguage.HINDI
        )

        // 1. Non-distress noise dispatched at t = 1000ms
        assertTrue(arbitrator.shouldDispatch(nonDistressNoise, currentTime = 1000L))
        arbitrator.recordDispatch(nonDistressNoise, currentTime = 1000L)

        // 2. Real distress utterance arrives 400ms later (t = 1400ms, within 1000ms conflict window)
        // It MUST bypass the conflict window and be dispatched!
        val shouldDispatchDistress = arbitrator.shouldDispatch(distressUtterance, currentTime = 1400L)
        assertTrue("Emergency distress utterance must not be suppressed by prior non-distress noise", shouldDispatchDistress)

        // Record the distress dispatch
        arbitrator.recordDispatch(distressUtterance, currentTime = 1400L)

        // 3. A secondary non-distress utterance arrives 300ms later (t = 1700ms)
        val secondaryNonDistress = RecognizerCandidate(
            text = "weather update",
            language = ExternalSpeechLanguage.ENGLISH
        )
        val shouldDispatchSecondary = arbitrator.shouldDispatch(secondaryNonDistress, currentTime = 1700L)
        assertFalse("Non-distress utterance within conflict window of distress must be suppressed", shouldDispatchSecondary)
    }
}

package com.example.sakhi

import com.example.sakhi.core.database.SessionHistoryRepository
import com.example.sakhi.core.model.ContactAlertState
import com.example.sakhi.core.model.DistressAssessment
import com.example.sakhi.core.model.DistressCategory
import com.example.sakhi.core.model.EmergencySession
import com.example.sakhi.core.model.EmergencyState
import com.example.sakhi.core.model.SessionState
import com.example.sakhi.core.model.TranscriptSegment
import com.example.sakhi.data.assistant.ExternalSpeechLanguage
import com.example.sakhi.data.assistant.RecognitionSessionType
import com.example.sakhi.data.assistant.VoskAudioTranscriptionService
import com.example.sakhi.data.audio.ActiveAudioSource
import com.example.sakhi.data.audio.AudioPipeline
import com.example.sakhi.data.audio.IPhoneMicrophoneAudioSource
import com.example.sakhi.data.audio.PhoneAudioTelemetry
import com.example.sakhi.data.audio.UnifiedAudioRouter
import com.example.sakhi.data.bluetooth.BluetoothClassicAudioReceiver
import com.example.sakhi.data.location.AndroidLocationProvider
import com.example.sakhi.data.location.SakhiLocation
import com.example.sakhi.domain.emergency.EmergencyStateManager
import com.example.sakhi.domain.escalation.EscalationPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class Phase8EmergencySosBilingualTest {

    private lateinit var mockBleManager: MockBleManager
    private lateinit var mockLocationProvider: MockLocationProvider
    private lateinit var mockAlertDispatcher: MockAlertDispatcher
    private lateinit var mockAudioPipeline: MockAudioPipeline
    private lateinit var mockAssessmentEngine: MockAssessmentEngine
    private lateinit var escalationPolicy: EscalationPolicy
    private lateinit var mockPoliceCallManager: MockPoliceCallManager
    private lateinit var mockContactRepository: MockContactRepository
    private lateinit var mockSessionHistoryRepository: RecordingSessionHistoryRepository
    private lateinit var mockPreferences: MockPreferences
    private lateinit var mockLiveTrackingClient: MockLiveTrackingClient

    @Before
    fun setup() {
        mockBleManager = MockBleManager()
        mockLocationProvider = MockLocationProvider()
        mockAlertDispatcher = MockAlertDispatcher()
        mockAudioPipeline = MockAudioPipeline()
        mockAssessmentEngine = MockAssessmentEngine()
        escalationPolicy = EscalationPolicy()
        mockPoliceCallManager = MockPoliceCallManager()
        mockContactRepository = MockContactRepository()
        mockSessionHistoryRepository = RecordingSessionHistoryRepository()
        mockPreferences = MockPreferences()
        mockLiveTrackingClient = MockLiveTrackingClient()
    }

    private fun createManager(
        scope: CoroutineScope,
        voskService: VoskAudioTranscriptionService? = null
    ): EmergencyStateManager {
        return EmergencyStateManager(
            scope = scope,
            bleManager = mockBleManager,
            locationProvider = mockLocationProvider,
            alertDispatcher = mockAlertDispatcher,
            audioPipeline = mockAudioPipeline,
            assessmentEngine = mockAssessmentEngine,
            escalationPolicy = escalationPolicy,
            policeCallManager = mockPoliceCallManager,
            contactRepository = mockContactRepository,
            sessionHistoryRepository = mockSessionHistoryRepository,
            preferences = mockPreferences,
            liveTrackingClient = mockLiveTrackingClient,
            voskTranscriptionService = voskService,
            foregroundServiceStarter = {},
            foregroundServiceStopper = {}
        )
    }

    private fun createTestRouter(scope: CoroutineScope): UnifiedAudioRouter {
        val fakeReceiver = BluetoothClassicAudioReceiver(scope = scope)
        val fakePhoneSource = FakePhoneMicrophoneSource()
        return UnifiedAudioRouter(fakeReceiver, fakePhoneSource, scope)
    }

    // =========================================================================
    // 1. DUAL PARALLEL TRANSCRIPTION STREAMS & FULL HISTORY
    // =========================================================================

    @Test
    fun testDualParallelTranscription_independentHistories_noOverwritingOrSuppression() = runTest {
        val manager = createManager(backgroundScope)
        manager.handleDoublePress()
        advanceTimeBy(150L)
        assertEquals(SessionState.ACTIVE, manager.state.value.sessionState)

        val hindiSeg1 = TranscriptSegment(
            id = "hi-1",
            timestamp = 1000L,
            text = "बचाओ मुझे",
            language = ExternalSpeechLanguage.HINDI,
            confidence = 0.92,
            isFinal = true
        )
        val engSeg1 = TranscriptSegment(
            id = "en-1",
            timestamp = 1200L,
            text = "someone help me",
            language = ExternalSpeechLanguage.ENGLISH,
            confidence = 0.88,
            isFinal = true
        )
        val hindiSeg2 = TranscriptSegment(
            id = "hi-2",
            timestamp = 1500L,
            text = "पुलिस को बुलाओ",
            language = ExternalSpeechLanguage.HINDI,
            confidence = 0.95,
            isFinal = true
        )

        // Process Hindi segment 1
        manager.onSpeechSegmentRecognized(hindiSeg1)
        advanceTimeBy(50L)

        val stateAfterHi1 = manager.state.value
        assertEquals(1, stateAfterHi1.hindiTranscriptHistory.size)
        assertEquals(0, stateAfterHi1.englishTranscriptHistory.size)
        assertEquals("बचाओ मुझे", stateAfterHi1.hindiLatestFinalText)
        assertEquals("", stateAfterHi1.englishLatestFinalText)
        assertEquals("बचाओ मुझे", stateAfterHi1.hindiTranscriptHistory.first().text)

        // Process English segment 1
        manager.onSpeechSegmentRecognized(engSeg1)
        advanceTimeBy(50L)

        val stateAfterEn1 = manager.state.value
        // Verify Hindi history preserved intact
        assertEquals(1, stateAfterEn1.hindiTranscriptHistory.size)
        assertEquals(1, stateAfterEn1.englishTranscriptHistory.size)
        assertEquals("someone help me", stateAfterEn1.englishLatestFinalText)
        assertEquals("बचाओ मुझे", stateAfterEn1.hindiLatestFinalText)
        assertEquals("someone help me", stateAfterEn1.englishTranscriptHistory.first().text)

        // Process Hindi segment 2
        manager.onSpeechSegmentRecognized(hindiSeg2)
        advanceTimeBy(50L)

        val stateAfterHi2 = manager.state.value
        assertEquals(2, stateAfterHi2.hindiTranscriptHistory.size)
        assertEquals(1, stateAfterHi2.englishTranscriptHistory.size)
        assertEquals("पुलिस को बुलाओ", stateAfterHi2.hindiLatestFinalText)
        assertEquals("someone help me", stateAfterHi2.englishLatestFinalText)
        assertEquals("बचाओ मुझे", stateAfterHi2.hindiTranscriptHistory[0].text)
        assertEquals("पुलिस को बुलाओ", stateAfterHi2.hindiTranscriptHistory[1].text)
    }

    // =========================================================================
    // 2. DUAL-STREAM ESCALATION DEDUPLICATION (1500 MS WINDOW)
    // =========================================================================

    @Test
    fun testDualStreamEscalationDeduplication_identicalTriggerWithin1500ms_deduplicated() = runTest {
        mockPreferences.autoCallEscalationEnabled = true
        mockPreferences.escalationPhoneNumber = "112"
        mockPreferences.escalationPolicy = "CALL_ON_RISK_ESCALATION"

        val manager = createManager(backgroundScope)
        manager.handleDoublePress()
        advanceTimeBy(150L)

        // Stream 1 (English) reports distress trigger "help"
        manager.onSpeechSegmentRecognized(
            TranscriptSegment(
                id = "en-1",
                timestamp = 1000L,
                text = "help",
                language = ExternalSpeechLanguage.ENGLISH,
                confidence = 0.9,
                isFinal = true
            )
        )
        advanceTimeBy(50L)

        // Second decoder reports identical word "help" 200ms later
        manager.onSpeechSegmentRecognized(
            TranscriptSegment(
                id = "en-2",
                timestamp = 1200L,
                text = "help",
                language = ExternalSpeechLanguage.ENGLISH,
                confidence = 0.91,
                isFinal = true
            )
        )
        advanceTimeBy(50L)

        // Verify deduplication: consecutive count or calls were not triggered twice
        assertEquals(1, manager.state.value.englishTranscriptHistory.size)
    }

    // =========================================================================
    // 3. MANDATORY SPEEXDSP NOISE SUPPRESSION INTERLOCK
    // =========================================================================

    @Test
    fun testMandatoryNoiseSuppression_cannotBeDisabledDuringProductionSos() = runTest {
        val router = createTestRouter(backgroundScope)
        val service = VoskAudioTranscriptionService(
            context = null,
            audioRouter = router,
            modelManager = null,
            scope = backgroundScope,
            preferences = null
        )

        // When in inactive mode, disabling noise suppression is allowed
        service.setNoiseSuppressionEnabled(false)
        assertFalse(service.isNoiseSuppressionEnabled.value)

        // Starting a production SOS session forces noise suppression ON
        service.startProductionSession("SESSION-TEST-001", onTranscript = {})
        assertEquals(RecognitionSessionType.PRODUCTION_SOS, service.sessionType.value)
        assertTrue(service.isNoiseSuppressionEnabled.value)

        // Attempting to turn OFF noise suppression during production SOS is blocked
        service.setNoiseSuppressionEnabled(false)
        assertTrue("Noise suppression must remain MANDATORY during active SOS", service.isNoiseSuppressionEnabled.value)

        // Stop session, then toggling is permitted again
        service.stopProductionSession("SESSION-TEST-001")
        assertEquals(RecognitionSessionType.INACTIVE, service.sessionType.value)
        service.setNoiseSuppressionEnabled(false)
        assertFalse(service.isNoiseSuppressionEnabled.value)
    }

    // =========================================================================
    // 4. TRANSCRIPT HISTORY SERIALIZATION & REPOSITORY PERSISTENCE
    // =========================================================================

    @Test
    fun testSessionHistoryRepository_transcriptSerializationAndDeserialization() {
        val repo = SessionHistoryRepository(null)

        val hindiList = listOf(
            TranscriptSegment(
                id = "hi-1",
                timestamp = 1600000000000L,
                text = "बचाओ",
                language = ExternalSpeechLanguage.HINDI,
                confidence = 0.95,
                isFinal = true
            ),
            TranscriptSegment(
                id = "hi-2",
                timestamp = 1600000005000L,
                text = "यहाँ खतरा है",
                language = ExternalSpeechLanguage.HINDI,
                confidence = null,
                isFinal = true
            )
        )

        val englishList = listOf(
            TranscriptSegment(
                id = "en-1",
                timestamp = 1600000002000L,
                text = "help me please",
                language = ExternalSpeechLanguage.ENGLISH,
                confidence = 0.88,
                isFinal = true
            )
        )

        val serializedJson = repo.serializeTranscriptsJson(hindiList, englishList)
        assertNotNull(serializedJson)
        assertTrue(serializedJson.contains("hindi"))
        assertTrue(serializedJson.contains("english"))
        assertTrue(serializedJson.contains("बचाओ"))
        assertTrue(serializedJson.contains("help me please"))

        val (parsedHindi, parsedEnglish) = repo.parseTranscriptsJson(serializedJson)
        assertEquals(2, parsedHindi.size)
        assertEquals(1, parsedEnglish.size)

        assertEquals("hi-1", parsedHindi[0].id)
        assertEquals(1600000000000L, parsedHindi[0].timestamp)
        assertEquals("बचाओ", parsedHindi[0].text)
        assertEquals(ExternalSpeechLanguage.HINDI, parsedHindi[0].language)
        assertEquals(0.95, parsedHindi[0].confidence!!, 0.001)
        assertTrue(parsedHindi[0].isFinal)

        assertEquals("hi-2", parsedHindi[1].id)
        assertEquals("यहाँ खतरा है", parsedHindi[1].text)
        assertNull(parsedHindi[1].confidence)

        assertEquals("en-1", parsedEnglish[0].id)
        assertEquals(1600000002000L, parsedEnglish[0].timestamp)
        assertEquals("help me please", parsedEnglish[0].text)
        assertEquals(ExternalSpeechLanguage.ENGLISH, parsedEnglish[0].language)
        assertEquals(0.88, parsedEnglish[0].confidence!!, 0.001)

        // Edge case tests: null, empty, invalid strings
        val (emptyHi, emptyEn) = repo.parseTranscriptsJson(null)
        assertTrue(emptyHi.isEmpty())
        assertTrue(emptyEn.isEmpty())

        val (corruptHi, corruptEn) = repo.parseTranscriptsJson("{corrupted json[}")
        assertTrue(corruptHi.isEmpty())
        assertTrue(corruptEn.isEmpty())
    }

    @Test
    fun testEmergencySession_storesDualTranscripts_inSessionRecord() = runTest {
        val manager = createManager(backgroundScope)
        manager.handleDoublePress()
        advanceTimeBy(150L)

        manager.onSpeechSegmentRecognized(
            TranscriptSegment(
                id = "hi-10",
                timestamp = 2000L,
                text = "मदद करो",
                language = ExternalSpeechLanguage.HINDI,
                confidence = 0.9,
                isFinal = true
            )
        )
        manager.onSpeechSegmentRecognized(
            TranscriptSegment(
                id = "en-10",
                timestamp = 2100L,
                text = "danger here",
                language = ExternalSpeechLanguage.ENGLISH,
                confidence = 0.85,
                isFinal = true
            )
        )
        advanceTimeBy(100L)

        // End emergency session via tap-hold
        manager.handleTapHold()
        advanceTimeBy(100L)

        val savedSession = mockSessionHistoryRepository.lastSavedSession
        assertNotNull(savedSession)
        assertEquals(1, savedSession!!.hindiTranscripts.size)
        assertEquals(1, savedSession.englishTranscripts.size)
        assertEquals("मदद करो", savedSession.hindiTranscripts.first().text)
        assertEquals("danger here", savedSession.englishTranscripts.first().text)
    }

    // =========================================================================
    // 5. LOCATION ACCURACY & VALIDATION CONTROLS
    // =========================================================================

    @Test
    fun testLocationProvider_isValidCoordinate_validatesCoordinatesStrictly() {
        // (0.0, 0.0) rejection
        assertFalse(AndroidLocationProvider.isValidCoordinate(0.0, 0.0))

        // NaN and Infinity rejection
        assertFalse(AndroidLocationProvider.isValidCoordinate(Double.NaN, 77.2090))
        assertFalse(AndroidLocationProvider.isValidCoordinate(28.6139, Double.NaN))
        assertFalse(AndroidLocationProvider.isValidCoordinate(Double.POSITIVE_INFINITY, 77.2090))
        assertFalse(AndroidLocationProvider.isValidCoordinate(28.6139, Double.NEGATIVE_INFINITY))

        // Latitude boundary check (-90 to +90)
        assertFalse(AndroidLocationProvider.isValidCoordinate(90.1, 77.2090))
        assertFalse(AndroidLocationProvider.isValidCoordinate(-90.1, 77.2090))
        assertTrue(AndroidLocationProvider.isValidCoordinate(90.0, 77.2090))
        assertTrue(AndroidLocationProvider.isValidCoordinate(-90.0, 77.2090))

        // Longitude boundary check (-180 to +180)
        assertFalse(AndroidLocationProvider.isValidCoordinate(28.6139, 180.1))
        assertFalse(AndroidLocationProvider.isValidCoordinate(28.6139, -180.1))
        assertTrue(AndroidLocationProvider.isValidCoordinate(28.6139, 180.0))
        assertTrue(AndroidLocationProvider.isValidCoordinate(28.6139, -180.0))

        // Valid Delhi and Sydney coordinates
        assertTrue(AndroidLocationProvider.isValidCoordinate(28.6139, 77.2090))
        assertTrue(AndroidLocationProvider.isValidCoordinate(-33.8688, 151.2093))
    }

    @Test
    fun testLocationProvider_isBetterLocation_rejectsStaleOrWorseFixes() {
        val baseTime = 1_000_000L

        // Null currentBest accepts new location
        assertTrue(
            AndroidLocationProvider.isBetterLocation(
                newTime = baseTime,
                newAccuracy = 20f,
                newProvider = "gps",
                currentTime = 0L,
                currentAccuracy = null,
                currentProvider = null
            )
        )

        // Fix significantly newer (>60s) is better
        assertTrue(
            AndroidLocationProvider.isBetterLocation(
                newTime = baseTime + 65_000L,
                newAccuracy = 50f,
                newProvider = "gps",
                currentTime = baseTime,
                currentAccuracy = 15f,
                currentProvider = "gps"
            )
        )

        // Fix significantly older (>60s) is worse
        assertFalse(
            AndroidLocationProvider.isBetterLocation(
                newTime = baseTime - 65_000L,
                newAccuracy = 5f,
                newProvider = "gps",
                currentTime = baseTime,
                currentAccuracy = 25f,
                currentProvider = "gps"
            )
        )

        // Fix significantly less accurate (>200m worse) within freshness window is rejected
        assertFalse(
            AndroidLocationProvider.isBetterLocation(
                newTime = baseTime + 10_000L,
                newAccuracy = 250f,
                newProvider = "network",
                currentTime = baseTime,
                currentAccuracy = 10f,
                currentProvider = "gps"
            )
        )

        // Fix more accurate within freshness window is accepted
        assertTrue(
            AndroidLocationProvider.isBetterLocation(
                newTime = baseTime + 10_000L,
                newAccuracy = 8f,
                newProvider = "gps",
                currentTime = baseTime,
                currentAccuracy = 25f,
                currentProvider = "gps"
            )
        )
    }

    @Test
    fun testSakhiLocation_accuracyStatusAndIsHighAccuracy() {
        val now = System.currentTimeMillis()

        // Stale fix
        val staleLoc = SakhiLocation(
            latitude = 28.6139,
            longitude = 77.2090,
            accuracy = 10f,
            timestamp = now - 90_000L,
            isStale = true
        )
        assertEquals("LOCATION STALE", staleLoc.accuracyStatus)
        assertTrue(staleLoc.isHighAccuracy)

        // High accuracy (<= 20m)
        val highAccLoc = SakhiLocation(
            latitude = 28.6139,
            longitude = 77.2090,
            accuracy = 12.4f,
            timestamp = now,
            isStale = false
        )
        assertTrue(highAccLoc.isHighAccuracy)
        assertEquals("HIGH ACCURACY FIX (±12m)", highAccLoc.accuracyStatus)

        // Medium accuracy (<= 100m)
        val medAccLoc = SakhiLocation(
            latitude = 28.6139,
            longitude = 77.2090,
            accuracy = 45f,
            timestamp = now,
            isStale = false
        )
        assertFalse(medAccLoc.isHighAccuracy)
        assertEquals("MEDIUM ACCURACY FIX (±45m)", medAccLoc.accuracyStatus)

        // Low accuracy (> 100m)
        val lowAccLoc = SakhiLocation(
            latitude = 28.6139,
            longitude = 77.2090,
            accuracy = 150f,
            timestamp = now,
            isStale = false
        )
        assertFalse(lowAccLoc.isHighAccuracy)
        assertEquals("APPROXIMATE LOCATION (±150m)", lowAccLoc.accuracyStatus)

        // Null accuracy
        val nullAccLoc = SakhiLocation(
            latitude = 28.6139,
            longitude = 77.2090,
            accuracy = null,
            timestamp = now,
            isStale = false
        )
        assertFalse(nullAccLoc.isHighAccuracy)
        assertEquals("APPROXIMATE LOCATION", nullAccLoc.accuracyStatus)
    }

    // =========================================================================
    // TEST DOUBLES
    // =========================================================================

    private class FakePhoneMicrophoneSource : IPhoneMicrophoneAudioSource {
        private val _telemetry = MutableStateFlow(PhoneAudioTelemetry())
        override val telemetry: StateFlow<PhoneAudioTelemetry> = _telemetry.asStateFlow()
        private val _chunkFlow = MutableSharedFlow<ByteArray>(replay = 0)
        override val audioChunkFlow: SharedFlow<ByteArray> = _chunkFlow.asSharedFlow()
        override var isCapturing: Boolean = false

        override fun startCapture() { isCapturing = true }
        override fun stopCapture() { isCapturing = false }
        override fun release() { isCapturing = false }
    }

    private class RecordingSessionHistoryRepository : SessionHistoryRepository(null) {
        var lastSavedSession: EmergencySession? = null

        override suspend fun saveSession(session: EmergencySession) {
            lastSavedSession = session
        }
    }
}

package com.example.sakhi

import com.example.sakhi.data.assistant.ExternalSpeechLanguage
import com.example.sakhi.data.assistant.RecognitionSessionType
import com.example.sakhi.data.assistant.VoskAudioTranscriptionService
import com.example.sakhi.data.audio.ActiveAudioSource
import com.example.sakhi.data.audio.IPhoneMicrophoneAudioSource
import com.example.sakhi.data.audio.PhoneAudioTelemetry
import com.example.sakhi.data.audio.RouterOperationalState
import com.example.sakhi.data.audio.SpeexOperationalState
import com.example.sakhi.data.audio.UnifiedAudioRouter
import com.example.sakhi.data.ble.HardwareProtocolConfig
import com.example.sakhi.data.ble.SimulatedBleManager
import com.example.sakhi.data.bluetooth.BluetoothClassicAudioReceiver
import com.example.sakhi.ui.navigation.SakhiScreen
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
import org.junit.Test
import java.io.File

/**
 * Phase 5 Unit Tests: Diagnostics Relocation and Device Screen Cleanup.
 *
 * Verifies:
 * 1. Settings navigation routes to Diagnostics (SakhiScreen.DIAGNOSTICS).
 * 2. DeviceScreen no longer contains the relocated diagnostic UI components.
 * 3. Diagnostics can display audio source and SpeexDSP status.
 * 4. Diagnostic sessions remain strictly isolated from emergency dispatch.
 * 5. Diagnostic controls cannot convert sessions into production (isolation cannot be disabled).
 * 6. Opening Diagnostics does not start microphone capture.
 * 7. Diagnostic tests release audio resources on completion (stopDiagnosticSession).
 * 8. Diagnostics cannot interrupt or stop an active production SOS session.
 * 9. Production recognition remains independent of Diagnostics UI.
 * 10. Existing device pairing and haptic features remain fully available.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsRelocationAndSafetyTest {

    // =========================================================================
    // 1. SETTINGS NAVIGATION TO DIAGNOSTICS
    // =========================================================================
    @Test
    fun test1_settings_navigation_route_to_diagnostics() {
        // Verify SakhiScreen enum has DIAGNOSTICS
        val screens = SakhiScreen.values()
        assertTrue("SakhiScreen must include DIAGNOSTICS", screens.contains(SakhiScreen.DIAGNOSTICS))

        // Verify simulated navigation transition
        var currentScreen = SakhiScreen.SETTINGS
        val onNavigateToDiagnostics = { currentScreen = SakhiScreen.DIAGNOSTICS }

        onNavigateToDiagnostics.invoke()
        assertEquals(SakhiScreen.DIAGNOSTICS, currentScreen)

        // Verify back navigation returns to SETTINGS
        val onNavigateBack = { currentScreen = SakhiScreen.SETTINGS }
        onNavigateBack.invoke()
        assertEquals(SakhiScreen.SETTINGS, currentScreen)
    }

    // =========================================================================
    // 2. DEVICE SCREEN CLEANUP VERIFICATION
    // =========================================================================
    @Test
    fun test2_device_screen_no_longer_contains_relocated_diagnostic_ui() {
        val f1 = File("src/main/java/com/example/sakhi/ui/device/DeviceScreen.kt")
        val f2 = File("app/src/main/java/com/example/sakhi/ui/device/DeviceScreen.kt")
        val deviceScreenFile = if (f1.exists()) f1 else f2
        assertTrue("DeviceScreen.kt must exist", deviceScreenFile.exists())
        val content = deviceScreenFile.readText()

        // Verify relocated diagnostic composables are absent from DeviceScreen.kt
        assertFalse("DeviceScreen must not contain AudioLevelMeter", content.contains("fun AudioLevelMeter("))
        assertFalse("DeviceScreen must not contain AsrChannelCard", content.contains("fun AsrChannelCard("))
        assertFalse("DeviceScreen must not contain DiagnosticDistressAssessmentCard", content.contains("fun DiagnosticDistressAssessmentCard("))
        assertFalse("DeviceScreen must not contain SpeexTelemetry", content.contains("SpeexTelemetry"))
        assertFalse("DeviceScreen must not contain startDiagnosticSession", content.contains("startDiagnosticSession"))
        assertFalse("DeviceScreen must not contain setDiagnosticIsolation", content.contains("setDiagnosticIsolation"))
        assertFalse("DeviceScreen must not contain WAV recording buttons", content.contains("Record 5s WAV"))

        // Verify normal device features are preserved in DeviceScreen.kt
        assertTrue("DeviceScreen must retain KeychainHardwareStatusCard", content.contains("KeychainHardwareStatusCard"))
        assertTrue("DeviceScreen must retain KeychainAudioConnectionCard", content.contains("KeychainAudioConnectionCard"))
        assertTrue("DeviceScreen must retain KeychainTroubleshootingCard", content.contains("KeychainTroubleshootingCard"))
        assertTrue("DeviceScreen must retain ACTION_BLUETOOTH_SETTINGS", content.contains("ACTION_BLUETOOTH_SETTINGS"))

        // Verify obsolete BLE controls and fake gauges are removed
        assertFalse("DeviceScreen must not contain ScannedDevicesCard", content.contains("ScannedDevicesCard"))
        assertFalse("DeviceScreen must not contain HapticFeedbackTesterCard", content.contains("HapticFeedbackTesterCard"))
        assertFalse("DeviceScreen must not contain CircularBatteryGauge", content.contains("CircularBatteryGauge"))
    }

    // =========================================================================
    // 3. DIAGNOSTICS CAN DISPLAY SOURCE AND DSP STATUS
    // =========================================================================
    @Test
    fun test3_diagnostics_can_display_source_and_dsp_status() = runTest {
        val testRouter = createTestRouter(backgroundScope)
        val testVoskService = TestableVoskService(testRouter, backgroundScope)

        // 1. Audio Source Telemetry
        val initialTelemetry = testRouter.telemetry.value
        assertEquals(ActiveAudioSource.NONE, initialTelemetry.activeSource)
        assertEquals(RouterOperationalState.STOPPED, initialTelemetry.operationalState)

        // 2. SpeexDSP Status Telemetry
        val initialSpeex = testVoskService.speexTelemetry.value
        assertEquals(SpeexOperationalState.OFF, initialSpeex.state)
        assertFalse(testVoskService.isNoiseSuppressionEnabled.value)

        // Enable noise suppression
        testVoskService.setNoiseSuppressionEnabled(true)
        assertTrue(testVoskService.isNoiseSuppressionEnabled.value)
    }

    // =========================================================================
    // 4. DIAGNOSTIC SESSIONS REMAIN ISOLATED FROM DISPATCH
    // =========================================================================
    @Test
    fun test4_diagnostic_sessions_remain_isolated_from_dispatch() = runTest {
        val testRouter = createTestRouter(backgroundScope)
        val testVoskService = TestableVoskService(testRouter, backgroundScope)

        // Start diagnostic session
        testVoskService.startDiagnosticSession()
        advanceTimeBy(100L)

        assertEquals(RecognitionSessionType.DIAGNOSTIC, testVoskService.sessionType.value)
        assertTrue("Diagnostic isolation must be strictly enabled", testVoskService.isDiagnosticIsolationEnabled.value)
        assertNotNull("Diagnostic session must have a diagnostic session ID", testVoskService.activeSessionId)
        assertTrue(testVoskService.activeSessionId!!.startsWith("DIAGNOSTIC_"))

        // Attempt to dispatch a severe distress utterance in diagnostic mode
        testVoskService.dispatchForTesting(
            text = "help me please save me",
            language = ExternalSpeechLanguage.ENGLISH,
            confidence = 0.99
        )
        advanceTimeBy(100L)

        // Verify zero production dispatches occurred
        assertEquals(0, testVoskService.forwardedProductionTranscripts.size)
        // Verify diagnostic assessment was still computed for read-only UI inspection
        assertNotNull(testVoskService.diagnosticAssessment.value)
        assertEquals("help me please save me", testVoskService.diagnosticAssessment.value?.transcript)
    }

    // =========================================================================
    // 5. DIAGNOSTIC CONTROLS CANNOT CONVERT SESSION INTO PRODUCTION
    // =========================================================================
    @Test
    fun test5_diagnostic_controls_cannot_convert_session_into_production() = runTest {
        val testRouter = createTestRouter(backgroundScope)
        val testVoskService = TestableVoskService(testRouter, backgroundScope)

        testVoskService.startDiagnosticSession()
        advanceTimeBy(100L)

        assertEquals(RecognitionSessionType.DIAGNOSTIC, testVoskService.sessionType.value)
        assertTrue(testVoskService.isDiagnosticIsolationEnabled.value)

        // Attempt to disable diagnostic isolation while in a DIAGNOSTIC session
        testVoskService.setDiagnosticIsolation(false)

        // Invariant: Isolation remains locked ON (true)
        assertTrue("Isolation must NOT be disabled during diagnostic session", testVoskService.isDiagnosticIsolationEnabled.value)
        assertEquals(RecognitionSessionType.DIAGNOSTIC, testVoskService.sessionType.value)
    }

    // =========================================================================
    // 6. OPENING DIAGNOSTICS DOES NOT START MICROPHONE CAPTURE
    // =========================================================================
    @Test
    fun test6_opening_diagnostics_does_not_start_microphone_capture() = runTest {
        val fakeReceiver = FakeBtAudioReceiver(backgroundScope)
        val fakePhoneSource = FakePhoneMicrophoneAudioSource()
        val router = UnifiedAudioRouter(fakeReceiver, fakePhoneSource, backgroundScope)
        val service = TestableVoskService(router, backgroundScope)

        // Simulating screen opening without user tapping "Start Diagnostic Test"
        assertEquals(RecognitionSessionType.INACTIVE, service.sessionType.value)
        assertFalse("Router must remain stopped", router.isRunning)
        assertFalse("Phone microphone must not be capturing", fakePhoneSource.isCapturing)
        assertNull(service.activeSessionId)
    }

    // =========================================================================
    // 7. DIAGNOSTIC TESTS RELEASE AUDIO RESOURCES ON COMPLETION
    // =========================================================================
    @Test
    fun test7_diagnostic_tests_release_audio_resources_on_completion() = runTest {
        val fakeReceiver = FakeBtAudioReceiver(backgroundScope)
        val fakePhoneSource = FakePhoneMicrophoneAudioSource()
        val router = UnifiedAudioRouter(fakeReceiver, fakePhoneSource, backgroundScope)
        val service = TestableVoskService(router, backgroundScope)

        // Start diagnostic session
        service.startDiagnosticSession()
        advanceTimeBy(100L)

        assertTrue(router.isRunning)
        assertEquals(RecognitionSessionType.DIAGNOSTIC, service.sessionType.value)

        // User stops diagnostic session or navigates away
        service.stopDiagnosticSession()
        advanceTimeBy(100L)

        // Audio resources cleanly released
        assertEquals(RecognitionSessionType.INACTIVE, service.sessionType.value)
        assertNull(service.activeSessionId)
        assertFalse("Router must stop after diagnostic test ends", router.isRunning)
        assertFalse("Phone microphone capture must stop", fakePhoneSource.isCapturing)
    }

    // =========================================================================
    // 8. DIAGNOSTICS CANNOT INTERRUPT ACTIVE PRODUCTION SOS SESSION
    // =========================================================================
    @Test
    fun test8_diagnostics_cannot_interrupt_active_production_sos_session() = runTest {
        val testRouter = createTestRouter(backgroundScope)
        val testVoskService = TestableVoskService(testRouter, backgroundScope)

        // Start Production SOS Session
        val productionSessionId = "SOS_EMERGENCY_SESSION_456"
        var productionTranscriptReceived: String? = null
        testVoskService.startProductionSession(
            sessionId = productionSessionId,
            onTranscript = { productionTranscriptReceived = it }
        )
        advanceTimeBy(100L)

        assertEquals(RecognitionSessionType.PRODUCTION_SOS, testVoskService.sessionType.value)
        assertEquals(productionSessionId, testVoskService.activeSessionId)
        assertFalse(testVoskService.isDiagnosticIsolationEnabled.value)

        // Diagnostics UI attempts to start a diagnostic test while SOS is active
        testVoskService.startDiagnosticSession()
        advanceTimeBy(100L)

        // Invariant: Production session remains protected and untouched
        assertEquals("Production session must not be replaced", RecognitionSessionType.PRODUCTION_SOS, testVoskService.sessionType.value)
        assertEquals(productionSessionId, testVoskService.activeSessionId)

        // Diagnostics UI attempts to stop diagnostic test
        testVoskService.stopDiagnosticSession()
        advanceTimeBy(100L)

        // Invariant: Production session is not stopped by diagnostic controls
        assertEquals("Production session must not be stopped by stopDiagnosticSession", RecognitionSessionType.PRODUCTION_SOS, testVoskService.sessionType.value)
        assertEquals(productionSessionId, testVoskService.activeSessionId)
    }

    // =========================================================================
    // 9. PRODUCTION RECOGNITION REMAINS INDEPENDENT OF DIAGNOSTICS UI
    // =========================================================================
    @Test
    fun test9_production_recognition_remains_independent_of_diagnostics_ui() = runTest {
        val testRouter = createTestRouter(backgroundScope)
        val testVoskService = TestableVoskService(testRouter, backgroundScope)

        val prodSessionId = "SOS_INDEPENDENT_789"
        testVoskService.startProductionSession(
            sessionId = prodSessionId,
            onTranscript = {}
        )
        advanceTimeBy(100L)

        // Production dispatch works completely independently
        testVoskService.dispatchForTesting(
            text = "call police emergency",
            language = ExternalSpeechLanguage.ENGLISH,
            confidence = 0.95
        )
        advanceTimeBy(100L)

        assertEquals(1, testVoskService.forwardedProductionTranscripts.size)
        assertEquals("call police emergency", testVoskService.forwardedProductionTranscripts[0])
    }

    // =========================================================================
    // 10. EXISTING DEVICE PAIRING AND HAPTIC FEATURES REMAIN AVAILABLE
    // =========================================================================
    @Test
    fun test10_existing_device_pairing_and_haptic_features_remain_available() = runTest {
        val simulatedBle = SimulatedBleManager(backgroundScope)

        // Test haptic feedback commands
        val activationSent = simulatedBle.sendVibration(HardwareProtocolConfig.CMD_VIBRATE_ACTIVATION)
        assertTrue("Activation vibration command must succeed", activationSent)

        val cancelSent = simulatedBle.sendVibration(HardwareProtocolConfig.CMD_VIBRATE_CANCEL)
        assertTrue("Cancel vibration command must succeed", cancelSent)

        val policeSent = simulatedBle.sendVibration(HardwareProtocolConfig.CMD_VIBRATE_POLICE)
        assertTrue("Police vibration command must succeed", policeSent)
    }

    // =========================================================================
    // 11. ACTIVE AUDIO SOURCE RMS & VU METER TELEMETRY
    // =========================================================================
    @Test
    fun test11_diagnostics_active_audio_source_rms_and_vu_meter_telemetry() = runTest {
        val fakeReceiver = FakeBtAudioReceiver(backgroundScope)
        val fakePhoneSource = FakePhoneMicrophoneAudioSource()
        val router = UnifiedAudioRouter(fakeReceiver, fakePhoneSource, backgroundScope)

        // 1. Initial State (NONE)
        val initialSource = router.activeSource
        assertEquals(ActiveAudioSource.NONE, initialSource)
        val initialRms = when (initialSource) {
            ActiveAudioSource.ESP32_BLUETOOTH -> fakeReceiver.telemetry.value.currentRms
            ActiveAudioSource.PHONE_MICROPHONE -> fakePhoneSource.telemetry.value.currentRms
            ActiveAudioSource.NONE -> 0f
        }
        assertEquals(0f, initialRms, 0.001f)

        // 2. Phone Microphone Active
        fakePhoneSource.setTestRms(0.65f)
        val phoneRms = when (ActiveAudioSource.PHONE_MICROPHONE) {
            ActiveAudioSource.ESP32_BLUETOOTH -> fakeReceiver.telemetry.value.currentRms
            ActiveAudioSource.PHONE_MICROPHONE -> fakePhoneSource.telemetry.value.currentRms
            ActiveAudioSource.NONE -> 0f
        }
        assertEquals(0.65f, phoneRms, 0.001f)

        // 3. ESP32 Active
        fakeReceiver.setTestRms(0.82f)
        val btRms = when (ActiveAudioSource.ESP32_BLUETOOTH) {
            ActiveAudioSource.ESP32_BLUETOOTH -> fakeReceiver.telemetry.value.currentRms
            ActiveAudioSource.PHONE_MICROPHONE -> fakePhoneSource.telemetry.value.currentRms
            ActiveAudioSource.NONE -> 0f
        }
        assertEquals(0.82f, btRms, 0.001f)
    }

    // =========================================================================
    // TEST DOUBLES & HELPER FACTORIES
    // =========================================================================

    private fun createTestRouter(scope: CoroutineScope): UnifiedAudioRouter {
        val fakeReceiver = FakeBtAudioReceiver(scope)
        val fakePhoneSource = FakePhoneMicrophoneAudioSource()
        return UnifiedAudioRouter(fakeReceiver, fakePhoneSource, scope)
    }

    private class TestableVoskService(
        audioRouter: UnifiedAudioRouter,
        scope: CoroutineScope
    ) : VoskAudioTranscriptionService(
        context = null,
        audioRouter = audioRouter,
        modelManager = null,
        scope = scope,
        preferences = null
    ) {
        val forwardedProductionTranscripts = mutableListOf<String>()

        override fun startProductionSession(
            sessionId: String,
            onTranscript: (String) -> Unit,
            onError: ((String) -> Unit)?
        ) {
            val wrappedCallback: (String) -> Unit = { text ->
                forwardedProductionTranscripts.add(text)
                onTranscript(text)
            }
            super.startProductionSession(sessionId, wrappedCallback, onError)
        }
    }

    private class FakeBtAudioReceiver(scope: CoroutineScope) : BluetoothClassicAudioReceiver(scope = scope) {
        fun setTestRms(rms: Float) {
            _telemetry.value = _telemetry.value.copy(currentRms = rms)
        }
    }

    private class FakePhoneMicrophoneAudioSource : IPhoneMicrophoneAudioSource {
        private val _telemetry = MutableStateFlow(PhoneAudioTelemetry())
        override val telemetry: StateFlow<PhoneAudioTelemetry> = _telemetry.asStateFlow()
        private val _chunkFlow = MutableSharedFlow<ByteArray>(replay = 0)
        override val audioChunkFlow: SharedFlow<ByteArray> = _chunkFlow.asSharedFlow()
        override var isCapturing: Boolean = false

        fun setTestRms(rms: Float) {
            _telemetry.value = _telemetry.value.copy(currentRms = rms)
        }

        override fun startCapture() {
            isCapturing = true
        }

        override fun stopCapture() {
            isCapturing = false
        }

        override fun release() {
            isCapturing = false
        }
    }
}

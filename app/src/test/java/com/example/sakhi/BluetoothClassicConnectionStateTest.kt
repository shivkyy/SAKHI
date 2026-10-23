package com.example.sakhi

import com.example.sakhi.core.model.HardwareEvent
import com.example.sakhi.core.model.KeychainLinkState
import com.example.sakhi.data.bluetooth.BluetoothClassicAudioReceiver
import com.example.sakhi.data.bluetooth.BtAudioConnectionState
import com.example.sakhi.data.bluetooth.BtAudioTelemetry
import com.example.sakhi.domain.emergency.EmergencyStateManager
import com.example.sakhi.domain.escalation.EscalationPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying authoritative Bluetooth Classic SPP connection state
 * mapping in EmergencyStateManager.
 *
 * Validates:
 * 1. SPP CONNECTED maps to KeychainLinkState.CONNECTED and clears unsupported BLE metrics.
 * 2. SPP STREAMING maps to KeychainLinkState.CONNECTED.
 * 3. SPP CONNECTING maps to CONNECTING or RECONNECTING based on auto-reconnect flag.
 * 4. SPP DISCONNECTED maps to KeychainLinkState.DISCONNECTED.
 * 5. SPP ERROR maps to KeychainLinkState.LOST with error message.
 * 6. Dormant BLE events do not overwrite physical ESP32 SPP connection state.
 * 7. reconnectDevice and disconnectDevice delegate directly to BluetoothClassicAudioReceiver.
 * 8. Phone microphone fallback does not report ESP32 as connected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothClassicConnectionStateTest {

    private lateinit var mockBleManager: MockBleManager
    private lateinit var mockLocationProvider: MockLocationProvider
    private lateinit var mockAlertDispatcher: MockAlertDispatcher
    private lateinit var mockAudioPipeline: MockAudioPipeline
    private lateinit var mockAssessmentEngine: MockAssessmentEngine
    private lateinit var escalationPolicy: EscalationPolicy
    private lateinit var mockPoliceCallManager: MockPoliceCallManager
    private lateinit var mockContactRepository: MockContactRepository
    private lateinit var mockSessionHistoryRepository: MockSessionHistoryRepository
    private lateinit var mockPreferences: MockPreferences
    private lateinit var mockLiveTrackingClient: MockLiveTrackingClient
    private lateinit var fakeBtAudioReceiver: FakeBtAudioReceiver

    private class FakeBtAudioReceiver(scope: CoroutineScope) : BluetoothClassicAudioReceiver(scope = scope) {
        var connectCalledWith: String? = null
        var disconnectCalledCount: Int = 0

        override fun connect(targetAddress: String?, isAutoRetry: Boolean) {
            connectCalledWith = targetAddress
        }

        override fun disconnect() {
            disconnectCalledCount++
        }

        fun emitTelemetry(telemetry: BtAudioTelemetry) {
            _telemetry.value = telemetry
        }
    }

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
        mockSessionHistoryRepository = MockSessionHistoryRepository()
        mockPreferences = MockPreferences()
        mockLiveTrackingClient = MockLiveTrackingClient()
    }

    private fun createManager(
        scope: CoroutineScope,
        isSimulated: Boolean = false
    ): EmergencyStateManager {
        mockBleManager.isSimulatedField = isSimulated
        fakeBtAudioReceiver = FakeBtAudioReceiver(scope)

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
            btAudioReceiver = fakeBtAudioReceiver
        )
    }

    @Test
    fun test1_sppConnected_mapsToKeychainConnected_andClearsUnsupportedBleMetrics() = runTest {
        val manager = createManager(backgroundScope, isSimulated = false)
        testScheduler.advanceTimeBy(100)

        fakeBtAudioReceiver.emitTelemetry(
            BtAudioTelemetry(
                state = BtAudioConnectionState.CONNECTED,
                deviceName = "SAKHI_AUDIO",
                deviceAddress = "00:11:22:33:44:55",
                isReceivingData = false,
                totalBytesReceived = 0L
            )
        )
        testScheduler.advanceTimeBy(100)

        val state = manager.state.value
        assertEquals("Keychain link state must be CONNECTED", KeychainLinkState.CONNECTED, state.keychainLinkState)
        assertEquals("btClassicState must be CONNECTED", "CONNECTED", state.btClassicState)
        assertEquals("Device name must reflect SPP telemetry", "SAKHI_AUDIO", state.deviceName)
        assertEquals("Device address must reflect SPP telemetry", "00:11:22:33:44:55", state.deviceAddress)
        assertNull("deviceBatteryPercent must be null for SPP hardware", state.deviceBatteryPercent)
        assertNull("deviceRssi must be null for SPP hardware", state.deviceRssi)
        assertNull("deviceFirmwareVersion must be null for SPP hardware", state.deviceFirmwareVersion)
    }

    @Test
    fun test2_sppStreaming_mapsToKeychainConnected() = runTest {
        val manager = createManager(backgroundScope, isSimulated = false)
        testScheduler.advanceTimeBy(100)

        fakeBtAudioReceiver.emitTelemetry(
            BtAudioTelemetry(
                state = BtAudioConnectionState.STREAMING,
                deviceName = "SAKHI_AUDIO",
                deviceAddress = "00:11:22:33:44:55",
                isReceivingData = true,
                totalBytesReceived = 16000L
            )
        )
        testScheduler.advanceTimeBy(100)

        val state = manager.state.value
        assertEquals("STREAMING must map to KeychainLinkState.CONNECTED", KeychainLinkState.CONNECTED, state.keychainLinkState)
        assertEquals("btClassicState must reflect STREAMING", "STREAMING", state.btClassicState)
    }

    @Test
    fun test3_sppConnecting_mapsToConnectingOrReconnecting() = runTest {
        val manager = createManager(backgroundScope, isSimulated = false)
        testScheduler.advanceTimeBy(100)

        // Case A: Initial connecting
        fakeBtAudioReceiver.emitTelemetry(
            BtAudioTelemetry(
                state = BtAudioConnectionState.CONNECTING,
                isAutoReconnecting = false,
                reconnectAttempt = 0
            )
        )
        testScheduler.advanceTimeBy(100)
        assertEquals("Initial connecting maps to CONNECTING", KeychainLinkState.CONNECTING, manager.state.value.keychainLinkState)
        assertEquals("btClassicState must be CONNECTING", "CONNECTING", manager.state.value.btClassicState)

        // Case B: Auto-reconnecting
        fakeBtAudioReceiver.emitTelemetry(
            BtAudioTelemetry(
                state = BtAudioConnectionState.CONNECTING,
                isAutoReconnecting = true,
                reconnectAttempt = 2
            )
        )
        testScheduler.advanceTimeBy(100)
        assertEquals("Auto-reconnecting maps to RECONNECTING", KeychainLinkState.RECONNECTING, manager.state.value.keychainLinkState)
        assertEquals(2, manager.state.value.btReconnectAttempts)
    }

    @Test
    fun test4_sppDisconnected_mapsToDisconnected() = runTest {
        val manager = createManager(backgroundScope, isSimulated = false)
        testScheduler.advanceTimeBy(100)

        fakeBtAudioReceiver.emitTelemetry(
            BtAudioTelemetry(
                state = BtAudioConnectionState.DISCONNECTED,
                errorMessage = null
            )
        )
        testScheduler.advanceTimeBy(100)

        assertEquals("DISCONNECTED maps to KeychainLinkState.DISCONNECTED", KeychainLinkState.DISCONNECTED, manager.state.value.keychainLinkState)
        assertEquals("DISCONNECTED", manager.state.value.btClassicState)
    }

    @Test
    fun test5_sppError_mapsToLostWithErrorMessage() = runTest {
        val manager = createManager(backgroundScope, isSimulated = false)
        testScheduler.advanceTimeBy(100)

        fakeBtAudioReceiver.emitTelemetry(
            BtAudioTelemetry(
                state = BtAudioConnectionState.ERROR,
                errorMessage = "Socket connection failed: Connection refused"
            )
        )
        testScheduler.advanceTimeBy(100)

        assertEquals("ERROR maps to KeychainLinkState.LOST", KeychainLinkState.LOST, manager.state.value.keychainLinkState)
        assertEquals("ERROR", manager.state.value.btClassicState)
        assertEquals("Socket connection failed: Connection refused", manager.state.value.btClassicErrorMessage)
    }

    @Test
    fun test6_dormantBleHardwareEventsDoNotOverwriteSppConnectionState() = runTest {
        val manager = createManager(backgroundScope, isSimulated = false)
        testScheduler.advanceTimeBy(100)

        // Set SPP as connected
        fakeBtAudioReceiver.emitTelemetry(
            BtAudioTelemetry(
                state = BtAudioConnectionState.CONNECTED,
                deviceName = "SAKHI_AUDIO"
            )
        )
        testScheduler.advanceTimeBy(100)
        assertEquals(KeychainLinkState.CONNECTED, manager.state.value.keychainLinkState)

        // Simulate a dormant BLE manager firing a Disconnected event
        manager.handleHardwareEvent(HardwareEvent.Disconnected("Dormant BLE disconnected"))
        testScheduler.advanceTimeBy(100)

        // State must remain CONNECTED because physical mode ignores dormant BLE link events
        assertEquals("Dormant BLE events must not overwrite physical SPP connection state",
            KeychainLinkState.CONNECTED, manager.state.value.keychainLinkState)
    }

    @Test
    fun test7_reconnectDeviceAndDisconnectDeviceInvokeBtAudioReceiver() = runTest {
        val manager = createManager(backgroundScope, isSimulated = false)
        testScheduler.advanceTimeBy(100)

        manager.reconnectDevice("11:22:33:44:55:66")
        assertEquals("11:22:33:44:55:66", fakeBtAudioReceiver.connectCalledWith)

        manager.disconnectDevice()
        assertEquals(1, fakeBtAudioReceiver.disconnectCalledCount)
    }

    @Test
    fun test8_phoneMicFallbackDoesNotReportEsp32Connected() = runTest {
        val manager = createManager(backgroundScope, isSimulated = false)
        testScheduler.advanceTimeBy(100)

        // SPP is disconnected
        fakeBtAudioReceiver.emitTelemetry(
            BtAudioTelemetry(state = BtAudioConnectionState.DISCONNECTED)
        )
        testScheduler.advanceTimeBy(100)

        // Audio pipeline is active (capturing from phone mic)
        mockAudioPipeline.startCapture(backgroundScope)
        testScheduler.advanceTimeBy(100)

        // Emergency state must honestly report keychain as DISCONNECTED
        assertEquals(KeychainLinkState.DISCONNECTED, manager.state.value.keychainLinkState)
        assertEquals("DISCONNECTED", manager.state.value.btClassicState)
    }
}

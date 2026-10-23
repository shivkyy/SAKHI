package com.example.sakhi

import android.app.Notification
import com.example.sakhi.core.model.ContactAlertState
import com.example.sakhi.core.model.EmergencyState
import com.example.sakhi.core.model.SessionState
import com.example.sakhi.data.bluetooth.BtAudioConnectionState
import com.example.sakhi.data.bluetooth.BtAudioTelemetry
import com.example.sakhi.service.EmergencyForegroundService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationCleanupAndDeduplicationTest {

    private class TestableEmergencyForegroundService : EmergencyForegroundService() {
        var notifyCount = 0
        var lastNotifiedNotification: Notification? = null

        override fun updateNotification(notification: Notification, newText: String) {
            if (newText == lastDisplayedText) {
                return
            }
            lastDisplayedText = newText
            notifyCount++
            lastNotifiedNotification = notification
        }
    }

    // 1. Verify monitoring status text formatting and removal of attempt-counter churn
    @Test
    fun testMonitoringStatusText_noReconnectCounterChurn() {
        val service = TestableEmergencyForegroundService()

        // Connected / Streaming
        val connectedTelemetry = BtAudioTelemetry(
            state = BtAudioConnectionState.CONNECTED,
            deviceName = "SAKHI_AUDIO"
        )
        assertEquals(
            "Keychain Connected (SAKHI_AUDIO) • Double-press for SOS",
            service.getMonitoringStatusText(connectedTelemetry)
        )

        val streamingTelemetry = BtAudioTelemetry(
            state = BtAudioConnectionState.STREAMING,
            deviceName = "SAKHI_AUDIO",
            totalBytesReceived = 100000L,
            currentRms = 1250f
        )
        assertEquals(
            "Keychain Connected (SAKHI_AUDIO) • Double-press for SOS",
            service.getMonitoringStatusText(streamingTelemetry)
        )

        // Reconnecting attempts must NOT churn status text with attempt numbers
        val reconnectingAttempt1 = BtAudioTelemetry(
            state = BtAudioConnectionState.CONNECTING,
            isAutoReconnecting = true,
            reconnectAttempt = 1
        )
        val reconnectingAttempt5 = BtAudioTelemetry(
            state = BtAudioConnectionState.CONNECTING,
            isAutoReconnecting = true,
            reconnectAttempt = 5
        )
        assertEquals("Reconnecting to Keychain...", service.getMonitoringStatusText(reconnectingAttempt1))
        assertEquals("Reconnecting to Keychain...", service.getMonitoringStatusText(reconnectingAttempt5))
        assertEquals(
            service.getMonitoringStatusText(reconnectingAttempt1),
            service.getMonitoringStatusText(reconnectingAttempt5)
        )

        // Initial connection
        val initialConnecting = BtAudioTelemetry(
            state = BtAudioConnectionState.CONNECTING,
            isAutoReconnecting = false
        )
        assertEquals("Connecting to SAKHI_AUDIO keychain...", service.getMonitoringStatusText(initialConnecting))

        // Disconnected
        val disconnected = BtAudioTelemetry(state = BtAudioConnectionState.DISCONNECTED)
        assertEquals("Keychain Offline • Safety monitoring active", service.getMonitoringStatusText(disconnected))

        // Error
        val error = BtAudioTelemetry(state = BtAudioConnectionState.ERROR)
        assertEquals("Keychain Disconnected • Tap to open SAKHI", service.getMonitoringStatusText(error))
    }

    // 2. Verify emergency status text formatting
    @Test
    fun testEmergencyStatusText_formatting() {
        val service = TestableEmergencyForegroundService()

        // Countdown active
        val countdownState = EmergencyState(
            sessionState = SessionState.ACTIVATING,
            contactAlertState = ContactAlertState.COUNTDOWN,
            countdownRemainingSeconds = 7
        )
        assertEquals(
            "Alert sending in 7s • Tap Cancel to abort",
            service.getEmergencyStatusText(countdownState)
        )

        // Dispatching SMS
        val sendingState = EmergencyState(
            sessionState = SessionState.ACTIVE,
            contactAlertState = ContactAlertState.SENDING
        )
        assertEquals(
            "Dispatching SMS alerts to contacts...",
            service.getEmergencyStatusText(sendingState)
        )

        // Alert sent & live tracking
        val sentState = EmergencyState(
            sessionState = SessionState.ACTIVE,
            contactAlertState = ContactAlertState.SENT
        )
        assertEquals(
            "Alert Sent to Contacts • Live Tracking Active",
            service.getEmergencyStatusText(sentState)
        )

        // Fallback active session
        val otherState = EmergencyState(
            sessionState = SessionState.ACTIVE,
            contactAlertState = ContactAlertState.NOT_STARTED
        )
        assertEquals(
            "Emergency session active. Monitoring safety.",
            service.getEmergencyStatusText(otherState)
        )
    }

    // 3. Verify deduplication logic suppresses duplicate notification updates
    @Test
    fun testUpdateNotification_suppressesDuplicates() {
        val service = TestableEmergencyForegroundService()
        val dummyNotification = Notification()

        assertEquals(0, service.notifyCount)
        assertNull(service.lastDisplayedText)

        // First update
        service.updateNotification(dummyNotification, "Keychain Connected (SAKHI_AUDIO) • Double-press for SOS")
        assertEquals(1, service.notifyCount)
        assertEquals("Keychain Connected (SAKHI_AUDIO) • Double-press for SOS", service.lastDisplayedText)

        // Identical updates must be skipped
        service.updateNotification(dummyNotification, "Keychain Connected (SAKHI_AUDIO) • Double-press for SOS")
        service.updateNotification(dummyNotification, "Keychain Connected (SAKHI_AUDIO) • Double-press for SOS")
        service.updateNotification(dummyNotification, "Keychain Connected (SAKHI_AUDIO) • Double-press for SOS")
        assertEquals(1, service.notifyCount)

        // State changes to Reconnecting
        service.updateNotification(dummyNotification, "Reconnecting to Keychain...")
        assertEquals(2, service.notifyCount)
        assertEquals("Reconnecting to Keychain...", service.lastDisplayedText)

        // Repeated reconnect retry ticks must be skipped
        service.updateNotification(dummyNotification, "Reconnecting to Keychain...")
        service.updateNotification(dummyNotification, "Reconnecting to Keychain...")
        assertEquals(2, service.notifyCount)
    }

    // 4. Verify telemetry stream (RMS, byte counts) does not cause notification churn
    @Test
    fun testTelemetryAudioFrameStreaming_zeroNotificationChurn() = runTest(UnconfinedTestDispatcher()) {
        val service = TestableEmergencyForegroundService()
        val dummyNotification = Notification()
        val telemetryFlow = MutableStateFlow(BtAudioTelemetry())

        var emissionsObserved = 0
        val job = launch {
            telemetryFlow
                .distinctUntilChangedBy { telemetry ->
                    Triple(telemetry.state, telemetry.deviceName, telemetry.isAutoReconnecting)
                }
                .collect { telemetry ->
                    emissionsObserved++
                    val text = service.getMonitoringStatusText(telemetry)
                    service.updateNotification(dummyNotification, text)
                }
        }

        // Initial default state
        assertEquals(1, emissionsObserved)
        assertEquals(1, service.notifyCount)

        // Device connects
        telemetryFlow.value = BtAudioTelemetry(
            state = BtAudioConnectionState.CONNECTED,
            deviceName = "SAKHI_AUDIO"
        )
        assertEquals(2, emissionsObserved)
        assertEquals(2, service.notifyCount)

        // Stream 100 audio chunks with fluctuating RMS and bytes
        for (i in 1..100) {
            telemetryFlow.value = BtAudioTelemetry(
                state = BtAudioConnectionState.STREAMING,
                deviceName = "SAKHI_AUDIO",
                totalBytesReceived = i * 640L,
                currentRms = 100f + (i % 20) * 50f
            )
        }

        // Stream state transitioned from CONNECTED to STREAMING once
        // Both map to identical status text, so notifyCount remains stable
        assertEquals(3, emissionsObserved)
        assertEquals(2, service.notifyCount) // Deduplicated at text level

        // Further frames do not trigger flow emissions
        for (i in 101..200) {
            telemetryFlow.value = BtAudioTelemetry(
                state = BtAudioConnectionState.STREAMING,
                deviceName = "SAKHI_AUDIO",
                totalBytesReceived = i * 640L,
                currentRms = 200f + (i % 10) * 10f
            )
        }

        // Emissions strictly kept at 3, notifyCount strictly 2
        assertEquals(3, emissionsObserved)
        assertEquals(2, service.notifyCount)

        job.cancel()
    }

    // 5. Verify emergency state updates during live tracking (GPS coordinate fixes) do not churn notification
    @Test
    fun testEmergencyStateGpsFixes_zeroNotificationChurn() = runTest(UnconfinedTestDispatcher()) {
        val service = TestableEmergencyForegroundService()
        val dummyNotification = Notification()
        val emergencyFlow = MutableStateFlow(
            EmergencyState(
                sessionState = SessionState.ACTIVE,
                contactAlertState = ContactAlertState.SENT
            )
        )

        var flowEmissions = 0
        val job = launch {
            emergencyFlow
                .distinctUntilChangedBy { state ->
                    listOf(
                        state.isEmergencyActive,
                        state.isCountdownActive,
                        state.countdownRemainingSeconds,
                        state.contactAlertState
                    )
                }
                .collect { state ->
                    flowEmissions++
                    val text = service.getEmergencyStatusText(state)
                    service.updateNotification(dummyNotification, text)
                }
        }

        assertEquals(1, flowEmissions)
        assertEquals(1, service.notifyCount)
        assertEquals("Alert Sent to Contacts • Live Tracking Active", service.lastDisplayedText)

        // Simulate 50 incoming GPS coordinate updates and battery updates during active tracking
        for (i in 1..50) {
            emergencyFlow.value = emergencyFlow.value.copy(
                latitude = 28.6139 + (i * 0.0001),
                longitude = 77.2090 + (i * 0.0001),
                locationAccuracy = 5.0f,
                deviceBatteryPercent = 80 - (i / 10)
            )
        }

        // GPS updates must NOT cause flow emissions or notification re-posts
        assertEquals(1, flowEmissions)
        assertEquals(1, service.notifyCount)

        job.cancel()
    }
}

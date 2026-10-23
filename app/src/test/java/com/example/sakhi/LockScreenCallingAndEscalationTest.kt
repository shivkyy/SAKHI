package com.example.sakhi

import android.app.Notification
import com.example.sakhi.core.model.ContactAlertState
import com.example.sakhi.core.model.EmergencyState
import com.example.sakhi.core.model.PoliceState
import com.example.sakhi.core.model.SessionState
import com.example.sakhi.domain.escalation.PoliceCallManager
import com.example.sakhi.service.EmergencyForegroundService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockScreenCallingAndEscalationTest {

    // 1. Direct calling on unlocked device uses performDirectCall (ACTION_CALL)
    @Test
    fun testUnlockedDevice_usesDirectCall() = runTest {
        var directCallInvokedWith: String? = null
        var telecomInvoked = false
        var dialerFallbackInvoked = false

        val manager = object : PoliceCallManager(null) {
            override fun hasCallPhonePermission(): Boolean = true
            override fun isDeviceLocked(): Boolean = false
            override fun performDirectCall(cleanedNumber: String): Boolean {
                directCallInvokedWith = cleanedNumber
                return true
            }
            override fun performTelecomPlaceCall(cleanedNumber: String): Boolean {
                telecomInvoked = true
                return true
            }
            override fun performDialerFallback(cleanedNumber: String): Boolean {
                dialerFallbackInvoked = true
                return true
            }
        }

        val state = manager.initiateEmergencyCall("+919876543210")
        assertEquals(PoliceState.CALL_INITIATED, state)
        assertEquals("+919876543210", directCallInvokedWith)
        assertFalse(telecomInvoked)
        assertFalse(dialerFallbackInvoked)
        assertEquals(android.content.Intent.ACTION_CALL, manager.lastCallAction)
    }

    // 2. Direct calling on locked device routes to TelecomManager.placeCall (bypassing BAL)
    @Test
    fun testLockedDevice_routesToTelecomPlaceCall() = runTest {
        var telecomInvokedWith: String? = null
        var dialerFallbackInvoked = false

        val manager = object : PoliceCallManager(null) {
            override fun hasCallPhonePermission(): Boolean = true
            override fun isDeviceLocked(): Boolean = true
            // performDirectCall should throw because device is locked
            override fun performTelecomPlaceCall(cleanedNumber: String): Boolean {
                telecomInvokedWith = cleanedNumber
                return true
            }
            override fun performDialerFallback(cleanedNumber: String): Boolean {
                dialerFallbackInvoked = true
                return true
            }
        }

        val state = manager.initiateEmergencyCall("+919876543210")
        assertEquals(PoliceState.CALL_INITIATED, state)
        assertEquals("+919876543210", telecomInvokedWith)
        assertFalse(dialerFallbackInvoked)
        assertEquals(android.content.Intent.ACTION_CALL, manager.lastCallAction)
    }

    // 3. Recognized emergency helpline numbers (112, 911, 100) route to dialer fallback
    @Test
    fun testEmergencyHelpline_routesToDialerFallback() = runTest {
        var directCallInvoked = false
        var telecomInvoked = false
        var dialerInvokedWith: String? = null

        val manager = object : PoliceCallManager(null) {
            override fun hasCallPhonePermission(): Boolean = true
            override fun isDeviceLocked(): Boolean = true
            override fun performDirectCall(cleanedNumber: String): Boolean {
                directCallInvoked = true
                return true
            }
            override fun performTelecomPlaceCall(cleanedNumber: String): Boolean {
                telecomInvoked = true
                return true
            }
            override fun performDialerFallback(cleanedNumber: String): Boolean {
                dialerInvokedWith = cleanedNumber
                return true
            }
        }

        // Test with 112
        val state112 = manager.initiateEmergencyCall("112")
        assertEquals(PoliceState.CALL_INITIATED, state112)
        assertEquals("112", dialerInvokedWith)
        assertFalse(directCallInvoked)
        assertFalse(telecomInvoked)
        assertEquals(android.content.Intent.ACTION_DIAL, manager.lastCallAction)

        // Test with 911
        manager.resetState()
        val state911 = manager.initiateEmergencyCall("911")
        assertEquals(PoliceState.CALL_INITIATED, state911)
        assertEquals("911", dialerInvokedWith)
        assertEquals(android.content.Intent.ACTION_DIAL, manager.lastCallAction)
    }

    // 4. Missing CALL_PHONE permission routes safely to dialer fallback
    @Test
    fun testMissingCallPhonePermission_routesToDialerFallback() = runTest {
        var dialerInvokedWith: String? = null

        val manager = object : PoliceCallManager(null) {
            override fun hasCallPhonePermission(): Boolean = false
            override fun performDialerFallback(cleanedNumber: String): Boolean {
                dialerInvokedWith = cleanedNumber
                return true
            }
        }

        val state = manager.initiateEmergencyCall("+919876543210")
        assertEquals(PoliceState.CALL_INITIATED, state)
        assertEquals("+919876543210", dialerInvokedWith)
        assertEquals(android.content.Intent.ACTION_DIAL, manager.lastCallAction)
    }

    // 5. Total failure handling (telecom and dialer fail) transitions safely to FAILED
    @Test
    fun testTotalCallFailure_transitionsToFailedWithoutThrowing() = runTest {
        val manager = object : PoliceCallManager(null) {
            override fun hasCallPhonePermission(): Boolean = true
            override fun isDeviceLocked(): Boolean = true
            override fun performTelecomPlaceCall(cleanedNumber: String): Boolean = false
            override fun performDialerFallback(cleanedNumber: String): Boolean = false
        }

        val state = manager.initiateEmergencyCall("+919876543210")
        assertEquals(PoliceState.FAILED, state)
        assertEquals(PoliceState.FAILED, manager.policeState.value)
    }

    // 6. Emergency notification status text displays configured escalation number when call is requested
    @Test
    fun testEmergencyNotification_displaysEscalationTarget() {
        val service = object : EmergencyForegroundService() {}

        val emergencyState = EmergencyState(
            sessionState = SessionState.ACTIVE,
            contactAlertState = ContactAlertState.SENT,
            policeState = PoliceState.REQUESTED,
            escalationDestinationNumber = "+919988776655"
        )

        val statusText = service.getEmergencyStatusText(emergencyState)
        assertEquals("Emergency Call: +919988776655 • Tap to connect", statusText)
    }
}

package com.example.sakhi

import com.example.sakhi.core.gesture.DoublePressGestureDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the deterministic double-press SOS activation state machine.
 *
 * Validates:
 * 1. Single press does not activate SOS and arms the confirmation window.
 * 2. Double-press within 1000 ms triggers activation exactly once.
 * 3. Second press after 1000 ms timeout does not activate and begins a new window.
 * 4. Long press/hold (> 500 ms) is rejected and does not activate.
 * 5. Duplicate triggers are prevented after activation.
 * 6. Presses are completely ignored while SOS is activating or active.
 * 7. Explicit reset clears any pending confirmation window.
 */
class DoublePressSosGestureTest {

    private var virtualTimeMs: Long = 1000L
    private lateinit var detector: DoublePressGestureDetector

    @Before
    fun setUp() {
        virtualTimeMs = 1000L
        detector = DoublePressGestureDetector(
            confirmationWindowMs = 1000L,
            maxHoldDurationMs = 500L,
            timeProvider = { virtualTimeMs }
        )
    }

    @Test
    fun test1_singlePressArmsWindowAndDoesNotActivate() {
        assertFalse("Detector must start idle", detector.isArmed)
        assertFalse("Window must not be active initially", detector.isWindowActive())

        val activated = detector.recordPress(isEmergencyActive = false, pressDurationMs = 120L)

        assertFalse("Single press must NOT trigger SOS activation", activated)
        assertTrue("Detector must be armed after first distinct press", detector.isArmed)
        assertTrue("Confirmation window must be active", detector.isWindowActive())
    }

    @Test
    fun test2_doublePressWithin1000msActivatesExactlyOnce() {
        // First press at t=1000
        val firstPressResult = detector.recordPress(isEmergencyActive = false, pressDurationMs = 100L)
        assertFalse("First press must not trigger SOS", firstPressResult)
        assertTrue("Confirmation window must be open", detector.isWindowActive())

        // Advance virtual time by 450 ms (t=1450)
        virtualTimeMs += 450L

        // Second press at t=1450
        val secondPressResult = detector.recordPress(isEmergencyActive = false, pressDurationMs = 100L)
        assertTrue("Second press within 1000 ms MUST trigger SOS activation", secondPressResult)
        assertFalse("Detector must immediately reset after triggering to prevent duplicates", detector.isArmed)
        assertFalse("Confirmation window must be closed after activation", detector.isWindowActive())
    }

    @Test
    fun test3_secondPressAfterTimeoutDoesNotActivateAndStartsNewWindow() {
        // First press at t=1000
        detector.recordPress(isEmergencyActive = false, pressDurationMs = 80L)
        assertTrue("Detector must be armed", detector.isArmed)

        // Advance virtual time past the 1000 ms window (t=2050, elapsed=1050ms)
        virtualTimeMs += 1050L
        assertFalse("Window must have expired", detector.isWindowActive())

        // Second press after timeout
        val secondPressResult = detector.recordPress(isEmergencyActive = false, pressDurationMs = 90L)
        assertFalse("Second press after 1000 ms timeout must NOT trigger SOS", secondPressResult)
        assertTrue("Second press after timeout must start a new armed confirmation window", detector.isArmed)
        assertTrue("New window must now be active", detector.isWindowActive())

        // Quick follow-up press within 300 ms (t=2350) of the new window
        virtualTimeMs += 300L
        val thirdPressResult = detector.recordPress(isEmergencyActive = false, pressDurationMs = 90L)
        assertTrue("Follow-up press within new window must trigger SOS", thirdPressResult)
    }

    @Test
    fun test4_longHoldExceedingMaxDurationIsRejected() {
        // Hold lasting 600 ms (> 500 ms threshold)
        val holdResult = detector.recordPress(isEmergencyActive = false, pressDurationMs = 600L)

        assertFalse("Continuous long hold must NOT trigger SOS", holdResult)
        assertFalse("Continuous long hold must NOT arm the double-press window", detector.isArmed)
        assertFalse("Window must remain inactive", detector.isWindowActive())
    }

    @Test
    fun test5_longHoldCancelsPreviouslyArmedWindow() {
        // Valid first tap
        detector.recordPress(isEmergencyActive = false, pressDurationMs = 100L)
        assertTrue("Must be armed after tap", detector.isArmed)

        // Advance 200 ms and perform an accidental long hold
        virtualTimeMs += 200L
        val holdResult = detector.recordPress(isEmergencyActive = false, pressDurationMs = 700L)

        assertFalse("Long hold must not trigger SOS", holdResult)
        assertFalse("Long hold must cancel the armed window", detector.isArmed)
    }

    @Test
    fun test6_duplicateTriggersPreventedAfterActivation() {
        // First press at t=1000
        detector.recordPress(isEmergencyActive = false, pressDurationMs = 100L)

        // Second press at t=1300 triggers activation
        virtualTimeMs += 300L
        val activated = detector.recordPress(isEmergencyActive = false, pressDurationMs = 100L)
        assertTrue("Must activate", activated)

        // Immediate third tap at t=1350 before state change propagates
        virtualTimeMs += 50L
        val thirdPressResult = detector.recordPress(isEmergencyActive = false, pressDurationMs = 100L)
        assertFalse("Third press immediately after trigger must NOT duplicate-trigger SOS", thirdPressResult)
        assertTrue("Third press simply starts a new candidate window", detector.isArmed)
    }

    @Test
    fun test7_pressesIgnoredWhenEmergencyIsActiveOrActivating() {
        // Case A: Emergency is actively running
        val pressWhileActive = detector.recordPress(isEmergencyActive = true, pressDurationMs = 100L)
        assertFalse("Presses while emergency is active must be ignored", pressWhileActive)
        assertFalse("Must not arm while emergency is active", detector.isArmed)

        // Case B: Window was armed, but emergency became active externally
        detector.recordPress(isEmergencyActive = false, pressDurationMs = 100L)
        assertTrue(detector.isArmed)

        virtualTimeMs += 200L
        val secondPressWhileActive = detector.recordPress(isEmergencyActive = true, pressDurationMs = 100L)
        assertFalse("Press while emergency is active must not trigger", secondPressWhileActive)
        assertFalse("Detector must be reset", detector.isArmed)
    }

    @Test
    fun test8_resetClearsPendingConfirmationWindow() {
        detector.recordPress(isEmergencyActive = false, pressDurationMs = 100L)
        assertTrue("Must be armed", detector.isArmed)
        assertTrue("Window must be active", detector.isWindowActive())

        detector.reset()

        assertFalse("Must not be armed after reset", detector.isArmed)
        assertFalse("Window must not be active after reset", detector.isWindowActive())

        // Next press should act as press 1, not press 2
        virtualTimeMs += 200L
        val result = detector.recordPress(isEmergencyActive = false, pressDurationMs = 100L)
        assertFalse("Must not activate as second press", result)
        assertTrue("Must be armed as new first press", detector.isArmed)
    }
}

package com.example.sakhi.core.gesture

/**
 * Deterministic, clock-injectable state machine for double-press emergency activation.
 *
 * Rules:
 * 1. First distinct press arms a 1000 ms confirmation window.
 * 2. A second distinct press within that window triggers SOS.
 * 3. A single press must not trigger SOS.
 * 4. A continuous long hold must not count as two presses.
 * 5. Expire the first press after 1000 ms; subsequent press starts a new window.
 * 6. Prevent duplicate triggers from the same double-press.
 * 7. Ignore additional presses while SOS is activating or active.
 */
class DoublePressGestureDetector(
    val confirmationWindowMs: Long = DEFAULT_CONFIRMATION_WINDOW_MS,
    val maxHoldDurationMs: Long = DEFAULT_MAX_HOLD_DURATION_MS,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val DEFAULT_CONFIRMATION_WINDOW_MS = 1000L
        const val DEFAULT_MAX_HOLD_DURATION_MS = 500L
    }

    var isArmed: Boolean = false
        private set

    var armedTimestamp: Long = 0L
        private set

    /**
     * Records a press event.
     *
     * @param isEmergencyActive true if an emergency session is already active or in countdown
     * @param pressDurationMs duration for which the button was held before release (used to reject long presses)
     * @return true if this press successfully satisfied the double-press condition and activates SOS
     */
    fun recordPress(isEmergencyActive: Boolean, pressDurationMs: Long = 0L): Boolean {
        // Rule 7: Ignore presses if already activating or active
        if (isEmergencyActive) {
            reset()
            return false
        }

        // Rule 4: A continuous long hold is rejected and cancels any armed state
        if (pressDurationMs > maxHoldDurationMs) {
            reset()
            return false
        }

        val now = timeProvider()

        if (isArmed) {
            val elapsed = now - armedTimestamp
            // Rule 2: A second distinct press within the window triggers SOS
            if (elapsed in 1..confirmationWindowMs) {
                // Rule 6: Reset immediately so subsequent presses cannot duplicate-trigger
                reset()
                return true
            } else {
                // Rule 5: Confirmation window expired (> 1000 ms); start a new window with this press
                armedTimestamp = now
                isArmed = true
                return false
            }
        } else {
            // Rule 1: First distinct press arms the window
            // Rule 3: Single press does not trigger SOS
            isArmed = true
            armedTimestamp = now
            return false
        }
    }

    /**
     * Returns true if the confirmation window is currently open at [currentTime].
     */
    fun isWindowActive(currentTime: Long = timeProvider()): Boolean {
        if (!isArmed) return false
        val elapsed = currentTime - armedTimestamp
        return elapsed in 0..confirmationWindowMs
    }

    /**
     * Resets the gesture detector to idle state.
     */
    fun reset() {
        isArmed = false
        armedTimestamp = 0L
    }
}

package com.example.sakhi.domain.emergency

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages the emergency SOS countdown ticker before contacts are alerted.
 *
 * Supports pausing and resuming:
 * - Pauses countdown when physical button is pressed and held during countdown.
 * - Resumes from the exact remaining time if the button is released before 3 seconds.
 * - Prevents alert dispatch while hold-to-cancel is evaluated.
 */
class CountdownController(
    private val scope: CoroutineScope
) {
    private var countdownJob: Job? = null

    var isRunning: Boolean = false
        private set

    var isPaused: Boolean = false
        private set

    var remainingSeconds: Int = 0
        private set

    private var tickCallback: ((secondsRemaining: Int) -> Unit)? = null
    private var finishCallback: (suspend () -> Unit)? = null

    fun startCountdown(
        totalSeconds: Int,
        onTick: (secondsRemaining: Int) -> Unit,
        onFinished: suspend () -> Unit
    ): Boolean {
        // Duplicate-activation prevention: if already running (and not paused), do not restart
        if (isRunning && !isPaused && countdownJob?.isActive == true) {
            return false
        }

        stopCountdown()
        isRunning = true
        isPaused = false
        remainingSeconds = totalSeconds
        tickCallback = onTick
        finishCallback = onFinished

        launchCountdownLoop(remainingSeconds)
        return true
    }

    /**
     * Pauses the countdown ticker.
     * Prevents expiry and alert dispatch while hold-to-cancel is evaluated.
     */
    fun pauseCountdown(): Boolean {
        if (!isRunning || isPaused) return false
        isPaused = true
        countdownJob?.cancel()
        countdownJob = null
        return true
    }

    /**
     * Resumes the countdown ticker from the remaining seconds.
     * Does NOT restart from totalSeconds.
     */
    fun resumeCountdown(): Boolean {
        if (!isRunning || !isPaused) return false
        isPaused = false
        launchCountdownLoop(remainingSeconds)
        return true
    }

    private fun launchCountdownLoop(startSeconds: Int) {
        countdownJob = scope.launch {
            var remaining = startSeconds
            remainingSeconds = remaining
            tickCallback?.invoke(remaining)

            while (isActive && remaining > 0) {
                delay(1000L)
                if (!isActive) break
                remaining--
                remainingSeconds = remaining
                tickCallback?.invoke(remaining)
            }

            if (isActive && remaining == 0) {
                isRunning = false
                isPaused = false
                finishCallback?.invoke()
            }
        }
    }

    fun stopCountdown() {
        isRunning = false
        isPaused = false
        remainingSeconds = 0
        countdownJob?.cancel()
        countdownJob = null
        tickCallback = null
        finishCallback = null
    }
}

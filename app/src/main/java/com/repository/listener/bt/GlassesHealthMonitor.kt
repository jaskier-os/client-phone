package com.repository.listener.bt

import android.os.Handler
import android.os.Looper
import com.repository.listener.util.LogCollector

/**
 * Monitors glasses listener health via BT ping/pong.
 * Sends periodic pings; if no pong within timeout, triggers onListenerDead
 * so the phone can restart the glasses app via appOpen.
 */
class GlassesHealthMonitor(
    private val phoneBtHost: PhoneBtHost,
    private val onListenerDead: () -> Unit
) {

    companion object {
        private const val TAG = "GlassesHealthMonitor"
        private const val PING_INTERVAL_MS = 60_000L
        private const val PONG_TIMEOUT_MS = 3_000L
        private const val RETRY_DELAY_MS = 5_000L
        private const val MAX_RETRIES = 2
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var running = false
    @Volatile
    private var awaitingPong = false
    private var retryCount = 0
    private var recoveryAttemptedThisCycle = false

    /** Whether the glasses app responded to the last ping. Used for fast-path activation. */
    @Volatile
    var isResponsive = false

    private val pingRunnable = Runnable { sendPing() }

    private val pongTimeoutRunnable = Runnable {
        if (!running || !awaitingPong) return@Runnable
        awaitingPong = false
        isResponsive = false
        retryCount++
        LogCollector.w(TAG, "No pong received (attempt $retryCount/$MAX_RETRIES)")

        if (retryCount > MAX_RETRIES) {
            LogCollector.w(TAG, "Max retries exceeded, giving up until next cycle")
            retryCount = 0
            schedulePing()
            return@Runnable
        }

        // Only trigger app recovery once per cycle to avoid repeatedly waking the glasses screen
        if (!recoveryAttemptedThisCycle) {
            recoveryAttemptedThisCycle = true
            LogCollector.i(TAG, "Glasses listener unresponsive, requesting app launch")
            onListenerDead.invoke()
        }

        // Wait then retry ping
        handler.postDelayed(Runnable { sendPing() }, RETRY_DELAY_MS)
    }

    fun start() {
        if (running) return
        running = true
        isResponsive = false
        retryCount = 0
        recoveryAttemptedThisCycle = false
        LogCollector.i(TAG, "Health monitor started")
        sendPing()
        schedulePing()
    }

    fun stop() {
        if (!running) return
        running = false
        isResponsive = false
        awaitingPong = false
        retryCount = 0
        recoveryAttemptedThisCycle = false
        handler.removeCallbacks(pingRunnable)
        handler.removeCallbacks(pongTimeoutRunnable)
        LogCollector.i(TAG, "Health monitor stopped")
    }

    fun onPongReceived() {
        if (!awaitingPong) return
        awaitingPong = false
        isResponsive = true
        retryCount = 0
        recoveryAttemptedThisCycle = false
        handler.removeCallbacks(pongTimeoutRunnable)
        LogCollector.d(TAG, "Pong received - glasses listener alive")
    }

    private fun sendPing() {
        if (!running) return
        awaitingPong = true
        phoneBtHost.sendHealthPing()
        handler.postDelayed(pongTimeoutRunnable, PONG_TIMEOUT_MS)
    }

    private fun schedulePing() {
        handler.removeCallbacks(pingRunnable)
        handler.postDelayed(pingRunnable, PING_INTERVAL_MS)
    }
}

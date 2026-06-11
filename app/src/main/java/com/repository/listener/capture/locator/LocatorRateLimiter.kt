package com.repository.listener.capture.locator

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide rate limiter for the Yandex Locator API.
 *
 * Enforces a minimum spacing between consecutive calls so the phone never
 * exceeds LOCATOR_MAX_RPS requests per second, even when multiple coroutines
 * fire concurrently. The ceiling is stricter than Yandex's documented 50 RPS
 * limit -- we keep our own budget at 20 RPS so retries and simultaneous
 * callers share headroom without tripping 429 errors.
 *
 * Implementation: single AtomicLong carries the next timestamp (ms since
 * epoch) at which a call is allowed. Each caller CAS-advances that timestamp
 * by INTERVAL_MS and then suspends (via kotlinx delay) until its reserved
 * slot arrives. Because every acquired slot is spaced exactly INTERVAL_MS
 * from the previous, the number of calls in any 1000 ms sliding window is
 * bounded above by LOCATOR_MAX_RPS.
 */
object LocatorRateLimiter {

    const val LOCATOR_MAX_RPS = 20
    private const val INTERVAL_MS = 1000L / LOCATOR_MAX_RPS  // 50 ms

    private val nextAllowedAtMs = AtomicLong(0L)

    /**
     * Suspend until a rate-limit slot is available. Returns the number of
     * milliseconds the caller was forced to wait (0 if it was allowed
     * immediately). Useful for logging.
     */
    suspend fun acquire(): Long {
        while (true) {
            val now = System.currentTimeMillis()
            val prev = nextAllowedAtMs.get()
            val target = if (prev < now) now else prev
            val newNext = target + INTERVAL_MS
            if (nextAllowedAtMs.compareAndSet(prev, newNext)) {
                val wait = target - now
                if (wait > 0L) delay(wait)
                return wait
            }
            // CAS lost -- another coroutine advanced the counter, retry.
        }
    }

    /**
     * Reset the limiter state. Only for tests -- never call in production code.
     */
    fun resetForTest() {
        nextAllowedAtMs.set(0L)
    }
}

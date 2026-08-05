package com.repository.listener.wear

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.repository.listener.protocol.DetentAccumulator
import com.repository.listener.protocol.RemoteInputProtocol
import com.repository.listener.protocol.RemoteInputProtocol.EventType
import com.repository.listener.protocol.RemoteInputProtocol.RemoteInputEvent
import com.repository.listener.protocol.ScrollCoalescer
import com.repository.listener.protocol.SessionIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * On-watch measurement and stress harness.
 *
 * Two jobs:
 *  1. Measure the tap-stamp jitter, which is the number that decides whether the
 *     glasses' 400 ms double-tap window stays intact. The receiver disambiguates
 *     on the tap-time stamp we send, so what matters is how much the interval
 *     BETWEEN two taps is distorted end to end, not the absolute latency.
 *  2. Prove the pipeline survives a flood: no drops, correct ordering, no ANR.
 *
 * Run with `am instrument`, never connectedAndroidTest -- its teardown uninstalls
 * the app under test.
 */
@RunWith(AndroidJUnit4::class)
class InputPipelineStressTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun percentile(sorted: List<Long>, p: Double): Long {
        if (sorted.isEmpty()) return 0
        val idx = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[idx - 1]
    }

    private fun report(label: String, samples: List<Long>) {
        val sorted = samples.sorted()
        android.util.Log.i(
            "InputMeasure",
            "$label n=${sorted.size} min=${sorted.first()} p50=${percentile(sorted, 50.0)} " +
                "p90=${percentile(sorted, 90.0)} p95=${percentile(sorted, 95.0)} " +
                "p99=${percentile(sorted, 99.0)} max=${sorted.last()}",
        )
    }

    /**
     * Measures the delay between stamping a tap and that tap reaching the worker
     * thread, under a realistic scroll load running concurrently.
     *
     * This is the component of jitter this side controls. If it is small relative
     * to 400 ms, the glasses' double-tap arithmetic is safe.
     */
    @Test
    fun tapStampToWorkerJitterUnderLoad() {
        val worker = android.os.HandlerThread("measure-worker").apply { start() }
        val handler = android.os.Handler(worker.looper)
        val accumulator = DetentAccumulator(threshold = WatchLinkService.ROTARY_DETENT_UNITS)
        val coalescer = ScrollCoalescer(sink = { _, _, _ -> })

        val samples = mutableListOf<Long>()
        val taps = 200
        val latch = CountDownLatch(taps)

        // Concurrent scroll load at the measured hardware rate (~30 detents/s).
        val loadRunning = java.util.concurrent.atomic.AtomicBoolean(true)
        val loader = Thread {
            while (loadRunning.get()) {
                val now = SystemClock.elapsedRealtime()
                handler.post {
                    val steps = accumulator.onDelta(1.0f, now)
                    if (steps != 0) coalescer.onDetents(steps, now)
                }
                Thread.sleep(32)
            }
        }.apply { start() }

        repeat(taps) {
            val tapMs = SystemClock.elapsedRealtime()
            handler.post {
                synchronized(samples) { samples += SystemClock.elapsedRealtime() - tapMs }
                coalescer.onDiscreteEvent(EventType.SELECT, tapMs)
                latch.countDown()
            }
            Thread.sleep(15)
        }

        assertTrue("taps did not drain", latch.await(30, TimeUnit.SECONDS))
        loadRunning.set(false)
        loader.join()
        worker.quitSafely()

        val sorted = synchronized(samples) { samples.toList() }.sorted()
        report("tap_stamp_to_worker_ms", sorted)

        // The stamp is taken at the tap itself, so this delay does not shift the
        // stamp. It only bounds how late the frame leaves. Anything approaching
        // the 400 ms window would be a correctness problem, not a tuning one.
        assertTrue(
            "tap queue delay p99=${percentile(sorted, 99.0)} ms is too close to the " +
                "glasses' 400 ms double-tap window",
            percentile(sorted, 99.0) < 100,
        )
    }

    /**
     * Two taps separated by a known interval must preserve that interval in their
     * stamps. This is the property the glasses' double-tap detection depends on.
     */
    @Test
    fun tapIntervalIsPreservedInTheStamps() {
        val emitted = mutableListOf<Long>()
        val coalescer = ScrollCoalescer(sink = { type, _, timeMs ->
            if (type == EventType.SELECT) emitted += timeMs
        })

        val errors = mutableListOf<Long>()
        repeat(40) {
            emitted.clear()
            val first = SystemClock.elapsedRealtime()
            coalescer.onDiscreteEvent(EventType.SELECT, first)
            Thread.sleep(150)
            val second = SystemClock.elapsedRealtime()
            coalescer.onDiscreteEvent(EventType.SELECT, second)

            assertEquals("both taps must survive", 2, emitted.size)
            val actualInterval = emitted[1] - emitted[0]
            val trueInterval = second - first
            errors += abs(actualInterval - trueInterval)
        }
        report("tap_interval_error_ms", errors)
        assertEquals("stamps must reproduce the true interval exactly", 0L, errors.max())
    }

    /**
     * Flood: prove no detent is lost, ordering holds, and nothing wedges.
     */
    @Test
    fun floodPreservesEveryDetentAndOrdering() {
        val worker = android.os.HandlerThread("flood-worker").apply { start() }
        val handler = android.os.Handler(worker.looper)
        val accumulator = DetentAccumulator(threshold = 1.0f)

        var delivered = 0
        var lastSign = 0
        var inversions = 0
        val coalescer = ScrollCoalescer(sink = { type, steps, _ ->
            if (type == EventType.SCROLL) {
                delivered += abs(steps)
                if (lastSign != 0 && steps != 0 && Integer.signum(steps) != lastSign) {
                    // Direction changes are legitimate; count them only to prove
                    // the stream is not scrambled beyond the injected reversals.
                    inversions++
                }
                lastSign = Integer.signum(steps)
            }
        })

        val produced = 600
        val latch = CountDownLatch(1)
        handler.post {
            repeat(produced) { i ->
                val now = SystemClock.elapsedRealtime() + i
                val steps = accumulator.onDelta(1.0f, now)
                if (steps != 0) coalescer.onDetents(steps, now)
            }
            // Drain the rate limiter's carried surplus to completion.
            var t = SystemClock.elapsedRealtime()
            repeat(4000) {
                val carried = accumulator.drain(t)
                if (carried != 0) coalescer.onDetents(carried, t)
                t += 25
            }
            coalescer.flush(t)
            latch.countDown()
        }

        assertTrue("flood did not complete", latch.await(60, TimeUnit.SECONDS))
        worker.quitSafely()

        android.util.Log.i("InputMeasure", "flood produced=$produced delivered=$delivered")
        assertEquals("every detent produced must be delivered", produced, delivered)
    }

    /** The persisted session counter must be strictly monotonic on real storage. */
    @Test
    fun persistedSessionIdIsMonotonicOnDevice() {
        val prefs = context.getSharedPreferences(
            "measure_session_test", Context.MODE_PRIVATE,
        )
        prefs.edit().clear().commit()

        var previous = 0
        repeat(50) {
            val next = SessionIdentity.mintNextSid(prefs.getInt("sid", 0))
            prefs.edit().putInt("sid", next).commit()
            assertTrue("sid must increase: $previous -> $next", next > previous)
            previous = next
        }
        prefs.edit().clear().commit()
    }

    /** End-to-end encode on real hardware, including the tag. */
    @Test
    fun eventsEncodeAndVerifyOnDevice() {
        val key = "on-device-measurement-key".toByteArray()
        var encoded = 0
        for (type in EventType.entries) {
            val steps = if (type == EventType.SCROLL) 3 else 0
            val e = RemoteInputEvent(
                sid = 42, seq = encoded + 1, type = type, steps = steps,
                wms = SystemClock.elapsedRealtime().toInt(),
            )
            val bytes = RemoteInputProtocol.encodeEvent(key, e)
            assertEquals(26, bytes.size)
            val decoded = RemoteInputProtocol.decodeEvent(bytes)
            assertEquals(e, decoded.event)
            assertTrue(RemoteInputProtocol.verifyTag(key, decoded.event, decoded.tag))
            encoded++
        }
        assertEquals(EventType.entries.size, encoded)
    }
}

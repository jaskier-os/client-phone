package com.repository.listener.wear

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the LIVE watch link service so the whole chain can be observed on the
 * real devices: watch -> phone -> glasses -> UI.
 *
 * This asserts almost nothing on purpose. It is an input driver, not a verdict:
 * the evidence is the correlated log lines on all three devices, which is the only
 * place the end-to-end behaviour is actually visible. An assertion here could only
 * restate what the watch already believes about itself, and this project has
 * already been burned three times by evidence of exactly that shape.
 *
 * The input it produces is SYNTHETIC -- `onRotaryDelta` / `onTap` called directly,
 * exactly as the rotary and tap handlers in `ScrollRemoteActivity` call them. It
 * exercises everything downstream of the watch's input dispatch, and nothing
 * upstream of it: the bezel encoder and the touchscreen digitizer are not covered.
 *
 * Selected by `-e mode` so one instrument invocation drives one behaviour and the
 * logs stay unambiguous.
 */
@RunWith(AndroidJUnit4::class)
class LiveChainDriverTest {

    /**
     * The LIVE service, started here rather than assumed to be running.
     *
     * `am instrument` restarts the target app's process, so any service brought up
     * by launching the activity beforehand is gone by the time this runs. Starting
     * it here means the session observed in the logs is the one this test drove --
     * not a leftover from an earlier build, which is precisely the confusion that
     * would make the evidence untrustworthy.
     */
    private fun service(): WatchLinkService {
        WatchLinkService.current()?.let { return it }
        val ctx = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        ctx.startForegroundService(android.content.Intent(ctx, WatchLinkService::class.java))
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            WatchLinkService.current()?.let { return it }
            Thread.sleep(50)
        }
        val s = WatchLinkService.current()
        assertNotNull("WatchLinkService never started", s)
        return s!!
    }

    private val mode: String
        get() = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("mode", "scroll_cw")

    @Test
    fun drive() {
        val s = service()
        // Let the session establish before producing anything: a detent generated
        // before node resolution completes is legitimately dropped, and a driver
        // that ignores that produces a confusing log rather than a wrong result.
        Thread.sleep(3000)
        when (mode) {
            // One detent, each direction, well separated so each is unambiguous in
            // the logs and cannot be coalesced with its neighbour.
            "single_cw" -> single(+1.0f)
            "single_ccw" -> single(-1.0f)
            "scroll_cw" -> spin(+1.0f, 12)
            "scroll_ccw" -> spin(-1.0f, 12)
            "tap" -> {
                s.onTap()
                Thread.sleep(2000)
            }
            "double_tap" -> {
                // Two taps 150 ms apart, well inside the watch's own recognition
                // window. The gap is SPECIFIED rather than slept for: this thread was
                // measured stretching a sleep(150) to 552 ms under instrumentation
                // load, which the recogniser correctly reads as two singles and would
                // make the live evidence say the opposite of the truth. Everything
                // downstream of the stamp -- recogniser, worker, coalescer, encoder,
                // radio, phone relay, glasses -- is the real chain.
                val t = SystemClock.elapsedRealtime()
                s.onTapAt(t)
                Thread.sleep(60)
                s.onTapAt(t + 150)
                Thread.sleep(3000)
            }
            "two_singles" -> {
                // Control for the above: far apart, must stay two SELECTs.
                val t = SystemClock.elapsedRealtime()
                s.onTapAt(t)
                Thread.sleep(1500)
                s.onTapAt(t + 1500)
                Thread.sleep(3000)
            }
            "sustained" -> spin(+1.0f, 90, gapMs = 35)
            else -> throw IllegalArgumentException("unknown mode '$mode'")
        }
        // Let the tail of the pipeline drain before the process is torn down.
        Thread.sleep(2500)
    }

    private fun single(delta: Float) {
        val s = service()
        android.util.Log.i("LiveChain", "DRIVE single delta=$delta t=${SystemClock.elapsedRealtime()}")
        s.onRotaryDelta(delta, SystemClock.elapsedRealtime())
        Thread.sleep(2500)
    }

    private fun spin(delta: Float, count: Int, gapMs: Long = 80) {
        val s = service()
        android.util.Log.i(
            "LiveChain",
            "DRIVE spin delta=$delta count=$count gap=$gapMs t=${SystemClock.elapsedRealtime()}",
        )
        repeat(count) {
            s.onRotaryDelta(delta, SystemClock.elapsedRealtime())
            Thread.sleep(gapMs)
        }
    }
}

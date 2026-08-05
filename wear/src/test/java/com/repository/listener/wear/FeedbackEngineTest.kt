package com.repository.listener.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine is deliberately free of Android types so the properties that matter
 * -- no strobing, no runaway state, no cost when idle -- are provable on the JVM
 * rather than argued about in a comment.
 */
class FeedbackEngineTest {

    /** Worst measured spin: 60 detents at one every 32 ms. */
    private fun spin(engine: FeedbackEngine, detents: Int, intervalMs: Long, delta: Float = 1f) {
        var t = 0L
        repeat(detents) {
            engine.onDetent(delta, t)
            engine.advance(t)
            t += intervalMs
        }
    }

    @Test
    fun `charge saturates and never exceeds one during the fastest possible spin`() {
        val engine = FeedbackEngine()
        spin(engine, detents = 200, intervalMs = 32L)
        assertTrue("charge ${engine.charge} left the 0..1 range", engine.charge in 0f..1f)
    }

    @Test
    fun `a fast spin produces no flashing because charge stays high between detents`() {
        // The strobing failure mode is the charge collapsing and re-igniting
        // between detents. At the fastest spin the charge must stay saturated,
        // so the arc is continuous light rather than a train of flashes.
        val engine = FeedbackEngine()
        val detents = 20
        val gap = 32L
        spin(engine, detents = detents, intervalMs = gap)
        val floor = engine.charge
        // Advance EXACTLY one more inter-detent gap past the spin's last frame
        // (which was at (detents - 1) * gap) and confirm the brightness barely
        // moves -- a change too small to read as a flash.
        engine.advance((detents - 1) * gap + gap)
        val drop = floor - engine.charge
        // The ripple must stay under the 10% relative-luminance change that WCAG
        // 2.3.1 treats as a flash. The alpha mapping compresses charge, so the
        // charge-domain budget is the looser number and this is the strict test.
        assertTrue("charge dropped $drop in one 32 ms gap, which would flicker", drop <= 0.11f)
    }

    @Test
    fun `charge decays to nothing shortly after the user stops`() {
        val engine = FeedbackEngine()
        spin(engine, detents = 10, intervalMs = 32L)
        assertTrue(engine.charge > 0f)
        // Saturated charge reaches the invisibility floor at about 4.6 tau. Give
        // it 5 tau and require it to be genuinely zero, not merely small: a
        // non-zero charge is what keeps the frame loop alive.
        engine.advance(10L * 32L + (5f * FeedbackEngine.CHARGE_TAU_MS).toLong())
        assertEquals(0f, engine.charge, 0f)
    }

    @Test
    fun `the arc is still lit through the longest gap in a slow deliberate turn`() {
        // Measured detent gaps run 32-110 ms. At the slow end the arc must still
        // be clearly lit when the next detent lands, or a careful turn would
        // stutter instead of tracking the finger.
        val engine = FeedbackEngine()
        engine.onDetent(1f, 0L)
        engine.advance(0L)
        val peak = engine.charge
        engine.advance(110L)
        assertTrue(
            "arc faded to ${engine.charge} of $peak across a 110 ms gap",
            engine.charge > peak * 0.6f,
        )
    }

    @Test
    fun `advance reports dead once nothing is visible so the frame loop can stop`() {
        val engine = FeedbackEngine()
        engine.onDetent(1f, 0L)
        assertTrue("loop stopped while the arc was still lit", engine.advance(100L))
        assertFalse("loop kept running with nothing to draw", engine.advance(2_000L))
    }

    @Test
    fun `an idle engine never asks to be woken`() {
        val engine = FeedbackEngine()
        assertFalse("idle engine requested a wake", engine.needsWake())
    }

    @Test
    fun `only the first detent of a spin requests a wake`() {
        // This is the property that keeps snapshot writes off the input path: a
        // spin must ask for a wake exactly once, not once per detent.
        val engine = FeedbackEngine()
        var wakes = 0
        var t = 0L
        repeat(60) {
            engine.onDetent(1f, t)
            if (engine.needsWake()) {
                wakes++
                engine.markRunning(true)
            }
            engine.advance(t)
            t += 32L
        }
        assertEquals("a spin requested more than one wake", 1, wakes)
    }

    @Test
    fun `direction is legible from the sign of the travel`() {
        val engine = FeedbackEngine()
        engine.onDetent(1f, 0L)
        val forward = engine.headDegrees
        assertEquals(1, engine.direction)
        engine.onDetent(-1f, 32L)
        assertEquals(-1, engine.direction)
        assertTrue(
            "a reverse detent did not move the head back",
            engine.headDegrees < forward,
        )
    }

    @Test
    fun `one detent moves the rim exactly one sixtieth of a turn`() {
        // The arc is a 1:1 mirror of the physical bezel, so a full 60-detent
        // revolution of the finger must be exactly one lap of the rim.
        val engine = FeedbackEngine()
        engine.onDetent(1f, 0L)
        assertEquals(360f / 60f, engine.headDegrees, 0.001f)
    }

    @Test
    fun `head angle stays bounded over a long session`() {
        val engine = FeedbackEngine()
        spin(engine, detents = 5_000, intervalMs = 32L)
        assertTrue(
            "head angle ${engine.headDegrees} drifted out of range",
            engine.headDegrees > -361f && engine.headDegrees < 361f,
        )
    }

    /** Phase of every live wave, in slot order. */
    private fun phases(engine: FeedbackEngine): List<Float> =
        (0 until FeedbackEngine.MAX_WAVES)
            .map { engine.waveProgressAt(it) }
            .filter { it >= 0f }

    @Test
    fun `wave creation is rate limited without bound on how many can coexist`() {
        // The limit bounds how often a NEW wave is born, never how long an
        // existing one lives. Fire far faster than a human can and confirm the
        // creation rate is capped.
        val engine = FeedbackEngine()
        var shown = 0
        var t = 0L
        repeat(100) {
            if (engine.onWave(t, 10f, 20f, refusal = false)) shown++
            t += 10L
        }
        // 100 taps across 1000 ms, limiter at WAVE_MIN_INTERVAL_MS.
        val ceiling = (1000L / FeedbackEngine.WAVE_MIN_INTERVAL_MS).toInt() + 1
        assertTrue("$shown waves born in one second exceeds the cap of $ceiling", shown <= ceiling)
    }

    @Test
    fun `two quick taps produce two coexisting waves at distinct phases`() {
        // The reported bug: the second tap overwrote the first, so a double tap
        // -- the app's own BACK gesture -- drew one wave that visibly restarted.
        val engine = FeedbackEngine()
        assertTrue(engine.onWave(0L, 100f, 100f, refusal = false))
        // 180 ms is inside a natural double tap and must NOT be suppressed.
        assertTrue(
            "the second tap of a natural double tap was suppressed",
            engine.onWave(180L, 300f, 300f, refusal = false),
        )
        engine.advance(200L)

        assertEquals("both waves are not alive at once", 2, engine.activeWaveCount)
        val p = phases(engine)
        assertTrue("waves share a phase; one restarted the other", p[0] != p[1])

        // Each kept its own origin.
        val origins = (0 until FeedbackEngine.MAX_WAVES)
            .filter { engine.waveProgressAt(it) >= 0f }
            .map { engine.waveOriginXAt(it, 216f) }
            .sorted()
        assertEquals(listOf(100f, 300f), origins)
    }

    @Test
    fun `the pool holds the maximum number of waves at once`() {
        val engine = FeedbackEngine()
        var t = 0L
        repeat(FeedbackEngine.MAX_WAVES) {
            assertTrue(engine.onWave(t, 10f * it, 10f * it, refusal = false))
            if (it < FeedbackEngine.MAX_WAVES - 1) t += FeedbackEngine.WAVE_MIN_INTERVAL_MS
        }
        // Sample at the instant the last wave was born, which is when all of them
        // overlap. The pool size and the creation limit are matched to the wave
        // lifetime (TAP_WAVE_MS / WAVE_MIN_INTERVAL_MS = 3.5, so at most 4 can
        // ever be alive), which is why a tap never has to evict anything.
        engine.advance(t)
        assertEquals(
            "pool did not hold MAX_WAVES concurrently",
            FeedbackEngine.MAX_WAVES,
            engine.activeWaveCount,
        )
        assertEquals(
            "concurrent waves do not have distinct phases",
            FeedbackEngine.MAX_WAVES,
            phases(engine).distinct().size,
        )
    }

    @Test
    fun `the pool is large enough that a tap never evicts a live wave`() {
        // MAX_WAVES must cover the most waves the creation limiter can keep alive
        // at once, or a fast tapper would silently lose the oldest ring. Assert
        // the relationship rather than trusting the three constants to stay in
        // step: changing any one of them alone would reintroduce eviction.
        val maxAlive = (FeedbackEngine.TAP_WAVE_MS / FeedbackEngine.WAVE_MIN_INTERVAL_MS).toInt() + 1
        assertTrue(
            "MAX_WAVES=${FeedbackEngine.MAX_WAVES} cannot hold the $maxAlive waves the " +
                "limiter permits; a tap would evict a live wave",
            FeedbackEngine.MAX_WAVES >= maxAlive,
        )
    }

    @Test
    fun `a new wave never disturbs one already running`() {
        val engine = FeedbackEngine()
        engine.onWave(0L, 10f, 10f, refusal = false)
        engine.advance(150L)
        val slot = (0 until FeedbackEngine.MAX_WAVES).first { engine.waveProgressAt(it) >= 0f }
        val before = engine.waveProgressAt(slot)

        engine.onWave(150L, 200f, 200f, refusal = true)
        // Same instant, so the existing wave must not have moved at all.
        assertEquals(
            "an existing wave was restarted or cancelled by a new one",
            before,
            engine.waveProgressAt(slot),
            0f,
        )
        assertFalse("an existing wave took the new wave's kind", engine.waveIsRefusalAt(slot))
    }

    @Test
    fun `waves retire independently`() {
        val engine = FeedbackEngine()
        engine.onWave(0L, 10f, 10f, refusal = false)
        engine.onWave(200L, 20f, 20f, refusal = false)
        // Past the first wave's lifetime but not the second's.
        engine.advance(FeedbackEngine.TAP_WAVE_MS.toLong() + 10L)
        assertEquals("waves did not retire independently", 1, engine.activeWaveCount)
    }

    @Test
    fun `a full pool of overlapping waves stays far under the flash threshold`() {
        // WCAG 2.3.1 treats a flash as a >=10% relative-luminance change over
        // >25% of the field. A wave is a thin RING, so the summed lit area of a
        // maximally overlapped pool stays far below that -- which is exactly why
        // discrete tap waves are safe where discrete scroll pulses were not.
        val dp = 340f / 160f
        val radiusPx = 432f / 2f
        val discArea = Math.PI * radiusPx * radiusPx
        val strokePx = 2.5f * dp
        val maxRadius = radiusPx - 5f * dp

        val engine = FeedbackEngine()
        var t = 0L
        repeat(FeedbackEngine.MAX_WAVES) {
            engine.onWave(t, 216f, 216f, refusal = false)
            t += FeedbackEngine.WAVE_MIN_INTERVAL_MS
        }

        var worst = 0.0
        var now = 0L
        while (now <= t + FeedbackEngine.TAP_WAVE_MS.toLong()) {
            engine.advance(now)
            var lit = 0.0
            for (i in 0 until FeedbackEngine.MAX_WAVES) {
                val p = engine.waveProgressAt(i)
                if (p < 0f) continue
                val eased = 1f - (1f - p) * (1f - p)
                val r = maxRadius * (0.10f + 0.90f * eased)
                val alpha = 0.55f * (1f - p) * (1f - p)
                lit += 2.0 * Math.PI * r * strokePx * alpha
            }
            worst = maxOf(worst, lit / discArea)
            now += 8L
        }
        assertTrue(
            "peak luminance modulation ${worst * 100} percent exceeds the 10 percent budget",
            worst < 0.10,
        )
    }

    @Test
    fun `a wave completes and then clears itself`() {
        val engine = FeedbackEngine()
        assertTrue(engine.onWave(0L, 10f, 20f, refusal = false))
        engine.advance(10L)
        assertEquals("wave did not start", 1, engine.activeWaveCount)
        engine.advance(FeedbackEngine.TAP_WAVE_MS.toLong() + 50L)
        assertEquals("wave did not clear", 0, engine.activeWaveCount)
    }

    @Test
    fun `a refusal wave is distinguishable from a dispatch`() {
        val engine = FeedbackEngine()
        engine.onWave(0L, 10f, 20f, refusal = true)
        engine.advance(10L)
        val slot = (0 until FeedbackEngine.MAX_WAVES).first { engine.waveProgressAt(it) >= 0f }
        assertTrue("refusal not flagged", engine.waveIsRefusalAt(slot))
    }

    @Test
    fun `waves of different kinds coexist without contaminating each other`() {
        val engine = FeedbackEngine()
        engine.onWave(0L, 10f, 10f, refusal = false)
        engine.onWave(FeedbackEngine.WAVE_MIN_INTERVAL_MS, 20f, 20f, refusal = true)
        engine.advance(FeedbackEngine.WAVE_MIN_INTERVAL_MS)
        val kinds = (0 until FeedbackEngine.MAX_WAVES)
            .filter { engine.waveProgressAt(it) >= 0f }
            .map { engine.waveIsRefusalAt(it) }
        assertEquals("both kinds are not present", setOf(true, false), kinds.toSet())
    }

    @Test
    fun `reset drops animation state but keeps the dial position`() {
        val engine = FeedbackEngine()
        spin(engine, detents = 5, intervalMs = 32L)
        engine.onWave(200L, 10f, 20f, refusal = false)
        val dial = engine.headDegrees
        engine.reset()
        assertEquals(0f, engine.charge, 0f)
        assertEquals(0, engine.activeWaveCount)
        assertFalse(engine.needsWake())
        assertEquals("reset moved the dial", dial, engine.headDegrees, 0f)
    }

    @Test
    fun `decay is frame rate independent`() {
        // The watch renders at both 60 and 90 Hz. A per-frame multiplier would
        // fade at different speeds on each, so the same wall-clock elapsed time
        // must produce the same brightness regardless of how it was stepped.
        val coarse = FeedbackEngine()
        val fine = FeedbackEngine()
        coarse.onDetent(1f, 0L)
        fine.onDetent(1f, 0L)
        var t = 0L
        while (t < 300L) {
            t += 16L
            coarse.advance(t)
        }
        var u = 0L
        while (u < t) {
            u += 4L
            fine.advance(u)
        }
        assertEquals("decay depended on frame rate", coarse.charge, fine.charge, 0.01f)
    }

    @Test
    fun `a zero delta is ignored`() {
        val engine = FeedbackEngine()
        engine.onDetent(0f, 0L)
        assertEquals(0f, engine.charge, 0f)
        assertEquals(0, engine.direction)
    }

    @Test
    fun `a wave originates where it was triggered`() {
        val engine = FeedbackEngine()
        engine.onWave(0L, 10f, 20f, refusal = false)
        val slot = (0 until FeedbackEngine.MAX_WAVES).first { engine.waveProgressAt(it) >= 0f }
        assertEquals(10f, engine.waveOriginXAt(slot, 216f), 0f)
        assertEquals(20f, engine.waveOriginYAt(slot, 216f), 0f)
    }

    @Test
    fun `a wave with no touch point falls back to the supplied centre`() {
        val engine = FeedbackEngine()
        engine.onWaveAtCenter(0L, refusal = true)
        val slot = (0 until FeedbackEngine.MAX_WAVES).first { engine.waveProgressAt(it) >= 0f }
        assertEquals(216f, engine.waveOriginXAt(slot, 216f), 0f)
        assertEquals(216f, engine.waveOriginYAt(slot, 216f), 0f)
    }

    @Test
    fun `a suppressed wave never moves the running one`() {
        // A second tap inside the creation window must not teleport or re-kind a
        // wave that is still in flight -- that reads as a glitch, not feedback.
        val engine = FeedbackEngine()
        assertTrue(engine.onWave(0L, 10f, 20f, refusal = false))
        assertFalse("rate limit did not suppress", engine.onWave(20L, 400f, 400f, refusal = true))
        val slot = (0 until FeedbackEngine.MAX_WAVES).first { engine.waveProgressAt(it) >= 0f }
        assertEquals("suppressed wave moved the origin", 10f, engine.waveOriginXAt(slot, 216f), 0f)
        assertFalse("suppressed wave changed the kind", engine.waveIsRefusalAt(slot))
        assertEquals("suppression created a wave anyway", 1, engine.activeWaveCount)
    }
}

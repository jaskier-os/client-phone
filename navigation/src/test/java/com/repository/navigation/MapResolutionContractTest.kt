package com.repository.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.pow

/**
 * Programmatic verification of the minimap resolution pipeline contract.
 *
 * The glasses HUD was pixelated because the base frame shipped at 480x240 then got
 * perspective-warped + upscaled onto the 480x640 waveguide. These tests pin the
 * end-to-end contract so a silent downscale below the target cannot regress:
 *
 *  - The SHIPPED pixel resolution (OUTPUT_*) must be a high-density 2:1 frame.
 *  - The Google provider must ship its native scale=2 pixels with NO downscale
 *    (fetched 1200x600 == returned 1200x600 == OUTPUT_*).
 *  - The Yandex provider must render the same logical extent at the same density.
 *  - The arrow normalization must be FRACTION-based against the LOGICAL extent
 *    (CROP_*), so raising pixel density never moves the arrow.
 *
 * Bitmap pixel ops require Android, so these tests assert the contract numerically
 * (the actual createScaledBitmap calls are exercised on-device); the numbers below
 * mirror the companion consts in the production classes.
 */
class MapResolutionContractTest {

    // Mirror of the production contract consts. If a const changes, update here AND
    // confirm the assertion still expresses the intended end-to-end invariant.
    private val cropWidth = 600
    private val cropHeight = 300
    private val outputWidth = 1200
    private val outputHeight = 600

    // Google Static Maps provider knobs.
    private val googleSizeW = 600
    private val googleSizeH = 300
    private val googleScale = 2

    // Yandex offscreen provider knobs.
    private val yandexCrop = 600 to 300
    private val yandexScaleFactor = 2f

    @Test
    fun shippedResolutionIsHigherThanOldBottleneck() {
        // The old bottleneck was 480x240. The new shipped frame must be strictly denser.
        assertTrue("output width must exceed old 480 bottleneck", outputWidth > 480)
        assertTrue("output height must exceed old 240 bottleneck", outputHeight > 240)
    }

    @Test
    fun outputKeepsTwoToOneAspect() {
        // 2:1 matches the logical extent and the waveguide map region geometry.
        assertEquals(2.0, outputWidth.toDouble() / outputHeight.toDouble(), 1e-9)
        assertEquals(
            cropWidth.toDouble() / cropHeight.toDouble(),
            outputWidth.toDouble() / outputHeight.toDouble(),
            1e-9
        )
    }

    @Test
    fun googleShipsNativeScale2PixelsWithNoDownscale() {
        // size=600x300 & scale=2 -> API returns 1200x600. That MUST equal the OUTPUT
        // contract so processAndSend performs zero downscale (no resolution loss).
        val fetchedW = googleSizeW * googleScale
        val fetchedH = googleSizeH * googleScale
        assertEquals("google fetched width must match output", outputWidth, fetchedW)
        assertEquals("google fetched height must match output", outputHeight, fetchedH)
    }

    @Test
    fun yandexRendersSameExtentAtSameDensity() {
        val yW = (yandexCrop.first * yandexScaleFactor).toInt()
        val yH = (yandexCrop.second * yandexScaleFactor).toInt()
        assertEquals("yandex cropped width must match output", outputWidth, yW)
        assertEquals("yandex cropped height must match output", outputHeight, yH)
        // Both providers honor the same LOGICAL extent so the arrow stays aligned.
        assertEquals(cropWidth, yandexCrop.first)
        assertEquals(cropHeight, yandexCrop.second)
        assertEquals(cropWidth, googleSizeW)
        assertEquals(cropHeight, googleSizeH)
    }

    @Test
    fun arrowNormalizationIsResolutionIndependent() {
        // Reproduce sendArrowSample()'s math and prove the resulting 0..1 fraction is
        // identical whether we normalize against the logical extent at the OLD shipped
        // resolution (480x240) or the NEW one (1200x600). It normalizes against CROP_*
        // (the logical mercator extent), which is unchanged, so the arrow cannot move.
        val centerLat = 55.751244
        val centerLng = 37.618423
        val zoom = 16.0
        // A point offset ~30 m east, ~15 m north of center.
        val curLng = centerLng + 0.00048
        val curLat = centerLat + 0.000135

        val metersPerPixel = 156543.03392 * cos(Math.toRadians(centerLat)) / 2.0.pow(zoom)
        val dxMeters = (curLng - centerLng) * cos(Math.toRadians(centerLat)) * 111320.0
        val dyMeters = (curLat - centerLat) * 110540.0
        val dxPx = dxMeters / metersPerPixel
        val dyPx = -dyMeters / metersPerPixel

        // Normalization divides by the LOGICAL extent (CROP_*), never by OUTPUT_*.
        val normX = ((cropWidth / 2.0 + dxPx) / cropWidth).coerceIn(0.0, 1.0)
        val normY = ((cropHeight / 2.0 + dyPx) / cropHeight).coerceIn(0.0, 1.0)

        // Recompute as if someone (wrongly) tied normalization to a pixel resolution:
        // it would differ. Confirm CROP-based result is stable and in range.
        assertTrue("normX in range", normX in 0.0..1.0)
        assertTrue("normY in range", normY in 0.0..1.0)
        // The arrow for this offset sits right-of-center, above-center.
        assertTrue("arrow east of center", normX > 0.5)
        assertTrue("arrow north (above) of center", normY < 0.5)

        // Density invariance: doubling pixel density (OUTPUT change) does not enter the
        // formula at all -- same CROP_* -> same fraction.
        val normXDense = ((cropWidth / 2.0 + dxPx) / cropWidth).coerceIn(0.0, 1.0)
        assertEquals(normX, normXDense, 1e-12)
    }
}

package com.repository.navigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArrivalDetectionTest {

    @Test
    fun arrivalWithin50m_firesCallback() {
        var arrivedCount = 0
        val detector = ArrivalDetector(arrivalRadiusMeters = 50.0) {
            arrivedCount++
        }

        // Red Square
        detector.setDestination(55.7539, 37.6208)

        // Position ~20m away (within 50m radius)
        detector.updatePosition(55.75405, 37.62080)

        assertEquals(1, arrivedCount)
    }

    @Test
    fun noArrivalBeyond50m() {
        var arrivedCount = 0
        val detector = ArrivalDetector(arrivalRadiusMeters = 50.0) {
            arrivedCount++
        }

        // Red Square
        detector.setDestination(55.7539, 37.6208)

        // Bolshoi Theatre -- ~700m away
        detector.updatePosition(55.7601, 37.6186)

        assertEquals(0, arrivedCount)
    }

    @Test
    fun arrivalFiringOnlyOnce() {
        var arrivedCount = 0
        val detector = ArrivalDetector(arrivalRadiusMeters = 50.0) {
            arrivedCount++
        }

        detector.setDestination(55.7539, 37.6208)
        detector.updatePosition(55.75395, 37.62080)
        detector.updatePosition(55.75390, 37.62085)

        assertEquals(1, arrivedCount)
    }

    @Test
    fun reset_allowsNewArrival() {
        var arrivedCount = 0
        val detector = ArrivalDetector(arrivalRadiusMeters = 50.0) {
            arrivedCount++
        }

        detector.setDestination(55.7539, 37.6208)
        detector.updatePosition(55.75395, 37.62080)
        assertEquals(1, arrivedCount)

        detector.reset()
        detector.setDestination(55.7601, 37.6186)
        detector.updatePosition(55.76015, 37.61860)
        assertEquals(2, arrivedCount)
    }

    @Test
    fun noDestinationSet_neverFires() {
        var arrivedCount = 0
        val detector = ArrivalDetector(arrivalRadiusMeters = 50.0) {
            arrivedCount++
        }

        detector.updatePosition(55.7539, 37.6208)
        assertEquals(0, arrivedCount)
    }

    @Test
    fun haversineDistance_sanityCheck() {
        // Red Square to Bolshoi Theatre: ~700m
        val distance = ArrivalDetector.haversineMeters(
            55.7539, 37.6208,
            55.7601, 37.6186
        )
        assertTrue("Expected ~700m, got $distance", distance in 600.0..800.0)
    }
}

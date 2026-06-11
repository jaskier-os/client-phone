package com.repository.navigation

import android.util.Log
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.location.LocationListener
import com.yandex.mapkit.location.LocationManager
import com.yandex.mapkit.location.LocationSettingsFactory
import com.yandex.mapkit.location.LocationSimulator
import com.yandex.mapkit.location.LocationSimulatorListener
import com.yandex.mapkit.location.SimulationSettings
import com.yandex.mapkit.location.SubscriptionSettings

/**
 * Single per-journey location source shared by NavigationManager and
 * MapBitmapStreamer. Forwards subscriptions to either the real Yandex
 * LocationManager or to a polyline-driven LocationSimulator while keeping
 * subscriber identities stable across the swap.
 *
 * Yandex MapKit's LocationSimulator is itself a LocationManager that walks a
 * given polyline at a settable speed and dispatches LocationListener callbacks
 * exactly like the real GPS source, so downstream listeners need no awareness
 * of the swap.
 */
class JourneyLocationSource {

    private data class Sub(val settings: SubscriptionSettings, val listener: LocationListener)

    private val subs = mutableListOf<Sub>()
    private var realManager: LocationManager? = null
    private var simulator: LocationSimulator? = null
    private var simulatorListener: LocationSimulatorListener? = null
    private var onSimFinished: (() -> Unit)? = null

    @Volatile var mockActive: Boolean = false
        private set

    @Synchronized
    fun subscribe(settings: SubscriptionSettings, listener: LocationListener) {
        subs.removeAll { it.listener === listener }
        subs.add(Sub(settings, listener))
        activeManager().subscribeForLocationUpdates(settings, listener)
    }

    @Synchronized
    fun unsubscribe(listener: LocationListener) {
        subs.removeAll { it.listener === listener }
        try { activeManager().unsubscribe(listener) } catch (e: Exception) {
            Log.w(TAG, "unsubscribe failed: ${e.message}")
        }
    }

    @Synchronized
    fun enableMock(polyline: List<Point>, speedMps: Double, onFinished: () -> Unit) {
        if (polyline.size < 2) {
            Log.w(TAG, "enableMock: need >=2 points, got ${polyline.size}")
            return
        }
        disableMockInternal()

        // Yandex's native simulator aborts with "Set settings before starting
        // simulation" if startSimulation() runs without a SimulationSettings
        // entry. Build one from the polyline + LocationSettings carrying the
        // requested speed, then start with that list.
        val sim = MapKitFactory.getInstance().createLocationSimulator()
        // Empirically the native simulator tops out around 30 m/s (it ignores higher
        // setSpeed values internally), so clamp to that honoured range.
        val clampedSpeed = speedMps.coerceIn(0.5, 30.0)
        if (clampedSpeed != speedMps) {
            Log.w(TAG, "speed $speedMps out of [0.5, 30.0], clamped to $clampedSpeed")
        }
        val locSettings = LocationSettingsFactory.fineSettings().setSpeed(clampedSpeed)
        val simSettings = SimulationSettings(Polyline(polyline), locSettings)
        val simListener = object : LocationSimulatorListener {
            override fun onSimulationFinished() {
                Log.i(TAG, "Simulation finished")
                onSimFinished?.invoke()
            }
        }
        sim.subscribeForSimulatorEvents(simListener)
        simulator = sim
        simulatorListener = simListener
        onSimFinished = onFinished
        mockActive = true

        // Move all subscribers from real -> simulator. unsubscribe() guards
        // against the case where they were never bound to the real manager.
        for (s in subs) {
            try { realManager?.unsubscribe(s.listener) } catch (_: Exception) {}
            sim.subscribeForLocationUpdates(s.settings, s.listener)
        }
        sim.startSimulation(listOf(simSettings))

        // Wire the simulator as MapKit's global LocationManager so anything
        // built on UserLocationLayer (the on-screen Navigation map view, in
        // particular) follows the simulated track instead of real GPS.
        try {
            MapKitFactory.getInstance().setLocationManager(sim)
        } catch (e: Exception) {
            Log.w(TAG, "setLocationManager(sim) failed: ${e.message}")
        }

        Log.i(TAG, "Mock enabled: ${polyline.size} pts, ${"%.2f".format(speedMps)} m/s, " +
                "${subs.size} subscribers")
    }

    @Synchronized
    fun disableMock() {
        disableMockInternal()
    }

    private fun disableMockInternal() {
        val sim = simulator ?: return
        try { MapKitFactory.getInstance().resetLocationManagerToDefault() } catch (e: Exception) {
            Log.w(TAG, "resetLocationManagerToDefault failed: ${e.message}")
        }
        try { sim.stopSimulation() } catch (e: Exception) {
            Log.w(TAG, "stopSimulation failed: ${e.message}")
        }
        simulatorListener?.let {
            try { sim.unsubscribeFromSimulatorEvents(it) } catch (_: Exception) {}
        }
        for (s in subs) {
            try { sim.unsubscribe(s.listener) } catch (_: Exception) {}
            // Re-subscribe to the real manager so passive listeners (the
            // streamer in particular) keep ticking after a mid-journey cancel.
            activeRealManager().subscribeForLocationUpdates(s.settings, s.listener)
        }
        simulator = null
        simulatorListener = null
        onSimFinished = null
        mockActive = false
        Log.i(TAG, "Mock disabled")
    }

    private fun activeManager(): LocationManager = simulator ?: activeRealManager()

    private fun activeRealManager(): LocationManager {
        val cached = realManager
        if (cached != null) return cached
        val fresh = MapKitFactory.getInstance().createLocationManager()
        realManager = fresh
        return fresh
    }

    companion object {
        private const val TAG = "JourneyLocSource"
    }
}

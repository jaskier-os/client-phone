package com.repository.navigation

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.repository.navigation.model.TransportMethodInfo
import com.yandex.mapkit.geometry.Point

// Note: Route preview (PREVIEW state) is a phone-UI-only feature handled by NavigationFragment.
// The AIDL interface uses chooseMethodAndStart() which skips preview and starts navigation directly.
class NavigationService : Service() {

    companion object {
        private const val TAG = "NavigationService"
    }

    private lateinit var navigationManager: NavigationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        navigationManager = NavigationManager.getInstance(applicationContext)
    }

    private val binder = object : INavigationService.Stub() {

        override fun planJourney(
            fromLat: Double,
            fromLng: Double,
            toLat: Double,
            toLng: Double,
            callback: INavigationCallback?
        ) {
            mainHandler.post {
                try {
                    val from = Point(fromLat, fromLng)
                    val to = Point(toLat, toLng)
                    navigationManager.planJourney(from, to) { methods ->
                        val aidlMethods = methods.map { it.toTransportMethod() }
                        callback?.onJourneyPlanned(aidlMethods)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "planJourney failed", e)
                    callback?.onError("planJourney failed: ${e.message}")
                }
            }
        }

        override fun chooseMethodAndStart(methodId: String?, callback: INavigationCallback?) {
            if (methodId == null) {
                callback?.onError("methodId is required")
                return
            }
            mainHandler.post {
                try {
                    navigationManager.chooseMethodAndStart(methodId) { sessionId, etaSeconds ->
                        callback?.onJourneyStarted(sessionId, etaSeconds)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "chooseMethodAndStart failed", e)
                    callback?.onError("chooseMethodAndStart failed: ${e.message}")
                }
            }
        }

        override fun stopJourney(callback: INavigationCallback?) {
            mainHandler.post {
                try {
                    navigationManager.stopJourney()
                    callback?.onJourneyStopped()
                } catch (e: Exception) {
                    Log.e(TAG, "stopJourney failed", e)
                    callback?.onError("stopJourney failed: ${e.message}")
                }
            }
        }

        override fun modifyJourney(
            waypointLat: Double,
            waypointLng: Double,
            callback: INavigationCallback?
        ) {
            mainHandler.post {
                try {
                    navigationManager.modifyJourney(waypointLat, waypointLng) { newEtaSeconds ->
                        callback?.onJourneyModified(newEtaSeconds)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "modifyJourney failed", e)
                    callback?.onError("modifyJourney failed: ${e.message}")
                }
            }
        }

        override fun getCurrentEta(callback: INavigationCallback?) {
            mainHandler.post {
                try {
                    val (etaSeconds, etaFormatted) = navigationManager.getCurrentEta()
                    callback?.onEtaResult(etaSeconds, etaFormatted)
                } catch (e: Exception) {
                    Log.e(TAG, "getCurrentEta failed", e)
                    callback?.onError("getCurrentEta failed: ${e.message}")
                }
            }
        }

        override fun getAvailableMethods(
            fromLat: Double,
            fromLng: Double,
            toLat: Double,
            toLng: Double,
            callback: INavigationCallback?
        ) {
            mainHandler.post {
                try {
                    val from = Point(fromLat, fromLng)
                    val to = Point(toLat, toLng)
                    navigationManager.getAvailableMethods(from, to) { methods ->
                        val aidlMethods = methods.map { it.toTransportMethod() }
                        callback?.onJourneyPlanned(aidlMethods)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "getAvailableMethods failed", e)
                    callback?.onError("getAvailableMethods failed: ${e.message}")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private fun TransportMethodInfo.toTransportMethod(): TransportMethod {
        return TransportMethod(
            methodId = methodId,
            mode = mode.name,
            etaSeconds = etaSeconds,
            etaFormatted = etaFormatted,
            description = description,
            distanceMeters = distanceMeters
        )
    }
}

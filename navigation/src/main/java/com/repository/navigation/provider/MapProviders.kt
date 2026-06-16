package com.repository.navigation.provider

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.repository.navigation.BuildConfig

/**
 * Registry resolving the active [MapProvider]. The provider id string is supplied
 * by the :app module (which reads AppConfig.getMapProvider) since :navigation must
 * not depend on :app.
 *
 * Default provider = Yandex. N9 blank-key fallback: if the requested provider is
 * Google but GOOGLE_MAPS_API_KEY is blank, fall back to Yandex and log one error.
 */
object MapProviders {

    private const val TAG = "MapProviders"

    @Volatile
    private var current: MapProvider = YandexMapProvider()

    /** The active provider. Defaults to Yandex before resolve() runs. */
    val active: MapProvider get() = current

    // --- Reference-counted engine lifecycle ---
    //
    // The map SDK engine (Yandex MapKitFactory.onStart/onStop) is shared by every consumer:
    // the interactive map view, an active/preparing journey, the minimap renderer, and
    // one-shot geocode/search/suggest/route calls. We start it on the first acquire and stop
    // it only after the last release, so the SDK's background threads are not kept alive (and
    // able to crash the process) when nothing map-related is happening.
    //
    // All counter mutation and onProcessStart/onProcessStop calls happen on the main thread,
    // which the Yandex SDK requires. A short grace delay before the actual stop absorbs brief
    // gaps between two consumers (e.g. the map view stopping just as a journey starts) so the
    // engine is not thrashed off/on.
    private const val ENGINE_STOP_GRACE_MS = 4000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engineRefCount = 0
    private var engineStarted = false
    private val engineStopRunnable = Runnable { stopEngineNow() }

    /**
     * Acquire the shared map engine for a consumer. Idempotent per-consumer is NOT enforced --
     * each acquire must be balanced by exactly one [releaseEngine]. Safe to call from any
     * thread; the work is marshalled to the main thread.
     */
    fun acquireEngine(reason: String) {
        runOnMain {
            engineRefCount++
            Log.d(TAG, "acquireEngine($reason) -> count=$engineRefCount")
            mainHandler.removeCallbacks(engineStopRunnable)
            if (!engineStarted) {
                val p = current
                if (p.requiresProcessMapStart) {
                    p.onProcessStart()
                    Log.i(TAG, "map engine started (first consumer: $reason)")
                }
                engineStarted = true
            }
        }
    }

    /** Release a previously [acquireEngine]'d consumer. Stops the engine (after a grace delay) on 0. */
    fun releaseEngine(reason: String) {
        runOnMain {
            if (engineRefCount == 0) {
                Log.w(TAG, "releaseEngine($reason) with count already 0 -- ignoring (unbalanced release)")
                return@runOnMain
            }
            engineRefCount--
            Log.d(TAG, "releaseEngine($reason) -> count=$engineRefCount")
            if (engineRefCount == 0 && engineStarted) {
                mainHandler.removeCallbacks(engineStopRunnable)
                mainHandler.postDelayed(engineStopRunnable, ENGINE_STOP_GRACE_MS)
            }
        }
    }

    private fun stopEngineNow() {
        if (engineRefCount != 0 || !engineStarted) return
        val p = current
        if (p.requiresProcessMapStart) {
            p.onProcessStop()
            Log.i(TAG, "map engine stopped (no consumers)")
        }
        engineStarted = false
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    /**
     * Resolve and init the active provider from [providerId]
     * (AppConfig.MAP_PROVIDER_YANDEX / MAP_PROVIDER_GOOGLE). Applies the N9
     * blank-key fallback. Calls init() on the resolved provider.
     */
    fun resolve(context: Context, providerId: String) {
        val resolved = pick(providerId)
        current = resolved
        resolved.init(context.applicationContext)
        Log.i(TAG, "resolved provider=${resolved.id}")
    }

    /**
     * Switch to [providerId] at runtime (idle-only; the caller is responsible for
     * gating on NavigationManager state). Tears down the old provider's process
     * map lifecycle and inits the new one. N9 is honored via [pick]: requesting
     * Google with a blank key resolves back to Yandex. Returns the id of the
     * provider that is active after the call so the caller can detect a refused
     * switch and skip the dependent rebind.
     */
    fun switchTo(context: Context, providerId: String): String {
        val old = current
        val next = pick(providerId)
        if (next.id == old.id) return old.id
        // Transfer the engine running-state across the switch: if consumers currently hold the
        // engine under the old provider, stop the old engine and start the new one so the
        // refcount stays valid against the now-active provider. switchTo is only called while
        // IDLE (no journey), but the map view or a one-shot call could still hold a ref.
        val wasStarted = engineStarted
        if (wasStarted && old.requiresProcessMapStart) old.onProcessStop()
        current = next
        next.init(context.applicationContext)
        if (wasStarted) {
            if (next.requiresProcessMapStart) next.onProcessStart()
            engineStarted = next.requiresProcessMapStart
        }
        Log.i(TAG, "switched provider ${old.id} -> ${next.id} (engineWasStarted=$wasStarted, count=$engineRefCount)")
        return next.id
    }

    /**
     * Test seam: force [active] to [provider] without a key, the settings UI, or
     * [pick]'s blank-key fallback. Lets instrumented/JVM tests drive a Google
     * journey through a provider whose routing/location are fakes. Does NOT call
     * init() and does NOT alter normal resolve/switchTo/pick behavior.
     */
    @VisibleForTesting
    internal fun setActiveForTest(provider: MapProvider) {
        current = provider
    }

    /** Test seam: restore the default (Yandex) provider after a test. */
    @VisibleForTesting
    internal fun resetForTest() {
        current = YandexMapProvider()
    }

    private fun pick(providerId: String): MapProvider {
        return when (providerId) {
            GoogleMapProvider.ID -> {
                if (BuildConfig.GOOGLE_MAPS_API_KEY.isBlank()) {
                    Log.e(TAG, "provider=google requested but GOOGLE_MAPS_API_KEY is blank; falling back to Yandex")
                    YandexMapProvider()
                } else {
                    GoogleMapProvider()
                }
            }
            else -> YandexMapProvider()
        }
    }
}

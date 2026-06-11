package com.repository.navigation.provider

import android.content.Context
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
        if (old.requiresProcessMapStart) old.onProcessStop()
        current = next
        next.init(context.applicationContext)
        if (next.requiresProcessMapStart) next.onProcessStart()
        Log.i(TAG, "switched provider ${old.id} -> ${next.id}")
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

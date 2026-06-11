package com.repository.navigation

import android.content.Context
import com.repository.navigation.provider.MapProviders

object NavigationModule {

    @Volatile
    private var initialized = false

    /**
     * Resolve + init the active map provider. [providerId] comes from the :app
     * module (AppConfig.getMapProvider). Defaults to Yandex when omitted. This is
     * the gate that decides whether MapKit is initialized -- with Google selected
     * and a blank MapKit key, this still runs because the Google provider's init
     * does no Yandex work (and the N9 fallback handles a blank Google key).
     */
    fun init(context: Context, providerId: String = "yandex") {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            MapProviders.resolve(context, providerId)
            initialized = true
        }
    }
}

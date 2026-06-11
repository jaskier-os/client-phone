package com.repository.navigation

import com.repository.navigation.model.RouteAlternative
import com.repository.navigation.model.RouteInfo
import com.repository.navigation.model.TransportMethodInfo
import com.repository.navigation.model.TransportMode
import com.yandex.mapkit.geometry.Point

interface NavigationStateListener {
    fun onStateChanged(state: NavigationManager.State)
    fun onEtaUpdated(etaSeconds: Long)
    fun onInstructionChanged(instruction: String)
    fun onRouteUpdated(points: List<Point>)
    fun onMethodsReady(methods: List<TransportMethodInfo>)
    fun onLocationUpdated(lat: Double, lng: Double)
    fun onRoutePreviewReady(route: RouteInfo)
    fun onRouteAlternativesReady(mode: TransportMode, alternatives: List<RouteAlternative>) {}
    fun onError(message: String) {}
    /** Active step/section index advanced (or reset). Mirrors the nav_step_index
     *  sent to the glasses so the phone UI highlights the same current step. */
    fun onStepChanged(stepIndex: Int) {}
}

package com.repository.navigation.provider

import com.repository.navigation.RouteEngine
import com.repository.navigation.model.RouteAlternative
import com.repository.navigation.model.RouteInfo
import com.repository.navigation.model.TransportMethodInfo
import com.repository.navigation.model.TransportMode
import com.yandex.mapkit.geometry.Point

/**
 * Yandex implementation of [RoutingEngine]. Thin delegate over the existing
 * RouteEngine object (which keeps its routers `by lazy`, so linking this class
 * never inits MapKit on its own).
 */
class YandexRoutingEngine : RoutingEngine {

    override fun queryAllModes(
        from: Point,
        to: Point,
        callback: (List<TransportMethodInfo>) -> Unit
    ) = RouteEngine.queryAllModes(from, to, callback)

    override fun buildRoute(
        from: Point,
        to: Point,
        waypoints: List<Point>,
        mode: TransportMode,
        callback: (RouteInfo?) -> Unit
    ) = RouteEngine.buildRoute(from, to, waypoints, mode, callback)

    override fun rebuildWithWaypoint(
        currentRoute: RouteInfo,
        waypoint: Point,
        callback: (RouteInfo?) -> Unit
    ) = RouteEngine.rebuildWithWaypoint(currentRoute, waypoint, callback)

    override fun queryMode(
        from: Point,
        to: Point,
        mode: TransportMode,
        callback: (TransportMethodInfo?) -> Unit
    ) = RouteEngine.queryMode(from, to, mode, callback)

    override fun buildRouteAlternatives(
        from: Point,
        to: Point,
        mode: TransportMode,
        waypoints: List<Point>,
        departureTime: java.util.Date?,
        callback: (List<RouteAlternative>) -> Unit
    ) = RouteEngine.buildRouteAlternatives(from, to, mode, waypoints, departureTime, callback)

    override fun warmUp() = RouteEngine.warmUp()
}

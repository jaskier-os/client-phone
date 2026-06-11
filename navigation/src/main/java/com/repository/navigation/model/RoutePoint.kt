package com.repository.navigation.model

import com.yandex.mapkit.geometry.Point

data class RoutePoint(
    val type: RoutePointType,
    val point: Point?,
    val label: String
)

enum class RoutePointType { ORIGIN, WAYPOINT, DESTINATION }

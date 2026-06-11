package com.repository.navigation.model

import com.yandex.mapkit.geometry.Point

data class RouteInfo(
    val mode: TransportMode,
    val polylinePoints: List<Point>,
    val etaSeconds: Long,
    val etaFormatted: String,
    val distanceMeters: Int,
    val distanceFormatted: String,
    val instructions: List<NavigationInstruction>,
    val from: Point,
    val to: Point,
    val waypoints: List<Point> = emptyList(),
    val transitSections: List<TransitSection> = emptyList()
)

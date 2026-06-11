package com.repository.navigation.model

import com.yandex.mapkit.geometry.Point

data class JourneySession(
    val id: Long = 0,
    val from: Point,
    val to: Point,
    val transportMode: TransportMode,
    val methodId: String,
    val estimatedEtaSeconds: Long,
    val createdAt: Long,
    val plannedEndTime: Long,
    val expiryTime: Long,
    val waypoint: Point? = null,
    val routePolyline: String? = null,
    val isActive: Boolean = true,
    val currentStepIndex: Int = 0
)

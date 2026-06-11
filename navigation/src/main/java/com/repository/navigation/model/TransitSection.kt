package com.repository.navigation.model

import com.yandex.mapkit.geometry.Point

data class TransitSection(
    val type: TransitSectionType,
    val polylinePoints: List<Point>,
    val durationSeconds: Long,
    val durationFormatted: String,
    val distanceMeters: Int,
    val distanceFormatted: String,
    val lineName: String?,
    val lineShortName: String?,
    val lineShortNames: List<String> = emptyList(),
    val vehicleType: String?,
    val boardStop: String?,
    val alightStop: String?,
    val stopCount: Int,
    val cumulativeEtaSeconds: Long,
    val direction: String? = null,
    val isTransfer: Boolean = false,
    val lineColor: Int? = null,
    val departureTimeText: String? = null
)

enum class TransitSectionType {
    WALK,
    BUS,
    METRO,
    TRAM,
    TROLLEYBUS,
    TRAIN,
    SUBURBAN,
    FERRY,
    CABLE_CAR,
    FUNICULAR,
    GONDOLA,
    HIGH_SPEED_TRAIN,
    SHARE_TAXI,
    OTHER
}

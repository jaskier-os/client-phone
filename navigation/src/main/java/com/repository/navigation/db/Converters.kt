package com.repository.navigation.db

import androidx.room.TypeConverter
import com.repository.navigation.model.JourneySession
import com.repository.navigation.model.TransportMode
import com.yandex.mapkit.geometry.Point

object Converters {

    @TypeConverter
    @JvmStatic
    fun fromTransportMode(mode: TransportMode): String = mode.name

    @TypeConverter
    @JvmStatic
    fun toTransportMode(value: String): TransportMode = TransportMode.valueOf(value)

    fun toEntity(session: JourneySession): JourneySessionEntity = JourneySessionEntity(
        id = session.id,
        fromLat = session.from.latitude,
        fromLng = session.from.longitude,
        toLat = session.to.latitude,
        toLng = session.to.longitude,
        transportMode = session.transportMode.name,
        methodId = session.methodId,
        estimatedEtaSeconds = session.estimatedEtaSeconds,
        createdAt = session.createdAt,
        plannedEndTime = session.plannedEndTime,
        expiryTime = session.expiryTime,
        waypointLat = session.waypoint?.latitude,
        waypointLng = session.waypoint?.longitude,
        routePolyline = session.routePolyline,
        isActive = session.isActive,
        currentStepIndex = session.currentStepIndex
    )

    fun toDomain(entity: JourneySessionEntity): JourneySession = JourneySession(
        id = entity.id,
        from = Point(entity.fromLat, entity.fromLng),
        to = Point(entity.toLat, entity.toLng),
        transportMode = TransportMode.valueOf(entity.transportMode),
        methodId = entity.methodId,
        estimatedEtaSeconds = entity.estimatedEtaSeconds,
        createdAt = entity.createdAt,
        plannedEndTime = entity.plannedEndTime,
        expiryTime = entity.expiryTime,
        waypoint = if (entity.waypointLat != null && entity.waypointLng != null) {
            Point(entity.waypointLat, entity.waypointLng)
        } else null,
        routePolyline = entity.routePolyline,
        isActive = entity.isActive,
        currentStepIndex = entity.currentStepIndex
    )
}

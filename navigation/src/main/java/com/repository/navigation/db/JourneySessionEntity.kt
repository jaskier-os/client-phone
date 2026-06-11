package com.repository.navigation.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "journey_sessions")
data class JourneySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromLat: Double,
    val fromLng: Double,
    val toLat: Double,
    val toLng: Double,
    val transportMode: String,
    val methodId: String,
    val estimatedEtaSeconds: Long,
    val createdAt: Long,
    val plannedEndTime: Long,
    val expiryTime: Long,
    val waypointLat: Double?,
    val waypointLng: Double?,
    val routePolyline: String?,
    val isActive: Boolean = true,
    val currentStepIndex: Int = 0
)

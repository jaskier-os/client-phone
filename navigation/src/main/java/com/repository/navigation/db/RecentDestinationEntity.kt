package com.repository.navigation.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recent_destinations",
    indices = [Index(value = ["roundedLat", "roundedLng"], unique = true)]
)
data class RecentDestinationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val subtitle: String,
    val lat: Double,
    val lng: Double,
    val roundedLat: Double,
    val roundedLng: Double,
    val lastUsedAt: Long
)

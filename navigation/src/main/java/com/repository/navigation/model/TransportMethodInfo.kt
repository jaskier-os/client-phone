package com.repository.navigation.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class TransportMethodInfo(
    val methodId: String,
    val mode: TransportMode,
    val etaSeconds: Long,
    val etaFormatted: String,
    val description: String,
    val distanceMeters: Int
) : Parcelable

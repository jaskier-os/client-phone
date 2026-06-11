package com.repository.navigation

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class TransportMethod(
    val methodId: String,
    val mode: String,
    val etaSeconds: Long,
    val etaFormatted: String,
    val description: String,
    val distanceMeters: Int
) : Parcelable

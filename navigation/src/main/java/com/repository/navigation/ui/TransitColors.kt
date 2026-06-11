package com.repository.navigation.ui

import com.repository.navigation.R
import com.repository.navigation.model.TransitSectionType

object TransitColors {

    fun colorResForSectionType(type: TransitSectionType): Int = when (type) {
        TransitSectionType.WALK -> R.color.nav_transit_walk
        TransitSectionType.BUS -> R.color.nav_transit_bus
        TransitSectionType.METRO -> R.color.nav_transit_metro
        TransitSectionType.TRAM -> R.color.nav_transit_tram
        TransitSectionType.TROLLEYBUS -> R.color.nav_transit_trolleybus
        TransitSectionType.TRAIN -> R.color.nav_transit_train
        TransitSectionType.SUBURBAN -> R.color.nav_transit_suburban
        // High-speed/suburban rail variants reuse the train palette.
        TransitSectionType.HIGH_SPEED_TRAIN -> R.color.nav_transit_train
        // Ferries, ropeways and share-taxis have no dedicated palette yet; reuse
        // the bus color so they render as a regular colored leg.
        TransitSectionType.FERRY,
        TransitSectionType.CABLE_CAR,
        TransitSectionType.FUNICULAR,
        TransitSectionType.GONDOLA,
        TransitSectionType.SHARE_TAXI,
        TransitSectionType.OTHER -> R.color.nav_transit_bus
    }

    fun androidColorForSectionType(ctx: android.content.Context, type: TransitSectionType): Int =
        androidx.core.content.ContextCompat.getColor(ctx, colorResForSectionType(type))

    private val segmentPalette = intArrayOf(
        R.color.nav_segment_0, R.color.nav_segment_1,
        R.color.nav_segment_2, R.color.nav_segment_3,
        R.color.nav_segment_4, R.color.nav_segment_5,
        R.color.nav_segment_6, R.color.nav_segment_7
    )

    fun segmentColor(ctx: android.content.Context, segmentIndex: Int): Int =
        androidx.core.content.ContextCompat.getColor(ctx, segmentPalette[segmentIndex % segmentPalette.size])

    fun iconResForSectionType(type: TransitSectionType): Int = when (type) {
        TransitSectionType.WALK -> R.drawable.ic_transit_walk
        TransitSectionType.BUS -> R.drawable.ic_transit_bus
        TransitSectionType.METRO -> R.drawable.ic_transit_metro
        TransitSectionType.TRAM -> R.drawable.ic_transit_tram
        TransitSectionType.TROLLEYBUS -> R.drawable.ic_transit_trolleybus
        TransitSectionType.TRAIN -> R.drawable.ic_transit_train
        TransitSectionType.SUBURBAN -> R.drawable.ic_transit_suburban
        TransitSectionType.HIGH_SPEED_TRAIN -> R.drawable.ic_transit_train
        // No dedicated glyphs yet for ferry/ropeway/share-taxi/other; fall back to
        // the generic bus icon so the row still renders an icon.
        TransitSectionType.FERRY,
        TransitSectionType.CABLE_CAR,
        TransitSectionType.FUNICULAR,
        TransitSectionType.GONDOLA,
        TransitSectionType.SHARE_TAXI,
        TransitSectionType.OTHER -> R.drawable.ic_transit_bus
    }
}

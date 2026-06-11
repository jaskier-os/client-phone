package com.repository.navigation.util

import java.util.Locale

/**
 * Human-readable journey-duration formatting shared across providers and the nav UI.
 *
 * Renders the largest two non-zero units so long transit ETAs read naturally
 * ("6 h 16 min" instead of "376 min", "1 d 2 h" for multi-day routes) rather than
 * an unbounded minute count.
 */
object DurationFormat {

    fun format(seconds: Long): String {
        val totalMinutes = seconds / 60L
        if (totalMinutes <= 0L) return "<1 min"

        val days = totalMinutes / (24L * 60L)
        val hours = (totalMinutes % (24L * 60L)) / 60L
        val minutes = totalMinutes % 60L

        return when {
            days > 0L -> if (hours > 0L) {
                String.format(Locale.US, "%d d %d h", days, hours)
            } else {
                String.format(Locale.US, "%d d", days)
            }
            hours > 0L -> if (minutes > 0L) {
                String.format(Locale.US, "%d h %d min", hours, minutes)
            } else {
                String.format(Locale.US, "%d h", hours)
            }
            else -> String.format(Locale.US, "%d min", minutes)
        }
    }
}

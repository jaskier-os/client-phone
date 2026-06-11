package com.repository.navigation.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.repository.navigation.R
import com.repository.navigation.model.TransitSection
import com.repository.navigation.model.TransitSectionType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vertical timeline adapter for transit route details, mimicking Yandex Maps style.
 * Colored vertical lines connect stops; each stop shows a colored dot with name and time.
 * Walk sections show walk icon with duration and distance.
 * Transit sections produce board -> travel -> alight rows.
 */
class TransitTimelineAdapter : RecyclerView.Adapter<TransitTimelineAdapter.ViewHolder>() {

    private enum class RowType { BOARD, TRAVEL, ALIGHT, WALK }

    private data class Row(
        val type: RowType,
        val primaryText: String,
        val badgeText: String?,
        val lineNameText: String?,
        val timeText: String?,
        val sectionType: TransitSectionType,
        val showModeIcon: Boolean,
        val showDot: Boolean,
        val showTopLine: Boolean,
        val showBottomLine: Boolean,
        val topLineType: TransitSectionType?,
        val bottomLineType: TransitSectionType?,
        val segmentIndex: Int = -1,
        val topLineSegmentIndex: Int = -1,
        val bottomLineSegmentIndex: Int = -1,
        val circleBadgeText: String? = null,
        val lineColor: Int? = null,
        val isSuburban: Boolean = false
    )

    private val rows = mutableListOf<Row>()
    private var routeStartTimeMs = 0L
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun submitList(sections: List<TransitSection>) {
        rows.clear()
        routeStartTimeMs = System.currentTimeMillis()

        // Pre-compute segment indices to match map polyline colors
        var segCounter = 0
        val sectionSegIdx = IntArray(sections.size) { i ->
            if (sections[i].type == TransitSectionType.WALK) -1 else segCounter++
        }

        for ((i, section) in sections.withIndex()) {
            val prevSection = sections.getOrNull(i - 1)
            val nextSection = sections.getOrNull(i + 1)
            val isFirst = i == 0
            val isLast = i == sections.size - 1
            val curSeg = sectionSegIdx[i]
            val prevSeg = if (i > 0) sectionSegIdx[i - 1] else -1
            val nextSeg = if (i < sections.size - 1) sectionSegIdx[i + 1] else -1

            val startEta = if (i > 0) sections[i - 1].cumulativeEtaSeconds else 0L
            val endEta = section.cumulativeEtaSeconds

            if (section.type == TransitSectionType.WALK) {
                rows.add(Row(
                    type = RowType.WALK,
                    primaryText = "${section.durationFormatted}, ${section.distanceFormatted}",
                    badgeText = null,
                    lineNameText = null,
                    timeText = null,
                    sectionType = TransitSectionType.WALK,
                    showModeIcon = true,
                    showDot = true,
                    showTopLine = !isFirst,
                    showBottomLine = !isLast,
                    topLineType = section.type,
                    bottomLineType = section.type
                ))
            } else {
                val displayLineName = section.lineShortName ?: section.lineName
                val isMetro = section.type == TransitSectionType.METRO
                val isSuburban = section.type == TransitSectionType.SUBURBAN || section.type == TransitSectionType.TRAIN
                val boardTime = section.departureTimeText ?: formatAbsoluteTime(startEta)

                // Board stop
                rows.add(Row(
                    type = RowType.BOARD,
                    primaryText = section.boardStop ?: "Board",
                    badgeText = if (isMetro) null else sectionTypeName(section.type),
                    lineNameText = if (isMetro) null else displayLineName,
                    timeText = boardTime,
                    sectionType = section.type,
                    showModeIcon = !isMetro,
                    showDot = true,
                    showTopLine = rows.isNotEmpty(),
                    showBottomLine = true,
                    topLineType = prevSection?.type ?: section.type,
                    bottomLineType = section.type,
                    segmentIndex = curSeg,
                    topLineSegmentIndex = prevSeg,
                    bottomLineSegmentIndex = curSeg,
                    circleBadgeText = if (isMetro) displayLineName else null,
                    lineColor = section.lineColor,
                    isSuburban = isSuburban
                ))

                // Travel row
                val intermediateStops = if (section.stopCount > 2) section.stopCount - 2 else 0
                val travelText = if (intermediateStops > 0)
                    "${section.durationFormatted}, $intermediateStops stops"
                else
                    section.durationFormatted
                rows.add(Row(
                    type = RowType.TRAVEL,
                    primaryText = travelText,
                    badgeText = null,
                    lineNameText = null,
                    timeText = null,
                    sectionType = section.type,
                    showModeIcon = true,
                    showDot = false,
                    showTopLine = true,
                    showBottomLine = true,
                    topLineType = section.type,
                    bottomLineType = section.type,
                    segmentIndex = curSeg,
                    topLineSegmentIndex = curSeg,
                    bottomLineSegmentIndex = curSeg
                ))

                // Alight stop
                val showAlight = isLast || nextSection?.type == TransitSectionType.WALK
                if (showAlight && section.alightStop != null) {
                    rows.add(Row(
                        type = RowType.ALIGHT,
                        primaryText = section.alightStop,
                        badgeText = null,
                        lineNameText = null,
                        timeText = formatAbsoluteTime(endEta),
                        sectionType = section.type,
                        showModeIcon = false,
                        showDot = true,
                        showTopLine = true,
                        showBottomLine = !isLast,
                        topLineType = section.type,
                        bottomLineType = nextSection?.type,
                        segmentIndex = curSeg,
                        topLineSegmentIndex = curSeg,
                        bottomLineSegmentIndex = nextSeg
                    ))
                }
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transit_timeline, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(position)
    }

    override fun getItemCount() = rows.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val topLine: View = view.findViewById(R.id.timelineTopLine)
        private val dot: View = view.findViewById(R.id.timelineDot)
        private val bottomLine: View = view.findViewById(R.id.timelineBottomLine)
        private val circleBadge: TextView = view.findViewById(R.id.timelineCircleBadge)
        private val primaryText: TextView = view.findViewById(R.id.timelinePrimaryText)
        private val badgeRow: LinearLayout = view.findViewById(R.id.timelineBadgeRow)
        private val modeIcon: ImageView = view.findViewById(R.id.timelineModeIcon)
        private val badgeText: TextView = view.findViewById(R.id.timelineBadgeText)
        private val lineNameLabel: TextView = view.findViewById(R.id.timelineLineNameText)
        private val timeText: TextView = view.findViewById(R.id.timelineTimeText)

        private fun resolveColor(ctx: android.content.Context, type: TransitSectionType?, segIdx: Int): Int {
            return if (segIdx >= 0) TransitColors.segmentColor(ctx, segIdx)
            else ContextCompat.getColor(ctx, TransitColors.colorResForSectionType(type ?: TransitSectionType.WALK))
        }

        fun bind(position: Int) {
            val row = rows[position]
            val ctx = itemView.context
            val color = resolveColor(ctx, row.sectionType, row.segmentIndex)

            // Dot
            if (row.showDot) {
                dot.visibility = View.VISIBLE
                dot.backgroundTintList = ColorStateList.valueOf(color)
            } else {
                dot.visibility = View.GONE
            }

            // Top line
            if (row.showTopLine && row.topLineType != null) {
                topLine.visibility = View.VISIBLE
                topLine.setBackgroundColor(resolveColor(ctx, row.topLineType, row.topLineSegmentIndex))
            } else {
                topLine.visibility = View.INVISIBLE
            }

            // Bottom line
            if (row.showBottomLine && row.bottomLineType != null) {
                bottomLine.visibility = View.VISIBLE
                bottomLine.setBackgroundColor(resolveColor(ctx, row.bottomLineType, row.bottomLineSegmentIndex))
            } else {
                bottomLine.visibility = View.INVISIBLE
            }

            // Circle badge (metro line number)
            if (row.circleBadgeText != null) {
                circleBadge.visibility = View.VISIBLE
                circleBadge.text = row.circleBadgeText
                circleBadge.backgroundTintList = ColorStateList.valueOf(row.lineColor ?: color)
            } else {
                circleBadge.visibility = View.GONE
            }

            // Primary text
            primaryText.text = row.primaryText
            primaryText.setTextColor(
                if (row.type == RowType.TRAVEL || row.type == RowType.WALK)
                    ContextCompat.getColor(ctx, R.color.nav_gray)
                else
                    ContextCompat.getColor(ctx, R.color.nav_fg)
            )
            primaryText.textSize = if (row.type == RowType.TRAVEL || row.type == RowType.WALK) 12f else 14f

            // Badge row (type + line name on BOARD rows)
            if (row.badgeText != null) {
                badgeRow.visibility = View.VISIBLE
                badgeText.text = row.badgeText
                if (row.lineNameText != null) {
                    lineNameLabel.visibility = View.VISIBLE
                    lineNameLabel.text = row.lineNameText
                    lineNameLabel.setTextColor(color)
                    if (row.isSuburban) {
                        lineNameLabel.setBackgroundResource(R.drawable.bg_line_chip)
                    } else {
                        lineNameLabel.background = null
                    }
                } else {
                    lineNameLabel.visibility = View.GONE
                    lineNameLabel.background = null
                }
                if (row.showModeIcon) {
                    modeIcon.visibility = View.VISIBLE
                    modeIcon.setImageResource(TransitColors.iconResForSectionType(row.sectionType))
                    modeIcon.setColorFilter(color)
                } else {
                    modeIcon.visibility = View.GONE
                }
            } else {
                badgeRow.visibility = View.GONE
                lineNameLabel.visibility = View.GONE
            }

            // Mode icon inline with primary text when no badge row is shown
            if (row.showModeIcon && row.badgeText == null) {
                primaryText.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    TransitColors.iconResForSectionType(row.sectionType), 0, 0, 0
                )
                primaryText.compoundDrawablePadding = 8
                primaryText.compoundDrawableTintList = ColorStateList.valueOf(color)
            } else {
                primaryText.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            }

            // Time text
            if (row.timeText != null) {
                timeText.visibility = View.VISIBLE
                timeText.text = row.timeText
            } else {
                timeText.visibility = View.GONE
            }
        }
    }

    private fun formatAbsoluteTime(etaSeconds: Long): String {
        return timeFormat.format(Date(routeStartTimeMs + etaSeconds * 1000))
    }

    private fun sectionTypeName(type: TransitSectionType): String = when (type) {
        TransitSectionType.BUS -> "Bus"
        TransitSectionType.METRO -> "Metro"
        TransitSectionType.TRAM -> "Tram"
        TransitSectionType.TROLLEYBUS -> "Trolleybus"
        TransitSectionType.TRAIN -> "Train"
        TransitSectionType.SUBURBAN -> "Suburban"
        TransitSectionType.WALK -> "Walk"
        TransitSectionType.FERRY -> "Ferry"
        TransitSectionType.CABLE_CAR -> "Cable car"
        TransitSectionType.FUNICULAR -> "Funicular"
        TransitSectionType.GONDOLA -> "Gondola"
        TransitSectionType.HIGH_SPEED_TRAIN -> "High-speed train"
        TransitSectionType.SHARE_TAXI -> "Share taxi"
        TransitSectionType.OTHER -> "Transit"
    }
}

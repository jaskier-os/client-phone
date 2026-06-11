package com.repository.navigation.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.repository.navigation.R
import com.repository.navigation.model.RouteAlternative
import com.repository.navigation.model.TransitSectionType
import com.repository.navigation.model.TransportMode

class RouteAlternativeAdapter(
    private val onAlternativeSelected: (RouteAlternative) -> Unit
) : RecyclerView.Adapter<RouteAlternativeAdapter.ViewHolder>() {

    private val items = mutableListOf<RouteAlternative>()
    private var selectedId: String? = null

    fun submitList(alternatives: List<RouteAlternative>) {
        items.clear()
        items.addAll(alternatives)
        selectedId = alternatives.firstOrNull()?.alternativeId
        notifyDataSetChanged()
    }

    fun setSelected(alternativeId: String?) {
        val old = selectedId
        selectedId = alternativeId
        items.forEachIndexed { index, item ->
            if (item.alternativeId == old || item.alternativeId == alternativeId) {
                notifyItemChanged(index)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_route_alternative, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: MaterialCardView = view as MaterialCardView
        private val etaText: TextView = view.findViewById(R.id.altEtaText)
        private val distanceText: TextView = view.findViewById(R.id.altDistanceText)
        private val summaryText: TextView = view.findViewById(R.id.altSummaryText)
        private val transitIconsScroll: HorizontalScrollView = view.findViewById(R.id.altTransitIconsScroll)
        private val transitIcons: LinearLayout = view.findViewById(R.id.altTransitIcons)

        fun bind(alt: RouteAlternative) {
            val route = alt.routeInfo
            etaText.text = route.etaFormatted
            distanceText.text = route.distanceFormatted
            if (alt.mode == TransportMode.DRIVING) {
                summaryText.visibility = View.GONE
            } else {
                summaryText.visibility = View.VISIBLE
                summaryText.text = alt.summary
            }

            val isSelected = alt.alternativeId == selectedId
            card.strokeWidth = if (isSelected) dpToPx(1) else 0
            card.strokeColor = if (isSelected)
                card.context.getColor(R.color.nav_orange) else 0

            // Transit icons
            transitIcons.removeAllViews()
            if (alt.mode == TransportMode.TRANSIT && route.transitSections.isNotEmpty()) {
                transitIconsScroll.visibility = View.VISIBLE
                val nonWalkSections = route.transitSections.filter { it.type != TransitSectionType.WALK }
                for ((i, section) in nonWalkSections.withIndex()) {
                    if (i > 0) {
                        val chevron = ImageView(card.context).apply {
                            val size = dpToPx(12)
                            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                                marginStart = dpToPx(2)
                                marginEnd = dpToPx(2)
                            }
                            setImageResource(R.drawable.ic_chevron_right)
                            setColorFilter(ContextCompat.getColor(context, R.color.nav_gray))
                            scaleType = ImageView.ScaleType.CENTER_INSIDE
                        }
                        transitIcons.addView(chevron)
                    }
                    when (section.type) {
                        TransitSectionType.METRO -> {
                            // Red "M" badge + line number (e.g. "M 11")
                            val displayName = section.lineShortName ?: section.lineName
                            val mBadge = TextView(card.context).apply {
                                text = if (displayName != null) "M $displayName" else "M"
                                setTextColor(TransitColors.androidColorForSectionType(context, TransitSectionType.METRO))
                                textSize = 12f
                                setTypeface(null, Typeface.BOLD)
                                maxLines = 1
                                setSingleLine(true)
                                setPadding(dpToPx(2), 0, dpToPx(4), 0)
                            }
                            transitIcons.addView(mBadge)
                        }
                        TransitSectionType.SUBURBAN, TransitSectionType.TRAIN -> {
                            val displayName = section.lineShortName ?: section.lineName ?: "Train"
                            val icon = ImageView(card.context).apply {
                                val size = dpToPx(16)
                                layoutParams = LinearLayout.LayoutParams(size, size)
                                setImageResource(TransitColors.iconResForSectionType(section.type))
                                setColorFilter(ContextCompat.getColor(context, R.color.nav_gray))
                                scaleType = ImageView.ScaleType.CENTER_INSIDE
                            }
                            transitIcons.addView(icon)
                            val label = TextView(card.context).apply {
                                text = displayName
                                setTextColor(ContextCompat.getColor(context, R.color.nav_gray))
                                textSize = 11f
                                maxLines = 1
                                setSingleLine(true)
                                setPadding(dpToPx(2), 0, dpToPx(4), 0)
                            }
                            transitIcons.addView(label)
                        }
                        else -> {
                            // Default: colored icon + line name
                            val icon = ImageView(card.context).apply {
                                val size = dpToPx(16)
                                layoutParams = LinearLayout.LayoutParams(size, size)
                                setImageResource(TransitColors.iconResForSectionType(section.type))
                                setColorFilter(TransitColors.androidColorForSectionType(context, section.type))
                                scaleType = ImageView.ScaleType.CENTER_INSIDE
                            }
                            transitIcons.addView(icon)
                            if (section.lineName != null) {
                                val label = TextView(card.context).apply {
                                    text = section.lineName
                                    setTextColor(TransitColors.androidColorForSectionType(context, section.type))
                                    textSize = 11f
                                    maxLines = 1
                                    setSingleLine(true)
                                    setPadding(dpToPx(2), 0, dpToPx(4), 0)
                                }
                                transitIcons.addView(label)
                            }
                        }
                    }
                }
            } else {
                transitIconsScroll.visibility = View.GONE
            }

            card.setOnClickListener {
                setSelected(alt.alternativeId)
                onAlternativeSelected(alt)
            }
        }

        private fun dpToPx(dp: Int): Int {
            return (dp * itemView.context.resources.displayMetrics.density).toInt()
        }
    }
}

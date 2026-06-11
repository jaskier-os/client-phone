package com.repository.navigation.ui

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
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

class TransitSectionAdapter : RecyclerView.Adapter<TransitSectionAdapter.ViewHolder>() {

    private val items = mutableListOf<TransitSection>()
    private var currentIndex: Int = 0

    fun submitList(sections: List<TransitSection>) {
        items.clear()
        items.addAll(sections)
        notifyDataSetChanged()
    }

    /** Highlight the active section as the journey advances (driven by onStepChanged). */
    fun setCurrentIndex(index: Int) {
        if (index == currentIndex) return
        val prev = currentIndex
        currentIndex = index
        if (prev in items.indices) notifyItemChanged(prev)
        if (index in items.indices) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transit_section, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position == currentIndex)
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val typeIcon: ImageView = view.findViewById(R.id.sectionTypeIcon)
        private val title: TextView = view.findViewById(R.id.sectionTitle)
        private val detail: TextView = view.findViewById(R.id.sectionDetail)
        private val duration: TextView = view.findViewById(R.id.sectionDuration)
        private val lineChips: LinearLayout = view.findViewById(R.id.sectionLineChips)

        fun bind(section: TransitSection, isCurrent: Boolean) {
            // Highlight the active section: full opacity + subtle background; others dim.
            itemView.alpha = if (isCurrent) 1.0f else 0.5f
            itemView.setBackgroundColor(
                if (isCurrent) 0x33FFFFFF else 0x00000000
            )
            val color = ContextCompat.getColor(
                itemView.context,
                TransitColors.colorResForSectionType(section.type)
            )
            typeIcon.setImageResource(TransitColors.iconResForSectionType(section.type))
            typeIcon.setColorFilter(color)

            title.text = when (section.type) {
                TransitSectionType.WALK -> "Walk"
                else -> "${sectionTypeName(section.type)} ${section.lineName ?: ""}".trim()
            }

            val parts = mutableListOf<String>()
            if (section.distanceMeters > 0 && section.distanceFormatted.isNotEmpty()) {
                parts.add(section.distanceFormatted)
            }
            if (section.boardStop != null && section.alightStop != null) {
                parts.add("${section.boardStop} -> ${section.alightStop}")
            }
            if (parts.isEmpty()) {
                parts.add(section.durationFormatted)
            }
            detail.text = parts.joinToString(" -- ")

            renderLineChips(section, color)

            val stepMinutes = section.durationSeconds / 60
            duration.text = if (stepMinutes > 0) "${stepMinutes} min" else "<1 min"
        }

        /** Render all equivalent line numbers (34, 34G, 34AS...) as rounded chips.
         *  Walk legs and lineless legs show no chips. */
        private fun renderLineChips(section: TransitSection, color: Int) {
            lineChips.removeAllViews()
            val names = section.lineShortNames
            if (section.type == TransitSectionType.WALK || names.isEmpty()) {
                lineChips.visibility = View.GONE
                return
            }
            lineChips.visibility = View.VISIBLE
            val ctx = lineChips.context
            for ((idx, name) in names.withIndex()) {
                val chip = TextView(ctx).apply {
                    text = name
                    setTextColor(color)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    val padH = dp(8)
                    val padV = dp(2)
                    setPadding(padH, padV, padH, padV)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), color)
                        setColor(0x00000000)
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                if (idx > 0) lp.marginStart = dp(6)
                lineChips.addView(chip, lp)
            }
        }

        private fun dp(value: Int): Int =
            (value * itemView.resources.displayMetrics.density).toInt()

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
}

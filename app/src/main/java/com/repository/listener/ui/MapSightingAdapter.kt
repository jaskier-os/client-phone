package com.repository.listener.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.repository.listener.R

sealed class MapTimelineItem {
    data class DayHeader(val label: String) : MapTimelineItem()
    data class Sighting(val item: SightingItem, val originalIndex: Int) : MapTimelineItem()
}

class MapSightingAdapter(
    private val onSightingClick: (SightingItem) -> Unit,
    private val onVisibilityToggle: (Int) -> Unit
) : ListAdapter<MapTimelineItem, RecyclerView.ViewHolder>(DiffCallback) {

    private var hiddenIndices = setOf<Int>()

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SIGHTING = 1
    }

    object DiffCallback : DiffUtil.ItemCallback<MapTimelineItem>() {
        override fun areItemsTheSame(a: MapTimelineItem, b: MapTimelineItem): Boolean {
            return when {
                a is MapTimelineItem.DayHeader && b is MapTimelineItem.DayHeader -> a.label == b.label
                a is MapTimelineItem.Sighting && b is MapTimelineItem.Sighting -> a.originalIndex == b.originalIndex
                else -> false
            }
        }
        override fun areContentsTheSame(a: MapTimelineItem, b: MapTimelineItem) = a == b
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textHeader: TextView = view.findViewById(R.id.textDayHeader)
    }

    class SightingViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textCameraId: TextView = view.findViewById(R.id.textCameraId)
        val textTimestamp: TextView = view.findViewById(R.id.textTimestamp)
        val textHeading: TextView = view.findViewById(R.id.textHeading)
        val btnVisibility: ImageView = view.findViewById(R.id.btnVisibility)
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is MapTimelineItem.DayHeader -> TYPE_HEADER
            is MapTimelineItem.Sighting -> TYPE_SIGHTING
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_day_header, parent, false)
                HeaderViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_map_sighting, parent, false)
                SightingViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is MapTimelineItem.DayHeader -> {
                (holder as HeaderViewHolder).textHeader.text = item.label
            }
            is MapTimelineItem.Sighting -> {
                val h = holder as SightingViewHolder
                val s = item.item
                val isHidden = hiddenIndices.contains(item.originalIndex)

                h.textCameraId.text = "Cam ${s.cameraId}"
                h.textTimestamp.text = formatTimestamp(s.detectedAt)

                if (s.headingDegrees != null) {
                    h.textHeading.visibility = View.VISIBLE
                    h.textHeading.text = "${s.headingDegrees.toInt()}deg"
                } else {
                    h.textHeading.visibility = View.GONE
                }

                h.btnVisibility.setImageResource(
                    if (isHidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility
                )
                h.btnVisibility.alpha = if (isHidden) 0.4f else 1f
                h.itemView.alpha = if (isHidden) 0.4f else 1f

                h.btnVisibility.setOnClickListener {
                    onVisibilityToggle(item.originalIndex)
                }
                h.itemView.setOnClickListener {
                    if (s.latitude != null && s.longitude != null) {
                        onSightingClick(s)
                    }
                }
            }
        }
    }

    fun setHiddenIndices(indices: Set<Int>) {
        hiddenIndices = indices
        notifyDataSetChanged()
    }

    private fun formatTimestamp(dateStr: String): String {
        return try {
            val temporal = java.time.LocalDateTime.parse(dateStr.take(19))
            temporal.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        } catch (_: Exception) {
            dateStr
        }
    }
}

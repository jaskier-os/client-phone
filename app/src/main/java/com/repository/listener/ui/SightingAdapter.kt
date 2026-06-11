package com.repository.listener.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.repository.listener.R

data class SightingItem(
    val id: String = "",
    val cameraId: String,
    val detectedAt: String,
    val confidenceScore: Double,
    val latitude: Double?,
    val longitude: Double?,
    val headingDegrees: Double?,
    val gazeDegrees: Double?,
    val snapshotPath: String? = null
)

class SightingAdapter : ListAdapter<SightingItem, SightingAdapter.ViewHolder>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<SightingItem>() {
        override fun areItemsTheSame(a: SightingItem, b: SightingItem) =
            a.cameraId == b.cameraId && a.detectedAt == b.detectedAt
        override fun areContentsTheSame(a: SightingItem, b: SightingItem) = a == b
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val viewConfidenceIndicator: View = view.findViewById(R.id.viewConfidenceIndicator)
        val textCameraId: TextView = view.findViewById(R.id.textCameraId)
        val textTimestamp: TextView = view.findViewById(R.id.textTimestamp)
        val textConfidence: TextView = view.findViewById(R.id.textConfidence)
        val textLocation: TextView = view.findViewById(R.id.textLocation)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sighting, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val sighting = getItem(position)
        val ctx = holder.itemView.context

        holder.textCameraId.text = sighting.cameraId
        holder.textTimestamp.text = formatTimestamp(sighting.detectedAt)

        val pct = (sighting.confidenceScore * 100).toInt()
        holder.textConfidence.text = "$pct%"

        val colorRes = when {
            sighting.confidenceScore >= 0.8 -> R.color.gbx_green
            sighting.confidenceScore >= 0.5 -> R.color.gbx_yellow
            else -> R.color.gbx_red
        }
        val color = ContextCompat.getColor(ctx, colorRes)
        holder.textConfidence.setTextColor(color)
        holder.viewConfidenceIndicator.background.setTint(color)

        if (sighting.latitude != null && sighting.longitude != null) {
            holder.textLocation.visibility = View.VISIBLE
            holder.textLocation.text = "%.4f, %.4f".format(sighting.latitude, sighting.longitude)
        } else {
            holder.textLocation.visibility = View.GONE
        }
    }

    private fun formatTimestamp(dateStr: String): String {
        return try {
            val temporal = java.time.LocalDateTime.parse(dateStr.take(19))
            temporal.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))
        } catch (_: Exception) {
            dateStr
        }
    }
}

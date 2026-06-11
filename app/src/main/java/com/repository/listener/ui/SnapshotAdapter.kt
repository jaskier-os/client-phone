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
import com.repository.listener.util.ImageCacheUtil

data class SnapshotItem(
    val url: String,
    val cameraId: String,
    val timestamp: String
)

class SnapshotAdapter(
    private val apiKey: String,
    private val onClick: (SnapshotItem) -> Unit
) : ListAdapter<SnapshotItem, SnapshotAdapter.ViewHolder>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<SnapshotItem>() {
        override fun areItemsTheSame(a: SnapshotItem, b: SnapshotItem) = a.url == b.url
        override fun areContentsTheSame(a: SnapshotItem, b: SnapshotItem) = a == b
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgSnapshot: ImageView = view.findViewById(R.id.imgSnapshot)
        val textCamera: TextView = view.findViewById(R.id.textCamera)
        val textTimestamp: TextView = view.findViewById(R.id.textTimestamp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_snapshot, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)

        val cacheKey = "snap_${item.cameraId}_${item.timestamp}"
        ImageCacheUtil.loadImage(holder.imgSnapshot, item.url, cacheKey, apiKey)
        holder.textCamera.text = "Cam ${item.cameraId}"
        holder.textTimestamp.text = formatTimestamp(item.timestamp)
        holder.itemView.setOnClickListener { onClick(item) }
    }

    private fun formatTimestamp(dateStr: String): String {
        return try {
            val temporal = java.time.LocalDateTime.parse(dateStr.take(19))
            temporal.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
        } catch (_: Exception) {
            dateStr.take(16)
        }
    }
}

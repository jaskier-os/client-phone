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

data class PersonItem(
    val id: String,
    val createdAt: String,
    val lastSeenAt: String,
    val totalSightings: Int,
    val isActive: Boolean,
    val displayName: String? = null,
    val searchKeys: List<String> = emptyList()
)

class PersonCardAdapter(
    private val imageBaseUrl: String,
    private val apiKey: String,
    private val onClick: (PersonItem) -> Unit
) : ListAdapter<PersonItem, PersonCardAdapter.ViewHolder>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<PersonItem>() {
        override fun areItemsTheSame(a: PersonItem, b: PersonItem) = a.id == b.id
        override fun areContentsTheSame(a: PersonItem, b: PersonItem) = a == b
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgPerson: ImageView = view.findViewById(R.id.imgPerson)
        val textPersonId: TextView = view.findViewById(R.id.textPersonId)
        val textFirstSeen: TextView = view.findViewById(R.id.textFirstSeen)
        val textLastSeen: TextView = view.findViewById(R.id.textLastSeen)
        val textSightings: TextView = view.findViewById(R.id.textSightings)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_person_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val person = getItem(position)

        holder.textPersonId.text = person.displayName?.takeIf { it.isNotEmpty() } ?: "Unknown"
        holder.textFirstSeen.text = "First: ${formatDate(person.createdAt)}"
        holder.textLastSeen.text = "Last: ${formatDate(person.lastSeenAt)}"
        holder.textSightings.text = "${person.totalSightings} sightings"

        holder.itemView.setOnClickListener { onClick(person) }

        ImageCacheUtil.loadPersonImage(holder.imgPerson, person.id, imageBaseUrl, apiKey)
    }

    private fun formatDate(dateStr: String): String {
        return try {
            val temporal = java.time.LocalDateTime.parse(dateStr.take(19))
            temporal.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        } catch (_: Exception) {
            dateStr.take(10)
        }
    }
}

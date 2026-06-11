package com.repository.navigation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.repository.navigation.R
import com.yandex.mapkit.geometry.Point

data class SuggestItem(
    val title: String,
    val subtitle: String,
    val point: Point?,
    val isHistory: Boolean = false,
    val distanceFormatted: String? = null
)

class SuggestAdapter(
    private val onItemClicked: (SuggestItem) -> Unit
) : RecyclerView.Adapter<SuggestAdapter.ViewHolder>() {

    private val items = mutableListOf<SuggestItem>()

    fun submitList(suggestions: List<SuggestItem>) {
        items.clear()
        items.addAll(suggestions)
        notifyDataSetChanged()
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_suggest, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.suggestIcon)
        private val title: TextView = view.findViewById(R.id.suggestTitle)
        private val subtitle: TextView = view.findViewById(R.id.suggestSubtitle)
        private val distance: TextView = view.findViewById(R.id.suggestDistance)

        fun bind(item: SuggestItem) {
            title.text = item.title
            subtitle.text = item.subtitle
            icon.visibility = if (item.isHistory) View.VISIBLE else View.GONE
            if (item.distanceFormatted != null) {
                distance.text = item.distanceFormatted
                distance.visibility = View.VISIBLE
            } else {
                distance.visibility = View.GONE
            }
            itemView.setOnClickListener { onItemClicked(item) }
        }
    }
}

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

data class SimilarPersonItem(
    val id: String,
    val similarity: Double
)

class SimilarPersonAdapter(
    private val baseUrl: String,
    private val apiKey: String,
    private val onClick: (SimilarPersonItem) -> Unit
) : ListAdapter<SimilarPersonItem, SimilarPersonAdapter.ViewHolder>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<SimilarPersonItem>() {
        override fun areItemsTheSame(a: SimilarPersonItem, b: SimilarPersonItem) = a.id == b.id
        override fun areContentsTheSame(a: SimilarPersonItem, b: SimilarPersonItem) = a == b
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgPerson: ImageView = view.findViewById(R.id.imgPerson)
        val textPersonId: TextView = view.findViewById(R.id.textPersonId)
        val textSimilarity: TextView = view.findViewById(R.id.textSimilarity)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_similar_person, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)

        ImageCacheUtil.loadPersonImage(holder.imgPerson, item.id, baseUrl, apiKey)
        holder.textPersonId.text = item.id.take(12) + "..."
        holder.textSimilarity.text = "%.1f%% match".format(item.similarity * 100)
        holder.itemView.setOnClickListener { onClick(item) }
    }
}

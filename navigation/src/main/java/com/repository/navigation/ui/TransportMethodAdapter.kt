package com.repository.navigation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.repository.navigation.R
import com.repository.navigation.model.TransportMethodInfo
import com.repository.navigation.model.TransportMode

class TransportMethodAdapter(
    private val onMethodClicked: (TransportMethodInfo) -> Unit
) : RecyclerView.Adapter<TransportMethodAdapter.ViewHolder>() {

    private val items = mutableListOf<TransportMethodInfo>()
    private var selectedMethodId: String? = null

    fun submitList(methods: List<TransportMethodInfo>) {
        items.clear()
        items.addAll(methods.sortedBy { it.etaSeconds })
        selectedMethodId = null
        notifyDataSetChanged()
    }

    fun setSelected(methodId: String?) {
        val oldSelected = selectedMethodId
        selectedMethodId = methodId
        items.forEachIndexed { index, item ->
            if (item.methodId == oldSelected || item.methodId == methodId) {
                notifyItemChanged(index)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transport_method, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val modeIcon: ImageView = view.findViewById(R.id.modeIcon)
        private val methodEta: TextView = view.findViewById(R.id.methodEta)
        private val methodDescription: TextView = view.findViewById(R.id.methodDescription)
        private val card: MaterialCardView = view as MaterialCardView

        fun bind(method: TransportMethodInfo) {
            modeIcon.setImageResource(iconForMode(method.mode))
            methodEta.text = method.etaFormatted
            methodDescription.text = method.description

            val isSelected = method.methodId == selectedMethodId
            card.strokeWidth = if (isSelected) dpToPx(2) else 0
            card.strokeColor = if (isSelected)
                card.context.getColor(R.color.nav_orange) else 0

            card.setOnClickListener { onMethodClicked(method) }
        }

        private fun dpToPx(dp: Int): Int {
            return (dp * itemView.context.resources.displayMetrics.density).toInt()
        }
    }

    companion object {
        fun iconForMode(mode: TransportMode): Int = when (mode) {
            TransportMode.DRIVING -> R.drawable.ic_mode_drive
            TransportMode.WALKING -> R.drawable.ic_mode_walk
            TransportMode.TRANSIT -> R.drawable.ic_mode_transit
            TransportMode.BICYCLE -> R.drawable.ic_mode_bicycle
        }
    }
}

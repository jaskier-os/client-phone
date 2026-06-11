package com.repository.navigation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.repository.navigation.R
import com.repository.navigation.model.InstructionType
import com.repository.navigation.model.NavigationInstruction

class NavigationInstructionAdapter : RecyclerView.Adapter<NavigationInstructionAdapter.ViewHolder>() {

    private val items = mutableListOf<NavigationInstruction>()
    private var currentIndex: Int = 0

    fun submitList(instructions: List<NavigationInstruction>) {
        items.clear()
        items.addAll(instructions)
        notifyDataSetChanged()
    }

    /** Highlight the active instruction as the journey advances (onStepChanged). */
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
        holder.bind(items[position], position, position == currentIndex)
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val typeIcon: ImageView = view.findViewById(R.id.sectionTypeIcon)
        private val title: TextView = view.findViewById(R.id.sectionTitle)
        private val detail: TextView = view.findViewById(R.id.sectionDetail)
        private val duration: TextView = view.findViewById(R.id.sectionDuration)

        fun bind(instruction: NavigationInstruction, position: Int, isCurrent: Boolean) {
            // Highlight the active instruction: full opacity + subtle background; others dim.
            itemView.alpha = if (isCurrent) 1.0f else 0.5f
            itemView.setBackgroundColor(if (isCurrent) 0x33FFFFFF else 0x00000000)
            val iconRes = iconForType(instruction.type)
            val colorRes = colorForType(instruction.type)
            val color = ContextCompat.getColor(itemView.context, colorRes)

            typeIcon.setImageResource(iconRes)
            typeIcon.setColorFilter(color)

            title.text = instruction.text

            val parts = mutableListOf<String>()
            instruction.lineName?.let { parts.add(it) }
            instruction.stopName?.let { parts.add(it) }
            detail.text = parts.joinToString(" -- ")
            detail.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE

            duration.text = "${position + 1}"
        }

        private fun iconForType(type: InstructionType): Int = when (type) {
            InstructionType.WALK -> R.drawable.ic_transit_walk
            InstructionType.BOARD_BUS -> R.drawable.ic_transit_bus
            InstructionType.BOARD_METRO -> R.drawable.ic_transit_metro
            InstructionType.BOARD_TRAM -> R.drawable.ic_transit_tram
            InstructionType.BOARD_TROLLEYBUS -> R.drawable.ic_transit_trolleybus
            InstructionType.BOARD_TRAIN -> R.drawable.ic_transit_train
            InstructionType.BOARD_SUBURBAN -> R.drawable.ic_transit_suburban
            InstructionType.BOARD_HIGH_SPEED_TRAIN -> R.drawable.ic_transit_train
            // No dedicated glyphs for ferry/ropeway/share-taxi/other boardings yet;
            // reuse the generic bus icon so a transit glyph still shows.
            InstructionType.BOARD_FERRY,
            InstructionType.BOARD_CABLE_CAR,
            InstructionType.BOARD_FUNICULAR,
            InstructionType.BOARD_GONDOLA,
            InstructionType.BOARD_SHARE_TAXI,
            InstructionType.BOARD_OTHER -> R.drawable.ic_transit_bus
            InstructionType.EXIT_TRANSPORT, InstructionType.TRANSFER -> R.drawable.ic_transit_walk
            InstructionType.TURN -> R.drawable.ic_transit_walk
            InstructionType.DRIVE -> R.drawable.ic_transit_walk
            InstructionType.CYCLE -> R.drawable.ic_transit_walk
            InstructionType.ARRIVE -> R.drawable.ic_transit_walk
        }

        private fun colorForType(type: InstructionType): Int = when (type) {
            InstructionType.WALK, InstructionType.TURN,
            InstructionType.EXIT_TRANSPORT, InstructionType.TRANSFER -> R.color.nav_transit_walk
            InstructionType.BOARD_BUS -> R.color.nav_transit_bus
            InstructionType.BOARD_METRO -> R.color.nav_transit_metro
            InstructionType.BOARD_TRAM -> R.color.nav_transit_tram
            InstructionType.BOARD_TROLLEYBUS -> R.color.nav_transit_trolleybus
            InstructionType.BOARD_TRAIN -> R.color.nav_transit_train
            InstructionType.BOARD_SUBURBAN -> R.color.nav_transit_suburban
            InstructionType.BOARD_HIGH_SPEED_TRAIN -> R.color.nav_transit_train
            InstructionType.BOARD_FERRY,
            InstructionType.BOARD_CABLE_CAR,
            InstructionType.BOARD_FUNICULAR,
            InstructionType.BOARD_GONDOLA,
            InstructionType.BOARD_SHARE_TAXI,
            InstructionType.BOARD_OTHER -> R.color.nav_transit_bus
            InstructionType.DRIVE -> R.color.nav_blue
            InstructionType.CYCLE -> R.color.nav_green
            InstructionType.ARRIVE -> R.color.nav_orange
        }
    }
}

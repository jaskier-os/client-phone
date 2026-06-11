package com.repository.listener.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.repository.listener.R
import com.repository.listener.alarm.AlarmItem

class AlarmAdapter(
    private val onToggle: (AlarmItem, Boolean) -> Unit,
    private val onDelete: (AlarmItem) -> Unit,
    private val onClick: (AlarmItem) -> Unit
) : RecyclerView.Adapter<AlarmAdapter.ViewHolder>() {

    private val items = mutableListOf<AlarmItem>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val alarmTime: TextView = view.findViewById(R.id.alarmTime)
        val alarmTitle: TextView = view.findViewById(R.id.alarmTitle)
        val alarmSwitch: SwitchCompat = view.findViewById(R.id.alarmSwitch)
        val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alarm, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context

        holder.alarmTime.text = String.format("%02d:%02d", item.hour, item.minute)
        holder.alarmTime.setTextColor(
            ContextCompat.getColor(ctx, if (item.enabled) R.color.gbx_fg else R.color.gbx_gray)
        )

        if (item.title.isNotEmpty()) {
            holder.alarmTitle.text = item.title
            holder.alarmTitle.visibility = View.VISIBLE
        } else {
            holder.alarmTitle.visibility = View.GONE
        }

        holder.alarmSwitch.setOnCheckedChangeListener(null)
        holder.alarmSwitch.isChecked = item.enabled
        holder.alarmSwitch.setOnCheckedChangeListener { _, checked -> onToggle(item, checked) }

        holder.deleteButton.setColorFilter(ContextCompat.getColor(ctx, R.color.gbx_gray))
        holder.deleteButton.setOnClickListener { onDelete(item) }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<AlarmItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}

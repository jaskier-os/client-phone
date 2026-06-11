package com.repository.listener.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.repository.listener.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JobListAdapter(
    private val onDelete: (JobItem) -> Unit,
    private val onClick: (JobItem) -> Unit
) : RecyclerView.Adapter<JobListAdapter.ViewHolder>() {

    private val items = mutableListOf<JobItem>()
    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.US)

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val jobName: TextView = view.findViewById(R.id.jobName)
        val jobSchedule: TextView = view.findViewById(R.id.jobSchedule)
        val jobResult: TextView = view.findViewById(R.id.jobResult)
        val jobStatus: TextView = view.findViewById(R.id.jobStatus)
        val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_job, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context

        val nameColor = when (item.status) {
            "pending" -> ContextCompat.getColor(ctx, R.color.gbx_fg)
            "running" -> ContextCompat.getColor(ctx, R.color.gbx_orange)
            "completed" -> ContextCompat.getColor(ctx, R.color.gbx_green)
            "failed" -> ContextCompat.getColor(ctx, R.color.gbx_red)
            "needs_input" -> ContextCompat.getColor(ctx, R.color.gbx_yellow)
            else -> ContextCompat.getColor(ctx, R.color.gbx_fg)
        }

        holder.jobName.text = item.name
        holder.jobName.setTextColor(nameColor)

        holder.jobSchedule.text = dateFormat.format(Date(item.scheduledAt))

        holder.jobStatus.text = item.status.replaceFirstChar { it.uppercase() }
        holder.jobStatus.setTextColor(nameColor)

        if (item.status in listOf("completed", "failed", "needs_input")) {
            val resultText = when {
                item.status == "failed" && !item.error.isNullOrBlank() -> item.error
                !item.result.isNullOrBlank() -> item.result
                else -> null
            }
            if (resultText != null) {
                holder.jobResult.text = resultText
                holder.jobResult.visibility = View.VISIBLE
            } else {
                holder.jobResult.visibility = View.GONE
            }
        } else {
            holder.jobResult.visibility = View.GONE
        }

        holder.deleteButton.setColorFilter(ContextCompat.getColor(ctx, R.color.gbx_gray))
        holder.deleteButton.setOnClickListener { onDelete(item) }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<JobItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}

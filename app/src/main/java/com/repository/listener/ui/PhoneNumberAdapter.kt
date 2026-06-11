package com.repository.listener.ui

import android.provider.CallLog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.repository.listener.R
import com.repository.listener.phone.PhoneContact
import com.repository.listener.util.ImageCacheUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhoneNumberAdapter(
    private val imageBaseUrl: String,
    private val apiKey: String,
    private val onSherlockClick: (PhoneContact) -> Unit,
    private val onPersonClick: (String) -> Unit
) : ListAdapter<PhoneContact, PhoneNumberAdapter.ViewHolder>(DiffCallback) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private var expandedPosition: Int = -1
    private var sherlockLoadingPosition: Int = -1

    object DiffCallback : DiffUtil.ItemCallback<PhoneContact>() {
        override fun areItemsTheSame(a: PhoneContact, b: PhoneContact) = a.number == b.number
        override fun areContentsTheSame(a: PhoneContact, b: PhoneContact) = a == b
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textPhoneNumber: TextView = view.findViewById(R.id.textPhoneNumber)
        val textContactName: TextView = view.findViewById(R.id.textContactName)
        val textCallCount: TextView = view.findViewById(R.id.textCallCount)
        val textLastCall: TextView = view.findViewById(R.id.textLastCall)
        val expandSection: LinearLayout = view.findViewById(R.id.expandSection)
        val callEntriesContainer: LinearLayout = view.findViewById(R.id.callEntriesContainer)
        val btnSherlock: MaterialButton = view.findViewById(R.id.btnSherlock)
        val progressSherlock: ProgressBar = view.findViewById(R.id.progressSherlock)
        val imgPersonAvatar: ImageView = view.findViewById(R.id.imgPersonAvatar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_phone_number, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = getItem(position)
        val ctx = holder.itemView.context
        val isExpanded = position == expandedPosition

        holder.textPhoneNumber.text = contact.number
        holder.textContactName.text = contact.name ?: ""
        holder.textContactName.visibility = if (contact.name != null) View.VISIBLE else View.GONE
        holder.textCallCount.text = "${contact.totalCalls} calls"
        holder.textLastCall.text = dateFormat.format(Date(contact.lastCallDate))

        holder.expandSection.visibility = if (isExpanded) View.VISIBLE else View.GONE

        if (isExpanded) {
            holder.callEntriesContainer.removeAllViews()
            val inflater = LayoutInflater.from(ctx)

            for (entry in contact.calls) {
                val entryView = inflater.inflate(R.layout.item_call_entry, holder.callEntriesContainer, false)

                val indicator = entryView.findViewById<View>(R.id.viewCallTypeIndicator)
                val textCallType = entryView.findViewById<TextView>(R.id.textCallType)
                val textDate = entryView.findViewById<TextView>(R.id.textCallDate)
                val textDuration = entryView.findViewById<TextView>(R.id.textCallDuration)

                val (typeLabel, typeColor) = when (entry.type) {
                    CallLog.Calls.INCOMING_TYPE -> "IN" to R.color.gbx_green
                    CallLog.Calls.OUTGOING_TYPE -> "OUT" to R.color.gbx_blue
                    CallLog.Calls.MISSED_TYPE -> "MISS" to R.color.gbx_red
                    CallLog.Calls.REJECTED_TYPE -> "REJ" to R.color.gbx_red
                    else -> "?" to R.color.gbx_gray
                }

                val color = ContextCompat.getColor(ctx, typeColor)
                indicator.background.setTint(color)
                textCallType.text = typeLabel
                textCallType.setTextColor(color)
                textDate.text = dateFormat.format(Date(entry.date))
                textDuration.text = formatDuration(entry.duration)

                holder.callEntriesContainer.addView(entryView)
            }
        }

        val isLoading = position == sherlockLoadingPosition
        holder.btnSherlock.isEnabled = !isLoading
        holder.progressSherlock.visibility = if (isLoading) View.VISIBLE else View.INVISIBLE

        holder.btnSherlock.setOnClickListener {
            val adapterPos = holder.adapterPosition
            if (adapterPos == RecyclerView.NO_POSITION) return@setOnClickListener
            sherlockLoadingPosition = adapterPos
            notifyItemChanged(adapterPos)
            onSherlockClick(getItem(adapterPos))
        }

        holder.itemView.setOnClickListener {
            val adapterPos = holder.adapterPosition
            if (adapterPos == RecyclerView.NO_POSITION) return@setOnClickListener

            val previousExpanded = expandedPosition
            expandedPosition = if (expandedPosition == adapterPos) -1 else adapterPos

            if (previousExpanded >= 0) notifyItemChanged(previousExpanded)
            if (expandedPosition >= 0) notifyItemChanged(expandedPosition)
        }

        if (contact.linkedPersonId != null) {
            holder.imgPersonAvatar.visibility = View.VISIBLE
            ImageCacheUtil.loadPersonImage(holder.imgPersonAvatar, contact.linkedPersonId, imageBaseUrl, apiKey)
            holder.imgPersonAvatar.setOnClickListener {
                onPersonClick(contact.linkedPersonId)
            }
        } else {
            holder.imgPersonAvatar.visibility = View.GONE
            holder.imgPersonAvatar.setImageBitmap(null)
            holder.imgPersonAvatar.setOnClickListener(null)
        }
    }

    fun clearSherlockProgress() {
        val prev = sherlockLoadingPosition
        sherlockLoadingPosition = -1
        if (prev >= 0) notifyItemChanged(prev)
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds == 0L) return "0s"
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }
}

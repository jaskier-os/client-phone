package com.repository.listener.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.repository.listener.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TelegramSavedAdapter(
    private val onSelectionChanged: (count: Int) -> Unit = {}
) : RecyclerView.Adapter<TelegramSavedAdapter.ViewHolder>() {

    private val messages = mutableListOf<TelegramMessage>()
    private val selected = mutableSetOf<Int>()
    val isSelecting: Boolean get() = selected.isNotEmpty()
    val selectedCount: Int get() = selected.size

    private val dateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")
        .withZone(ZoneId.systemDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val indexNumber: TextView = view.findViewById(R.id.indexNumber)
        val messageText: TextView = view.findViewById(R.id.messageText)
        val messageDate: TextView = view.findViewById(R.id.messageDate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_telegram_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = messages[position]
        holder.indexNumber.text = (position + 1).toString()
        holder.messageText.text = msg.text
        holder.messageDate.text = formatDate(msg.date)

        // Selection visual
        val isSelected = selected.contains(position)
        val bg2 = ContextCompat.getColor(holder.itemView.context, R.color.gbx_bg2)
        holder.itemView.setBackgroundColor(if (isSelected) bg2 else 0x00000000)

        // Tap: toggle selection if in selection mode, else show actions
        holder.itemView.setOnClickListener {
            if (isSelecting) {
                toggleSelection(position)
            } else {
                showMessageActions(it.context, msg)
            }
        }

        // Long-tap: enter selection mode
        holder.itemView.setOnLongClickListener {
            toggleSelection(position)
            true
        }
    }

    override fun getItemCount(): Int = messages.size

    fun submitList(newMessages: List<TelegramMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        selected.clear()
        onSelectionChanged(0)
        notifyDataSetChanged()
    }

    fun clearSelection() {
        val prev = selected.toSet()
        selected.clear()
        prev.forEach { notifyItemChanged(it) }
        onSelectionChanged(0)
    }

    fun getSelectedMessages(): List<TelegramMessage> {
        return selected.sorted().mapNotNull { messages.getOrNull(it) }
    }

    fun copySelected(context: Context) {
        val texts = getSelectedMessages().joinToString("\n\n") { it.text }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Telegram messages", texts))
        Toast.makeText(context, "Copied ${selected.size} message(s)", Toast.LENGTH_SHORT).show()
        clearSelection()
    }

    fun shareSelected(context: Context) {
        val texts = getSelectedMessages().joinToString("\n\n") { it.text }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, texts)
        }
        context.startActivity(Intent.createChooser(intent, "Share messages"))
        clearSelection()
    }

    private fun toggleSelection(position: Int) {
        if (selected.contains(position)) {
            selected.remove(position)
        } else {
            selected.add(position)
        }
        notifyItemChanged(position)
        onSelectionChanged(selected.size)
    }

    private fun formatDate(isoDate: String): String {
        if (isoDate.isBlank()) return ""
        return try {
            val instant = Instant.parse(isoDate)
            dateFormatter.format(instant)
        } catch (_: Exception) {
            isoDate
        }
    }

    private fun showMessageActions(context: Context, msg: TelegramMessage) {
        val actions = arrayOf("Copy text", "Share")
        MaterialAlertDialogBuilder(context)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Telegram message", msg.text))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, msg.text)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share message"))
                    }
                }
            }
            .show()
    }
}

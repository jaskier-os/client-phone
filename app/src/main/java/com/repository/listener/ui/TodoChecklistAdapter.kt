package com.repository.listener.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.repository.listener.R
import java.util.Collections

class TodoChecklistAdapter(
    private val onToggle: (TodoItem) -> Unit,
    private val onDelete: (TodoItem) -> Unit,
    private val onMove: (String, Int) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_ITEM = 0
        private const val TYPE_HEADER = 1
    }

    private val activeItems = mutableListOf<TodoItem>()
    private val doneItems = mutableListOf<TodoItem>()
    private var lastToggleTimeMs = 0L
    private var lastDeleteTimeMs = 0L

    // Snapshot before optimistic toggle, for rollback
    private var snapshot: Pair<List<TodoItem>, List<TodoItem>>? = null
    private var lastOptimisticMs = 0L
    private val animGuardMs = 600L // suppress server refresh during animation

    private val hasDoneHeader: Boolean get() = doneItems.isNotEmpty()

    class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkBox: CheckBox = view.findViewById(R.id.checkBox)
        val todoText: TextView = view.findViewById(R.id.todoText)
        val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)
    }

    class HeaderViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun getItemViewType(position: Int): Int {
        return if (hasDoneHeader && position == activeItems.size) TYPE_HEADER else TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val dp = parent.resources.displayMetrics.density
            val tv = TextView(parent.context).apply {
                setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
                setTextColor(0xFFa89984.toInt())
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
                text = "DONE"
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }
            HeaderViewHolder(tv)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_todo_checklist, parent, false)
            ItemViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderViewHolder) return
        val vh = holder as ItemViewHolder
        val item = getItemAt(position)

        vh.checkBox.setOnCheckedChangeListener(null)
        vh.checkBox.isChecked = item.completed

        vh.todoText.text = item.text
        if (item.completed) {
            vh.todoText.paintFlags = vh.todoText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            vh.todoText.paintFlags = vh.todoText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }

        val alpha = if (item.completed) 0.6f else 1.0f
        vh.checkBox.alpha = alpha
        vh.todoText.alpha = alpha

        val doToggle = {
            val now = System.currentTimeMillis()
            if (now - lastToggleTimeMs >= 400) {
                lastToggleTimeMs = now
                toggleOptimistic(item.id)
                onToggle(item)
            }
        }
        vh.itemView.setOnClickListener { doToggle() }
        vh.checkBox.setOnClickListener { doToggle() }
        vh.deleteButton.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastDeleteTimeMs >= 500) {
                lastDeleteTimeMs = now
                onDelete(item)
            }
        }
    }

    /** Optimistic animated toggle. Saves snapshot for rollback. */
    private fun toggleOptimistic(id: String) {
        snapshot = activeItems.toList() to doneItems.toList()
        lastOptimisticMs = System.currentTimeMillis()

        // Active -> Done
        val activeIdx = activeItems.indexOfFirst { it.id == id }
        if (activeIdx >= 0) {
            val item = activeItems.removeAt(activeIdx)
            notifyItemRemoved(activeIdx)
            doneItems.add(0, item.copy(completed = true))
            if (doneItems.size == 1) {
                notifyItemInserted(activeItems.size)     // header
                notifyItemInserted(activeItems.size + 1) // item
            } else {
                notifyItemInserted(activeItems.size + 1)
            }
            return
        }
        // Done -> Active
        val doneIdx = doneItems.indexOfFirst { it.id == id }
        if (doneIdx >= 0) {
            val item = doneItems.removeAt(doneIdx)
            val adapterPos = activeItems.size + 1 + doneIdx
            notifyItemRemoved(adapterPos)
            if (doneItems.isEmpty()) notifyItemRemoved(activeItems.size)
            activeItems.add(item.copy(completed = false))
            notifyItemInserted(activeItems.size - 1)
        }
    }

    /** Rollback to pre-toggle state (call on error). */
    fun rollback() {
        val snap = snapshot ?: return
        snapshot = null
        activeItems.clear(); activeItems.addAll(snap.first)
        doneItems.clear(); doneItems.addAll(snap.second)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int =
        activeItems.size + (if (hasDoneHeader) 1 else 0) + doneItems.size

    /** Server-authoritative refresh. Skips if animation is in progress
     *  (optimistic toggle already shows the right state). */
    fun submitList(newItems: List<TodoItem>) {
        if (System.currentTimeMillis() - lastOptimisticMs < animGuardMs) {
            snapshot = null // server confirmed, clear rollback
            return         // let animation finish
        }
        snapshot = null
        activeItems.clear()
        doneItems.clear()
        for (item in newItems) {
            if (item.completed) doneItems.add(item) else activeItems.add(item)
        }
        notifyDataSetChanged()
    }

    fun moveItem(from: Int, to: Int) {
        if (from >= activeItems.size || to >= activeItems.size) return
        Collections.swap(activeItems, from, to)
        notifyItemMoved(from, to)
    }

    fun getItem(position: Int): TodoItem = getItemAt(position)

    fun isActiveItem(position: Int): Boolean = position < activeItems.size

    private fun getItemAt(position: Int): TodoItem {
        return if (position < activeItems.size) {
            activeItems[position]
        } else {
            val doneIndex = position - activeItems.size - (if (hasDoneHeader) 1 else 0)
            doneItems[doneIndex]
        }
    }
}

package com.repository.listener.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Typeface
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.repository.listener.R
import com.repository.listener.service.ListenerService
import com.repository.listener.util.LogCollector
import org.json.JSONArray
import org.json.JSONObject

class TodoPrimaryFragment : Fragment() {

    companion object {
        private const val TAG = "TodoPrimaryFragment"
    }

    private lateinit var adapter: TodoChecklistAdapter
    private val items = mutableListOf<TodoItem>()

    private val todoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ListenerService.ACTION_TODO_RESULT) {
                val json = intent.getStringExtra(ListenerService.EXTRA_TODO_DATA) ?: return
                parseTodoResult(json)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_todo_primary, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = TodoChecklistAdapter(
            onToggle = { item -> sendTodoToggle(item) },
            onDelete = { item -> sendTodoDelete(item) },
            onMove = { id, position -> sendTodoMove(id, position) }
        )

        val recyclerView = view.findViewById<RecyclerView>(R.id.todoRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun getMovementFlags(rv: RecyclerView, holder: RecyclerView.ViewHolder): Int {
                if (!adapter.isActiveItem(holder.adapterPosition)) return makeMovementFlags(0, 0)
                return super.getMovementFlags(rv, holder)
            }
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                if (!adapter.isActiveItem(from.adapterPosition) || !adapter.isActiveItem(to.adapterPosition)) return false
                adapter.moveItem(from.adapterPosition, to.adapterPosition)
                return true
            }
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(rv, holder)
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION && adapter.isActiveItem(pos)) {
                    val item = adapter.getItem(pos)
                    sendTodoMove(item.id, pos)
                }
            }
            override fun isLongPressDragEnabled(): Boolean = true
        })
        touchHelper.attachToRecyclerView(recyclerView)

        view.findViewById<FloatingActionButton>(R.id.addTodoFab).setOnClickListener {
            showAddTodoDialog()
        }

        requireContext().registerReceiver(
            todoReceiver,
            IntentFilter(ListenerService.ACTION_TODO_RESULT),
            Context.RECEIVER_NOT_EXPORTED
        )

        // Request initial todo list
        sendTodoListRequest()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        requireContext().unregisterReceiver(todoReceiver)
    }

    private fun sendTodoListRequest() {
        requireContext().sendBroadcast(Intent(ListenerService.ACTION_TODO_LIST_REQ).apply {
            setPackage(requireContext().packageName)
        })
    }

    private fun sendTodoToggle(item: TodoItem) {
        requireContext().sendBroadcast(Intent(ListenerService.ACTION_TODO_TOGGLE).apply {
            setPackage(requireContext().packageName)
            putExtra("todo_id", item.id)
            putExtra("todo_completed", !item.completed)
        })
    }

    private fun sendTodoMove(id: String, position: Int) {
        requireContext().sendBroadcast(Intent(ListenerService.ACTION_TODO_MOVE).apply {
            setPackage(requireContext().packageName)
            putExtra("todo_id", id)
            putExtra("todo_position", position)
        })
    }

    private fun sendTodoDelete(item: TodoItem) {
        requireContext().sendBroadcast(Intent(ListenerService.ACTION_TODO_DELETE).apply {
            setPackage(requireContext().packageName)
            putExtra("todo_id", item.id)
        })
    }

    private fun sendTodoAdd(text: String) {
        requireContext().sendBroadcast(Intent(ListenerService.ACTION_TODO_ADD).apply {
            setPackage(requireContext().packageName)
            putExtra("todo_text", text)
        })
    }

    private fun showAddTodoDialog() {
        val ctx = requireContext()
        val bg = ContextCompat.getColor(ctx, R.color.gbx_bg)
        val bg1 = ContextCompat.getColor(ctx, R.color.gbx_bg1)
        val fg = ContextCompat.getColor(ctx, R.color.gbx_fg)
        val gray = ContextCompat.getColor(ctx, R.color.gbx_gray)
        val orange = ContextCompat.getColor(ctx, R.color.gbx_orange)
        val green = ContextCompat.getColor(ctx, R.color.gbx_green)

        // Container: 24dp root padding (design system: Dialogs)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(8))
        }

        // Title: 16sp bold orange (design system: Typography > Title)
        val title = TextView(ctx).apply {
            text = "New Task"
            setTextColor(orange)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        }
        container.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) })

        // Input: bg1 background, 24dp radius pill shape, monospace 14sp
        // (design system: Input Bar -- bg_input_pill pattern)
        val inputBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(bg1)
            cornerRadius = dp(24).toFloat()
        }
        val editText = EditText(ctx).apply {
            hint = "What needs to be done?"
            setHintTextColor(gray)
            setTextColor(fg)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            background = inputBg
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    val text = text.toString().trim()
                    if (text.isNotEmpty()) {
                        sendTodoAdd(text)
                        (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                            ?.hideSoftInputFromWindow(windowToken, 0)
                        (tag as? AlertDialog)?.dismiss()
                    }
                    true
                } else false
            }
        }
        container.addView(editText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(24) })

        // Button row: right-aligned, Cancel (gray) + Add (green)
        // (design system: Buttons > Programmatic -- bg1 bg, text fg/green)
        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        val cancelBtn = TextView(ctx).apply {
            text = "Cancel"
            setTextColor(gray)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(bg1)
        }
        val addBtn = TextView(ctx).apply {
            text = "Add"
            setTextColor(green)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(bg1)
        }
        buttonRow.addView(cancelBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) })
        buttonRow.addView(addBtn)
        container.addView(buttonRow)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setView(container)
            .create()

        editText.tag = dialog

        cancelBtn.setOnClickListener { dialog.dismiss() }
        addBtn.setOnClickListener {
            val text = editText.text.toString().trim()
            if (text.isNotEmpty()) {
                sendTodoAdd(text)
                dialog.dismiss()
            }
        }

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
            // Auto-show keyboard
            editText.requestFocus()
            editText.postDelayed({
                val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 200)
        }

        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun parseTodoResult(json: String) {
        try {
            val arr = JSONArray(json)
            items.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                items.add(TodoItem(
                    id = obj.optString("id", ""),
                    text = obj.optString("text", ""),
                    completed = obj.optBoolean("completed", false),
                    createdAt = obj.optLong("createdAt", 0)
                ))
            }
            adapter.submitList(items.toList())
        } catch (e: Exception) {
            LogCollector.e(TAG, "Failed to parse todo result: ${e.message}")
        }
    }
}

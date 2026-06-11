package com.repository.navigation.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.repository.navigation.R
import com.repository.navigation.model.RoutePoint
import com.repository.navigation.model.RoutePointType

class RoutePointAdapter(
    private val onPointTap: (Int) -> Unit,
    private val onPointRemove: (Int) -> Unit
) : RecyclerView.Adapter<RoutePointAdapter.ViewHolder>() {

    val items = mutableListOf<RoutePoint>()
    var itemTouchHelper: ItemTouchHelper? = null

    fun submitList(points: List<RoutePoint>) {
        items.clear()
        items.addAll(points)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_route_point, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(position)
    }

    override fun getItemCount() = items.size

    fun reassignTypes() {
        for (i in items.indices) {
            val newType = when (i) {
                0 -> RoutePointType.ORIGIN
                items.size - 1 -> RoutePointType.DESTINATION
                else -> RoutePointType.WAYPOINT
            }
            if (items[i].type != newType) {
                items[i] = items[i].copy(type = newType)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.routePointIcon)
        private val label: TextView = view.findViewById(R.id.routePointLabel)
        private val removeButton: ImageButton = view.findViewById(R.id.routePointRemove)
        private val dragHandle: ImageView = view.findViewById(R.id.routePointDragHandle)

        fun bind(position: Int) {
            val point = items[position]
            val ctx = itemView.context

            // Icon color based on type
            when (point.type) {
                RoutePointType.ORIGIN -> {
                    icon.setImageResource(R.drawable.ic_origin_dot)
                    icon.setColorFilter(ContextCompat.getColor(ctx, R.color.nav_orange))
                }
                RoutePointType.DESTINATION -> {
                    icon.setImageResource(R.drawable.ic_destination_pin)
                    icon.setColorFilter(ContextCompat.getColor(ctx, R.color.nav_gray))
                }
                RoutePointType.WAYPOINT -> {
                    icon.setImageResource(R.drawable.ic_origin_dot)
                    icon.setColorFilter(ContextCompat.getColor(ctx, R.color.nav_gray))
                }
            }

            label.text = point.label
            if (point.point == null) {
                label.setTextColor(ContextCompat.getColor(ctx, R.color.nav_gray))
            } else {
                label.setTextColor(ContextCompat.getColor(ctx, R.color.nav_fg))
            }

            // X button: hidden if only 2 points (origin + destination minimum)
            if (items.size <= 2) {
                removeButton.visibility = View.GONE
            } else {
                removeButton.visibility = View.VISIBLE
                removeButton.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) onPointRemove(pos)
                }
            }

            // Tap row to edit
            itemView.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) onPointTap(pos)
            }

            // Drag handle
            dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper?.startDrag(this)
                }
                false
            }
        }
    }

    class DragCallback(private val adapter: RoutePointAdapter) : ItemTouchHelper.Callback() {
        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            source: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = source.adapterPosition
            val to = target.adapterPosition
            val item = adapter.items.removeAt(from)
            adapter.items.add(to, item)
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            adapter.reassignTypes()
            adapter.notifyDataSetChanged()
        }

        override fun isLongPressDragEnabled() = false
    }
}

package com.repository.listener.ui

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter

/** ArrayAdapter that never filters -- always shows all items in dropdown. */
class NoFilterAdapter<T>(context: Context, resource: Int, private val items: List<T>) :
    ArrayAdapter<T>(context, resource, items) {
    private val noFilter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?) = FilterResults().apply {
            values = items; count = items.size
        }
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            notifyDataSetChanged()
        }
    }
    override fun getFilter() = noFilter
}

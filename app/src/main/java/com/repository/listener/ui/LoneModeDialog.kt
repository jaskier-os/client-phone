package com.repository.listener.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.repository.listener.R
import com.repository.listener.service.ListenerService
import org.json.JSONObject
import java.util.UUID

/**
 * Lone mode modal: two vertically-stacked lists -- Trusted (top) and All devices (bottom).
 * The glasses own the merged, deduplicated device map and push it here via ACTION_LONE_DEVICES.
 * Tapping a device in "All" trusts it; tapping one in "Trusted" untrusts it. Opening the modal
 * activates Lone mode; the Stop button deactivates it (the only off-switch besides a glasses reboot).
 */
class LoneModeDialog : BottomSheetDialogFragment() {

    data class LoneDevice(
        val address: String,
        val name: String,
        val rssi: Int,
        val source: String,
        var trusted: Boolean
    )

    private val devices = LinkedHashMap<String, LoneDevice>()
    private val trustedAdapter = LoneAdapter { onRowTapped(it) }
    private val allAdapter = LoneAdapter { onRowTapped(it) }
    private var emptyLabel: TextView? = null

    private val deviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ListenerService.ACTION_LONE_DEVICES) return
            val json = intent.getStringExtra(ListenerService.EXTRA_LONE_DEVICES) ?: return
            ingest(json)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_lone_mode, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        view.findViewById<RecyclerView>(R.id.rvLoneTrusted).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = trustedAdapter
            isNestedScrollingEnabled = false
        }
        view.findViewById<RecyclerView>(R.id.rvLoneAll).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = allAdapter
            isNestedScrollingEnabled = false
        }
        emptyLabel = view.findViewById(R.id.txtLoneEmpty)

        view.findViewById<Button>(R.id.btnLoneStop).setOnClickListener {
            dispatch("stop_lone", null)
            dismiss()
        }

        ContextCompat.registerReceiver(
            ctx, deviceReceiver,
            IntentFilter(ListenerService.ACTION_LONE_DEVICES),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Opening the modal activates Lone mode (glasses overlay + scanning + alarm).
        dispatch("start_lone", null)
        render()
    }

    override fun onDestroyView() {
        try { requireContext().unregisterReceiver(deviceReceiver) } catch (_: Exception) {}
        super.onDestroyView()
    }

    private fun ingest(json: String) {
        try {
            val arr = JSONObject(json).optJSONArray("devices") ?: return
            devices.clear()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val addr = o.optString("address").uppercase()
                if (addr.isBlank()) continue
                devices[addr] = LoneDevice(
                    address = addr,
                    name = o.optString("name").ifBlank { addr },
                    rssi = o.optInt("rssi", 0),
                    source = o.optString("source", "glasses"),
                    trusted = o.optBoolean("trusted", false)
                )
            }
            render()
        } catch (_: Exception) {}
    }

    private fun onRowTapped(device: LoneDevice) {
        val newTrusted = !device.trusted
        // Optimistic local update; the next glasses push reconciles.
        device.trusted = newTrusted
        dispatch("lone_trust", JSONObject().apply {
            put("address", device.address)
            put("trusted", newTrusted)
        })
        render()
    }

    private fun render() {
        if (view == null) return
        val trusted = devices.values.filter { it.trusted }.sortedByDescending { it.rssi }
        val all = devices.values.filter { !it.trusted }.sortedByDescending { it.rssi }
        trustedAdapter.submit(trusted)
        allAdapter.submit(all)
        emptyLabel?.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun dispatch(type: String, params: JSONObject?) {
        val intent = Intent(requireContext(), ListenerService::class.java).apply {
            action = ListenerService.ACTION_ADB_DISPATCH
            putExtra("command_id", "ui_lone_${UUID.randomUUID().toString().take(8)}")
            putExtra("type", type)
            putExtra("params", (params ?: JSONObject()).toString())
        }
        requireContext().startService(intent)
    }

    private class LoneAdapter(
        private val onClick: (LoneDevice) -> Unit
    ) : RecyclerView.Adapter<LoneAdapter.VH>() {

        private var items = listOf<LoneDevice>()

        fun submit(list: List<LoneDevice>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_lone_device, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val d = items[position]
            holder.name.text = d.name
            holder.info.text = "${d.address}  ${d.rssi} dBm  ${d.source}"
            holder.itemView.setOnClickListener { onClick(d) }
        }

        override fun getItemCount() = items.size

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.txtLoneName)
            val info: TextView = v.findViewById(R.id.txtLoneInfo)
        }
    }
}

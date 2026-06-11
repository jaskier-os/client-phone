package com.repository.listener.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.slider.Slider
import com.repository.listener.R
import com.repository.listener.config.AppConfig

@SuppressLint("MissingPermission")
class MouseConfigDialog : BottomSheetDialogFragment() {

    interface Listener {
        fun onMouseDeviceSelected(sensitivityX: Int, sensitivityY: Int, device: BluetoothDevice)
    }

    private var listener: Listener? = null
    private val adapter = DeviceAdapter { device -> onDeviceTapped(device) }
    private val devices = mutableListOf<DeviceEntry>()
    private var scanning = false
    private var progressScanning: ProgressBar? = null
    private var bondReceiver: BroadcastReceiver? = null

    data class DeviceEntry(
        val device: BluetoothDevice,
        val paired: Boolean,
        val name: String
    )

    fun setListener(l: Listener) { listener = l }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_mouse_config, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        val sliderX = view.findViewById<Slider>(R.id.sliderSensitivityX)
        val sliderY = view.findViewById<Slider>(R.id.sliderSensitivityY)
        val labelX = view.findViewById<TextView>(R.id.labelSensitivityX)
        val labelY = view.findViewById<TextView>(R.id.labelSensitivityY)
        progressScanning = view.findViewById(R.id.progressScanning)

        val savedX = AppConfig.getMouseSensitivityX(ctx).toFloat().coerceIn(500f, 5000f)
        val savedY = AppConfig.getMouseSensitivityY(ctx).toFloat().coerceIn(500f, 5000f)
        sliderX.value = savedX
        sliderY.value = savedY
        labelX.text = "Horizontal sensitivity: ${savedX.toInt()}"
        labelY.text = "Vertical sensitivity: ${savedY.toInt()}"

        sliderX.addOnChangeListener { _, value, _ ->
            labelX.text = "Horizontal sensitivity: ${value.toInt()}"
        }
        sliderY.addOnChangeListener { _, value, _ ->
            labelY.text = "Vertical sensitivity: ${value.toInt()}"
        }

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerDevices)
        recycler.layoutManager = LinearLayoutManager(ctx)
        recycler.adapter = adapter

        loadPairedDevices()
        startDiscovery()
    }

    private fun loadPairedDevices() {
        val btAdapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val bonded = btAdapter.bondedDevices ?: return
        for (device in bonded) {
            val name = device.name ?: device.address
            // Show all paired devices -- computers, laptops, etc.
            val majorClass = device.bluetoothClass?.majorDeviceClass ?: 0
            // Include computers (256), phones (512), uncategorized (0), misc (7936)
            addDevice(DeviceEntry(device, paired = true, name = name))
        }
        adapter.submitList(devices.toList())
    }

    private fun startDiscovery() {
        val btAdapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val ctx = context ?: return
        scanning = true
        progressScanning?.visibility = View.VISIBLE

        ctx.registerReceiver(discoveryReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        })

        // Cancel any ongoing discovery first
        btAdapter.cancelDiscovery()
        btAdapter.startDiscovery()
    }

    private fun stopDiscovery() {
        if (!scanning) return
        scanning = false
        progressScanning?.visibility = View.GONE
        try {
            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        } catch (_: Exception) {}
        try {
            context?.unregisterReceiver(discoveryReceiver)
        } catch (_: Exception) {}
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val name = device.name ?: return // Skip unnamed devices
                    val already = devices.any { it.device.address == device.address }
                    if (!already) {
                        addDevice(DeviceEntry(device, paired = false, name = name))
                        adapter.submitList(devices.toList())
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    progressScanning?.visibility = View.GONE
                }
            }
        }
    }

    private fun addDevice(entry: DeviceEntry) {
        // Keep paired devices at the top
        if (entry.paired) {
            val insertIdx = devices.count { it.paired }
            devices.add(insertIdx, entry)
        } else {
            devices.add(entry)
        }
    }

    private fun onDeviceTapped(device: BluetoothDevice) {
        stopDiscovery()
        val ctx = requireContext()
        val sliderX = requireView().findViewById<Slider>(R.id.sliderSensitivityX)
        val sliderY = requireView().findViewById<Slider>(R.id.sliderSensitivityY)
        val sensX = sliderX.value.toInt()
        val sensY = sliderY.value.toInt()
        AppConfig.setMouseSensitivityX(ctx, sensX)
        AppConfig.setMouseSensitivityY(ctx, sensY)

        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            listener?.onMouseDeviceSelected(sensX, sensY, device)
            dismiss()
        } else {
            // Initiate pairing -- system will show PIN dialog if needed
            device.createBond()
            // Listen for bond state change
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val bondDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    if (bondDevice.address != device.address) return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    if (state == BluetoothDevice.BOND_BONDED) {
                        unregisterBondReceiver()
                        listener?.onMouseDeviceSelected(sensX, sensY, device)
                        dismiss()
                    } else if (state == BluetoothDevice.BOND_NONE) {
                        unregisterBondReceiver()
                        // Pairing failed -- stay in dialog
                    }
                }
            }
            bondReceiver = receiver
            ctx.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        }
    }

    private fun unregisterBondReceiver() {
        bondReceiver?.let { r ->
            try { context?.unregisterReceiver(r) } catch (_: Exception) {}
            bondReceiver = null
        }
    }

    override fun onDestroyView() {
        stopDiscovery()
        unregisterBondReceiver()
        listener = null
        super.onDestroyView()
    }

    // -- Adapter --

    private class DeviceAdapter(
        private val onClick: (BluetoothDevice) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.VH>() {

        private var items = listOf<DeviceEntry>()

        fun submitList(list: List<DeviceEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_mouse_device, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            holder.name.text = entry.name
            holder.info.text = if (entry.paired) "Paired" else entry.device.address
            holder.itemView.setOnClickListener { onClick(entry.device) }
        }

        override fun getItemCount() = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.txtDeviceName)
            val info: TextView = view.findViewById(R.id.txtDeviceInfo)
        }
    }
}

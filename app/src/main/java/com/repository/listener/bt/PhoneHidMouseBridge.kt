package com.repository.listener.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.repository.listener.util.LogCollector

/**
 * Registers the phone as a BT Classic HID mouse device.
 * Any PC that pairs with the phone sees a standard Bluetooth mouse.
 *
 * Lifecycle:
 *   start() -> registers app as HID device, waits for PC connection
 *   sendReport(ByteArray) -> forwards 6-byte mouse report to connected PC
 *   stop() -> unregisters, disconnects
 */
@SuppressLint("MissingPermission")
class PhoneHidMouseBridge(private val context: Context) {

    companion object {
        private const val TAG = "PhoneHidMouse"
    }

    interface Listener {
        fun onRegistered(success: Boolean)
        fun onConnected(device: BluetoothDevice)
        fun onDisconnected()
    }

    var listener: Listener? = null

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var targetDevice: BluetoothDevice? = null
    var registered = false
        private set

    val isConnected: Boolean get() = connectedDevice != null
    val connectedDeviceName: String? get() = connectedDevice?.name

    // Same descriptor as glasses HidReportDescriptor -- 6-byte report, no report ID:
    // [0] buttons (3 bits) + padding
    // [1-2] X (16-bit signed LE relative)
    // [3-4] Y (16-bit signed LE relative)
    // [5] scroll (8-bit signed relative)
    private val reportDescriptor = byteArrayOf(
        0x05, 0x01,                   // USAGE_PAGE (Generic Desktop)
        0x09, 0x02,                   // USAGE (Mouse)
        0xA1.toByte(), 0x01,          // COLLECTION (Application)
        0x09, 0x01,                   //   USAGE (Pointer)
        0xA1.toByte(), 0x00,          //   COLLECTION (Physical)
        // Buttons (3 bits)
        0x05, 0x09,                   //     USAGE_PAGE (Button)
        0x19, 0x01,                   //     USAGE_MINIMUM (1)
        0x29, 0x03,                   //     USAGE_MAXIMUM (3)
        0x15, 0x00,                   //     LOGICAL_MINIMUM (0)
        0x25, 0x01,                   //     LOGICAL_MAXIMUM (1)
        0x95.toByte(), 0x03,          //     REPORT_COUNT (3)
        0x75, 0x01,                   //     REPORT_SIZE (1)
        0x81.toByte(), 0x02,          //     INPUT (Data,Var,Abs)
        // Padding (5 bits)
        0x95.toByte(), 0x01,          //     REPORT_COUNT (1)
        0x75, 0x05,                   //     REPORT_SIZE (5)
        0x81.toByte(), 0x01,          //     INPUT (Cnst)
        // X, Y (16-bit signed relative)
        0x05, 0x01,                   //     USAGE_PAGE (Generic Desktop)
        0x09, 0x30,                   //     USAGE (X)
        0x09, 0x31,                   //     USAGE (Y)
        0x16.toByte(), 0x01, 0x80.toByte(), // LOGICAL_MINIMUM (-32767)
        0x26, 0xFF.toByte(), 0x7F,          // LOGICAL_MAXIMUM (32767)
        0x75, 0x10,                   //     REPORT_SIZE (16)
        0x95.toByte(), 0x02,          //     REPORT_COUNT (2)
        0x81.toByte(), 0x06,          //     INPUT (Data,Var,Rel)
        // Scroll wheel (8-bit signed relative)
        0x09, 0x38,                   //     USAGE (Wheel)
        0x15, 0x81.toByte(),          //     LOGICAL_MINIMUM (-127)
        0x25, 0x7F,                   //     LOGICAL_MAXIMUM (127)
        0x75, 0x08,                   //     REPORT_SIZE (8)
        0x95.toByte(), 0x01,          //     REPORT_COUNT (1)
        0x81.toByte(), 0x06,          //     INPUT (Data,Var,Rel)
        0xC0.toByte(),                //   END_COLLECTION
        0xC0.toByte()                 // END_COLLECTION
    )

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = proxy as BluetoothHidDevice
            LogCollector.i(TAG, "HID_DEVICE profile proxy obtained")
            registerApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            hidDevice = null
            registered = false
            connectedDevice = null
            LogCollector.i(TAG, "HID_DEVICE profile service disconnected")
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@PhoneHidMouseBridge.registered = registered
            LogCollector.i(TAG, "HID app registered=$registered")
            listener?.onRegistered(registered)
            if (registered && targetDevice != null) {
                connectToTarget()
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    LogCollector.i(TAG, "HID connected to ${device.name ?: device.address}")
                    listener?.onConnected(device)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevice = null
                    LogCollector.i(TAG, "HID disconnected from ${device.name ?: device.address}")
                    listener?.onDisconnected()
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            hidDevice?.replyReport(device, type, id, ByteArray(6))
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
        }
    }

    fun start(device: BluetoothDevice) {
        targetDevice = device
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            LogCollector.e(TAG, "No Bluetooth adapter")
            return
        }
        adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        LogCollector.i(TAG, "Requesting HID_DEVICE profile proxy, target=${device.name ?: device.address}")
    }

    private fun registerApp() {
        val hid = hidDevice ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Jaskier Glasses Mouse",
            "Head-tracking mouse via glasses",
            "Jaskier",
            BluetoothHidDevice.SUBCLASS1_MOUSE,
            reportDescriptor
        )
        val ok = hid.registerApp(sdp, null, null, { it.run() }, hidCallback)
        LogCollector.i(TAG, "registerApp=$ok")
    }

    private fun connectToTarget() {
        val hid = hidDevice ?: return
        val target = targetDevice ?: return
        if (!registered) return
        val ok = hid.connect(target)
        LogCollector.i(TAG, "connect(${target.name ?: target.address})=$ok")
    }

    fun sendReport(report: ByteArray): Boolean {
        val device = connectedDevice ?: return false
        val hid = hidDevice ?: return false
        if (!registered) return false
        return hid.sendReport(device, 0, report)
    }

    /**
     * Disconnect from PC and unregister HID app, but keep the profile proxy alive.
     * Call [destroy] for full cleanup (onDestroy).
     * Closing the profile proxy mid-session can reset the BT stack on some devices
     * and kill unrelated connections (e.g. RFCOMM to glasses).
     */
    /**
     * Soft stop: disable report forwarding without touching the BT HID profile.
     * On MIUI, any HID profile operation (disconnect, unregisterApp) during an
     * active RFCOMM session can cascade and drop unrelated BT connections.
     * The HID app stays registered but idle -- reports silently no-op via the
     * connectedDevice=null guard in sendReport().
     */
    fun stop() {
        connectedDevice = null
        targetDevice = null
        LogCollector.i(TAG, "PhoneHidMouseBridge stopped (HID app stays registered)")
    }

    /** Full cleanup -- closes profile proxy. Call from onDestroy only. */
    fun destroy() {
        stop()
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null && hidDevice != null) {
            adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        }
        hidDevice = null
        listener = null
        LogCollector.i(TAG, "PhoneHidMouseBridge destroyed")
    }
}

package com.repository.listener.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import com.repository.listener.util.LogCollector
import java.util.concurrent.ConcurrentHashMap


/**
 * BLE discovery + device identification for the glasses. The actual data link is the
 * RFCOMM message relay ([GlassesRfcommClient]) dialed by bond on a fixed UUID, plus the
 * BLE wake link ([BleWakeNotifyClient]); this helper only scans for + positively
 * identifies our glasses (via REPO service data on the wake service UUID), tracks the
 * connected device identity, and manages BT adapter/permission lifecycle.
 */
class BluetoothHelper(
    val context: Context,
    val initStatus: (INIT_STATUS) -> Unit,
    val deviceFound: (BluetoothDevice) -> Unit,
) {

    @Volatile
    var connectedDeviceName: String? = null
        private set

    @Volatile
    var connectedDeviceMac: String? = null
        private set

    /** Record the device backing the live RFCOMM relay, for identity mirrors + UI. */
    fun setConnectedDevice(name: String?, mac: String?) {
        connectedDeviceName = name
        connectedDeviceMac = mac
    }

    fun clearConnectedDevice() {
        connectedDeviceName = null
        connectedDeviceMac = null
    }

    var statusCallback: ((String) -> Unit)? = null
    var logCallback: ((String) -> Unit)? = null


    companion object {
        const val TAG = "Rokid Glasses CXR-M"
        const val REQUEST_CODE_PERMISSIONS = 100
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.toTypedArray()

        enum class INIT_STATUS {
            NotStart,
            INITING,
            INIT_END
        }
    }

    private fun log(msg: String) {
        LogCollector.i(TAG, msg)
        mainHandler.post { logCallback?.invoke(msg) }
    }

    private fun setStatus(msg: String) {
        mainHandler.post { statusCallback?.invoke(msg) }
    }

    val scanResultMap: ConcurrentHashMap<String, BluetoothDevice> = ConcurrentHashMap()
    val bondedDeviceMap: ConcurrentHashMap<String, BluetoothDevice> = ConcurrentHashMap()

    private val scanner by lazy {
        adapter?.bluetoothLeScanner ?: run {
            log("BLE scanner unavailable - no adapter or permissions")
            throw Exception("Bluetooth is not supported!!")
        }
    }

    private var adapter: BluetoothAdapter? = null
    private var manager: BluetoothManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun onPermissionsGranted() {
        initStatus.invoke(INIT_STATUS.INITING)
        context.registerReceiver(
            bluetoothStateListener,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
        manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (manager == null) {
            log("BluetoothManager is null - no BT support?")
            return
        }
        adapter = manager?.adapter
        if (adapter == null || adapter?.isEnabled != true) {
            requestBluetoothEnableCallback?.invoke()
        } else {
            onBluetoothReady()
        }
    }

    var requestBluetoothEnableCallback: (() -> Unit)? = null

    fun onBluetoothEnabled() {
        adapter = manager?.adapter
        if (adapter != null && adapter!!.isEnabled) {
            onBluetoothReady()
        }
    }

    private fun onBluetoothReady() {
        initStatus.invoke(INIT_STATUS.INIT_END)
        startScan()
    }

    private val WAKE_UUID = ParcelUuid.fromString("c0de0001-cafe-beef-0000-000000000001")
    private val REPO_MAGIC = byteArrayOf(0x52, 0x45, 0x50, 0x4F) // "REPO"
    @Volatile private var repoIdentifiedThisScan = false

    /** Fires when BLE scan positively identifies our glasses via REPO service data.
     *  Second param is the classic BT MAC extracted from service data (for RFCOMM). */
    var onGlassesIdentified: ((BluetoothDevice, String?, Boolean) -> Unit)? = null

    val scanListener = object : android.bluetooth.le.ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.let { r ->
                val name = r.device.name ?: r.device.address
                // Check for our unique service data ("REPO" magic) to positively
                // identify our glasses vs any other BLE device.
                val svcData = r.scanRecord?.getServiceData(WAKE_UUID)
                val isOurs = svcData != null && svcData.size >= 4 &&
                    svcData[0] == REPO_MAGIC[0] && svcData[1] == REPO_MAGIC[1] &&
                    svcData[2] == REPO_MAGIC[2] && svcData[3] == REPO_MAGIC[3]
                // Always fire onGlassesIdentified for REPO devices (skip dedup so
                // re-identification works after glasses reboot while in pair mode).
                if (isOurs && !repoIdentifiedThisScan) {
                    repoIdentifiedThisScan = true
                    val classicMac = if (svcData!!.size >= 10) {
                        String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                            svcData[4], svcData[5], svcData[6],
                            svcData[7], svcData[8], svcData[9])
                    } else null
                    // Byte 10 (when present) is the pairing flag: 1 = this unit is currently
                    // available for pairing (unbonded / pairing window open), 0 = already paired.
                    // Older glasses firmware omits it (10-byte payload); treat absent as "pairing
                    // available" so a not-yet-updated unit still pairs.
                    val pairingAvailable = svcData.size < 11 || svcData[10].toInt() != 0
                    log("BLE: positively identified our glasses via REPO service data classicMac=$classicMac rssi=${r.rssi} pairingAvailable=$pairingAvailable")
                    onGlassesIdentified?.invoke(r.device, classicMac, pairingAvailable)
                }
                if (scanResultMap[name] == null) {
                    log("BLE scan found: $name (${r.device.address}) rssi=${r.rssi} repo=${isOurs}")
                    scanResultMap[name] = r.device
                    deviceFound.invoke(r.device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            log("BLE scan failed: errorCode=$errorCode")
            setStatus("Scan Failed $errorCode")
        }
    }

    fun checkPermissions() {
        initStatus.invoke(INIT_STATUS.NotStart)
        // Permissions handled by Activity; it calls onPermissionsGranted() when ready
    }

    @SuppressLint("MissingPermission")
    fun release() {
        try { context.unregisterReceiver(bluetoothStateListener) } catch (_: Exception) {}
        stopScan()
        stopClassicDiscovery()
    }


    private var fallbackScanRunnable: Runnable? = null
    private var classicDiscoveryReceiver: BroadcastReceiver? = null

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        scanResultMap.clear()
        repoIdentifiedThisScan = false
        log("Starting scan...")

        // Stop any running scans first
        try { scanner.stopScan(scanListener) } catch (_: Exception) {}
        stopClassicDiscovery()
        fallbackScanRunnable?.let { mainHandler.removeCallbacks(it) }

        // Layer 1: already-connected devices (no name filter -- show all)
        val connectedList = getConnectedDevices()
        log("Bonded+connected devices: ${connectedList.size}")
        for (device in connectedList) {
            val name = device.name ?: device.address
            log("  Connected: $name (${device.address}) type=${device.type} bondState=${device.bondState}")
            bondedDeviceMap[name] = device
            deviceFound.invoke(device)
        }

        // Layer 2: bonded/paired devices (no name filter -- show all)
        adapter?.bondedDevices?.forEach { d ->
            val name = d.name ?: d.address
            log("  Bonded: $name (${d.address}) type=${d.type}")
            if (bondedDeviceMap[name] == null) {
                bondedDeviceMap[name] = d
                deviceFound.invoke(d)
            }
        }

        // Layer 3: BLE scan with UUID filter (primary). Our glasses advertise the wake
        // service UUID (c0de0001) with "REPO" service data for positive identification.
        try {
            scanner.startScan(
                listOf(
                    android.bluetooth.le.ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid.fromString("c0de0001-cafe-beef-0000-000000000001"))
                        .build()
                ),
                android.bluetooth.le.ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                scanListener
            )
            log("BLE scan started (wake UUID filter, low latency)")
        } catch (e: Exception) {
            log("BLE scan start failed: ${e.message}")
            setStatus("Scan Failed: ${e.message}")
        }

        // Layer 4: fallback after 5s -- stop BLE, do classic discovery + unfiltered BLE
        fallbackScanRunnable?.let { mainHandler.removeCallbacks(it) }
        fallbackScanRunnable = Runnable {
            if (scanResultMap.isEmpty()) {
                log("No devices via UUID filter, switching to classic + unfiltered BLE...")
                try { scanner.stopScan(scanListener) } catch (_: Exception) {}

                // Classic BT discovery (same as phone BT settings)
                startClassicDiscovery()

                // Also restart BLE without UUID filter
                try {
                    scanner.startScan(
                        null,
                        android.bluetooth.le.ScanSettings.Builder()
                            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .build(),
                        scanListener
                    )
                    log("Unfiltered BLE scan started")
                } catch (e: Exception) {
                    log("Unfiltered BLE scan failed: ${e.message}")
                }
            }
        }
        mainHandler.postDelayed(fallbackScanRunnable!!, 5000)
    }

    @SuppressLint("MissingPermission")
    private fun startClassicDiscovery() {
        stopClassicDiscovery()
        val receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                            ?: return
                        val name = device.name ?: device.address
                        if (scanResultMap[name] == null && bondedDeviceMap[name] == null) {
                            log("Classic BT found: $name (${device.address}) type=${device.type}")
                            scanResultMap[name] = device
                            deviceFound.invoke(device)
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        log("Classic BT discovery finished")
                    }
                }
            }
        }
        classicDiscoveryReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(receiver, filter)

        if (adapter?.isDiscovering == true) {
            adapter?.cancelDiscovery()
        }
        val started = adapter?.startDiscovery() ?: false
        log("Classic BT discovery started: $started")
    }

    @SuppressLint("MissingPermission")
    private fun stopClassicDiscovery() {
        classicDiscoveryReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        classicDiscoveryReceiver = null
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        fallbackScanRunnable?.let { mainHandler.removeCallbacks(it) }
        fallbackScanRunnable = null
        scanner.stopScan(scanListener)
        stopClassicDiscovery()
    }

    @SuppressLint("MissingPermission")
    private fun getConnectedDevices(): List<BluetoothDevice> {
        return adapter?.bondedDevices?.filter { device ->
            try {
                val isConnected =
                    device::class.java.getMethod("isConnected").invoke(device) as Boolean
                isConnected
            } catch (_: Exception) {
                false
            }
        } ?: emptyList()
    }

    val bluetoothStateListener = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_OFF -> {
                        initStatus.invoke(INIT_STATUS.NotStart)
                    }
                    BluetoothAdapter.STATE_ON -> {
                        onBluetoothReady()
                    }
                }
            }
        }
    }
}

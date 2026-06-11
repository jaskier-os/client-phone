package com.repository.listener.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import com.repository.listener.config.AppConfig
import com.repository.listener.util.LogCollector
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.utils.ValueUtil
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec


class BluetoothHelper(
    val context: Context,
    val initStatus: (INIT_STATUS) -> Unit,
    val deviceFound: (BluetoothDevice) -> Unit,
    val onConnectionStateChanged: (ConnectionState) -> Unit
) {
    enum class ConnectionState {
        NOT_CONNECTED,
        CONNECTING,
        CONNECTED
    }

    @Volatile
    var connectionState: ConnectionState = ConnectionState.NOT_CONNECTED
        private set

    /**
     * `System.currentTimeMillis()` when the current state was entered. Callers use this
     * to detect zombie CONNECTING states (callback never fired after a BT stack restart)
     * without having to hook state-change events themselves.
     */
    @Volatile
    var connectionStateSince: Long = System.currentTimeMillis()
        private set

    @Volatile
    var connectedDeviceName: String? = null
        private set

    @Volatile
    var connectedDeviceMac: String? = null
        private set

    private fun setConnectionState(newState: ConnectionState) {
        if (newState == ConnectionState.NOT_CONNECTED) {
            connectedDeviceName = null
            connectedDeviceMac = null
        }
        if (newState != connectionState) connectionStateSince = System.currentTimeMillis()
        connectionState = newState
        mainHandler.post { onConnectionStateChanged(newState) }
    }

    fun resetConnectionState() {
        log("Resetting connection state from $connectionState to NOT_CONNECTED")
        connectionState = ConnectionState.NOT_CONNECTED
        connectionStateSince = System.currentTimeMillis()
        connectedDeviceName = null
        connectedDeviceMac = null
    }

    fun forceConnected(name: String?, mac: String?) {
        log("Forcing CONNECTED state (name=$name mac=$mac)")
        connectedDeviceName = name
        connectedDeviceMac = mac
        setConnectionState(ConnectionState.CONNECTED)
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
    var onGlassesIdentified: ((BluetoothDevice, String?) -> Unit)? = null

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
                    log("BLE: positively identified our glasses via REPO service data classicMac=$classicMac rssi=${r.rssi}")
                    onGlassesIdentified?.invoke(r.device, classicMac)
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
        glassesGattClient?.close()
        glassesGattClient = null
        try { CxrApi.getInstance().deinitWifiP2P() } catch (_: Exception) {}
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

        // Layer 3: BLE scan with UUID filter (primary)
        // Scan for both CXR service UUID (Rokid pairing) and our custom wake service UUID.
        // Our glasses advertise c0de0001 with "REPO" service data for positive identification.
        try {
            scanner.startScan(
                listOf(
                    android.bluetooth.le.ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid.fromString("00009100-0000-1000-8000-00805f9b34fb"))
                        .build(),
                    android.bluetooth.le.ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid.fromString("c0de0001-cafe-beef-0000-000000000001"))
                        .build()
                ),
                android.bluetooth.le.ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                scanListener
            )
            log("BLE scan started (CXR + wake UUID filter, low latency)")
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

    var mSocketUuid: String? = null
    var mMacAddress: String? = null
    private var tSocketUuid: String? = null
    private var tMacAddress: String? = null
    private var aesKey = "6b4b588923c84fb6b0a337c0ed3419d4"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var glassesGattClient: GlassesGattClient? = null

    // Saved BluetoothController refs for direct RFCOMM fallback
    private var savedBtCallback: Any? = null
    private var savedProtocol: Any? = null
    private var directRfcommAttempted = false

    private fun setReflectionField(obj: Any, fieldName: String, value: Any?) {
        val field = obj.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(obj, value)
    }

    private fun encryptSerialNumber(serialNumber: String): ByteArray {
        val secretKeySpec = SecretKeySpec(aesKey.toByteArray(), "AES")
        val ivParameterSpec = IvParameterSpec(aesKey.toByteArray(), 0, 16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec)
        return cipher.doFinal(serialNumber.toByteArray())
    }

    fun clearGattCache(deviceAddress: String) {
        context.getSharedPreferences("gatt_cache", Context.MODE_PRIVATE)
            .edit().remove("socketUuid_$deviceAddress").remove("macAddress_$deviceAddress").apply()
        log("Cleared cached GATT info for $deviceAddress")
    }

    @SuppressLint("MissingPermission")
    fun reconnectFromCache(mac: String, socketUuid: String, serialNumber: String) {
        log("=== reconnectFromCache ===")
        log("  mac=$mac uuid=$socketUuid")
        setConnectionState(ConnectionState.CONNECTING)

        if (manager == null) {
            manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        }
        adapter = manager?.adapter
        if (adapter == null || adapter?.isEnabled != true) {
            log("BT adapter not ready for cached reconnect")
            setConnectionState(ConnectionState.NOT_CONNECTED)
            return
        }

        val device = adapter!!.getRemoteDevice(mac)
        connectedDeviceName = device.name
        tSocketUuid = socketUuid
        tMacAddress = mac

        log("Deinit existing CXR connections...")
        try { CxrApi.getInstance().deinitWifiP2P() } catch (e: Exception) { log("  deinitWifiP2P: ${e.message}") }

        // Reset BluetoothController's internal connected state via reflection.
        // After force-stop + restart, the SDK singleton thinks it's still connected
        // and silently ignores new connectBluetooth() calls.
        // Full reset of BluetoothController singleton so CxrApi.connectBluetooth()
        // initializes fresh after force-stop. Field names from decompiled SDK.
        try {
            val btCtrl = com.rokid.cxr.client.controllers.BluetoothController.getInstance()
            val cls = btCtrl.javaClass
            fun resetField(name: String, value: Any?) {
                try {
                    val f = cls.getDeclaredField(name)
                    f.isAccessible = true
                    f.set(btCtrl, value)
                } catch (_: Exception) {}
            }
            resetField("n", false)   // connected flag
            resetField("b", false)   // init mode
            resetField("r", 0)       // socket retry count
            resetField("s", 0)       // BLE retry count
            resetField("l", null)    // stale socket
            resetField("k", null)    // stale device
            // Keep e (callback) and m (protocol) -- they survive force-stop
            // as SDK singleton objects and are needed for connectDirectRfcomm
            log("BluetoothController reset done (keeping callback+protocol)")
        } catch (e: Exception) {
            log("Failed to reset BluetoothController: ${e.message}")
        }

        connectWithFallback(device, serialNumber, socketUuid, mac)
    }

    @SuppressLint("MissingPermission")
    fun repairAndConnect(device: BluetoothDevice, serialNumber: String) {
        setConnectionState(ConnectionState.CONNECTING)
        log("=== repairAndConnect: removing bond then re-pairing ===")
        clearGattCache(device.address)
        try {
            val removeBond = device.javaClass.getMethod("removeBond")
            val result = removeBond.invoke(device) as Boolean
            log("  removeBond: $result")
        } catch (e: Exception) {
            log("  removeBond failed: ${e.message}")
        }

        // Wait for bond removal, then create new bond
        mainHandler.postDelayed({
            log("  Creating bond...")
            try {
                device.createBond()
            } catch (e: Exception) {
                log("  createBond failed: ${e.message}")
            }

            // Wait for pairing to complete, then initDevice
            mainHandler.postDelayed({
                log("  Bond state after re-pair: ${device.bondState}")
                doInitDevice(device, serialNumber)
            }, 5000)
        }, 2000)
    }

    @SuppressLint("MissingPermission")
    fun initDevice(device: BluetoothDevice, serialNumber: String) {
        setConnectionState(ConnectionState.CONNECTING)
        connectedDeviceName = device.name
        log("=== initDevice START ===")
        log("Device: ${device.name} (${device.address})")
        log("Device type: ${device.type} bondState: ${device.bondState}")
        log("Serial: $serialNumber")

        log("Deinit existing CXR connections...")
        try {
            CxrApi.getInstance().deinitWifiP2P()
            log("  deinitWifiP2P OK")
        } catch (e: Exception) {
            log("  deinitWifiP2P: ${e.message}")
        }

        doInitDevice(device, serialNumber)
    }

    @SuppressLint("MissingPermission")
    private fun doInitDevice(device: BluetoothDevice, serialNumber: String) {
        log("Device: ${device.name} addr=${device.address} type=${device.type}")

        // Try cached connection info first (skip BLE GATT entirely)
        val prefs = context.getSharedPreferences("gatt_cache", Context.MODE_PRIVATE)
        val cachedUuid = prefs.getString("socketUuid_${device.address}", null)
        val cachedMac = prefs.getString("macAddress_${device.address}", null)

        if (cachedUuid != null && cachedMac != null) {
            log("=== Using cached connection info (skip GATT) ===")
            log("  cachedUuid=$cachedUuid cachedMac=$cachedMac")
            setStatus("Connecting with cached info...")
            tSocketUuid = cachedUuid
            tMacAddress = cachedMac
            connectWithFallback(device, serialNumber, cachedUuid, cachedMac)
            return
        }

        // Fallback: check AppConfig for saved connection info from a previous session
        val savedUuid = AppConfig.getGlassesSocketUuid(context)
        val savedMac = AppConfig.getGlassesMac(context)
        if (savedUuid.isNotEmpty() && savedMac.isNotEmpty()) {
            log("=== Using AppConfig saved connection info (skip GATT) ===")
            log("  savedUuid=$savedUuid savedMac=$savedMac")
            setStatus("Connecting with saved info...")
            tSocketUuid = savedUuid
            tMacAddress = savedMac
            connectWithFallback(device, serialNumber, savedUuid, savedMac)
            return
        }

        // Last resort before GATT: try direct RFCOMM port 4 with the bonded address.
        // CxrApi.connectBluetooth will fail (no valid RFCOMM service UUID) but the
        // direct RFCOMM fallback uses a fixed channel and bypasses SDP entirely.
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            log("=== No cached info, trying direct RFCOMM with bonded address ===")
            log("  bondedAddr=${device.address}")
            setStatus("Trying direct RFCOMM...")
            tSocketUuid = "00000000-0000-0000-0000-000000000000"
            tMacAddress = device.address
            connectWithFallback(device, serialNumber, tSocketUuid!!, tMacAddress!!)
            return
        }

        doGattConnect(device, serialNumber)
    }

    private fun connectWithFallback(device: BluetoothDevice, serialNumber: String, uuid: String, mac: String) {
        // The CXR-M control channel must never dial the message-relay UUID. The
        // glasses CXRService listens on a per-session random serviceRecord UUID
        // advertised over the Rokid GATT characteristic (9301); the relay UUID
        // resolves to the MessageRelay socket and corrupts its length-prefix frame
        // parser. A cached value equal to MESSAGE_UUID is a poisoned/stale cache --
        // clear it and run GATT discovery to obtain the real serviceRecord UUID.
        if (uuid == GlassesRfcommClient.MESSAGE_UUID) {
            log("Cached CXR socketUuid is the message-relay UUID -- invalid; clearing and running GATT discovery")
            context.getSharedPreferences("gatt_cache", Context.MODE_PRIVATE)
                .edit().remove("socketUuid_${device.address}").apply()
            AppConfig.setGlassesSocketUuid(context, "")
            doGattConnect(device, serialNumber)
            return
        }

        setConnectionState(ConnectionState.CONNECTING)
        directRfcommAttempted = false
        log("=== connectBluetooth (cached) ===")
        log("  uuid=$uuid mac=$mac")
        setStatus("Connecting to bluetooth (cached)...")

        val snEncryptedContent = encryptSerialNumber(serialNumber)
        log("  Serial encrypted, ${snEncryptedContent.size} bytes")

        CxrApi.getInstance().connectBluetooth(context, uuid, mac, object : BluetoothStatusCallback {
            override fun onConnectionInfo(socketUuid: String?, macAddress: String?, rokidAccount: String?, glassesType: Int) {}

            override fun onConnected() {
                log(">>> connectBluetooth (cached) onConnected - SUCCESS")
                mSocketUuid = tSocketUuid
                mMacAddress = tMacAddress
                connectedDeviceMac = tMacAddress
                tMacAddress?.let { AppConfig.setGlassesMac(context, it) }
                tSocketUuid?.let { AppConfig.setGlassesSocketUuid(context, it) }
                connectedDeviceName?.let { AppConfig.setGlassesDeviceName(context, it) }
                installBtInterceptor()
                setConnectionState(ConnectionState.CONNECTED)
            }

            override fun onDisconnected() {
                log(">>> connectBluetooth (cached) onDisconnected")
                setConnectionState(ConnectionState.NOT_CONNECTED)
            }

            override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
                if (!directRfcommAttempted) {
                    directRfcommAttempted = true
                    log(">>> connectBluetooth (cached) FAILED: $errorCode -- trying direct RFCOMM port 4")
                    connectDirectRfcomm(device, serialNumber, uuid, mac)
                } else {
                    log(">>> direct RFCOMM also FAILED: $errorCode -- falling back to GATT discovery")
                    doGattConnect(device, serialNumber)
                }
            }
        }, snEncryptedContent, aesKey)

        // Save BluetoothController callback and protocol refs immediately after
        // CxrApi sets them up (before the background thread can fail and clear them).
        // These are needed for the direct RFCOMM fallback.
        saveBtControllerRefs()
    }

    private fun saveBtControllerRefs() {
        try {
            val btCtrl = com.rokid.cxr.client.controllers.BluetoothController.getInstance()
            val cls = btCtrl.javaClass

            val eField = cls.getDeclaredField("e")
            eField.isAccessible = true
            savedBtCallback = eField.get(btCtrl)

            val mField = cls.getDeclaredField("m")
            mField.isAccessible = true
            savedProtocol = mField.get(btCtrl)

            log("Saved BluetoothController refs (callback=${savedBtCallback != null}, protocol=${savedProtocol != null})")
        } catch (e: Exception) {
            log("Failed to save BT controller refs: ${e.message}")
            savedBtCallback = null
            savedProtocol = null
        }
    }

    /**
     * Direct RFCOMM port 4 connection -- fully self-contained, no SDK dependencies.
     *
     * Creates fresh CXRSocketProtocol and callback adapter via reflection,
     * opens RFCOMM channel 4 directly (bypasses SDP), injects into
     * BluetoothController singleton, and starts the protocol.
     *
     * This works even after force-stop or when the SDK is in a broken state.
     */
    @SuppressLint("MissingPermission")
    private fun connectDirectRfcomm(device: BluetoothDevice, serialNumber: String, uuid: String, mac: String) {
        log("=== Direct RFCOMM port 4 (self-contained) ===")
        setStatus("Direct RFCOMM connecting...")

        Thread {
            try {
                val btAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                if (btAdapter == null) {
                    log("No BT adapter for direct RFCOMM")
                    mainHandler.post { doGattConnect(device, serialNumber) }
                    return@Thread
                }
                btAdapter.cancelDiscovery()
                val btDevice = btAdapter.getRemoteDevice(mac)

                // Connect via UUID (SDP resolves to current RFCOMM channel -- channel is dynamic)
                val socket = btDevice.createRfcommSocketToServiceRecord(java.util.UUID.fromString(uuid))
                log("Connecting RFCOMM socket to $mac (uuid=$uuid)...")
                socket.connect()
                log("RFCOMM socket connected!")

                val btCtrl = com.rokid.cxr.client.controllers.BluetoothController.getInstance()
                val cls = btCtrl.javaClass

                // Create fresh CXRSocketProtocol: constructor(String, boolean, long, int)
                val protocolClass = Class.forName("com.rokid.cxr.CXRSocketProtocol")
                val protoCtor = protocolClass.getDeclaredConstructor(
                    String::class.java, Boolean::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType, Int::class.javaPrimitiveType
                )
                protoCtor.isAccessible = true
                val freshProtocol = protoCtor.newInstance("BluetoothController", true, 0L, 1024)
                log("Created fresh CXRSocketProtocol")

                // Get or create the callback adapter (field t = BluetoothController$a)
                // It bridges CXRSocketProtocol.Callback -> BluetoothController.Callback (field e)
                val tField = cls.getDeclaredField("t")
                tField.isAccessible = true
                var callbackAdapter = tField.get(btCtrl)
                if (callbackAdapter == null) {
                    // Create fresh: BluetoothController$a is inner class, needs outer ref
                    val innerClass = Class.forName("${cls.name}\$a")
                    val innerCtor = innerClass.getDeclaredConstructors().first()
                    innerCtor.isAccessible = true
                    callbackAdapter = innerCtor.newInstance(btCtrl)
                    log("Created fresh callback adapter (BluetoothController\$a)")
                }

                // Restore the upstream callback (e) from CxrController if null
                val eField = cls.getDeclaredField("e")
                eField.isAccessible = true
                if (eField.get(btCtrl) == null) {
                    try {
                        val cxrCtrl = com.rokid.cxr.client.controllers.CxrController.getInstance()
                        val cxrCls = cxrCtrl.javaClass
                        // CxrController's inner callback adapter is field 'e'
                        val cxrEField = cxrCls.getDeclaredField("e")
                        cxrEField.isAccessible = true
                        val cxrCallback = cxrEField.get(cxrCtrl)
                        if (cxrCallback != null) {
                            eField.set(btCtrl, cxrCallback)
                            log("Restored upstream callback from CxrController")
                        }
                    } catch (ex: Exception) {
                        log("Could not restore upstream callback: ${ex.message}")
                    }
                }

                // Set up ALL BluetoothController fields for a clean connection
                setReflectionField(btCtrl, "a", context)
                setReflectionField(btCtrl, "o", uuid)
                setReflectionField(btCtrl, "p", mac)
                setReflectionField(btCtrl, "j", btAdapter)
                setReflectionField(btCtrl, "k", btDevice)
                setReflectionField(btCtrl, "l", socket)
                setReflectionField(btCtrl, "m", freshProtocol)
                setReflectionField(btCtrl, "t", callbackAdapter)
                setReflectionField(btCtrl, "b", false)   // not init mode
                setReflectionField(btCtrl, "n", false)    // not connected yet
                setReflectionField(btCtrl, "r", 0)
                setReflectionField(btCtrl, "s", 0)

                // Start protocol: h() spawns daemon thread -> m.run(l, null, t, true, true)
                // On success: sets n=true, fires onConnected through callback chain
                val hMethod = cls.getDeclaredMethod("h")
                hMethod.isAccessible = true
                hMethod.invoke(btCtrl)
                log("Protocol started on direct RFCOMM socket")
            } catch (e: Exception) {
                log("Direct RFCOMM failed: ${e.message}")
                e.printStackTrace()
                mainHandler.post {
                    log("Falling back to GATT discovery after direct RFCOMM failure")
                    doGattConnect(device, serialNumber)
                }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun doGattConnect(device: BluetoothDevice, serialNumber: String) {
        log("=== GATT connect to glasses ===")
        setStatus("GATT connecting to glasses...")

        glassesGattClient?.close()
        glassesGattClient = GlassesGattClient(context) { msg -> mainHandler.post { logCallback?.invoke(msg) } }.also { client ->
            client.connect(device, object : GlassesGattClient.Callback {
                override fun onConnectionInfo(
                    socketUuid: String,
                    macAddress: String,
                    rokidAccount: String?,
                    glassesType: Int
                ) {
                    log(">>> GATT onConnectionInfo: uuid=$socketUuid mac=$macAddress type=$glassesType")
                    tSocketUuid = socketUuid
                    tMacAddress = macAddress

                    // Cache for next time
                    context.getSharedPreferences("gatt_cache", Context.MODE_PRIVATE)
                        .edit()
                        .putString("socketUuid_${device.address}", socketUuid)
                        .putString("macAddress_${device.address}", macAddress)
                        .apply()
                    log(">>> Cached connection info for ${device.address}")
                    AppConfig.setGlassesMac(context, macAddress)
                    AppConfig.setGlassesSocketUuid(context, socketUuid)

                    connect(socketUuid, macAddress, serialNumber)
                }

                override fun onFailed(reason: String) {
                    log(">>> GATT failed: $reason")
                    setConnectionState(ConnectionState.NOT_CONNECTED)
                    setStatus("GATT failed: $reason")
                }
            })
        }
    }

    fun connect(socketUuid: String, macAddress: String, serialNumber: String) {
        setConnectionState(ConnectionState.CONNECTING)
        log("=== connectBluetooth ===")
        log("  uuid=$socketUuid mac=$macAddress")
        setStatus("Connecting to bluetooth...")

        val snEncryptedContent = encryptSerialNumber(serialNumber)
        log("  Serial encrypted, ${snEncryptedContent.size} bytes")

        CxrApi.getInstance().connectBluetooth(context, socketUuid, macAddress, object : BluetoothStatusCallback {
            override fun onConnectionInfo(
                socketUuid: String?,
                macAddress: String?,
                rokidAccount: String?,
                glassesType: Int
            ) {
                log(">>> connect onConnectionInfo (unexpected here)")
            }

            override fun onConnected() {
                log(">>> connectBluetooth onConnected - SUCCESS")
                mSocketUuid = tSocketUuid
                mMacAddress = tMacAddress
                connectedDeviceMac = tMacAddress
                tMacAddress?.let { AppConfig.setGlassesMac(context, it) }
                tSocketUuid?.let { AppConfig.setGlassesSocketUuid(context, it) }
                connectedDeviceName?.let { AppConfig.setGlassesDeviceName(context, it) }
                installBtInterceptor()
                setConnectionState(ConnectionState.CONNECTED)
            }

            override fun onDisconnected() {
                log(">>> connectBluetooth onDisconnected")
                setConnectionState(ConnectionState.NOT_CONNECTED)
                setStatus("Disconnected from bluetooth")
            }

            override fun onFailed(p0: ValueUtil.CxrBluetoothErrorCode?) {
                log(">>> connectBluetooth onFailed: $p0")
                setConnectionState(ConnectionState.NOT_CONNECTED)
                setStatus("BT connect failed: $p0")
            }
        }, snEncryptedContent, aesKey)
    }

    /**
     * Wrap CxrController's callback to intercept ALL BT notifications.
     * Logs the raw command string from glasses so we can debug.
     */
    private fun installBtInterceptor() {
        try {
            val ctrl = com.rokid.cxr.client.controllers.CxrController.getInstance()
            val aField = ctrl.javaClass.getDeclaredField("a")
            aField.isAccessible = true
            val original = aField.get(ctrl) as? com.rokid.cxr.client.controllers.CxrController.Callback
            if (original == null) {
                log("[BT-INTERCEPT] No callback to wrap")
                return
            }
            val wrapper = object : com.rokid.cxr.client.controllers.CxrController.Callback {
                override fun onConnectionInfo(p0: String?, p1: String?, p2: String?, p3: Int) {
                    original.onConnectionInfo(p0, p1, p2, p3)
                }
                override fun onStatusUpdate(p0: ValueUtil.CxrStatus?, p1: ValueUtil.CxrBluetoothErrorCode?) {
                    original.onStatusUpdate(p0, p1)
                }
                override fun onValueUpdate(cmd: String?, caps: com.rokid.cxr.Caps?) {
                    // Log ALL notifications from glasses
                    var capsStr = ""
                    try {
                        if (caps != null) {
                            val parts = mutableListOf<String>()
                            for (i in 0..5) {
                                try { parts.add("[$i]=${caps.at(i).getString()}") } catch (_: Exception) { break }
                            }
                            capsStr = parts.joinToString(", ")
                        }
                    } catch (_: Exception) {}
                    log("[BT-NOTIFY] cmd=$cmd caps={$capsStr}")
                    original.onValueUpdate(cmd, caps)
                }
                override fun onStartAudioStream(p0: Int, p1: Int, p2: String?, p3: com.rokid.cxr.Caps?) {}
                override fun onAudioStream(p0: Int, p1: ByteArray?, p2: Int, p3: Int) {}
                override fun onAudioStreamFinish(p0: Int) {}
                override fun onARTCFrame(p0: ByteArray?, p1: Long) {}
            }
            aField.set(ctrl, wrapper)
            log("[BT-INTERCEPT] Installed BT notification interceptor")
        } catch (e: Exception) {
            log("[BT-INTERCEPT] Failed: ${e.message}")
        }
    }
}

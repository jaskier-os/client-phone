package com.repository.listener.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.repository.listener.util.LogCollector
import java.util.UUID

/**
 * Custom BLE GATT client replacing CxrApi.initBluetooth() for Phase 1.
 *
 * CXR GATT service is BLE-only (not visible over BR/EDR). Glasses stop BLE
 * advertising when a GATT client is connected. If the phone system BT
 * auto-connects after a glasses reboot, the glasses stop advertising and
 * our LE connectGatt hangs.
 *
 * Strategy:
 *   Attempt 1 - TRANSPORT_LE, autoConnect=false (fast, works if glasses are advertising)
 *   Attempt 2 - Disconnect system BT profiles from glasses, wait, then LE again
 *   Attempt 3 - Flush stale LE cache + fresh active connect (autoConnect=false, 15s)
 */
@SuppressLint("MissingPermission")
class GlassesGattClient(
    private val context: Context,
    private val logCallback: ((String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "Rokid Glasses CXR-M"
        private val SERVICE_UUID = UUID.fromString("00009100-0000-1000-8000-00805f9b34fb")
        private val CHARACTERISTIC_UUID = UUID.fromString("00009301-0000-1000-8000-00805f9b34fb")

        init {
            try { System.loadLibrary("mutils") } catch (_: UnsatisfiedLinkError) {}
            try { System.loadLibrary("caps") } catch (_: UnsatisfiedLinkError) {}
        }

        private const val TIMEOUT_LE_MS = 7_000L
        private const val TIMEOUT_AUTOCONNECT_MS = 60_000L
        private const val TIMEOUT_DISCOVER_MS = 5_000L
        private const val TIMEOUT_MTU_MS = 3_000L
        private const val TIMEOUT_READ_MS = 5_000L
        private const val PROFILE_DISCONNECT_WAIT_MS = 2_000L
        private const val PROFILE_DISCONNECT_SAFETY_TIMEOUT_MS = 5_000L
        private const val POST_CLOSE_DELAY_MS = 1_000L
        private const val TIMEOUT_ATTEMPT3_MS = 15_000L
        private const val SERVICE_RETRY_DELAY_MS = 3_000L
        private const val MAX_SERVICE_RETRIES = 3
    }

    interface Callback {
        fun onConnectionInfo(socketUuid: String, macAddress: String, rokidAccount: String?, glassesType: Int)
        fun onFailed(reason: String)
    }

    private enum class State {
        IDLE, CONNECTING, FLUSHING, DISCOVERING_SERVICES, REQUESTING_MTU, READING_CHAR, DONE
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var state = State.IDLE
    private var callback: Callback? = null
    private var device: BluetoothDevice? = null
    private var attempt = 0
    private var timeoutRunnable: Runnable? = null
    private var closed = false
    private var serviceRetryCount = 0

    private fun log(msg: String) {
        LogCollector.i(TAG, "[GATT] $msg")
        logCallback?.invoke("[GATT] $msg")
    }

    fun connect(device: BluetoothDevice, callback: Callback) {
        this.device = device
        this.callback = callback
        this.attempt = 0
        this.closed = false
        doAttempt1()
    }

    fun close() {
        closed = true
        cancelTimeout()
        closeGatt()
        state = State.IDLE
        callback = null
        device = null
    }

    // --- Attempt 1: TRANSPORT_LE, autoConnect=false (fast, needs advertising) ---

    private fun doAttempt1() {
        if (closed) return
        closeGatt()
        attempt = 1

        val dev = device ?: run { fail("No device set"); return }
        state = State.CONNECTING
        log("Attempt 1/3: LE connect to ${dev.name ?: dev.address} (autoConnect=false)...")

        startTimeout(TIMEOUT_LE_MS) {
            log("Attempt 1 timed out (glasses probably not advertising)")
            doAttempt2()
        }

        gatt = dev.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        gatt?.let {
            val refreshed = refreshGattCache(it)
            log("GATT cache refresh (attempt 1): $refreshed")
        }
    }

    // --- Attempt 2: Disconnect system BT profiles + LE autoConnect (patient) ---

    private fun doAttempt2() {
        if (closed) return
        closeGatt()
        attempt = 2

        val dev = device ?: run { fail("No device set"); return }

        // Delay after closeGatt() to give glasses time to detect BLE disconnect and restart advertising
        log("Attempt 2/3: Waiting ${POST_CLOSE_DELAY_MS}ms for glasses to restart advertising...")
        handler.postDelayed({
            if (closed) return@postDelayed

            log("Disconnecting system BT profiles from ${dev.name ?: dev.address}...")
            disconnectSystemProfiles(dev) {
                if (closed) return@disconnectSystemProfiles

                log("Waiting ${PROFILE_DISCONNECT_WAIT_MS}ms then LE autoConnect (up to ${TIMEOUT_AUTOCONNECT_MS/1000}s)...")
                handler.postDelayed({
                    if (closed) return@postDelayed

                    state = State.CONNECTING
                    log("LE connect with autoConnect=true (waiting for glasses to advertise)...")

                    startTimeout(TIMEOUT_AUTOCONNECT_MS) {
                        log("Attempt 2 timed out after ${TIMEOUT_AUTOCONNECT_MS/1000}s")
                        doAttempt3()
                    }

                    gatt = dev.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    gatt?.let {
                        val refreshed = refreshGattCache(it)
                        log("GATT cache refresh (attempt 2): $refreshed")
                    }
                }, PROFILE_DISCONNECT_WAIT_MS)
            }
        }, POST_CLOSE_DELAY_MS)
    }

    // --- Attempt 3: flush stale LE cache + fresh active connect ---

    private fun doAttempt3() {
        if (closed) return
        closeGatt()
        attempt = 3

        val dev = device ?: run { fail("No device set"); return }

        log("Attempt 3/3: flush GATT cache + fresh LE connect (autoConnect=false)...")

        // Delay after closeGatt() to give glasses time to detect BLE disconnect and restart advertising
        handler.postDelayed({
            if (closed) return@postDelayed

            // Create a temporary GATT handle just to refresh the cache
            val temp = dev.connectGatt(context, false, object : BluetoothGattCallback() {}, BluetoothDevice.TRANSPORT_LE)
            if (temp != null) {
                refreshGattCache(temp)
                temp.disconnect()
                temp.close()
                log("GATT cache flushed")
            }

            // Another short delay after temp handle close for glasses to restart advertising
            handler.postDelayed({
                if (closed) return@postDelayed

                state = State.CONNECTING
                log("LE connect with autoConnect=false (up to ${TIMEOUT_ATTEMPT3_MS/1000}s)...")

                startTimeout(TIMEOUT_ATTEMPT3_MS) {
                    log("Attempt 3 timed out")
                    fail("All 3 connection attempts failed. Glasses may need re-pairing.")
                }

                gatt = dev.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                gatt?.let {
                    val refreshed = refreshGattCache(it)
                    log("GATT cache refresh (attempt 3): $refreshed")
                }
            }, POST_CLOSE_DELAY_MS)
        }, POST_CLOSE_DELAY_MS)
    }

    // --- Disconnect system BT profiles (A2DP, HID, HEADSET) to free BLE ---

    private fun disconnectSystemProfiles(dev: BluetoothDevice, onDone: () -> Unit) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            log("No BluetoothAdapter, skipping profile disconnect")
            onDone()
            return
        }

        val profiles = listOf(
            BluetoothProfile.A2DP,
            BluetoothProfile.HEADSET,
            4 /* BluetoothProfile.HID_HOST */
        )
        var remaining = profiles.size
        var disconnectedAny = false
        var done = false

        // Safety timeout: proceed even if profile proxy callbacks never fire
        val safetyTimeout = Runnable {
            if (!done) {
                done = true
                log("Profile disconnect safety timeout after ${PROFILE_DISCONNECT_SAFETY_TIMEOUT_MS}ms (proceeding anyway, $remaining proxies never responded)")
                onDone()
            }
        }
        handler.postDelayed(safetyTimeout, PROFILE_DISCONNECT_SAFETY_TIMEOUT_MS)

        for (profile in profiles) {
            try {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profileType: Int, proxy: BluetoothProfile) {
                        try {
                            val connected = proxy.connectedDevices
                            val target = connected.find { it.address == dev.address }
                            if (target != null) {
                                val profileName = when (profileType) {
                                    BluetoothProfile.A2DP -> "A2DP"
                                    BluetoothProfile.HEADSET -> "HEADSET"
                                    4 -> "HID"
                                    else -> "PROFILE_$profileType"
                                }
                                try {
                                    val disconnect = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                                    val result = disconnect.invoke(proxy, target)
                                    log("Disconnected $profileName: $result")
                                    disconnectedAny = true
                                } catch (e: Exception) {
                                    log("Failed to disconnect $profileName: ${e.message}")
                                }
                            }
                        } finally {
                            adapter.closeProfileProxy(profileType, proxy)
                            remaining--
                            if (remaining <= 0 && !done) {
                                done = true
                                handler.removeCallbacks(safetyTimeout)
                                log("Profile disconnect done (disconnected=$disconnectedAny)")
                                handler.post { onDone() }
                            }
                        }
                    }

                    override fun onServiceDisconnected(profileType: Int) {}
                }, profile)
            } catch (e: Exception) {
                log("getProfileProxy failed for profile $profile: ${e.message}")
                remaining--
                if (remaining <= 0 && !done) {
                    done = true
                    handler.removeCallbacks(safetyTimeout)
                    log("Profile disconnect done (disconnected=$disconnectedAny)")
                    handler.post { onDone() }
                }
            }
        }
    }

    // --- Shared success path ---

    private fun onGattConnected(g: BluetoothGatt) {
        log("Connected (transport attempt $attempt), refreshing GATT cache...")
        val refreshed = refreshGattCache(g)
        log("GATT cache refresh: $refreshed")

        serviceRetryCount = 0
        discoverServicesWithRetry(g)
    }

    private fun discoverServicesWithRetry(g: BluetoothGatt) {
        if (closed) return
        state = State.DISCOVERING_SERVICES
        log("Discovering services (attempt ${serviceRetryCount + 1}/${MAX_SERVICE_RETRIES + 1})...")
        startTimeout(TIMEOUT_DISCOVER_MS) {
            log("Service discovery timeout")
            fail("Service discovery timed out")
        }
        g.discoverServices()
    }

    // --- Internal helpers ---

    private fun fail(reason: String) {
        if (closed) return
        closeGatt()
        state = State.IDLE
        log("Failed: $reason")
        callback?.onFailed(reason)
    }

    private fun closeGatt() {
        cancelTimeout()
        gatt?.let { g ->
            try { g.disconnect() } catch (_: Exception) {}
            try { g.close() } catch (_: Exception) {}
        }
        gatt = null
    }

    private fun refreshGattCache(gatt: BluetoothGatt): Boolean {
        return try {
            val method = gatt.javaClass.getMethod("refresh")
            method.invoke(gatt) as Boolean
        } catch (_: Exception) {
            false
        }
    }

    private fun startTimeout(ms: Long, onTimeout: () -> Unit) {
        cancelTimeout()
        val r = Runnable { onTimeout() }
        timeoutRunnable = r
        handler.postDelayed(r, ms)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    /**
     * Parse Caps binary data. Tries native Caps.fromBytes first, falls back to manual parsing.
     */
    private fun parseCaps(data: ByteArray): List<String> {
        try {
            val caps = com.rokid.cxr.Caps.fromBytes(data)
            if (caps != null) {
                val result = mutableListOf<String>()
                for (i in 0..9) {
                    try {
                        val v = caps.at(i)
                        try { result.add(v.getString()); continue } catch (_: Exception) {}
                        try { result.add(v.getInt().toString()); continue } catch (_: Exception) {}
                        break
                    } catch (_: Exception) { break }
                }
                if (result.size >= 2) {
                    log("Parsed via native Caps (${result.size} fields)")
                    return result
                }
            }
        } catch (e: Throwable) {
            log("Native Caps failed (${e.javaClass.simpleName}: ${e.message}), trying manual parse...")
        }

        return parseCapsManual(data)
    }

    /**
     * Rokid Caps binary format:
     *   [4B totalSize BE][1B version][1B memberCount][memberCount type chars][data...]
     *
     * Type encoding:
     *   'S' (0x53) = string: [1B length][UTF-8 bytes]
     *   'i' (0x69) = int32:  [4B BE value]
     *   'u' (0x75) = uint8:  [1B value]
     *   'l' (0x6C) = int64:  [8B BE value]
     */
    private fun parseCapsManual(data: ByteArray): List<String> {
        if (data.size < 6) throw RuntimeException("Caps data too short (${data.size} bytes)")

        val buf = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.BIG_ENDIAN)

        val totalSize = buf.getInt()
        val version = buf.get().toInt() and 0xFF
        val memberCount = buf.get().toInt() and 0xFF

        log("Caps header: size=$totalSize version=$version count=$memberCount")

        if (memberCount < 1 || memberCount > 20) throw RuntimeException("Bad member count: $memberCount")

        val types = ByteArray(memberCount)
        buf.get(types)

        log("Caps types: ${types.map { "'${it.toInt().toChar()}'" }}")

        val result = mutableListOf<String>()
        for (i in 0 until memberCount) {
            when (types[i].toInt().toChar()) {
                'S' -> {
                    val len = buf.get().toInt() and 0xFF
                    if (len > buf.remaining()) throw RuntimeException("String len $len > remaining ${buf.remaining()}")
                    val bytes = ByteArray(len)
                    buf.get(bytes)
                    result.add(String(bytes, Charsets.UTF_8))
                }
                'i' -> {
                    result.add(buf.getInt().toString())
                }
                'u' -> {
                    result.add((buf.get().toInt() and 0xFF).toString())
                }
                'l' -> {
                    result.add(buf.getLong().toString())
                }
                else -> {
                    log("Unknown Caps type '${types[i].toInt().toChar()}' at index $i, stopping")
                    break
                }
            }
        }

        log("Parsed ${result.size} fields manually")
        return result
    }

    // --- Main GATT callback ---

    @Suppress("DEPRECATION")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (closed) return
            handler.post {
                if (closed || state != State.CONNECTING) return@post
                cancelTimeout()

                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    onGattConnected(g)
                } else {
                    log("Connection failed: status=$status newState=$newState")
                    when (attempt) {
                        1 -> doAttempt2()
                        2 -> doAttempt3()
                        else -> fail("GATT connection failed (status=$status)")
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (closed) return
            handler.post {
                if (closed || state != State.DISCOVERING_SERVICES) return@post
                cancelTimeout()

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("Service discovery failed: status=$status")
                    fail("Service discovery failed (status=$status)")
                    return@post
                }

                val services = g.services
                log("Discovered ${services.size} services: ${services.joinToString { it.uuid.toString().take(8) }}")

                val service = g.getService(SERVICE_UUID)
                if (service == null) {
                    if (serviceRetryCount < MAX_SERVICE_RETRIES) {
                        serviceRetryCount++
                        log("CXR service not found, retrying in ${SERVICE_RETRY_DELAY_MS}ms (retry $serviceRetryCount/$MAX_SERVICE_RETRIES)...")
                        handler.postDelayed({
                            if (closed) return@postDelayed
                            log("Refreshing GATT cache before retry...")
                            refreshGattCache(g)
                            discoverServicesWithRetry(g)
                        }, SERVICE_RETRY_DELAY_MS)
                    } else {
                        log("CXR service not found after ${MAX_SERVICE_RETRIES} retries")
                        fail("Rokid GATT service not found (${services.size} other services visible)")
                    }
                    return@post
                }

                val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    log("Characteristic $CHARACTERISTIC_UUID not found")
                    fail("Rokid GATT characteristic not found")
                    return@post
                }

                log("Service found, requesting MTU...")
                state = State.REQUESTING_MTU
                startTimeout(TIMEOUT_MTU_MS) {
                    log("MTU request timeout")
                    fail("MTU request timed out")
                }
                g.requestMtu(512)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (closed) return
            handler.post {
                if (closed || state != State.REQUESTING_MTU) return@post
                cancelTimeout()

                log("MTU changed to $mtu (status=$status)")

                val service = g.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    fail("Characteristic lost after MTU change")
                    return@post
                }

                state = State.READING_CHAR
                log("Reading characteristic...")
                startTimeout(TIMEOUT_READ_MS) {
                    log("Characteristic read timeout")
                    fail("Characteristic read timed out")
                }
                g.readCharacteristic(characteristic)
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (closed) return
            handler.post {
                if (closed || state != State.READING_CHAR) return@post
                cancelTimeout()

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("Characteristic read failed: status=$status")
                    fail("Characteristic read failed (status=$status)")
                    return@post
                }

                val value = characteristic.value
                if (value == null || value.isEmpty()) {
                    log("Characteristic value is null/empty")
                    fail("Empty characteristic value")
                    return@post
                }

                log("Characteristic read OK (${value.size} bytes)")
                log("Raw hex: ${value.joinToString("") { "%02x".format(it) }}")

                try {
                    val parsed = parseCaps(value)
                    val socketUuid = parsed[0]
                    val macAddress = parsed[1]
                    val rokidAccount = parsed.getOrNull(2)
                    val glassesType = parsed.getOrNull(3)?.toIntOrNull() ?: 0

                    log("socketUuid=$socketUuid macAddress=$macAddress rokidAccount=$rokidAccount glassesType=$glassesType")

                    state = State.DONE
                    closeGatt()

                    callback?.onConnectionInfo(socketUuid, macAddress, rokidAccount, glassesType)
                } catch (e: Exception) {
                    log("Caps parsing failed: ${e.message}")
                    log(e.stackTraceToString())
                    fail("Failed to parse connection info: ${e.message}")
                }
            }
        }
    }
}

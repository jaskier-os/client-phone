package com.repository.listener.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.repository.listener.util.LogCollector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.random.Random

/**
 * Phone-side persistent GATT client to the glasses' BleWakeService (Phase G3).
 *
 * - Connects (TRANSPORT_LE), discovers service, enables CHAR_TX notify.
 * - Decodes 10-byte payloads from CHAR_TX and dispatches via [setOnNotifyCallback].
 * - Writes 10-byte payloads to CHAR_RX (WRITE_NO_RESPONSE for low latency).
 * - Auto-reconnects with bounded backoff (5s -> 60s, +/-20% jitter) on disconnect.
 */
@SuppressLint("MissingPermission")
class BleWakeNotifyClient(private val context: Context) {

    companion object {
        private const val TAG = "BleWakeNotifyClient"
        private val SERVICE_UUID = UUID.fromString("c0de0001-cafe-beef-0000-000000000001")
        private val CHAR_TX_UUID = UUID.fromString("c0de0002-cafe-beef-0000-000000000001")
        private val CHAR_RX_UUID = UUID.fromString("c0de0003-cafe-beef-0000-000000000001")
        private val CCCD_UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val INITIAL_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val PAYLOAD_SIZE = 10
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var charTx: BluetoothGattCharacteristic? = null
    @Volatile private var charRx: BluetoothGattCharacteristic? = null
    @Volatile private var device: BluetoothDevice? = null
    @Volatile private var connected: Boolean = false
    @Volatile private var stopped: Boolean = false
    @Volatile private var currentBackoffMs: Long = INITIAL_BACKOFF_MS

    /** Track whether we've had a service-not-found so we refresh cache on next connect. */
    @Volatile private var needsCacheRefresh: Boolean = false

    private var notifyCb: ((Byte, Byte, Long) -> Unit)? = null
    private var stateCb: ((Boolean) -> Unit)? = null
    private var pongCb: ((Int, Long) -> Unit)? = null
    private var helloCb: (() -> Unit)? = null

    /** Pending ping send timestamps (nanoTime), keyed by requestId (1..255). */
    private val pingSendNanos = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    fun setOnNotifyCallback(cb: (eventCode: Byte, data: Byte, epochNanos: Long) -> Unit) {
        notifyCb = cb
    }

    fun setOnConnectionStateCallback(cb: (connected: Boolean) -> Unit) {
        stateCb = cb
    }

    fun setOnPongCallback(cb: (requestId: Int, rttMillis: Long) -> Unit) {
        pongCb = cb
    }

    fun setOnHelloCallback(cb: () -> Unit) {
        helloCb = cb
    }

    fun isConnected(): Boolean = connected

    /** Begin (or restart) persistent connection to the given bonded glasses device. */
    fun start(device: BluetoothDevice) {
        stopped = false
        this.device = device
        log("start dev=${device.address}")
        connectNow()
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        charTx = null
        charRx = null
        connected = false
        log("stopped")
    }

    private fun connectNow() {
        val dev = device ?: return
        if (stopped) return
        log("connectGatt dev=${dev.address}")
        try { gatt?.close() } catch (_: Exception) {}
        gatt = dev.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun scheduleReconnect() {
        if (stopped) return
        val jitter = (currentBackoffMs * (Random.nextDouble(-0.2, 0.2))).toLong()
        val delay = (currentBackoffMs + jitter).coerceAtLeast(1_000L)
        log("scheduleReconnect in ${delay}ms (backoff=${currentBackoffMs}ms)")
        handler.postDelayed({ connectNow() }, delay)
        currentBackoffMs = (currentBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
    }

    /**
     * Send a 10-byte BLE_PING frame on CHAR_RX (WRITE_NO_RESPONSE) and remember the
     * send timestamp under [requestId] so the matching PONG can compute RTT.
     * Returns false if GATT is not connected.
     */
    fun sendPing(requestId: Int): Boolean {
        val g = gatt ?: run { log("sendPing: no gatt"); return false }
        val ch = charRx ?: run { log("sendPing: no rx char"); return false }
        if (!connected) { log("sendPing: not connected"); return false }
        val sendNanos = System.nanoTime()
        val payload = ByteBuffer.allocate(PAYLOAD_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(BleWakeEvent.BLE_PING)
            put(requestId.toByte())
            putLong(sendNanos)
        }.array()
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.value = payload
        return try {
            pingSendNanos[requestId] = sendNanos
            @Suppress("DEPRECATION")
            val ok = g.writeCharacteristic(ch)
            log("sendPing id=$requestId ok=$ok")
            if (!ok) pingSendNanos.remove(requestId)
            ok
        } catch (e: Exception) {
            pingSendNanos.remove(requestId)
            log("sendPing fail: ${e.message}")
            false
        }
    }

    /** Push a wake event to the glasses via CHAR_RX write. */
    fun notify(eventCode: Byte, epochNanos: Long): Boolean {
        val g = gatt ?: run { log("notify: no gatt"); return false }
        val ch = charRx ?: run { log("notify: no rx char"); return false }
        if (!connected) { log("notify: not connected"); return false }
        val payload = ByteBuffer.allocate(PAYLOAD_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(eventCode)
            put(0.toByte())
            putLong(epochNanos)
        }.array()
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.value = payload
        return try {
            @Suppress("DEPRECATION")
            val ok = g.writeCharacteristic(ch)
            log("notify code=$eventCode ok=$ok")
            ok
        } catch (e: Exception) {
            log("notify fail: ${e.message}")
            false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("onConnStateChange status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (needsCacheRefresh) {
                    needsCacheRefresh = false
                    try {
                        val refresh = g.javaClass.getMethod("refresh")
                        val ok = refresh.invoke(g) as Boolean
                        log("GATT cache refresh=$ok")
                    } catch (e: Exception) {
                        log("GATT cache refresh failed: ${e.message}")
                    }
                }
                handler.post {
                    try { g.discoverServices() } catch (e: Exception) { log("discover fail: ${e.message}") }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                charTx = null
                charRx = null
                try { g.close() } catch (_: Exception) {}
                gatt = null
                stateCb?.invoke(false)
                if (!stopped) scheduleReconnect()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val svc = g.getService(SERVICE_UUID) ?: run {
                log("service $SERVICE_UUID not found, will refresh cache and retry")
                needsCacheRefresh = true
                g.disconnect()
                return
            }
            charTx = svc.getCharacteristic(CHAR_TX_UUID)
            charRx = svc.getCharacteristic(CHAR_RX_UUID)
            val tx = charTx ?: run { log("CHAR_TX missing"); return }
            // Enable notifications on CHAR_TX (locally + remotely via CCCD).
            try {
                g.setCharacteristicNotification(tx, true)
                val cccd = tx.getDescriptor(CCCD_UUID) ?: run {
                    log("CCCD missing on CHAR_TX")
                    return
                }
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                val ok = g.writeDescriptor(cccd)
                log("writeDescriptor CCCD ok=$ok")
            } catch (e: Exception) {
                log("enable notify fail: ${e.message}")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            log("onDescriptorWrite ${descriptor.uuid} status=$status")
            if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true
                currentBackoffMs = INITIAL_BACKOFF_MS
                stateCb?.invoke(true)
                log("BLE wake link ready (notifications enabled)")
                // Request low-power GATT connection interval now that CCCD is up. This shifts the
                // link from default ~30 ms to ~100-125 ms with slave latency, which is the main
                // lever to recover G3's idle-power regression vs G2. Called after CCCD subscribe
                // so the connection has settled; some stacks reject the request if called too early.
                try {
                    val ok = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
                    log("requestConnectionPriority(LOW_POWER) ok=$ok")
                } catch (e: Exception) {
                    log("requestConnectionPriority fail: ${e.message}")
                }
            }
        }

        // The 1-arg onCharacteristicChanged overload is deprecated on Android 13+ (API 33);
        // when the phone target SDK moves past A12 (33+), port to the 3-arg form that takes the byte[] payload.
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid != CHAR_TX_UUID) return
            val v = ch.value ?: return
            if (v.size < PAYLOAD_SIZE) {
                log("rx short payload size=${v.size}")
                return
            }
            val bb = ByteBuffer.wrap(v, 0, PAYLOAD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val code = bb.get()
            val data = bb.get()
            val epoch = bb.long
            log("rx notify code=$code data=$data epoch=$epoch")
            when (code) {
                BleWakeEvent.BLE_PONG -> {
                    val requestId = data.toInt() and 0xFF
                    val sendNanos = pingSendNanos.remove(requestId)
                    val rttMillis = if (sendNanos != null) {
                        (System.nanoTime() - sendNanos) / 1_000_000L
                    } else {
                        // Unknown id (timed out + cleaned up, or stray). Still treat as
                        // proof of life; report 0ms so reachability flips Reachable.
                        0L
                    }
                    try { pongCb?.invoke(requestId, rttMillis) } catch (e: Exception) {
                        log("pongCb fail: ${e.message}")
                    }
                    return
                }
                BleWakeEvent.BLE_HELLO -> {
                    try { helloCb?.invoke() } catch (e: Exception) {
                        log("helloCb fail: ${e.message}")
                    }
                    return
                }
            }
            try { notifyCb?.invoke(code, data, epoch) } catch (e: Exception) {
                log("notifyCb fail: ${e.message}")
            }
        }
    }

    private fun log(msg: String) {
        LogCollector.i(TAG, msg)
    }
}

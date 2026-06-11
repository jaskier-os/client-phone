package com.repository.listener.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Dedicated outbound RFCOMM client for the navigation map frame stream.
 *
 * This is a focused, phone->glasses-only link separate from the control/relay
 * socket (GlassesRfcommClient on MESSAGE_UUID) so heavy map traffic at 10-15 FPS
 * cannot head-of-line-block audio/control frames. The glasses expose a second
 * server socket on MAP_UUID ("GlassesMap"); we connect outbound to it.
 *
 * Differences from GlassesRfcommClient:
 *   - No inbound read loop / frame parse (map is one-way).
 *   - No active-session machinery, no relay listener.
 *   - sendBinary() DROPS when disconnected instead of queuing -- map frames are
 *     droppable and must never build a backlog.
 *
 * Frame format (must match the glasses MessageRelay parser exactly):
 *   [4B frame length (BE)]
 *   [1B channel name length][channel bytes (UTF-8)]
 *   [1B arg count = 1]
 *   [4B payload length (BE)][payload bytes VERBATIM]
 */
@SuppressLint("MissingPermission")
class MapRfcommClient(private val context: Context) {

    companion object {
        private const val TAG = "MapRfcomm"
        const val MAP_UUID = "c3d4e5f6-a7b8-9012-cdef-234567890abc"
        /** Min spacing between self-heal reconnect requests triggered from the send
         *  path. At 15 FPS a per-frame trigger would wake-storm the connect thread;
         *  this throttles it to at most one request per interval. */
        private const val SELF_HEAL_THROTTLE_MS = 3000L
    }

    /** Event-driven reconnect signal. The connect thread blocks on this until a
     *  reconnect is requested (initial start, or a wake/connect trigger mirrored
     *  from the control link). */
    private val reconnectSignal = Semaphore(0)

    var onLog: ((String) -> Unit)? = null
    private fun log(msg: String) {
        onLog?.invoke(msg)
    }

    /** Connected flag. AtomicBoolean so handleDisconnect's compareAndSet(true,false)
     *  picks exactly one winner -- the streamer-thread write failure and the
     *  connect/reader-thread socket death can both race to tear down. */
    private val connected = AtomicBoolean(false)
    val isConnected: Boolean
        get() = connected.get()

    /** Last self-heal reconnect request time (uptimeMillis), for throttling. */
    private val lastSelfHealMs = AtomicLong(0L)

    @Volatile
    private var socket: BluetoothSocket? = null
    @Volatile
    private var outputStream: OutputStream? = null
    @Volatile
    private var shouldRun: Boolean = false
    @Volatile
    private var currentDevice: BluetoothDevice? = null

    private val writeLock = Any()

    fun requestImmediateReconnect(reason: String) {
        log("map requestImmediateReconnect: $reason")
        reconnectSignal.release()
    }

    /** Start the connect loop. Idempotent. Pass null to discover bonded glasses. */
    fun start(device: BluetoothDevice? = null) {
        if (shouldRun) return
        shouldRun = true
        currentDevice = device
        connectLoop()
        // Kick one initial attempt so cold start brings the link up.
        reconnectSignal.release()
    }

    fun stop() {
        shouldRun = false
        reconnectSignal.release()
        connected.set(false)
        closeSocket()
    }

    /**
     * Send a single-arg binary frame. Payload bytes are written VERBATIM (no
     * UTF-8 round-trip). Returns false and DROPS if the link is not connected --
     * map frames never queue.
     */
    fun sendBinary(channel: String, payload: ByteArray): Boolean {
        if (!connected.get()) {
            // Self-heal: a map-only drop (e.g. GlassesMap server restart) leaves
            // the control link up, so none of the external reconnect triggers
            // re-fire. Request a reconnect here, throttled so 15 FPS of dropped
            // frames cannot wake-storm the connect thread. The frame itself is
            // still dropped -- never queued.
            maybeSelfHeal()
            return false
        }
        val out = outputStream ?: return false
        try {
            val frame = buildBinaryFrame(channel, payload)
            synchronized(writeLock) { out.write(frame) }
            return true
        } catch (e: Exception) {
            log("map sendBinary($channel) failed: ${e.message}")
            handleDisconnect()
            return false
        }
    }

    private fun buildBinaryFrame(channel: String, payload: ByteArray): ByteArray {
        val channelBytes = channel.toByteArray(Charsets.UTF_8)
        require(channelBytes.size <= 255) { "channel name too long: ${channelBytes.size}" }

        val body = ByteArrayOutputStream()
        body.write(channelBytes.size and 0xFF)
        body.write(channelBytes)
        body.write(1) // argCount = 1
        body.write(ByteBuffer.allocate(4).putInt(payload.size).array())
        body.write(payload)

        val bodyBytes = body.toByteArray()
        val frame = ByteArrayOutputStream(4 + bodyBytes.size)
        frame.write(ByteBuffer.allocate(4).putInt(bodyBytes.size).array())
        frame.write(bodyBytes)
        return frame.toByteArray()
    }

    private fun connectLoop() {
        Thread({
            while (shouldRun) {
                try {
                    reconnectSignal.acquire()
                } catch (_: InterruptedException) {
                    continue
                }
                reconnectSignal.drainPermits()
                if (!shouldRun) break
                if (connected.get()) {
                    log("map reconnect signal ignored: already connected")
                    continue
                }
                val device = currentDevice ?: findGlassesDevice()
                if (device == null) {
                    log("map: no bonded glasses found; waiting for next reconnect signal")
                    continue
                }
                currentDevice = device
                try {
                    val uuid = UUID.fromString(MAP_UUID)
                    log("map connecting RFCOMM to ${device.name ?: device.address} uuid=$MAP_UUID")
                    val s = device.createRfcommSocketToServiceRecord(uuid)
                    s.connect()
                    socket = s
                    outputStream = s.outputStream
                    // Set connected ONLY after outputStream is assigned so sendBinary
                    // never sees connected=true with a null stream.
                    connected.set(true)
                    log("map connected to ${device.name ?: device.address}")
                    // One-way link: block here until the socket dies, then fall
                    // through to cleanup and wait for the next reconnect signal.
                    waitUntilClosed(s)
                } catch (e: Exception) {
                    log("map RFCOMM connect failed: ${e.message}")
                }
                closeSocket()
                // No retry-loop: the next requestImmediateReconnect() wakes us.
            }
        }, "MapRfcommConnect").apply { isDaemon = true; start() }
    }

    /**
     * Map is phone->glasses only, so we never read. To detect a dead peer we
     * block on a single read() -- it returns -1 / throws when the remote closes
     * or the link drops, which is exactly the teardown signal we need.
     */
    private fun waitUntilClosed(s: BluetoothSocket) {
        try {
            val inp = s.inputStream
            val sink = ByteArray(256)
            while (shouldRun && s.isConnected) {
                val n = inp.read(sink)
                if (n == -1) break
                // Glasses never send on this socket; ignore any stray bytes.
            }
        } catch (e: Exception) {
            log("map read loop ended: ${e.message}")
        }
        handleDisconnect()
    }

    private fun findGlassesDevice(): BluetoothDevice? {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
            val bonded = adapter.bondedDevices ?: return null
            bonded.firstOrNull {
                val name = it.name ?: ""
                name.contains("glasses", ignoreCase = true) ||
                    name.startsWith("Glasses_") ||
                    name.startsWith("RG_") ||
                    name.contains("Rokid", ignoreCase = true)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun handleDisconnect() {
        // compareAndSet picks exactly one winner across the racing streamer and
        // connect/reader threads, so closeSocket runs once.
        if (!connected.compareAndSet(true, false)) return
        closeSocket()
    }

    /**
     * Request a reconnect from the send path, throttled to SELF_HEAL_THROTTLE_MS
     * so a stream of dropped frames at 15 FPS cannot wake-storm the connect
     * thread. Does not block and does not queue the frame.
     */
    private fun maybeSelfHeal() {
        val now = android.os.SystemClock.uptimeMillis()
        val last = lastSelfHealMs.get()
        if (now - last < SELF_HEAL_THROTTLE_MS) return
        if (!lastSelfHealMs.compareAndSet(last, now)) return
        requestImmediateReconnect("self_heal:map_send_while_disconnected")
    }

    private fun closeSocket() {
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        outputStream = null
        socket = null
    }
}

package com.repository.listener.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Dedicated outbound RFCOMM client for remote input events.
 *
 * Input gets its own socket, exactly as the map bitmap stream already does. The
 * shared control socket carries TTS blobs, base64 audio and sideload payloads,
 * and its single blocking write lock has no priority or fairness: at a practical
 * RFCOMM goodput of ~40 KB/s a 100 KB TTS blob would head-of-line-block input for
 * about 2.5 s, and a 30 MB sideload for minutes. Its outbound queue is also
 * bounded and drop-oldest across ALL channels, so a burst of scrolling during an
 * outage would evict other features' queued frames.
 *
 * Differences from [GlassesRfcommClient]:
 *   - No inbound read loop (input is one-way).
 *   - send() DROPS when disconnected instead of queuing. Input is only meaningful
 *     while it is fresh; a queue would deliver a stale burst on reconnect and
 *     could never evict anything, because there is nothing to evict.
 */
@SuppressLint("MissingPermission")
class InputRfcommClient(private val context: Context) {

    companion object {
        const val INPUT_UUID = "d4e5f6a7-b8c9-0123-def0-345678901234"

        /**
         * Minimum spacing between self-heal reconnect requests raised from the
         * send path. Without it a user scrolling into a dead link would trigger a
         * BLE wake/page storm at the event rate.
         */
        private const val SELF_HEAL_THROTTLE_MS = 3000L
    }

    private val reconnectSignal = Semaphore(0)

    var onLog: ((String) -> Unit)? = null
    private fun log(msg: String) = onLog?.invoke(msg) ?: Unit

    /** Connect state. AtomicBoolean so exactly one racer wins the teardown. */
    private val connected = AtomicBoolean(false)
    val isConnected: Boolean get() = connected.get()

    private val lastSelfHealMs = AtomicLong(0L)

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var outputStream: OutputStream? = null
    @Volatile private var shouldRun: Boolean = false
    @Volatile private var currentDevice: BluetoothDevice? = null

    private val writeLock = Any()

    /** Raised on a connect/disconnect edge so the bridge can push status. */
    var onLinkStateChanged: ((Boolean) -> Unit)? = null

    fun requestImmediateReconnect(reason: String) {
        log("input requestImmediateReconnect: $reason")
        reconnectSignal.release()
    }

    fun start(device: BluetoothDevice? = null) {
        if (shouldRun) {
            device?.let { currentDevice = it }
            requestImmediateReconnect("start")
            return
        }
        shouldRun = true
        currentDevice = device
        Thread({ connectLoop() }, "input-rfcomm").apply { isDaemon = true }.start()
        reconnectSignal.release()
    }

    fun stop() {
        shouldRun = false
        handleDisconnect()
        closeSocket()
        reconnectSignal.release()
    }

    fun setTargetDevice(device: BluetoothDevice) {
        currentDevice = device
        // Tear the connection state down BEFORE closing the socket. Closing alone
        // would leave `connected` true until the blocked read unwinds, and a
        // caller checking isConnected in that window would see true with a null
        // stream.
        handleDisconnect()
        closeSocket()
        requestImmediateReconnect("retarget")
    }

    /**
     * Sends one frame. Returns false when the link is down or the write failed.
     *
     * NEVER queues. The caller is expected to treat false as "dropped" and
     * surface it, rather than retrying: a retried scroll arrives stale and a
     * retried select could confirm something the user did not choose.
     */
    fun send(channel: String, vararg args: String): Boolean {
        if (!connected.get()) {
            maybeSelfHeal()
            return false
        }
        val out = outputStream
        if (out == null) {
            // Lost between the isConnected check and here.
            maybeSelfHeal()
            return false
        }
        return try {
            val frame = buildFrame(channel, args)
            synchronized(writeLock) { out.write(frame); out.flush() }
            true
        } catch (e: Exception) {
            log("input send failed: ${e.message}")
            handleDisconnect()
            closeSocket()
            maybeSelfHeal()
            false
        }
    }

    /**
     * Frame format, matching the glasses MessageRelay parser exactly:
     *   [4B total length BE]
     *   [1B channel name length][channel UTF-8]
     *   [1B arg count]
     *   per arg: [4B length BE][UTF-8 bytes]
     */
    private fun buildFrame(channel: String, args: Array<out String>): ByteArray {
        val channelBytes = channel.toByteArray(Charsets.UTF_8)
        val argBytes = args.map { it.toByteArray(Charsets.UTF_8) }
        var body = 1 + channelBytes.size + 1
        for (a in argBytes) body += 4 + a.size

        val buf = ByteBuffer.allocate(4 + body)
        buf.putInt(body)
        buf.put(channelBytes.size.toByte())
        buf.put(channelBytes)
        buf.put(argBytes.size.toByte())
        for (a in argBytes) {
            buf.putInt(a.size)
            buf.put(a)
        }
        return buf.array()
    }

    private fun connectLoop() {
        while (shouldRun) {
            try {
                reconnectSignal.acquire()
            } catch (e: InterruptedException) {
                // Do not let an interrupt kill the loop permanently: shouldRun
                // would still be true and start() would refuse to respawn it.
                Thread.currentThread().interrupt()
                if (!shouldRun) return
                continue
            }
            reconnectSignal.drainPermits()
            if (!shouldRun) return
            if (connected.get()) continue

            val device = currentDevice ?: findGlassesDevice()
            if (device == null) {
                log("input: no bonded glasses; waiting")
                continue
            }
            currentDevice = device
            try {
                val uuid = UUID.fromString(INPUT_UUID)
                log("input connecting to ${device.name ?: device.address}")
                val s = device.createRfcommSocketToServiceRecord(uuid)
                socket = s
                s.connect()
                outputStream = s.outputStream
                // Publish connected only AFTER the stream exists, so send() can
                // never observe connected with a null stream.
                connected.set(true)
                onLinkStateChanged?.invoke(true)
                log("input connected")
                waitUntilClosed(s)
            } catch (e: Exception) {
                log("input connect failed: ${e.message}")
            } finally {
                handleDisconnect()
                closeSocket()
            }
        }
    }

    /** Blocks until the socket dies. One-way link, so nothing is parsed. */
    private fun waitUntilClosed(s: BluetoothSocket) {
        try {
            val input = s.inputStream
            val scratch = ByteArray(64)
            while (shouldRun && connected.get()) {
                if (input.read(scratch) < 0) break
            }
        } catch (e: Exception) {
            log("input socket closed: ${e.message}")
        }
    }

    private fun handleDisconnect() {
        if (connected.compareAndSet(true, false)) {
            outputStream = null
            onLinkStateChanged?.invoke(false)
            log("input disconnected")
        }
    }

    private fun closeSocket() {
        try { socket?.close() } catch (e: Exception) { }
        socket = null
        outputStream = null
    }

    private fun maybeSelfHeal() {
        // Skip RFCOMM pages while the desktop audio relay streams. Each page to
        // absent glasses blocks the shared BT/2.4GHz radio and stutters the
        // WebRTC audio; BLE wake reconnects the link when the glasses appear.
        // Omitting this would re-introduce that regression whenever the user
        // scrolls the watch during a relay session.
        if (com.repository.listener.service.ListenerService.audioRelayActive) return
        val now = android.os.SystemClock.uptimeMillis()
        val last = lastSelfHealMs.get()
        if (now - last < SELF_HEAL_THROTTLE_MS) return
        if (!lastSelfHealMs.compareAndSet(last, now)) return
        requestImmediateReconnect("self_heal:input")
    }

    private fun findGlassesDevice(): BluetoothDevice? {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
            val bonded = adapter.bondedDevices ?: return null
            // Prefer the exact paired unit (cached MAC) so a second bonded pair
            // cannot hijack the input socket. Case-insensitive: a lowercase-stored
            // MAC would otherwise silently fall through to the name fallback.
            val cachedMac = com.repository.listener.config.AppConfig.getGlassesMac(context)
            if (!cachedMac.isNullOrEmpty()) {
                bonded.firstOrNull { it.address.equals(cachedMac, ignoreCase = true) }?.let { return it }
            }
            // Name fallback ONLY when exactly ONE bonded unit matches, mirroring
            // the other relays: two bonded units must never silently pick wrong.
            val glasses = bonded.filter {
                val name = it.name ?: ""
                name.contains("glasses", ignoreCase = true) ||
                    name.startsWith("Glasses_") ||
                    name.startsWith("RG_") ||
                    name.contains("Rokid", ignoreCase = true)
            }
            glasses.singleOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /** Forget the targeted device so the next reconnect re-resolves via the
     *  cached MAC. Used on user re-pair; mirrors the other relays. */
    fun resetTarget() {
        currentDevice = null
        handleDisconnect()
        closeSocket()
        if (shouldRun) reconnectSignal.release()
    }
}

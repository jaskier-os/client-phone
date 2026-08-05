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
 *   - send() DROPS when disconnected instead of queuing. Input is only meaningful
 *     while it is fresh; a queue would deliver a stale burst on reconnect and
 *     could never evict anything, because there is nothing to evict.
 *
 * The inbound direction carries ONLY the glasses' input back-channel
 * (`CH_REMOTE_INPUT_SINK`, `CH_REMOTE_INPUT_STATUS`). It is parsed here, on this
 * socket, because this is the socket the glasses publish it on -- the shared
 * message socket has a parser but never receives these frames, so routing them
 * there would mean the watch is told "glasses screen not active" forever even
 * while scrolling works. Parsing it here rather than moving the publisher also
 * keeps the signal on the same transport whose connect/disconnect edges trigger
 * the glasses' re-announce, so the state and the link that carries it cannot
 * disagree.
 */
@SuppressLint("MissingPermission")
class InputRfcommClient(private val context: Context) :
    com.repository.listener.wear.WatchInputBridge.InputTransport {

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
    override val isConnected: Boolean get() = connected.get()

    private val lastSelfHealMs = AtomicLong(0L)

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var outputStream: OutputStream? = null
    @Volatile private var shouldRun: Boolean = false
    @Volatile private var currentDevice: BluetoothDevice? = null

    private val writeLock = Any()

    /** Raised on a connect/disconnect edge so the bridge can push status. */
    override var onLinkStateChanged: ((Boolean) -> Unit)? = null

    /**
     * Glasses -> phone sink state from `CH_REMOTE_INPUT_SINK`: whether a UI sink is
     * attached and would actually act on an event.
     *
     * The single source of this fact on the phone. The shared control socket
     * deliberately does NOT also handle this channel: two sources for one bit can
     * disagree, and a status display that lies is worse than none.
     */
    var onSinkState: ((Boolean) -> Unit)? = null

    /**
     * Glasses -> phone router status from `CH_REMOTE_INPUT_STATUS`:
     * (sessionOpen, sinkAttached, droppedTotal).
     *
     * Richer than [onSinkState] but reports the same `sinkAttached` bit, so the
     * consumer must fold them into one value rather than tracking two.
     */
    var onRouterStatus: ((Boolean, Boolean, Long) -> Unit)? = null

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
    override fun send(channel: String, vararg args: String): Boolean {
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

    /**
     * Reads the back-channel until the socket dies.
     *
     * Frames use the same length-prefixed format [buildFrame] writes, because the
     * glasses' MessageRelay speaks one format in both directions.
     */
    private fun waitUntilClosed(s: BluetoothSocket) {
        val parser = InputBackChannelParser(log = { log(it) }) { channel, args ->
            dispatchBackChannel(channel, args)
        }
        try {
            val input = s.inputStream
            val chunk = ByteArray(1024)
            while (shouldRun && connected.get()) {
                val n = input.read(chunk)
                if (n < 0) break
                parser.onBytes(chunk, n)
            }
        } catch (e: Exception) {
            log("input socket closed: ${e.message}")
        }
    }

    /**
     * Applies one decoded back-channel frame.
     *
     * Callbacks are invoked inside the socket read loop, so a throw here would kill
     * the loop and silently end the back channel. Guarded.
     */
    private fun dispatchBackChannel(channel: String, args: List<String>) {
        try {
            when (channel) {
                BtProtocol.CH_REMOTE_INPUT_SINK -> {
                    val attached = args.getOrNull(0) == "1"
                    log("input rx sink attached=$attached")
                    onSinkState?.invoke(attached)
                }
                BtProtocol.CH_REMOTE_INPUT_STATUS -> {
                    val sessionOpen = args.getOrNull(0) == "1"
                    val sinkAttached = args.getOrNull(1) == "1"
                    val dropped = args.getOrNull(2)?.toLongOrNull() ?: 0L
                    log("input rx status sessionOpen=$sessionOpen sink=$sinkAttached dropped=$dropped")
                    onRouterStatus?.invoke(sessionOpen, sinkAttached, dropped)
                }
                // Anything else on this socket is not ours. Ignored rather than
                // logged per frame: a peer controls the rate and the log is on flash.
                else -> Unit
            }
        } catch (e: Exception) {
            log("input rx dispatch failed: ${e.message}")
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

package com.repository.listener.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.Semaphore

/**
 * Direct RFCOMM client to glasses. Replaces CXR-M SDK with a reliable framed transport.
 *
 * Frame format matches MessageRelay on the glasses side:
 *   [4B frame length (BE)]
 *   [1B channel name length][channel bytes]
 *   [1B arg count]
 *   For each arg: [4B arg length (BE)][arg bytes]
 */
@SuppressLint("MissingPermission")
class GlassesRfcommClient(private val context: Context) {

    companion object {
        private const val TAG = "GlassesRfcomm"
        const val MESSAGE_UUID = "b2c3d4e5-f6a7-8901-bcde-f12345678901"
        private const val READ_BUF_SIZE = 4096
        private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
        private const val OUTBOUND_QUEUE_MAX = 100
    }

    /**
     * G3: event-driven reconnect signal. Replaces the old 5-s polling loop.
     * The connect thread blocks on this semaphore; callers tickle it via
     * requestImmediateReconnect() when a BLE wake event or outbound demand
     * means we need the RFCOMM link up right now.
     */
    private val reconnectSignal = Semaphore(0)

    fun requestImmediateReconnect(reason: String) {
        listener?.onLog("requestImmediateReconnect: $reason")
        // Single-permit release; if the semaphore already has permits queued
        // we don't pile up unnecessary attempts -- connect thread drains at
        // its own pace and re-blocks when nothing is pending.
        reconnectSignal.release()
    }

    interface Listener {
        fun onConnected(deviceName: String)
        fun onDisconnected()
        fun onMessage(channel: String, args: List<String>)
        fun onLog(msg: String) {}
    }

    var listener: Listener? = null

    /** Relay-style listener compatible with the old CustomCmdListener API. */
    private var relayListener: RelayListener? = null

    /** Register a listener using the Caps-compatible RelayListener API. */
    fun setRelayListener(l: RelayListener) {
        relayListener = l
    }

    private fun dispatchRelay(channel: String, args: List<String>) {
        val l = relayListener ?: return
        val caps = RelayCaps().also { c -> args.forEach { c.write(it) } }
        try {
            l.onCustomCmd(channel, caps)
        } catch (e: Exception) {
            listener?.onLog("RelayListener dispatch failed for $channel: ${e.message}")
        }
    }

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    private var socket: BluetoothSocket? = null
    @Volatile
    private var outputStream: OutputStream? = null
    @Volatile
    private var readerThread: Thread? = null
    @Volatile
    private var shouldRun: Boolean = false
    @Volatile
    private var currentDevice: BluetoothDevice? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val writeLock = Any()

    /**
     * Start connection to a specific bonded device. Runs in background, retries on failure.
     * Pass null to scan bonded devices for RG-glasses.
     */
    fun start(device: BluetoothDevice? = null) {
        if (shouldRun) return
        shouldRun = true
        currentDevice = device
        connectLoop()
        // Kick off one initial connection attempt without waiting for an
        // external trigger, so cold start still brings up the link.
        reconnectSignal.release()
    }

    fun stop() {
        shouldRun = false
        // Wake the connect thread so it can observe shouldRun=false and exit.
        reconnectSignal.release()
        closeSocket()
        val dropped = outboundQueue.size
        if (dropped > 0) listener?.onLog("clearing outbound queue, dropping $dropped frames")
        outboundQueue.clear()
    }

    /** G3: invoked when send() is called but the link is down -- caller can
     *  use this to push a BLE wake notify to the glasses before / alongside
     *  the immediate-reconnect request. */
    var onSendWhileDisconnected: ((channel: String) -> Unit)? = null

    /** G3: bounded outbound queue. Holds frames during the BLE-driven reconnect
     *  window so the first message after a wake is not dropped. Bounded at
     *  OUTBOUND_QUEUE_MAX entries; oldest dropped on overflow. */
    private data class QueuedFrame(val channel: String, val args: Array<out String>)
    private val outboundQueue = LinkedBlockingDeque<QueuedFrame>(OUTBOUND_QUEUE_MAX)

    fun send(channel: String, vararg args: String): Boolean {
        if (isConnected) {
            return sendNow(channel, args)
        }
        // Outbound demand: wake glasses via BLE (if hooked) and request an
        // immediate reconnect. Then queue the frame for delivery once RFCOMM
        // is up. Order matters: wake first, reconnect second, queue third --
        // so the wake signal is in flight before the queue grows.
        try { onSendWhileDisconnected?.invoke(channel) } catch (_: Exception) {}
        requestImmediateReconnect("send:$channel")
        val entry = QueuedFrame(channel, args)
        val accepted = outboundQueue.offerLast(entry)
        if (!accepted) {
            // Bounded queue full -- drop oldest, queue new.
            val dropped = outboundQueue.pollFirst()
            outboundQueue.offerLast(entry)
            listener?.onLog("outbound queue full, dropped oldest channel=${dropped?.channel}")
        }
        listener?.onLog("queued frame channel=$channel depth=${outboundQueue.size}")
        return true
    }

    private fun sendNow(channel: String, args: Array<out String>): Boolean {
        val out = outputStream ?: return false
        try {
            val frame = buildFrame(channel, args)
            synchronized(writeLock) { out.write(frame) }
            return true
        } catch (e: Exception) {
            listener?.onLog("send($channel) failed: ${e.message}")
            handleDisconnect()
            return false
        }
    }

    private fun drainOutboundQueue() {
        while (true) {
            val entry = outboundQueue.pollFirst() ?: break
            if (!isConnected) {
                // Re-queue and bail -- connection died mid-drain.
                outboundQueue.offerFirst(entry)
                break
            }
            try {
                if (!sendNow(entry.channel, entry.args)) {
                    outboundQueue.offerFirst(entry)
                    break
                }
            } catch (e: Exception) {
                listener?.onLog("drain send failed: ${e.message}")
                outboundQueue.offerFirst(entry)
                break
            }
        }
    }

    // -- Connection loop --

    private fun connectLoop() {
        Thread({
            // G3: event-driven. Block on reconnectSignal until something
            // requests a connect (BLE wake notify, outbound demand, or the
            // initial release in start()). When the read loop ends we DON'T
            // immediately retry -- we wait for the next signal, so an idle
            // socket teardown stays torn down until there's actual work.
            while (shouldRun) {
                try {
                    reconnectSignal.acquire()
                } catch (_: InterruptedException) {
                    continue
                }
                // Collapse any accumulated permits so rapid-fire signals (e.g. from
                // the 5s retry timer during a slow connect failure) don't cause
                // back-to-back connection attempts without pause.
                reconnectSignal.drainPermits()
                if (!shouldRun) break
                if (isConnected) {
                    listener?.onLog("reconnect signal ignored: already connected")
                    continue
                }
                val device = currentDevice ?: findGlassesDevice()
                if (device == null) {
                    listener?.onLog("No bonded glasses found; will wait for next reconnect signal")
                    continue
                }
                currentDevice = device
                try {
                    val uuid = UUID.fromString(MESSAGE_UUID)
                    listener?.onLog("Connecting RFCOMM to ${device.name ?: device.address} uuid=$MESSAGE_UUID")
                    val s = device.createRfcommSocketToServiceRecord(uuid)
                    s.connect()
                    socket = s
                    outputStream = s.outputStream
                    isConnected = true
                    val devName = device.name ?: device.address
                    listener?.onLog("Connected to $devName")
                    mainHandler.post { listener?.onConnected(devName) }
                    if (outboundQueue.isNotEmpty()) {
                        listener?.onLog("RFCOMM connected -- draining ${outboundQueue.size} queued frames")
                        drainOutboundQueue()
                    }
                    readLoop(s)
                } catch (e: Exception) {
                    listener?.onLog("RFCOMM connect failed: ${e.message}")
                }
                closeSocket()
                // No sleep, no retry-loop -- the next requestImmediateReconnect()
                // will wake us up. This is the whole point of G3.
            }
        }, "GlassesRfcommConnect").apply { isDaemon = true; start() }
    }

    private fun findGlassesDevice(): BluetoothDevice? {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
            val bonded = adapter.bondedDevices ?: return null
            // Match only devices that look like glasses -- never fall back to
            // arbitrary bonded devices (earbuds, speakers, etc.).
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

    private fun readLoop(s: BluetoothSocket) {
        val recvBuffer = ByteArrayOutputStream()
        val buf = ByteArray(READ_BUF_SIZE)
        val inp = s.inputStream
        try {
            while (shouldRun && s.isConnected) {
                val n = inp.read(buf)
                if (n == -1) break
                if (n == 0) continue
                recvBuffer.write(buf, 0, n)
                processBuffer(recvBuffer)
            }
        } catch (e: IOException) {
            listener?.onLog("read loop ended: ${e.message}")
        } catch (e: Exception) {
            listener?.onLog("read loop error: ${e.message}")
        }
        handleDisconnect()
    }

    private fun processBuffer(recvBuffer: ByteArrayOutputStream) {
        var bytes = recvBuffer.toByteArray()
        var offset = 0
        while (bytes.size - offset >= 4) {
            val len = ByteBuffer.wrap(bytes, offset, 4).int
            if (len < 0 || len > MAX_FRAME_BYTES) {
                listener?.onLog("invalid frame length=$len, resetting buffer")
                recvBuffer.reset()
                return
            }
            if (bytes.size - offset - 4 < len) break
            try {
                parseFrame(bytes, offset + 4, len)
            } catch (e: Exception) {
                listener?.onLog("frame parse failed: ${e.message}")
            }
            offset += 4 + len
        }
        if (offset > 0) {
            recvBuffer.reset()
            if (offset < bytes.size) recvBuffer.write(bytes, offset, bytes.size - offset)
        }
    }

    private fun parseFrame(buf: ByteArray, start: Int, length: Int) {
        var p = start
        val end = start + length

        val chanLen = buf[p].toInt() and 0xFF; p++
        require(p + chanLen <= end) { "channel bytes overflow" }
        val channel = String(buf, p, chanLen, Charsets.UTF_8); p += chanLen

        val argCount = buf[p].toInt() and 0xFF; p++
        val args = ArrayList<String>(argCount)
        for (i in 0 until argCount) {
            require(p + 4 <= end) { "arg length overflow" }
            val argLen = ByteBuffer.wrap(buf, p, 4).int; p += 4
            require(argLen >= 0 && p + argLen <= end) { "arg bytes overflow: len=$argLen" }
            args.add(String(buf, p, argLen, Charsets.UTF_8))
            p += argLen
        }

        val ch = channel
        val argList = args
        mainHandler.post {
            listener?.onMessage(ch, argList)
            dispatchRelay(ch, argList)
        }
    }

    // -- Framing --

    private fun buildFrame(channel: String, args: Array<out String>): ByteArray {
        val channelBytes = channel.toByteArray(Charsets.UTF_8)
        require(channelBytes.size <= 255) { "channel name too long: ${channelBytes.size}" }
        require(args.size <= 255) { "too many args: ${args.size}" }

        val payload = ByteArrayOutputStream()
        payload.write(channelBytes.size and 0xFF)
        payload.write(channelBytes)
        payload.write(args.size and 0xFF)
        for (arg in args) {
            val bytes = arg.toByteArray(Charsets.UTF_8)
            payload.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
            payload.write(bytes)
        }

        val payloadBytes = payload.toByteArray()
        val frame = ByteArrayOutputStream(4 + payloadBytes.size)
        frame.write(ByteBuffer.allocate(4).putInt(payloadBytes.size).array())
        frame.write(payloadBytes)
        return frame.toByteArray()
    }

    private fun handleDisconnect() {
        if (!isConnected) return
        isConnected = false
        // Close socket from any thread to unblock a reader stuck in read().
        // readLoop will then exit, connectLoop will advance to reconnect.
        closeSocket()
        mainHandler.post { listener?.onDisconnected() }
    }

    private fun closeSocket() {
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        outputStream = null
        socket = null
    }

    // Heartbeat removed in G2. Active-session ref-counting on the glasses bt-manager
    // (RfcommManager.setActiveSession) is the new keep-alive signal during real work.
    // For phone-side dead-peer detection we now rely on kernel RFCOMM disconnect (~30-60s
    // on Android); G3 (BLE link state) will replace this.

    // -- Active-session ref-counting (foundation for G3; not yet wired to teardown) --

    private data class SessionEntry(val refcount: Int, val expiresAtMs: Long)
    private val activeSessions = HashMap<String, SessionEntry>()
    private val sessionsLock = Any()
    private val safetyHandler = Handler(Looper.getMainLooper())

    private val DEFAULT_SAFETY_TIMEOUT_MS = mapOf(
        "tts_playback" to 60_000L,
        "transcription_pending" to 120_000L,
    )
    private val DEFAULT_SAFETY_FALLBACK_MS = 60_000L

    fun setActiveSession(label: String) {
        val timeoutMs = DEFAULT_SAFETY_TIMEOUT_MS[label] ?: DEFAULT_SAFETY_FALLBACK_MS
        val newCount: Int
        synchronized(sessionsLock) {
            val current = activeSessions[label]
            val next = (current?.refcount ?: 0) + 1
            activeSessions[label] = SessionEntry(next, System.currentTimeMillis() + timeoutMs)
            newCount = next
        }
        listener?.onLog("session.set label=$label refcount=$newCount")
        safetyHandler.postDelayed({
            synchronized(sessionsLock) {
                val entry = activeSessions[label] ?: return@synchronized
                if (System.currentTimeMillis() >= entry.expiresAtMs) {
                    activeSessions.remove(label)
                    listener?.onLog("session.safety_timeout label=$label refcount_was=${entry.refcount}")
                }
            }
        }, timeoutMs + 100)
    }

    fun clearActiveSession(label: String) {
        val newCount: Int
        synchronized(sessionsLock) {
            val current = activeSessions[label] ?: return
            val next = current.refcount - 1
            if (next <= 0) {
                activeSessions.remove(label)
                newCount = 0
            } else {
                activeSessions[label] = SessionEntry(next, current.expiresAtMs)
                newCount = next
            }
        }
        listener?.onLog("session.clear label=$label refcount=$newCount")
    }

    fun isAnySessionActive(): Boolean = synchronized(sessionsLock) { activeSessions.isNotEmpty() }
}

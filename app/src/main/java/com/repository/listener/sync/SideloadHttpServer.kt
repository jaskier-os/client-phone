package com.repository.listener.sync

import android.os.Build
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger

/**
 * LAN-facing HTTP server exposed to the desktop while the phone's enable_sideloading
 * toggle is on. Hand-rolled raw [ServerSocket] in the same style as the glasses
 * filesync FileHttpServer (no NanoHTTPD/Ktor). Forwards desktop requests to the
 * glasses sideload endpoints via [SideloadForwarder].
 *
 * Endpoints (desktop -> phone):
 *   GET  /sideload/health            -> {"ok":true,"glassesBt":<bool>}
 *   POST /sideload/open              -> establish BT+WiFi-Direct session
 *   POST /sideload/close             -> tear down session
 *   POST /sideload/upload?name=<f>   -> stream raw body to glasses /sideload/upload
 *   POST /sideload/exec  body {cmd}  -> forward to glasses /sideload/exec
 *   POST /sideload/cleanup           -> forward to glasses /sideload/cleanup
 *
 * No auth (intentional per spec). Each request runs on its own pooled thread; all
 * parsing is guarded so a malformed desktop request cannot crash the app.
 */
class SideloadHttpServer(
    private val forwarder: SideloadForwarder,
    private val isBtConnected: () -> Boolean,
    /**
     * Returns the phone's primary WiFi (wlan0) [android.net.Network], or null if unavailable.
     * Each accepted client socket is bound to it so the server's TCP replies carry the LAN
     * routing mark and bypass any active VPN (Hiddify/AmneziaVPN force unmarked app traffic
     * into the tun killswitch table, which silently blackholes the SYN-ACK to the desktop).
     * Must be the non-p2p WiFi network: the forwarder separately binds the *process* to the
     * p2p network for glasses traffic, so the LAN reply path has to be pinned per-socket.
     */
    private val lanNetwork: () -> android.net.Network? = { null },
    private val port: Int = DEFAULT_PORT,
) {

    companion object {
        const val DEFAULT_PORT = 8771
        private const val TAG = "SideloadHttpServer"
        /** Hard cap on a buffered (non-streamed) request body. Uploads are streamed. */
        private const val MAX_BUFFERED_BODY = 8 * 1024 * 1024
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_UPLOAD_BYTES = 512L * 1024 * 1024
    }

    var remoteLog: ((String) -> Unit)? = null
    private fun log(msg: String) { remoteLog?.invoke("$TAG: $msg") }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var running = false

    private val workerId = AtomicInteger(0)
    @Volatile private var workers: ThreadPoolExecutor = newWorkerPool()

    private fun newWorkerPool(): ThreadPoolExecutor =
        Executors.newCachedThreadPool { r ->
            Thread(r, "sideload-http-${workerId.incrementAndGet()}").apply { isDaemon = true }
        } as ThreadPoolExecutor

    val isRunning: Boolean get() = running

    @Synchronized
    fun start() {
        if (running) { log("start: already running"); return }
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", port))
            }
        } catch (e: IOException) {
            log("start: bind failed on port $port: ${e.message}")
            return
        }
        serverSocket = socket
        running = true
        // A prior stop() shut the pool down; cached pools are not restartable, so make a fresh one.
        if (workers.isShutdown) workers = newWorkerPool()
        val t = Thread({ acceptLoop(socket) }, "sideload-http-accept").apply { isDaemon = true }
        acceptThread = t
        t.start()
        log("started on 0.0.0.0:$port")
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread = null
        // Cancel in-flight workers (a worker can be parked up to ~70s inside forwarder.open()).
        try { workers.shutdownNow() } catch (_: Exception) {}
        // Best-effort: drop any live session so we do not leave the glasses WiFi up.
        try { forwarder.close() } catch (_: Exception) {}
        log("stopped")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running) log("accept error: ${e.message}")
                break
            }
            try {
                workers.execute { handleClient(client) }
            } catch (e: Exception) {
                log("worker reject: ${e.message}")
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 60_000
            // Pin this socket to wlan0 so the reply leaves over the LAN, not the VPN tun.
            // Returns false (and closes) if it could not be pinned -- abort rather than hang.
            if (!bindSocketToLan(client)) return
            val input = client.getInputStream()
            val out = BufferedOutputStream(client.getOutputStream())

            val requestLine = readLine(input)
            if (requestLine.isNullOrBlank()) { client.close(); return }
            val parts = requestLine.split(" ")
            if (parts.size < 2) { writeError(out, 400, "bad request line"); client.close(); return }
            val method = parts[0].uppercase()
            val rawPath = parts[1]
            val path = rawPath.substringBefore('?')
            val query = if (rawPath.contains('?')) rawPath.substringAfter('?') else ""

            val headers = HashMap<String, String>()
            var headerBytes = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                headerBytes += line.length
                if (headerBytes > MAX_HEADER_BYTES) { writeError(out, 431, "headers too large"); client.close(); return }
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L

            route(method, path, query, headers, contentLength, input, out)
            out.flush()
        } catch (e: Exception) {
            log("handleClient error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun route(
        method: String,
        path: String,
        query: String,
        headers: Map<String, String>,
        contentLength: Long,
        input: InputStream,
        out: BufferedOutputStream,
    ) {
        when {
            method == "GET" && path == "/sideload/health" -> {
                drain(input, contentLength)
                val body = JSONObject().put("ok", true).put("glassesBt", isBtConnected()).toString()
                writeJson(out, 200, body)
            }
            method == "POST" && path == "/sideload/open" -> {
                drain(input, contentLength)
                guarded(out) {
                    forwarder.open()
                    writeJson(out, 200, JSONObject().put("ok", true).toString())
                }
            }
            method == "POST" && path == "/sideload/close" -> {
                drain(input, contentLength)
                guarded(out) {
                    forwarder.close()
                    writeJson(out, 200, JSONObject().put("ok", true).toString())
                }
            }
            method == "POST" && path == "/sideload/upload" -> {
                val name = paramOf(query, "name")
                if (name.isNullOrBlank()) {
                    drain(input, contentLength)
                    writeJson(out, 400, errJson("missing name query param"))
                    return
                }
                if (contentLength <= 0L) {
                    writeJson(out, 400, errJson("missing or zero Content-Length"))
                    return
                }
                if (contentLength > MAX_UPLOAD_BYTES) {
                    drain(input, contentLength)
                    writeJson(out, 413, errJson("upload too large"))
                    return
                }
                guarded(out) {
                    val bounded = BoundedInputStream(input, contentLength)
                    val resp = forwarder.upload(sanitizeName(name), bounded, contentLength)
                    // Drain any unread bytes so the socket framing stays sane before close.
                    try { bounded.drainRemaining() } catch (_: Exception) {}
                    writeJson(out, 200, resp)
                }
            }
            method == "POST" && path == "/sideload/exec" -> {
                val body = readBody(input, contentLength) ?: run {
                    writeJson(out, 413, errJson("exec body too large")); return
                }
                guarded(out) {
                    val resp = forwarder.exec(body)
                    writeJson(out, 200, resp)
                }
            }
            method == "POST" && path == "/sideload/exec/start" -> {
                val body = readBody(input, contentLength) ?: run {
                    writeJson(out, 413, errJson("exec/start body too large")); return
                }
                guarded(out) {
                    val resp = forwarder.execStart(body)
                    writeJson(out, 200, resp)
                }
            }
            method == "POST" && path == "/sideload/exec/poll" -> {
                val body = readBody(input, contentLength) ?: run {
                    writeJson(out, 413, errJson("exec/poll body too large")); return
                }
                guarded(out) {
                    val resp = forwarder.execPoll(body)
                    writeJson(out, 200, resp)
                }
            }
            method == "POST" && path == "/sideload/cleanup" -> {
                drain(input, contentLength)
                guarded(out) {
                    val resp = forwarder.cleanup()
                    writeJson(out, 200, resp)
                }
            }
            else -> {
                drain(input, contentLength)
                writeJson(out, 404, errJson("not found: $method $path"))
            }
        }
    }

    private inline fun guarded(out: BufferedOutputStream, block: () -> Unit) {
        try {
            block()
        } catch (e: SideloadException) {
            writeJson(out, 502, errJson(e.message ?: "sideload error"))
        } catch (e: Exception) {
            log("request handler error: ${e.message}")
            writeJson(out, 500, errJson("internal error: ${e.message}"))
        }
    }

    // ----- helpers -----

    /**
     * Binds an accepted client socket to the WiFi (wlan0) network so its TCP replies route
     * over the LAN regardless of an active VPN killswitch. No-op (logged) when the LAN
     * network is unavailable -- on a phone without a VPN the default route already reaches
     * the LAN, so an unbound socket still works.
     */
    /**
     * @return true if the socket may proceed, false if it was closed and the caller must abort.
     * For a non-loopback peer we MUST pin the reply to wlan0 (a VPN killswitch otherwise routes
     * the SYN-ACK out the tun and the desktop hangs to its read timeout). If we cannot bind --
     * no LAN network, or bindSocket throws -- we close the socket so the desktop gets an immediate
     * RST instead of a multi-minute hang. Loopback (adb-forward over USB) is never bound and
     * always allowed: it has no VPN-routing problem and binding it to wlan0 would break it.
     */
    private fun bindSocketToLan(client: Socket): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (client.inetAddress?.isLoopbackAddress == true) return true
        val net = try { lanNetwork() } catch (_: Exception) { null }
        if (net == null) {
            log("bindSocketToLan: no wlan0 network for non-loopback peer; closing to avoid VPN black-hole")
            try { client.close() } catch (_: Exception) {}
            return false
        }
        return try {
            net.bindSocket(client)
            true
        } catch (e: Exception) {
            log("bindSocketToLan failed (${e.message}); closing to avoid VPN black-hole")
            try { client.close() } catch (_: Exception) {}
            false
        }
    }

    private fun sanitizeName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "upload.bin" }

    private fun paramOf(query: String, key: String): String? {
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq) == key) {
                return try { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") } catch (_: Exception) { null }
            }
        }
        return null
    }

    private fun errJson(msg: String): String =
        JSONObject().put("ok", false).put("error", msg).toString()

    /**
     * Reads a CRLF/LF-terminated line as ASCII; null on EOF before any byte. A single line
     * is capped at [MAX_HEADER_BYTES] so a malicious desktop cannot blow up memory with one
     * gigantic unterminated line; excess bytes are discarded until the line terminator.
     */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var sawAny = false
        var overflowed = false
        while (true) {
            val b = input.read()
            if (b < 0) return if (sawAny) sb.toString() else null
            sawAny = true
            if (b == '\n'.code) break
            if (b == '\r'.code) continue
            if (sb.length >= MAX_HEADER_BYTES) { overflowed = true; continue }
            if (!overflowed) sb.append(b.toChar())
        }
        return sb.toString()
    }

    /** Buffers a bounded body fully. Returns null if it exceeds [MAX_BUFFERED_BODY]. */
    private fun readBody(input: InputStream, contentLength: Long): String? {
        if (contentLength <= 0L) return ""
        if (contentLength > MAX_BUFFERED_BODY) { drain(input, contentLength); return null }
        val buf = ByteArray(contentLength.toInt())
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun drain(input: InputStream, contentLength: Long) {
        if (contentLength <= 0L) return
        var remaining = contentLength
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
    }

    private fun writeJson(out: BufferedOutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code ${statusText(code)}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        try {
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.write(bytes)
        } catch (_: Exception) {}
    }

    private fun writeError(out: BufferedOutputStream, code: Int, msg: String) =
        writeJson(out, code, errJson(msg))

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 413 -> "Payload Too Large"
        431 -> "Request Header Fields Too Large"; 500 -> "Internal Server Error"
        502 -> "Bad Gateway"; else -> "Status"
    }
}

/** Caps reads to [limit] bytes so a streamed upload cannot read past Content-Length. */
private class BoundedInputStream(
    private val src: InputStream,
    private val limit: Long,
) : InputStream() {
    private var consumed = 0L
    override fun read(): Int {
        if (consumed >= limit) return -1
        val b = src.read()
        if (b >= 0) consumed++
        return b
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (consumed >= limit) return -1
        val toRead = minOf(len.toLong(), limit - consumed).toInt()
        val n = src.read(b, off, toRead)
        if (n > 0) consumed += n
        return n
    }
    fun drainRemaining() {
        val buf = ByteArray(64 * 1024)
        while (consumed < limit) {
            val n = read(buf, 0, buf.size)
            if (n < 0) break
        }
    }
}

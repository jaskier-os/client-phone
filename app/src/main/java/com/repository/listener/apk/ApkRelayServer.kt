package com.repository.listener.apk

import com.repository.listener.bt.BluetoothHelper
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApkRelayServer(
    private val port: Int,
    private val cacheDir: File,
    private val onApkReceived: (File, ((Boolean, String) -> Unit)?) -> Unit,
    private val log: (String) -> Unit,
    private val getConnectionState: () -> BluetoothHelper.ConnectionState,
    private val onHotspotToggle: ((enabled: Boolean, callback: (String?) -> Unit) -> Unit)? = null
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var lastHash: String? = null
    @Volatile var feedTargetUrl: String? = null
    private val cachedApkFile = File(cacheDir, "relay-cached.apk")
    private val forwardClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    fun start() {
        running = true
        Thread {
            var socket: ServerSocket? = null
            for (attempt in 1..10) {
                try {
                    socket = ServerSocket()
                    socket.reuseAddress = true
                    socket.bind(java.net.InetSocketAddress(port))
                    break
                } catch (e: java.net.BindException) {
                    socket?.close()
                    socket = null
                    if (attempt == 1) {
                        log("[relay] Port $port in use, killing old process...")
                        killProcessOnPort(port)
                    }
                    log("[relay] Port $port in use (attempt $attempt/10), retrying in 1s...")
                    Thread.sleep(1000)
                }
            }
            if (socket == null) {
                log("[relay] Failed to bind port $port after 10 attempts")
                return@Thread
            }
            serverSocket = socket
            log("[relay] Server listening on port $port")
            while (running) {
                try {
                    val client = serverSocket?.accept() ?: break
                    Thread { handleClient(client) }.start()
                } catch (e: Exception) {
                    if (running) log("[relay] Accept error: ${e.message}")
                }
            }
        }.start()
    }

    private fun killProcessOnPort(port: Int) {
        try {
            val hexPort = "%04X".format(port)
            val myPid = android.os.Process.myPid()
            val inodes = mutableSetOf<String>()
            for (path in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
                try {
                    File(path).readLines().drop(1).forEach { line ->
                        val fields = line.trim().split("\\s+".toRegex())
                        if (fields.size >= 10) {
                            val localPort = fields[1].substringAfter(":")
                            if (localPort.equals(hexPort, ignoreCase = true) && fields[3] == "0A") {
                                inodes.add(fields[9])
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            if (inodes.isEmpty()) {
                log("[relay] No LISTEN socket found on port $port")
                return
            }
            log("[relay] Found socket inodes on port $port: $inodes")
            val procDir = File("/proc")
            for (pidDir in procDir.listFiles() ?: emptyArray()) {
                val pid = pidDir.name.toIntOrNull() ?: continue
                if (pid == myPid) continue
                val fdDir = File(pidDir, "fd")
                try {
                    for (fd in fdDir.listFiles() ?: emptyArray()) {
                        try {
                            val link = java.nio.file.Files.readSymbolicLink(fd.toPath()).toString()
                            for (inode in inodes) {
                                if (link == "socket:[$inode]") {
                                    log("[relay] Port $port held by PID $pid, killing...")
                                    android.os.Process.killProcess(pid)
                                    return
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
            log("[relay] Could not find PID for port $port (may lack /proc access)")
        } catch (e: Exception) {
            log("[relay] killProcessOnPort error: ${e.message}")
        }
    }

    fun stop() {
        running = false
        try { cachedApkFile.delete() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { sock ->
                val input = BufferedInputStream(sock.getInputStream())
                val out = sock.getOutputStream()

                val requestLine = readLine(input)
                if (requestLine.isNullOrEmpty()) return

                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0]
                val path = parts[1]

                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val colonIdx = line.indexOf(':')
                    if (colonIdx > 0) {
                        headers[line.substring(0, colonIdx).trim().lowercase()] =
                            line.substring(colonIdx + 1).trim()
                    }
                }

                when {
                    method == "GET" && path == "/status" -> {
                        val state = getConnectionState()
                        val statusStr = when (state) {
                            BluetoothHelper.ConnectionState.NOT_CONNECTED -> "not_connected"
                            BluetoothHelper.ConnectionState.CONNECTING -> "connecting"
                            BluetoothHelper.ConnectionState.CONNECTED -> "ready"
                        }
                        val body = """{"status":"$statusStr"}"""
                        respond(out, 200, "application/json", body.toByteArray())
                    }
                    method == "GET" && path == "/hash" -> {
                        val h = lastHash ?: ""
                        respond(out, 200, "text/plain", h.toByteArray())
                    }
                    method == "GET" && path == "/feed-target" -> {
                        val target = feedTargetUrl ?: ""
                        respond(out, 200, "text/plain", target.toByteArray())
                    }
                    method == "POST" && path == "/set-feed-target" -> {
                        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                        val bodyBytes = if (contentLength > 0) {
                            val buf = ByteArray(contentLength.toInt().coerceAtMost(1024))
                            var read = 0
                            while (read < buf.size) {
                                val n = input.read(buf, read, buf.size - read)
                                if (n == -1) break
                                read += n
                            }
                            buf.copyOf(read)
                        } else ByteArray(0)
                        val url = String(bodyBytes).trim()
                        if (url.isNotEmpty()) {
                            feedTargetUrl = url
                            log("[relay] Feed target set: $url")
                            respond(out, 200, "application/json",
                                """{"status":"ok","target":"$url"}""".toByteArray())
                        } else {
                            feedTargetUrl = null
                            log("[relay] Feed target cleared")
                            respond(out, 200, "application/json",
                                """{"status":"ok","target":null}""".toByteArray())
                        }
                    }
                    method == "POST" && path == "/retry" -> {
                        if (cachedApkFile.exists()) {
                            log("[relay] Retry requested, re-uploading cached APK to glasses...")
                            val latch = CountDownLatch(1)
                            var installSuccess = false
                            var installMessage = "timeout"

                            onApkReceived(cachedApkFile) { success, message ->
                                installSuccess = success
                                installMessage = message
                                latch.countDown()
                            }

                            if (latch.await(10, TimeUnit.MINUTES)) {
                                val status = if (installSuccess) "installed" else "failed"
                                val code = if (installSuccess) 200 else 502
                                respond(out, code, "application/json",
                                    """{"status":"$status","message":"${installMessage.replace("\"", "\\\"")}"}""".toByteArray())
                            } else {
                                respond(out, 504, "application/json",
                                    """{"status":"timeout","message":"Install did not complete within 10 minutes"}""".toByteArray())
                            }
                        } else {
                            respond(out, 400, "text/plain", "No cached APK".toByteArray())
                        }
                    }
                    method == "POST" && path == "/upload" -> {
                        val contentLength = headers["content-length"]?.toLongOrNull()
                        if (contentLength == null || contentLength <= 0) {
                            respond(out, 400, "text/plain", "Missing Content-Length".toByteArray())
                            return
                        }

                        log("[relay] Receiving APK ($contentLength bytes)...")
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buf = ByteArray(65536)
                        var remaining = contentLength
                        var totalWritten = 0L
                        FileOutputStream(cachedApkFile).use { fos ->
                            while (remaining > 0) {
                                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                                val read = input.read(buf, 0, toRead)
                                if (read == -1) break
                                fos.write(buf, 0, read)
                                digest.update(buf, 0, read)
                                remaining -= read
                                totalWritten += read
                            }
                        }

                        if (totalWritten != contentLength) {
                            log("[relay] Size mismatch: expected=$contentLength actual=$totalWritten")
                            cachedApkFile.delete()
                            respond(out, 400, "text/plain", "Incomplete upload".toByteArray())
                            return
                        }

                        val hash = digest.digest().joinToString("") { "%02x".format(it) }

                        if (hash != lastHash) {
                            lastHash = hash
                            log("[relay] New APK (hash=${hash.take(12)}...), uploading to glasses...")
                        } else {
                            log("[relay] APK unchanged (same hash), re-uploading to glasses...")
                        }

                        val latch = CountDownLatch(1)
                        var installSuccess = false
                        var installMessage = "timeout"

                        onApkReceived(cachedApkFile) { success, message ->
                            installSuccess = success
                            installMessage = message
                            latch.countDown()
                        }

                        if (latch.await(10, TimeUnit.MINUTES)) {
                            val status = if (installSuccess) "installed" else "failed"
                            val code = if (installSuccess) 200 else 502
                            respond(out, code, "application/json",
                                """{"status":"$status","size":$totalWritten,"message":"${installMessage.replace("\"", "\\\"")}"}""".toByteArray())
                        } else {
                            respond(out, 504, "application/json",
                                """{"status":"timeout","size":$totalWritten,"message":"Install did not complete within 10 minutes"}""".toByteArray())
                        }
                    }
                    method == "POST" && path == "/hotspot" -> {
                        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                        val bodyBytes = if (contentLength > 0) {
                            val buf = ByteArray(contentLength.toInt().coerceAtMost(1024))
                            var read = 0
                            while (read < buf.size) {
                                val n = input.read(buf, read, buf.size - read)
                                if (n == -1) break
                                read += n
                            }
                            buf.copyOf(read)
                        } else ByteArray(0)
                        val bodyStr = String(bodyBytes).trim()
                        val enabled = try {
                            JSONObject(bodyStr).optBoolean("enabled", false)
                        } catch (_: Exception) { false }

                        if (getConnectionState() != BluetoothHelper.ConnectionState.CONNECTED) {
                            respond(out, 503, "application/json",
                                """{"error":"not connected"}""".toByteArray())
                            return
                        }

                        val toggle = onHotspotToggle
                        if (toggle == null) {
                            respond(out, 501, "application/json",
                                """{"error":"hotspot not supported"}""".toByteArray())
                            return
                        }

                        val latch = CountDownLatch(1)
                        var resultJson: String? = null
                        toggle(enabled) { json ->
                            resultJson = json
                            latch.countDown()
                        }

                        if (latch.await(20, TimeUnit.SECONDS)) {
                            val json = resultJson ?: """{"error":"hotspot failed"}"""
                            respond(out, 200, "application/json", json.toByteArray())
                        } else {
                            respond(out, 504, "application/json",
                                """{"error":"timeout"}""".toByteArray())
                        }
                    }
                    method == "POST" && path.startsWith("/debug/") -> {
                        forwardToFeedTarget(path.removePrefix("/debug"), headers, input, out)
                    }
                    else -> {
                        respond(out, 404, "text/plain", "Not found".toByteArray())
                    }
                }
            }
        } catch (e: Exception) {
            log("[relay] Client error: ${e.message}")
        }
    }

    private fun forwardToFeedTarget(
        subPath: String,
        headers: Map<String, String>,
        input: BufferedInputStream,
        out: java.io.OutputStream
    ) {
        val target = feedTargetUrl
        if (target == null) {
            respond(out, 503, "text/plain", "No feed target configured (POST /set-feed-target first)".toByteArray())
            return
        }

        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
        val contentType = headers["content-type"] ?: "application/octet-stream"

        val bodyBytes = if (contentLength > 0) {
            val baos = ByteArrayOutputStream(contentLength.toInt())
            val buf = ByteArray(65536)
            var remaining = contentLength
            while (remaining > 0) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val read = input.read(buf, 0, toRead)
                if (read == -1) break
                baos.write(buf, 0, read)
                remaining -= read
            }
            baos.toByteArray()
        } else {
            ByteArray(0)
        }

        try {
            val targetUrl = "$target$subPath"
            val reqBody = bodyBytes.toRequestBody(contentType.toMediaType())
            val hopByHop = setOf("host", "content-length", "connection", "transfer-encoding")
            val fwdReqBuilder = Request.Builder()
                .url(targetUrl)
                .post(reqBody)
            for ((key, value) in headers) {
                if (key !in hopByHop) {
                    fwdReqBuilder.header(key, value)
                }
            }
            val fwdResp = forwardClient.newCall(fwdReqBuilder.build()).execute()
            val respBody = fwdResp.body?.bytes() ?: ByteArray(0)
            val respContentType = fwdResp.header("Content-Type") ?: "application/octet-stream"
            respond(out, fwdResp.code, respContentType, respBody)
            fwdResp.close()
        } catch (e: Exception) {
            respond(out, 502, "text/plain", "Forward failed: ${e.message}".toByteArray())
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun respond(out: java.io.OutputStream, code: Int, contentType: String, body: ByteArray) {
        val status = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            501 -> "Not Implemented"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            504 -> "Gateway Timeout"
            else -> "Error"
        }
        val header = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(body)
        out.flush()
    }
}

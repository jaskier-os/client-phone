package com.repository.listener.sync

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Phone-side sideload forwarder. Bridges the desktop-facing LAN server
 * ([SideloadHttpServer]) to the glasses sideload HTTP endpoints over WiFi-Direct.
 *
 * Session lifecycle:
 *   open()  -> send CH_SIDELOAD {"t":"OPEN_WIFI"} over BT, await WIFI_READY,
 *              join the glasses P2P group via [WifiDirectJoiner], bind the process
 *              network so OkHttp routes over p2p0.
 *   upload()/exec()/cleanup() -> POST to http://<details.ip>:<details.port>/sideload/...
 *   close() -> leave the P2P group, send CH_SIDELOAD {"t":"CLOSE_WIFI"} over BT.
 *
 * Reuses [WifiDirectJoiner] as the sole WiFi/P2P manager (no other WiFi toggling).
 * BT replies arrive asynchronously via [onBtReply]; open() blocks on a rendezvous
 * queue until WIFI_READY / WIFI_ERROR / timeout.
 */
class SideloadForwarder(
    private val context: Context,
    /** Send a CH_SIDELOAD JSON frame to the glasses. Returns false if BT is down. */
    private val sendBtFrame: (json: String) -> Boolean,
    /** True when the RFCOMM link to the glasses is up. */
    private val isBtConnected: () -> Boolean,
) {

    companion object {
        private const val TAG = "SideloadForwarder"
        private const val OPEN_TIMEOUT_MS = 40_000L
        private const val JOIN_TIMEOUT_MS = 30_000L
        private const val HTTP_CONNECT_TIMEOUT_S = 15L
        private const val HTTP_RW_TIMEOUT_S = 300L
    }

    var remoteLog: ((String) -> Unit)? = null
    private fun log(msg: String) { remoteLog?.invoke("$TAG: $msg") }

    private val joiner = WifiDirectJoiner(context).also { it.remoteLog = { m -> remoteLog?.invoke(m) } }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(HTTP_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(HTTP_RW_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(HTTP_RW_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val lock = Any()
    /** Set once the glasses report WIFI_READY and we have joined the group. */
    @Volatile private var baseUrl: String? = null
    /**
     * Rendezvous for the in-flight OPEN_WIFI reply; non-null only while open() waits.
     * A LinkedBlockingQueue (not SynchronousQueue) is required: the BT reply arrives
     * asynchronously and may be offered before the waiter parks on poll(); a buffered
     * queue keeps that reply instead of dropping it (which would force a spurious timeout).
     */
    @Volatile private var openReplyQueue: LinkedBlockingQueue<JSONObject>? = null
    @Volatile private var joinResult: LinkedBlockingQueue<Pair<Boolean, String>>? = null

    /** True when a session is established (joined + base URL known). */
    val isSessionOpen: Boolean get() = baseUrl != null

    /** Fed from PhoneBtHost when a CH_SIDELOAD frame arrives from the glasses. */
    fun onBtReply(json: String) {
        val obj = try { JSONObject(json) } catch (e: Exception) {
            log("onBtReply: bad json: ${e.message}"); return
        }
        when (obj.optString("t")) {
            "WIFI_READY", "WIFI_ERROR" -> openReplyQueue?.offer(obj)
            "WIFI_CLOSED" -> log("glasses confirmed WIFI_CLOSED")
            else -> log("onBtReply: unknown t=${obj.optString("t")}")
        }
    }

    /**
     * Establish the sideload session. Blocking; call off the LAN server accept thread's
     * critical section (the server already runs each request on its own thread).
     * @throws SideloadException on any failure.
     */
    fun open() {
        synchronized(lock) {
            if (baseUrl != null) { log("open: session already established"); return }
            if (!isBtConnected()) throw SideloadException("glasses not connected over BT")

            val q = LinkedBlockingQueue<JSONObject>()
            openReplyQueue = q
            try {
                if (!sendBtFrame(JSONObject().put("t", "OPEN_WIFI").toString())) {
                    throw SideloadException("failed to send OPEN_WIFI over BT")
                }
                val reply = q.poll(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    ?: throw SideloadException("timed out waiting for WIFI_READY")
                if (reply.optString("t") == "WIFI_ERROR") {
                    throw SideloadException("glasses WIFI_ERROR: ${reply.optString("reason")}")
                }
                val details = reply.optJSONObject("details")
                    ?: throw SideloadException("WIFI_READY missing details")
                val ip = details.optString("ip")
                val port = details.optInt("port")
                if (ip.isEmpty() || port <= 0) throw SideloadException("WIFI_READY bad ip/port")

                joinGroup(details)
                baseUrl = "http://$ip:$port"
                log("session established, base=$baseUrl")
            } finally {
                openReplyQueue = null
            }
        }
    }

    private fun joinGroup(details: JSONObject) {
        val jq = LinkedBlockingQueue<Pair<Boolean, String>>()
        joinResult = jq
        joiner.onReady = { ip -> jq.offer(true to ip) }
        joiner.onFailed = { reason -> jq.offer(false to reason) }
        try {
            joiner.join(
                WifiDirectJoiner.GroupDetails(
                    ssid = details.optString("ssid"),
                    passphrase = details.optString("passphrase"),
                    ip = details.optString("ip"),
                    port = details.optInt("port"),
                    deviceAddress = details.optString("deviceAddress").ifEmpty { null },
                )
            )
            val res = jq.poll(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ?: run { joiner.close(); throw SideloadException("timed out joining WiFi-Direct group") }
            if (!res.first) {
                joiner.close()
                throw SideloadException("WiFi-Direct join failed: ${res.second}")
            }
        } finally {
            joinResult = null
        }
    }

    /**
     * Invalidate the session locally without a BT round-trip. Called when the RFCOMM link
     * drops mid-session: the p2p0 route is gone, so any further HTTP would hang until the
     * 300s read timeout. Drops baseUrl and leaves the P2P group so the next desktop request
     * fails fast with a clear error instead of stalling.
     */
    fun invalidateSession() {
        synchronized(lock) {
            if (baseUrl == null) return
            baseUrl = null
            try { joiner.close() } catch (e: Exception) { log("invalidateSession: joiner error: ${e.message}") }
            log("session invalidated (BT link down)")
        }
    }

    /** Tear down the session: leave the P2P group and tell the glasses to close WiFi. */
    fun close() {
        synchronized(lock) {
            baseUrl = null
            try { joiner.close() } catch (e: Exception) { log("close: joiner error: ${e.message}") }
            if (isBtConnected()) {
                try { sendBtFrame(JSONObject().put("t", "CLOSE_WIFI").toString()) } catch (_: Exception) {}
            }
            log("session closed")
        }
    }

    private fun requireBase(): String =
        baseUrl ?: throw SideloadException("no sideload session; call /sideload/open first")

    /**
     * Forward an upload to the glasses. Streams [body] (no full buffering) to avoid OOM
     * on large APKs. Returns the glasses JSON response body verbatim.
     */
    fun upload(name: String, body: InputStream, contentLength: Long): String {
        val base = requireBase()
        val streaming = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = contentLength
            override fun writeTo(sink: BufferedSink) {
                body.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                    }
                }
            }
        }
        val req = Request.Builder()
            .url("$base/sideload/upload")
            .header("X-Upload-Name", name)
            .post(streaming)
            .build()
        return executeForString(req)
    }

    /** Forward an exec request to the glasses. Returns the glasses JSON response verbatim. */
    fun exec(cmdJson: String): String {
        val base = requireBase()
        val req = Request.Builder()
            .url("$base/sideload/exec")
            .post(cmdJson.toRequestBody("application/json".toMediaType()))
            .build()
        return executeForString(req)
    }

    /** Start an async root command on the glasses. Body {"cmd":...} -> {"job":...} verbatim. */
    fun execStart(cmdJson: String): String {
        val base = requireBase()
        val req = Request.Builder()
            .url("$base/sideload/exec/start")
            .post(cmdJson.toRequestBody("application/json".toMediaType()))
            .build()
        return executeForString(req)
    }

    /** Poll an async job. Body {"job":...,"stdoutFrom":N,"stderrFrom":M} -> incremental output. */
    fun execPoll(pollJson: String): String {
        val base = requireBase()
        val req = Request.Builder()
            .url("$base/sideload/exec/poll")
            .post(pollJson.toRequestBody("application/json".toMediaType()))
            .build()
        return executeForString(req)
    }

    /** Forward a cleanup request to the glasses. Returns the glasses JSON response verbatim. */
    fun cleanup(): String {
        val base = requireBase()
        val req = Request.Builder()
            .url("$base/sideload/cleanup")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return executeForString(req)
    }

    private fun executeForString(req: Request): String {
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    throw SideloadException("glasses HTTP ${resp.code}: ${text.take(500)}")
                }
                return text
            }
        } catch (e: SideloadException) {
            throw e
        } catch (e: Exception) {
            throw SideloadException("glasses request failed: ${e.message}")
        }
    }
}

class SideloadException(message: String) : Exception(message)

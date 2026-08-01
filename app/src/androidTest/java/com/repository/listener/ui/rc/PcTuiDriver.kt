package com.repository.listener.ui.rc

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Phone-side client for the PC control server at
 * `AI/clients/phone/test/rc-live-attach/tui-driver.mjs`.
 *
 * The live-attach feature is half PC, half phone; an on-device test that can
 * only see the phone cannot tell an attach from a spawn (both render an
 * assistant reply). This client is how the test drives and observes the PC
 * half: start a REAL interactive TUI, type into it as a human would, read what
 * it printed, and interrogate the CLI's own attach socket for its authoritative
 * self-view.
 *
 * Nothing here is a mock. Every endpoint either runs the real CLI binary or
 * reads real state (the session registry, the attach socket, the process
 * table). The orchestrator, pc-agent and attach handshake are untouched.
 *
 * Reached over `adb reverse tcp:8792 tcp:8792`, so it works regardless of LAN,
 * MIUI firewall or an active VPN on the phone.
 */
class PcTuiDriver(private val baseUrl: String = DEFAULT_BASE_URL) {

    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8792"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(190, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    private fun get(path: String): JSONObject = exec(
        Request.Builder().url("$baseUrl$path").get().build()
    )

    private fun post(path: String, body: JSONObject = JSONObject()): JSONObject = exec(
        Request.Builder().url("$baseUrl$path")
            .post(body.toString().toRequestBody(JSON))
            .build()
    )

    private fun exec(request: Request): JSONObject {
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw AssertionError(
                    "PC driver ${request.method} ${request.url.encodedPath} failed: " +
                        "HTTP ${resp.code} ${detail ?: text}"
                )
            }
            return JSONObject(text)
        }
    }

    // ------------------------------------------------------------------
    // Preconditions
    // ------------------------------------------------------------------

    /**
     * Fails with an actionable message when the driver is unreachable. Called
     * from @Before so a missing `adb reverse` surfaces as a setup error rather
     * than as a confusing mid-test failure.
     */
    fun requireReachable() {
        try {
            val health = get("/health")
            check(health.optBoolean("ok")) { "driver /health returned ok=false: $health" }
        } catch (e: Throwable) {
            throw AssertionError(
                "PC TUI driver not reachable at $baseUrl. On the PC run:\n" +
                    "  node AI/clients/phone/test/rc-live-attach/tui-driver.mjs &\n" +
                    "  adb reverse tcp:8792 tcp:8792\n" +
                    "cause: ${e.message}",
                e
            )
        }
    }

    // ------------------------------------------------------------------
    // TUI lifecycle
    // ------------------------------------------------------------------

    data class TuiSession(
        val sessionId: String,
        val pid: Int,
        val workDir: String,
        val attachSocketPath: String,
        val kind: String
    )

    /**
     * Start a real interactive remote-session TUI and block until it has
     * published its attach socket (i.e. pc-agent's findLiveSession can see it).
     * Returning early would make every downstream assertion a race.
     */
    fun startTui(
        workDir: String,
        model: String? = null,
        permissionMode: String = "default",
        sessionId: String? = null
    ): TuiSession {
        val body = JSONObject().apply {
            put("workDir", workDir)
            if (model != null) put("model", model)
            put("permissionMode", permissionMode)
            if (sessionId != null) put("sessionId", sessionId)
        }
        val out = post("/tui/start", body)
        return TuiSession(
            sessionId = out.getString("sessionId"),
            pid = out.getInt("pid"),
            workDir = workDir,
            attachSocketPath = out.optString("attachSocketPath", ""),
            kind = out.optString("kind", "")
        )
    }

    /** Type a line into the TUI and press Enter, exactly as the PC user would. */
    fun type(text: String) {
        post("/tui/type", JSONObject().put("text", text))
    }

    /** Raw keystrokes (e.g. "\u0019" for ctrl+y). */
    fun keys(raw: String) {
        post("/tui/keys", JSONObject().put("raw", raw))
    }

    /** ANSI-stripped pty output since [since] characters. */
    fun screen(since: Int = 0): String = get("/tui/screen?since=$since").getString("text")

    fun screenLength(): Int = get("/tui/screen?since=0").getInt("len")

    /** True once the TUI process has exited. */
    fun hasExited(): Boolean = get("/tui/screen?since=0").optJSONObject("exited") != null

    /**
     * Poll the TUI transcript for [needle]. Returns the matching screen text.
     * @throws AssertionError with the tail of the screen on timeout, so a
     *   failure says what the TUI actually showed instead of just "false".
     */
    fun awaitScreenContains(needle: String, timeoutMs: Long, since: Int = 0): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            last = screen(since)
            if (last.contains(needle)) return last
            Thread.sleep(500)
        }
        throw AssertionError(
            "TUI never printed '$needle' within ${timeoutMs}ms. Screen tail:\n" +
                last.takeLast(2000)
        )
    }

    fun killTui(signal: String = "SIGKILL") {
        post("/tui/kill", JSONObject().put("signal", signal))
    }

    fun reset() {
        runCatching { post("/reset") }
    }

    // ------------------------------------------------------------------
    // Authoritative attach state
    // ------------------------------------------------------------------

    data class Probe(
        val sessionId: String,
        val cwd: String,
        val attached: Boolean,
        val permissionMode: String,
        val pid: Int,
        /** The model the live CLI reports it is running. Read it; never assume. */
        val model: String
    )

    /**
     * The CLI's own answer to "am I attached, to what conversation, in which
     * tier". This is the load-bearing observation for the whole suite: it comes
     * from the attach server's `hello_ok`, not from anything the test set up.
     */
    fun probe(): Probe {
        val o = get("/tui/probe")
        return Probe(
            sessionId = o.getString("sessionId"),
            cwd = o.getString("cwd"),
            attached = o.getBoolean("attached"),
            permissionMode = o.optString("permissionMode", ""),
            pid = o.getInt("pid"),
            model = o.optString("model", "")
        )
    }

    /** Poll [probe] until `attached == expected`, else fail with the last view. */
    fun awaitAttached(expected: Boolean, timeoutMs: Long): Probe {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Probe? = null
        while (System.currentTimeMillis() < deadline) {
            last = runCatching { probe() }.getOrNull()
            if (last != null && last.attached == expected) return last
            Thread.sleep(400)
        }
        throw AssertionError(
            "CLI attach state never became attached=$expected within ${timeoutMs}ms (last=$last)"
        )
    }

    data class AttachVerdict(val type: String?, val code: String?, val message: String?)

    /**
     * Issue a real second `attach` frame on the live socket. Refused before any
     * WebSocket is opened, so this cannot disturb the attachment under test.
     */
    fun attachAttempt(): AttachVerdict {
        val o = post("/tui/attach-attempt")
        return AttachVerdict(
            type = o.optString("type", null),
            code = o.optString("code", null),
            message = o.optString("message", null)
        )
    }

    // ------------------------------------------------------------------
    // Spawn census
    // ------------------------------------------------------------------

    /**
     * Count of headless CLIs pc-agent has spawned (`--sdk-url` in argv). The
     * only way to distinguish attach from spawn from off the device.
     */
    fun headlessCount(): Int = get("/procs/headless").getInt("count")

    fun headlessArgvs(): List<String> {
        val arr: JSONArray = get("/procs/headless").optJSONArray("argvs") ?: JSONArray()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    data class LiveSession(
        val pid: Int,
        val kind: String,
        val cwd: String,
        val attachSocketPath: String
    )

    /**
     * Poll the session registry for a conversation until it appears.
     *
     * Works for sessions this driver did NOT start -- notably the interactive
     * CLI pc-agent launches when the phone opens a conversation with no TUI
     * already running.
     */
    fun awaitLiveSession(sessionId: String, timeoutMs: Long): LiveSession {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val o = get("/session?sessionId=$sessionId")
            if (o.optBoolean("found")) {
                return LiveSession(
                    pid = o.getInt("pid"),
                    kind = o.optString("kind", ""),
                    cwd = o.optString("cwd", ""),
                    attachSocketPath = o.optString("attachSocketPath", "")
                )
            }
            Thread.sleep(1_000)
        }
        throw AssertionError("No live session $sessionId appeared within ${timeoutMs}ms")
    }
}

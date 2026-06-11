package com.repository.listener.sync

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phone-side sync FSM. Talks to glasses over RFCOMM listener_sync channel and WiFi Direct HTTP.
 * Glasses is the source of truth; phone pulls + issues deletes.
 *
 * Wire up:
 *   - call [onBtConnected] when RFCOMM is up
 *   - route incoming CH_SYNC frames to [onMessage]
 *   - use [send] callback to publish CH_SYNC frames
 *   - call [requestDelete] to remove a file from both sides
 *
 * State machine:
 *   IDLE -> HANDSHAKING -> (match) IDLE
 *                       -> (diff)  FETCHING_MANIFEST -> DIFFING
 *                                    -> (no pulls) IDLE
 *                                    -> (pulls)    OPENING_WIFI -> JOINING -> PULLING -> CLOSING -> IDLE
 *   Any state on BT disconnect -> abort -> IDLE
 *   Session timeout 300s -> abort -> IDLE
 */
class GlassesSyncClient(
    private val context: Context,
    private val send: (msgType: String, sessionId: String, payload: Array<String>) -> Boolean,
) {

    enum class State { IDLE, HANDSHAKING, FETCHING_MANIFEST, DIFFING, OPENING_WIFI, JOINING, PULLING, CLOSING, ERROR }

    interface ProgressListener {
        fun onSyncProgress(state: String, current: Int, total: Int, message: String) {}
        fun onSyncComplete(pulled: Int, deleted: Int) {}
        fun onSyncError(msg: String) {}
        /**
         * Called after each successful file pull with its absolute on-disk
         * path inside the app's private external dir. Files are NOT
         * published to the shared gallery.
         */
        fun onFilePulled(absPath: String) {}
    }

    companion object {
        private const val TAG = "GlassesSyncClient"
        private const val SESSION_TIMEOUT_MS = 300_000L
        private const val JOIN_TIMEOUT_MS = 30_000L
        private const val CLOSING_WATCHDOG_MS = 5_000L
        /** Bumped 64 KB -> 1 MB. The previous 64 KB ceiling made pulls
         *  syscall-bound: at ~1000 read()s/s the wall-clock for a 10 MB
         *  file capped at ~10 s even though WiFi-Direct on this device
         *  hits 30-60 Mbps. 1 MB lets the kernel batch transfers and
         *  the link saturate -- ~2 s for the same 10 MB. */
        private const val PULL_CHUNK = 1024 * 1024

        /** Parallel downloads over a single WiFi-Direct link. Bumped 3 -> 6
         *  to better mask per-file handshake latency on batches. The glasses'
         *  HTTP server pool was also bumped to match. */
        private const val PULL_PARALLELISM = 6

        /** Sockets on a 192.168.43.x WiFi-Direct group have sub-ms RTT. 10 s is plenty
         *  for any real-world stall; 30 s was purely cargo-cult. Cuts worst-case failing-
         *  file cost from 30*3=90 s down to 10*3=30 s. */
        private const val PULL_READ_TIMEOUT_MS = 10_000

        /** After this many permanent failures across sessions, poison a file ID so we stop
         *  retrying it on every HELLO cycle (stale manifest entry whose bytes no longer
         *  exist on glasses, corrupted sha, etc.). */
        private const val PULL_POISON_THRESHOLD = 3
    }

    /**
     * Permanent: server actually answered with something fatal (HTTP 4xx, sha mismatch).
     * Transient: network-level problem (connect reset, timeout). Only transient errors get
     * retried; permanent errors short-circuit out of the retry loop to avoid burning 30 s
     * on a file that will never succeed.
     */
    private class PermanentPullException(msg: String) : Exception(msg)

    /**
     * Per-file failure counter across sync sessions. Keyed by fileId. Incremented each time
     * a permanent pull failure is seen; once above [PULL_POISON_THRESHOLD] the file is skipped
     * during diff so it stops blocking every sync round.
     */
    private val pullFailureCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val poisonedFiles = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    var remoteLog: ((String) -> Unit)? = null
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<ProgressListener>()
    fun addListener(l: ProgressListener) { listeners.add(l) }
    fun removeListener(l: ProgressListener) { listeners.remove(l) }

    private val rootDir: File by lazy {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        File(root, "Repository").apply { mkdirs() }
    }
    private val manifest = LocalManifest(rootDir).also { it.load() }

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "SyncClient-fsm") }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "SyncClient-sched") }
    private val pullPool = Executors.newFixedThreadPool(PULL_PARALLELISM) { r -> Thread(r, "SyncClient-pull") }
    private val pullsInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    private val wifiJoiner = WifiDirectJoiner(context).apply {
        remoteLog = { this@GlassesSyncClient.remoteLog?.invoke(it) }
        onReady = { ip -> post { onWifiJoined(ip) } }
        onFailed = { msg -> post { onWifiFailed(msg) } }
        onClosed = { post { onWifiClosed() } }
    }

    @Volatile private var state = State.IDLE
    private val sessionIdGen = AtomicInteger(1)
    @Volatile private var currentSessionId: String = "0"
    @Volatile private var dirty = false
    @Volatile private var timeoutTask: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var joinTimeoutTask: java.util.concurrent.ScheduledFuture<*>? = null

    private var pullQueue: MutableList<Pair<String, LocalManifest.Entry>> = mutableListOf()
    private var pullTotal = 0
    private var pullDone = 0
    /**
     * Latched true when COMPLETE has been fired to listeners early (in
     * startNextPull's last-pull branch). onWifiClosed checks this to avoid
     * firing a duplicate COMPLETE -- WiFi teardown can take seconds, and
     * the UI has already moved on. Reset on next handshake.
     */
    private var completeAlreadyFired = false
    private var wifiPort: Int = 8849
    @Volatile private var wifiIp: String? = null

    /** Queue of deletes the user requested while we were non-IDLE. */
    private val deferredDeletes = java.util.Collections.synchronizedList(mutableListOf<String>())

    fun state(): State = state
    fun isSyncing(): Boolean = state != State.IDLE && state != State.ERROR
    fun localStateHash(): String = manifest.stateHash()

    fun onBtConnected() {
        post { startHandshake() }
    }

    fun onBtDisconnected() {
        post { abort("BT disconnected") }
    }

    fun onMessage(msgType: String, sessionId: String, payload: List<String>) {
        post {
            // Drop frames from stale sessions. sessionId == "0" means unsolicited
            // (e.g. glasses emitting HELLO on manifest change before we had a session).
            if (sessionId != "0" && sessionId != currentSessionId) {
                remoteLog?.invoke("$TAG: drop $msgType stale session=$sessionId (current=$currentSessionId)")
                return@post
            }
            when (msgType) {
                "HELLO" -> onHelloFromGlasses(sessionId, payload.getOrElse(0) { "" })
                "HELLO_ACK" -> onHelloAck(payload.getOrElse(0) { "" })
                "MANIFEST" -> onManifestReceived(payload.getOrElse(0) { "[]" })
                "WIFI_READY" -> onWifiReady(payload.getOrElse(0) { "{}" })
                "WIFI_ERROR" -> onRemoteWifiError(payload.getOrElse(0) { "unknown" })
                "CLOSE_WIFI" -> onRemoteCloseWifi()
                "DELETED" -> onFileDeletedAck(payload.getOrElse(0) { "" })
                "DELETE_DENIED" -> onDeleteDenied(payload.getOrElse(0) { "" })
                else -> remoteLog?.invoke("$TAG: unhandled msg=$msgType")
            }
        }
    }

    fun requestDelete(fileId: String) {
        post {
            if (state == State.IDLE) {
                sendFrame("DELETE", currentSessionId, arrayOf(fileId))
            } else {
                remoteLog?.invoke("$TAG: queued DELETE($fileId) -- state=$state")
                deferredDeletes.add(fileId)
            }
        }
    }

    /** UI convenience: delete by relPath (e.g. "IMG_20260419_143012.jpg"). */
    fun requestDeleteByRelPath(relPath: String) {
        post {
            val entry = manifest.snapshot().firstOrNull { it.relPath == relPath }
            if (entry == null) {
                remoteLog?.invoke("$TAG: requestDeleteByRelPath($relPath) -- unknown")
                return@post
            }
            if (state == State.IDLE) {
                sendFrame("DELETE", currentSessionId, arrayOf(entry.id))
            } else {
                deferredDeletes.add(entry.id)
            }
        }
    }

    /** Force a fresh handshake. Used by UI "sync now" buttons + boot path. */
    fun forceSync() {
        post { startHandshake() }
    }

    fun shutdown() {
        post {
            cancelTimeout()
            wifiJoiner.close()
            state = State.IDLE
        }
        executor.shutdown()
        pullPool.shutdown()
        scheduler.shutdownNow()
    }

    // ----- FSM internals -----

    private fun startHandshake() {
        if (state != State.IDLE && state != State.ERROR) {
            remoteLog?.invoke("$TAG: startHandshake -- state=$state, setting dirty")
            dirty = true
            return
        }
        currentSessionId = sessionIdGen.incrementAndGet().toString()
        transitionTo(State.HANDSHAKING)
        armTimeout()
        completeAlreadyFired = false   // fresh session, allow exactly one COMPLETE emit
        val hash = manifest.stateHash()
        remoteLog?.invoke("$TAG: HELLO out session=$currentSessionId hash=${hash.take(12)}")
        val sent = sendFrame("HELLO", currentSessionId, arrayOf(hash))
        emitProgress("HANDSHAKING", 0, 0, "handshake")
        // If BT is down, sendFrame returned false. Don't park in HANDSHAKING for 5
        // minutes waiting for a HELLO_ACK that can never arrive -- bail out now.
        // dirty=true so that when BT reconnects (or onHelloFromGlasses fires) we retry.
        if (!sent) {
            remoteLog?.invoke("$TAG: HELLO send failed (BT down) -- aborting")
            dirty = true
            abort("bt link down")
        }
    }

    private fun onHelloFromGlasses(sessionId: String, remoteHash: String) {
        val localHash = manifest.stateHash()
        // Always reply with HELLO_ACK.
        sendFrame("HELLO_ACK", sessionId, arrayOf(localHash))
        if (remoteHash == localHash) {
            remoteLog?.invoke("$TAG: HELLO remote matches local -- in sync")
            // Even on match, a stale non-IDLE state must unwind -- finish cleanly.
            if (state == State.HANDSHAKING) finishSession(pulled = 0, deleted = 0)
            return
        }
        // Mismatch. If we're IDLE/ERROR, just start. If we're mid-handshake, the
        // unsolicited HELLO (sessionId="0") is authoritative -- it typically means
        // BT just reconnected and our previous session's HELLO was lost to the wire.
        // Treat it as a fresh start instead of parking on `dirty=true` for 5 minutes.
        if (state == State.IDLE || state == State.ERROR) {
            startHandshake()
        } else if (sessionId == "0" && (state == State.HANDSHAKING || state == State.CLOSING)) {
            remoteLog?.invoke("$TAG: unsolicited HELLO during $state -- restarting handshake")
            abort("superseded by remote HELLO")
            // abort() already schedules retry if dirty; force one regardless.
            post { if (state == State.IDLE) startHandshake() }
        } else {
            dirty = true
        }
    }

    private fun onHelloAck(remoteHash: String) {
        if (state != State.HANDSHAKING) return
        val localHash = manifest.stateHash()
        if (remoteHash == localHash) {
            remoteLog?.invoke("$TAG: hashes match, no sync needed")
            finishSession(pulled = 0, deleted = 0)
            return
        }
        remoteLog?.invoke("$TAG: hash mismatch local=${localHash.take(10)} remote=${remoteHash.take(10)} -- fetching manifest")
        transitionTo(State.FETCHING_MANIFEST)
        sendFrame("GET_MANIFEST", currentSessionId, emptyArray())
    }

    private fun onManifestReceived(json: String) {
        if (state != State.FETCHING_MANIFEST) return
        transitionTo(State.DIFFING)
        val remote = try { parseManifest(json) } catch (e: Exception) {
            abort("manifest parse failed: ${e.message}"); return
        }
        val local = manifest.snapshot()
        val localById = local.associateBy { it.id }
        val remoteById = remote.associateBy { it.id }

        // pulls: on glasses but not pulled locally, or hash differs. Skip poisoned files
        // (those that have failed permanently 3+ times across sessions) so they don't drag
        // every sync cycle down; user can reset by reinstalling the phone app.
        val toPull = remote.filter {
            val l = localById[it.id]
            val stale = l == null || l.sha256 != it.sha256
            stale && it.id !in poisonedFiles
        }
        val skippedPoison = remote.count { it.id in poisonedFiles && localById[it.id] == null }
        if (skippedPoison > 0) {
            remoteLog?.invoke("$TAG: diff skipped $skippedPoison poisoned file(s)")
        }
        // deletes: locally present but gone on glasses (glasses is source of truth)
        val toDelete = local.filter { it.id !in remoteById }

        for (e in toDelete) {
            val f = File(rootDir, e.relPath)
            try { if (f.exists()) f.delete() } catch (_: Exception) {}
            manifest.remove(e.id)
        }

        if (toPull.isEmpty()) {
            remoteLog?.invoke("$TAG: diff clean after deletes=${toDelete.size}")
            finishSession(pulled = 0, deleted = toDelete.size)
            return
        }

        pullQueue = toPull.map { it.id to it }.toMutableList()
        pullTotal = toPull.size
        pullDone = 0
        remoteLog?.invoke("$TAG: diff pulls=${toPull.size} deletes=${toDelete.size}")
        transitionTo(State.OPENING_WIFI)
        emitProgress("OPENING_WIFI", 0, pullTotal, "requesting wifi")
        sendFrame("OPEN_WIFI", currentSessionId, emptyArray())
    }

    private fun onWifiReady(detailsJson: String) {
        if (state != State.OPENING_WIFI) return
        val details = try {
            val o = JSONObject(detailsJson)
            WifiDirectJoiner.GroupDetails(
                ssid = o.optString("ssid", ""),
                passphrase = o.optString("passphrase", ""),
                ip = o.optString("ip", ""),
                port = o.optInt("port", 8849),
                deviceAddress = o.optString("deviceAddress", null),
            )
        } catch (e: Exception) {
            abort("WIFI_READY parse failed: ${e.message}"); return
        }
        wifiPort = details.port
        wifiIp = if (details.ip.isNotEmpty()) details.ip else null
        transitionTo(State.JOINING)
        emitProgress("JOINING", 0, pullTotal, "wifi join")
        wifiJoiner.join(details)
        // Safety: if nothing happens in JOIN_TIMEOUT_MS, abort. Stored so we can cancel
        // on successful transition to PULLING (prevents stale aborts of later sessions).
        joinTimeoutTask = scheduler.schedule({ post {
            if (state == State.JOINING) abort("wifi join timeout")
        } }, JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun onWifiJoined(ip: String) {
        if (state != State.JOINING) return
        wifiIp = ip
        joinTimeoutTask?.cancel(false)
        joinTimeoutTask = null
        transitionTo(State.PULLING)
        emitProgress("PULLING", 0, pullTotal, "pulling")
        startNextPull()
    }

    private fun onWifiFailed(msg: String) {
        abort("wifi failed: $msg")
    }

    private fun onWifiClosed() {
        if (state == State.CLOSING) {
            cancelTimeout()
            joinTimeoutTask?.cancel(false)
            joinTimeoutTask = null
            transitionTo(State.IDLE)
            // Only emit COMPLETE here as a fallback path -- the happy path
            // already fired it from startNextPull the moment the last pull
            // finished. Re-firing here would either (a) double-toast the user
            // or (b) overwrite a fresh-already state with a stale "Synced N"
            // count from the prior session.
            if (!completeAlreadyFired) {
                for (l in listeners) try { l.onSyncComplete(pullDone, 0) } catch (_: Exception) {}
            }
            completeAlreadyFired = false
            flushDeferredDeletes()
            if (dirty) {
                dirty = false
                startHandshake()
            }
        }
    }

    private fun onRemoteCloseWifi() {
        // Glasses told us it's closing. If we weren't in a WiFi-carrying state, this is an
        // unsolicited stale notification from a prior session -- ignore it, don't regress to CLOSING.
        if (state == State.IDLE || state == State.ERROR || state == State.HANDSHAKING ||
            state == State.FETCHING_MANIFEST || state == State.DIFFING) {
            remoteLog?.invoke("$TAG: CLOSE_WIFI ignored in state=$state (no active join)")
            return
        }
        wifiJoiner.close()
        transitionTo(State.CLOSING)
        // Safety: nothing on the phone is guaranteed to fire onWifiClosed() if we never
        // actually joined a group (which is exactly what happens when glasses fail fast
        // in OPENING_WIFI). Force-exit CLOSING after a short grace window so the FSM
        // doesn't sit until SESSION_TIMEOUT_MS (5 min) and the UI doesn't hang.
        scheduler.schedule({ post {
            if (state == State.CLOSING) {
                remoteLog?.invoke("$TAG: CLOSING watchdog fired -> IDLE")
                onWifiClosed()
            }
        } }, CLOSING_WATCHDOG_MS, TimeUnit.MILLISECONDS)
    }

    private fun onRemoteWifiError(reason: String) {
        // Terminal, diagnosable failure from the glasses (e.g. "wifi_disabled").
        // Abort with the reason so UI can surface it; clear `dirty` so we don't
        // hot-retry -- the condition won't fix itself from our side.
        remoteLog?.invoke("$TAG: WIFI_ERROR reason=$reason (state=$state)")
        dirty = false
        abort("wifi unavailable: $reason")
    }

    private fun onFileDeletedAck(fileId: String) {
        val f = manifest.get(fileId)
        if (f != null) {
            val file = File(rootDir, f.relPath)
            try { if (file.exists()) file.delete() } catch (_: Exception) {}
            manifest.remove(fileId)
            remoteLog?.invoke("$TAG: local copy removed for $fileId")
        }
    }

    private fun onDeleteDenied(fileId: String) {
        // Re-queue for later
        deferredDeletes.add(fileId)
        remoteLog?.invoke("$TAG: DELETE_DENIED $fileId -- requeued")
    }

    private fun startNextPull() {
        // Saturate the parallel pool, then idle-wait for in-flight pulls to finish before
        // transitioning to CLOSING. The last-to-finish worker will re-enter startNextPull()
        // and observe (queue empty AND inFlight == 0) to trigger the close.
        if (pullQueue.isEmpty()) {
            if (pullsInFlight.get() == 0) {
                transitionTo(State.CLOSING)
                emitProgress("CLOSING", pullDone, pullTotal, "done")
                // Fire COMPLETE to UI listeners RIGHT NOW, before the WiFi
                // teardown. All bytes are already on disk; making the user
                // wait for `WifiDirectJoiner.removeGroup` (often several
                // seconds) just to see "Synced N files" was a stale-state
                // bug -- the UI was showing "Pulling..." or "Finishing..."
                // long after the actual transfer was done. The `onSyncComplete`
                // path is idempotent in onWifiClosed (state is checked).
                for (l in listeners) {
                    try { l.onSyncComplete(pullDone, 0) } catch (_: Exception) {}
                }
                completeAlreadyFired = true
                // Tell glasses to tear down its WiFi Direct group + disable the radio
                // right now. Without this, the glasses sit in SERVING for 120s
                // (IDLE_TIMEOUT_MS) before disableWifiRadioIfWeEnabledIt fires, and the
                // user sees the WiFi indicator linger long after sync ended.
                sendFrame("CLOSE_WIFI", currentSessionId, emptyArray())
                wifiJoiner.close()
            }
            return
        }
        // Dispatch as many pulls as the pool can absorb.
        while (pullQueue.isNotEmpty() && pullsInFlight.get() < PULL_PARALLELISM) {
            val (id, entry) = pullQueue.removeAt(0)
            val ip = wifiIp
            if (ip.isNullOrEmpty()) {
                abort("no ip available for pull"); return
            }
            val url = "http://$ip:$wifiPort/file/$id"
            val outFile = File(rootDir, entry.relPath)
            pullsInFlight.incrementAndGet()
            pullPool.execute {
                try { runOnePull(id, entry, url, outFile) } finally {
                    pullsInFlight.decrementAndGet()
                    post { startNextPull() }
                }
            }
        }
    }

    private fun runOnePull(id: String, entry: LocalManifest.Entry, url: String, outFile: File) {
        var lastErr: Exception? = null
        var success = false
        // Up to 3 attempts for transient failures; PermanentPullException breaks out
        // immediately so we don't waste 30 s per try on hopeless cases.
        for (attempt in 1..3) {
            try {
                if (!outFile.parentFile!!.exists()) outFile.parentFile!!.mkdirs()
                downloadTo(url, outFile)
                val sha = LocalManifest.sha256OfFile(outFile)
                if (sha != entry.sha256) {
                    outFile.delete()
                    throw PermanentPullException("sha mismatch on ${entry.relPath}: got $sha exp ${entry.sha256}")
                }
                success = true
                break
            } catch (e: PermanentPullException) {
                lastErr = e
                Log.w(TAG, "pull $id permanent: ${e.message}")
                break
            } catch (e: Exception) {
                lastErr = e
                Log.w(TAG, "pull $id attempt $attempt failed: ${e.message}")
                if (attempt < 3) {
                    try { Thread.sleep(500L * attempt) } catch (_: InterruptedException) {}
                }
            }
        }
        if (!success) {
            // Track permanent failures across sessions. After N, auto-delete the file on
            // the glasses so it stops blocking every sync cycle. DELETE is the right
            // authoritative action: phone is the sync client, glasses is the source of
            // truth, and a file that can never be pulled is as good as gone anyway.
            val n = pullFailureCounts.merge(id, 1) { a, b -> a + b } ?: 1
            if (n >= PULL_POISON_THRESHOLD) {
                poisonedFiles.add(id)
                Log.w(TAG, "pull $id unsyncable after $n attempts -- auto-deleting from glasses")
                remoteLog?.invoke("$TAG: auto-deleting unsyncable file $id (${entry.relPath}) after $n failures")
                // Queues behind deferredDeletes since we're in PULLING; flushes at
                // finishSession -> IDLE, which is the only state that accepts DELETE.
                requestDelete(id)
            }
        } else {
            pullFailureCounts.remove(id)
            poisonedFiles.remove(id)
        }
        handlePullResult(id, entry, outFile, success, lastErr)
    }

    private fun handlePullResult(id: String, entry: LocalManifest.Entry, outFile: File, success: Boolean, lastErr: Exception?) {
        run {
            if (success) {
                post {
                    manifest.upsert(entry)
                    pullDone++
                    emitProgress("PULLING", pullDone, pullTotal, entry.relPath)
                    sendFrame("PULL_DONE", currentSessionId, arrayOf(id))
                    val absPath = outFile.absolutePath
                    for (l in listeners) try { l.onFilePulled(absPath) } catch (_: Exception) {}
                    // Next pull kicked by worker's finally block; nothing to do here.
                }
            } else {
                Log.w(TAG, "pull $id exhausted retries: ${lastErr?.message}")
                post {
                    pullDone++
                    emitProgress("PULLING", pullDone, pullTotal, "${entry.relPath} failed (after 3)")
                }
            }
        }
    }

    private fun downloadTo(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = PULL_READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            val code = conn.responseCode
            // 4xx = server says this file won't ever succeed (404, 410, 403). Bail fast,
            // no retry. 5xx + anything else = transient, let the retry loop handle it.
            if (code in 400..499) throw PermanentPullException("http $code for $url")
            if (code != 200) throw IllegalStateException("http $code for $url")
            FileOutputStream(dest).use { fos ->
                conn.inputStream.use { ins ->
                    val buf = ByteArray(PULL_CHUNK)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                    }
                }
            }
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun finishSession(pulled: Int, deleted: Int) {
        cancelTimeout()
        for (l in listeners) try { l.onSyncComplete(pulled, deleted) } catch (_: Exception) {}
        transitionTo(State.IDLE)
        flushDeferredDeletes()
        if (dirty) {
            dirty = false
            startHandshake()
        }
    }

    private fun flushDeferredDeletes() {
        val snapshot = synchronized(deferredDeletes) {
            val copy = deferredDeletes.toList()
            deferredDeletes.clear()
            copy
        }
        for (id in snapshot) sendFrame("DELETE", currentSessionId, arrayOf(id))
    }

    private fun abort(reason: String) {
        remoteLog?.invoke("$TAG: ABORT -- $reason (state=$state)")
        cancelTimeout()
        joinTimeoutTask?.cancel(false)
        joinTimeoutTask = null
        // Tell glasses to tear down its WiFi P2P group immediately. Without this,
        // dropping our local group via wifiJoiner.close() leaves the glasses radio
        // on for the full 120s idle timeout. Only meaningful in states where the
        // glasses-side group is actually up.
        if (state == State.JOINING || state == State.PULLING || state == State.CLOSING) {
            try { sendFrame("CLOSE_WIFI", currentSessionId, emptyArray()) } catch (_: Exception) {}
        }
        wifiJoiner.close()
        pullQueue.clear()
        pullTotal = 0
        pullDone = 0
        for (l in listeners) try { l.onSyncError(reason) } catch (_: Exception) {}
        transitionTo(State.IDLE)
        // If a HELLO arrived during the aborted session, retry so local manifest converges.
        if (dirty) {
            dirty = false
            // Defer slightly so the ERROR listener fires first and callers can observe.
            scheduler.schedule({ post { startHandshake() } }, 500, TimeUnit.MILLISECONDS)
        }
    }

    private fun armTimeout() {
        cancelTimeout()
        timeoutTask = scheduler.schedule({
            post { if (state != State.IDLE) abort("session timeout") }
        }, SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun cancelTimeout() {
        timeoutTask?.cancel(false)
        timeoutTask = null
    }

    private fun transitionTo(next: State) {
        remoteLog?.invoke("$TAG: state $state -> $next")
        state = next
    }

    private fun sendFrame(msgType: String, sessionId: String, payload: Array<String>): Boolean {
        return try { send(msgType, sessionId, payload) } catch (e: Exception) {
            remoteLog?.invoke("$TAG: send $msgType failed: ${e.message}"); false
        }
    }

    private fun emitProgress(state: String, current: Int, total: Int, message: String) {
        for (l in listeners) try { l.onSyncProgress(state, current, total, message) } catch (_: Exception) {}
    }

    private fun post(block: () -> Unit) {
        executor.execute {
            try { block() } catch (e: Exception) { Log.e(TAG, "fsm error: ${e.message}", e) }
        }
    }

    private fun parseManifest(json: String): List<LocalManifest.Entry> {
        val arr = JSONArray(json)
        val out = ArrayList<LocalManifest.Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(LocalManifest.Entry(
                id = o.getString("id"),
                relPath = o.getString("relPath"),
                sha256 = o.getString("sha256"),
                size = o.getLong("size"),
                mtime = o.getLong("mtime"),
                kind = o.optString("kind", "photo"),
            ))
        }
        return out
    }

}

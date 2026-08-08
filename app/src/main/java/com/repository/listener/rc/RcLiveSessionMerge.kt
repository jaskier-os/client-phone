package com.repository.listener.rc

/**
 * Service-level mirror of one RC session.
 *
 * Lives here rather than nested in ListenerService so the merge below is plain-JVM testable.
 *
 * [discovered] is provenance, and it is the field the whole merge turns on. An entry is
 * `discovered` when the ONLY thing that knows about it is the orchestrator's REST session list;
 * it is not `discovered` once an orchestrator WebSocket event has spoken for it. That distinction
 * decides who is allowed to delete the entry: REST owns removal of what REST created, and may
 * never remove an entry the WebSocket path built, because `onRcSessionEnd` deliberately retains
 * ended sessions as history the list no longer reports.
 */
data class RcDumpEntry(
    val workDir: String,
    val status: String,
    val turning: Boolean,
    val unread: Boolean = false,
    val sessionName: String? = null,
    val discovered: Boolean = false
)

/** One row of `GET /api/v1/remote-sessions`, reduced to the fields the merge actually uses. */
data class RcLiveSession(
    val sessionId: String,
    val workDir: String,
    val alive: Boolean,
    val title: String?
)

/** The merged map plus what moved, so the caller can log and decide whether to push. */
data class RcMergeResult(
    val entries: Map<String, RcDumpEntry>,
    val added: List<String>,
    val removed: List<String>,
    val revived: List<String>
) {
    /**
     * Whether anything at all differs. The caller pushes on this, and the push itself dedups on
     * byte equality -- but a poll that rebuilt the map every cycle would still churn the
     * ConcurrentHashMap for nothing, so the no-change case has to be recognisable here too.
     */
    val changed: Boolean get() = added.isNotEmpty() || removed.isNotEmpty() || revived.isNotEmpty() || edited
    internal var edited: Boolean = false
}

/**
 * Reconciles the service's session mirror against the orchestrator's authoritative live list.
 *
 * The list is the only source that sees a session started from the phone's own RC UI, which talks
 * to the REST API directly and never touches this service. It is also the poorest source: it
 * carries no turning, unread or lastSeq. So it may ADD and it may REVIVE, but for a session the
 * WebSocket path already owns it may not overwrite a single field.
 */
object RcLiveSessionMerge {

    fun merge(existing: Map<String, RcDumpEntry>, listed: List<RcLiveSession>): RcMergeResult {
        val alive = LinkedHashMap<String, RcLiveSession>()
        for (s in listed) {
            if (s.sessionId.isEmpty() || !s.alive) continue
            // Last writer wins on a duplicate id; either way there is exactly one entry.
            alive[s.sessionId] = s
        }

        val out = HashMap<String, RcDumpEntry>(existing)
        val added = ArrayList<String>()
        val removed = ArrayList<String>()
        val revived = ArrayList<String>()
        var edited = false

        for ((id, live) in alive) {
            val prior = existing[id]
            if (prior == null) {
                out[id] = RcDumpEntry(
                    workDir = live.workDir,
                    status = "active",
                    // The list cannot know either. true would paint a spinner or an unread dot on
                    // the glasses that no event would ever arrive to clear.
                    turning = false,
                    unread = false,
                    sessionName = live.title?.trim()?.takeIf { it.isNotEmpty() },
                    discovered = true
                )
                added.add(id)
                continue
            }
            if (prior.discovered) {
                // REST owns this entry outright, so a rename or a moved workDir must propagate.
                val next = prior.copy(
                    workDir = live.workDir,
                    status = "active",
                    sessionName = live.title?.trim()?.takeIf { it.isNotEmpty() } ?: prior.sessionName
                )
                if (next != prior) {
                    out[id] = next
                    // Reported separately, because one list response can both revive and rename:
                    // a revival is the interesting event in logcat, an edit is noise.
                    if (prior.status != "active") revived.add(id)
                    if (next.copy(status = prior.status) != prior) edited = true
                }
                continue
            }
            // WebSocket-owned: status is the ONLY field the list is allowed to touch, and only to
            // revive. A silent WS drop marks a session ended while it is in fact still running.
            if (prior.status != "active") {
                out[id] = prior.copy(status = "active")
                revived.add(id)
            }
        }

        for ((id, prior) in existing) {
            // Absence from the list removes only what the list itself put there.
            if (prior.discovered && !alive.containsKey(id)) {
                out.remove(id)
                removed.add(id)
            }
        }

        return RcMergeResult(out, added, removed, revived).also { it.edited = edited }
    }
}

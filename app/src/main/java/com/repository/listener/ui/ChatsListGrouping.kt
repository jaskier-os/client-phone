package com.repository.listener.ui

/**
 * Derive a displayable folder name from a session's workDir. Returns null
 * if the workDir is missing/blank/the literal string "null" (which Android's
 * JSONObject.optString returns when the JSON value is JSONObject.NULL).
 */
fun folderNameFromWorkDir(workDir: String?): String? {
    if (workDir.isNullOrBlank()) return null
    val trimmed = workDir.trim()
    if (trimmed.equals("null", ignoreCase = true)) return null
    val basename = trimmed.substringAfterLast('/').ifEmpty { trimmed }
    val finalName = basename.substringAfterLast('\\').ifEmpty { basename }
    if (finalName.isBlank()) return null
    if (finalName.equals("null", ignoreCase = true)) return null
    return finalName
}

/**
 * What a chat row's status dot is saying. Kept separate from the colour so the
 * rule can be tested without a screen: the mapping to gbx_* is a one-liner in
 * the adapter, the decision below is the part that has been wrong.
 */
enum class RcDotState {
    /** Session is over. */
    ENDED,

    /** A turn is in flight -- the dot pulses. */
    TURNING,

    /** Finished a turn the user has not opened yet. */
    UNREAD,

    /** Idle, but its CLI is running on the PC. */
    RUNNING,

    /** Finished and already read. */
    IDLE,
}

/**
 * Decide what a session's dot should say.
 *
 * `turning` and `unread` are only ever set by live WebSocket events, so a
 * session the phone has not been watching has both false. Before [isLive] was
 * considered, such a session fell through to [RcDotState.IDLE] and rendered
 * red -- indistinguishable from a stopped one, for a session that was running.
 *
 * @param status the orchestrator-side status ("active" or otherwise)
 * @param turning a turn is currently in flight
 * @param unread a turn finished and the user has not opened the chat
 * @param isLive the session's CLI is alive on the PC right now
 */
fun rcDotState(
    status: String,
    turning: Boolean,
    unread: Boolean,
    isLive: Boolean,
): RcDotState = when {
    status != "active" -> RcDotState.ENDED
    turning -> RcDotState.TURNING
    unread -> RcDotState.UNREAD
    isLive -> RcDotState.RUNNING
    else -> RcDotState.IDLE
}

/**
 * Group active RC sessions by folder (workDir basename) with FolderHeader
 * separators. Sessions within each folder are sorted by startedAt descending.
 * Folders are sorted alphabetically. Pure function, no Android deps.
 */
fun groupActiveByFolder(
    sessions: Collection<ChatListItem.RemoteControlSession>
): List<ChatListItem> {
    val active = sessions.filter { it.status == "active" }
    val grouped = active.groupBy { folderNameFromWorkDir(it.workDir) ?: "Other" }
        .toSortedMap()
    val result = mutableListOf<ChatListItem>()
    for ((folder, folderSessions) in grouped) {
        result.add(ChatListItem.FolderHeader(folder))
        result.addAll(folderSessions.sortedByDescending { it.startedAt })
    }
    return result
}

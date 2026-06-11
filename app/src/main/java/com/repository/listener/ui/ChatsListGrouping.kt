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

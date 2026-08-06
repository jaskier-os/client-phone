package com.repository.listener.rc

/**
 * Decides whether a read acknowledgement may clear a session's unread flag.
 *
 * The glasses acknowledge by sending the highest seq they have rendered. A turn that commits
 * between their render and the arrival of their request produces a HIGHER lastSeq, and clearing on
 * it would silently lose that turn's unread bar. The phone UI renders everything it holds, so it
 * passes [Long.MAX_VALUE] and always clears.
 */
object RcReadPolicy {

    fun shouldClearUnread(lastSeq: Long, seenSeq: Long): Boolean = lastSeq <= seenSeq
}

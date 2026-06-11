package com.repository.listener.network

/**
 * Shared data contract for Copilot chat logs fetched from the orchestrator.
 * Mirrors the orchestrator's persisted NDJSON turn shape and the read-only
 * GET /api/v1/copilot-chats[/:id] responses. Parser-agnostic plain data
 * classes; ChatHistoryClient populates them.
 */

/** One fact-check/coaching card emitted during a turn. */
data class CopilotCard(
    val id: String,
    val kind: String,   // "reply" (a line to say) | "note" (info to know)
    val heard: String,  // trigger phrase (verbatim, may be any language)
    val note: String,   // card text; *single-asterisk* highlight markers retained
    val why: String     // extremely short rationale (a few words); rendered small/dim beneath note, may be blank
)

/** One persisted batch: wearer + interlocutor speech and any cards, timestamped. */
data class CopilotTurn(
    val ts: String,              // ISO-8601
    val wearerText: String,
    val interlocutorText: String,
    val cards: List<CopilotCard>
)

/** Chat-list summary for one Copilot session. */
data class CopilotSummary(
    val id: String,
    val title: String,
    val startedAt: String,       // ISO-8601
    val lastActivityAt: String,  // ISO-8601
    val turnCount: Int
)

/** Full Copilot session log for the detail screen / PDF export. */
data class CopilotChatDetail(
    val id: String,
    val title: String,
    val startedAt: String,
    val turns: List<CopilotTurn>
)

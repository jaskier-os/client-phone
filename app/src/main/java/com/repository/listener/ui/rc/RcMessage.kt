package com.repository.listener.ui.rc

sealed class RcMessage {
    abstract val id: String
    abstract val timestamp: Long

    enum class Role { USER, ASSISTANT, SYSTEM }

    data class TextMessage(
        override val id: String, override val timestamp: Long,
        val role: Role, val text: String, val requestId: String
    ) : RcMessage()

    data class ToolStatus(
        override val id: String, override val timestamp: Long,
        val toolName: String, val status: String,
        val result: String? = null, val toolArgs: String? = null,
        val toolCallId: String? = null,
        val isAgent: Boolean = false, val agentName: String? = null,
        val agentTask: String? = null,
        // Agent live-state and final stats (Tier B: populated on Complete from
        // AgentTool's <usage> block; agentDispatchedAt is stamped on Calling
        // so the UI can tick elapsed time during the call).
        val agentDispatchedAt: Long? = null,
        val agentToolCount: Int? = null,
        val agentTokens: Long? = null,
        val agentElapsedMs: Long? = null
    ) : RcMessage()

    data class PermissionRequest(
        override val id: String, override val timestamp: Long,
        val toolName: String, val toolArgs: String,
        val requestId: String, val description: String,
        var pending: Boolean = true,
        var approved: Boolean = false,
        var result: String? = null
    ) : RcMessage()

    data class PlanUpdate(
        override val id: String, override val timestamp: Long,
        val entering: Boolean, val planContent: String? = null
    ) : RcMessage()

    data class AgentStatus(
        override val id: String, override val timestamp: Long,
        val agentId: String, val name: String,
        val status: String, val depth: Int
    ) : RcMessage()

    data class ThinkingBlock(
        override val id: String, override val timestamp: Long,
        val text: String, var collapsed: Boolean = false
    ) : RcMessage()

    data class UserInputRequest(
        override val id: String, override val timestamp: Long,
        val prompt: String, val requestId: String,
        var answered: Boolean = false
    ) : RcMessage()

    data class SessionEvent(
        override val id: String, override val timestamp: Long,
        val event: String
    ) : RcMessage()

    data class ModeChange(
        override val id: String, override val timestamp: Long,
        val newMode: String
    ) : RcMessage()

    data class ErrorMessage(
        override val id: String, override val timestamp: Long,
        val errorText: String, val source: String = "system"
    ) : RcMessage()
}

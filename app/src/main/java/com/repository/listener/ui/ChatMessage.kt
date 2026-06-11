package com.repository.listener.ui

data class ChatMessage(
    val id: String,
    val role: Role,
    var text: String,
    val requestId: String,
    val timestamp: Long = System.currentTimeMillis(),
    var responseTimeMs: Long? = null,
    var tokenCount: Int? = null,
    var isAnimating: Boolean = false
) {
    enum class Role { USER, ASSISTANT, TOOL, SYSTEM, METADATA }
}

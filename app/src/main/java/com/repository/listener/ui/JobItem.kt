package com.repository.listener.ui

data class JobItem(
    val id: String,
    val name: String,
    val prompt: String,
    val scheduledAt: Long,
    val status: String,
    val result: String?,
    val error: String?,
    val conversationId: String?,
    val createdAt: Long
)

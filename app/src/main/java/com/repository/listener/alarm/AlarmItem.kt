package com.repository.listener.alarm

data class AlarmItem(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val title: String,
    val enabled: Boolean,
    val triggerTimeMillis: Long,
    val createdAt: Long
)

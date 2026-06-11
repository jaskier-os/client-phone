package com.repository.listener.ui

data class TodoItem(
    val id: String,
    val text: String,
    val completed: Boolean,
    val createdAt: Long
)

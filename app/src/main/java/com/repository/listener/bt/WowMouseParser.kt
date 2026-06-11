package com.repository.listener.bt

data class MouseEvent(
    val dx: Int,
    val dy: Int,
    val buttons: Int,
    val scrollDelta: Int,
    val timestamp: Long
)

class WowMouseParser {
    fun parse(data: ByteArray): MouseEvent? = null // stub until protocol discovered
}

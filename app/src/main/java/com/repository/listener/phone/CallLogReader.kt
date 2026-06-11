package com.repository.listener.phone

import android.content.Context
import android.provider.CallLog

data class CallEntry(
    val number: String,
    val name: String?,
    val type: Int,
    val duration: Long,
    val date: Long
)

data class PhoneContact(
    val number: String,
    val name: String?,
    val calls: List<CallEntry>,
    val lastCallDate: Long,
    val totalCalls: Int,
    val linkedPersonId: String? = null
)

object CallLogReader {
    fun readCallLog(context: Context): List<PhoneContact> {
        val entries = mutableListOf<CallEntry>()
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DURATION,
                CallLog.Calls.DATE
            ),
            null, null,
            "${CallLog.Calls.DATE} DESC"
        )
        cursor?.use {
            while (it.moveToNext()) {
                entries.add(CallEntry(
                    number = it.getString(0) ?: "",
                    name = it.getString(1),
                    type = it.getInt(2),
                    duration = it.getLong(3),
                    date = it.getLong(4)
                ))
            }
        }
        return entries.groupBy { it.number }.map { (number, calls) ->
            PhoneContact(
                number = number,
                name = calls.firstOrNull { it.name != null }?.name,
                calls = calls.sortedByDescending { it.date },
                lastCallDate = calls.maxOf { it.date },
                totalCalls = calls.size
            )
        }.sortedByDescending { it.lastCallDate }
    }
}

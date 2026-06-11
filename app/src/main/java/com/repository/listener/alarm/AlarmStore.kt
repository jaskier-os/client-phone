package com.repository.listener.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.repository.listener.MainActivity
import com.repository.listener.util.LogCollector
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

object AlarmStore {

    private const val TAG = "AlarmStore"
    private const val PREFS_NAME = "alarm_store"
    private const val KEY_ALARMS = "alarms"
    private const val KEY_NEXT_ID = "next_id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(context: Context): List<AlarmItem> {
        val json = prefs(context).getString(KEY_ALARMS, "[]") ?: "[]"
        val arr = JSONArray(json)
        val list = mutableListOf<AlarmItem>()
        for (i in 0 until arr.length()) {
            list.add(fromJson(arr.getJSONObject(i)))
        }
        return list.sortedWith(compareBy({ it.hour }, { it.minute }))
    }

    fun save(context: Context, alarm: AlarmItem): AlarmItem {
        val alarms = getAll(context).toMutableList()
        val saved: AlarmItem
        val existing = alarms.indexOfFirst { it.id == alarm.id }
        if (existing >= 0 && alarm.id > 0) {
            saved = alarm
            alarms[existing] = saved
        } else {
            val newId = nextId(context)
            saved = alarm.copy(id = newId)
            alarms.add(saved)
        }
        persist(context, alarms)
        if (saved.enabled) {
            scheduleAlarm(context, saved)
        } else {
            cancelAlarm(context, saved.id)
        }
        return saved
    }

    fun delete(context: Context, alarmId: Int) {
        val alarms = getAll(context).toMutableList()
        alarms.removeAll { it.id == alarmId }
        persist(context, alarms)
        cancelAlarm(context, alarmId)
        LogCollector.i(TAG, "Alarm deleted: id=$alarmId")
    }

    fun findByTime(context: Context, hour: Int, minute: Int): AlarmItem? {
        return getAll(context).find { it.hour == hour && it.minute == minute }
    }

    fun findByTitle(context: Context, title: String): AlarmItem? {
        return getAll(context).find { it.title.equals(title, ignoreCase = true) }
    }

    fun scheduleAlarm(context: Context, alarm: AlarmItem) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = alarmPendingIntent(context, alarm.id)
        val showIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val info = AlarmManager.AlarmClockInfo(alarm.triggerTimeMillis, showIntent)
        am.setAlarmClock(info, pendingIntent)
        LogCollector.i(TAG, "Alarm scheduled: id=${alarm.id} at ${String.format("%02d:%02d", alarm.hour, alarm.minute)} trigger=${alarm.triggerTimeMillis}")
    }

    fun cancelAlarm(context: Context, alarmId: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmPendingIntent(context, alarmId))
        LogCollector.i(TAG, "Alarm cancelled: id=$alarmId")
    }

    fun rescheduleAll(context: Context) {
        val alarms = getAll(context)
        var count = 0
        val updated = alarms.map { a ->
            if (a.enabled) {
                val trigger = computeNextTrigger(a.hour, a.minute)
                val u = a.copy(triggerTimeMillis = trigger)
                scheduleAlarm(context, u)
                count++
                u
            } else a
        }
        if (count > 0) {
            persist(context, updated)
            LogCollector.i(TAG, "Rescheduled $count alarms after boot")
        }
    }

    fun computeNextTrigger(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun nextId(context: Context): Int {
        val p = prefs(context)
        val id = p.getInt(KEY_NEXT_ID, 1)
        p.edit().putInt(KEY_NEXT_ID, id + 1).apply()
        return id
    }

    private fun persist(context: Context, alarms: List<AlarmItem>) {
        val arr = JSONArray()
        for (a in alarms) arr.put(toJson(a))
        prefs(context).edit().putString(KEY_ALARMS, arr.toString()).apply()
    }

    private fun alarmPendingIntent(context: Context, alarmId: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            putExtra("alarm_id", alarmId)
        }
        return PendingIntent.getBroadcast(
            context, alarmId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun toJson(a: AlarmItem): JSONObject = JSONObject().apply {
        put("id", a.id)
        put("hour", a.hour)
        put("minute", a.minute)
        put("title", a.title)
        put("enabled", a.enabled)
        put("triggerTimeMillis", a.triggerTimeMillis)
        put("createdAt", a.createdAt)
    }

    private fun fromJson(j: JSONObject): AlarmItem = AlarmItem(
        id = j.optInt("id"),
        hour = j.optInt("hour"),
        minute = j.optInt("minute"),
        title = j.optString("title", ""),
        enabled = j.optBoolean("enabled", true),
        triggerTimeMillis = j.optLong("triggerTimeMillis"),
        createdAt = j.optLong("createdAt")
    )
}

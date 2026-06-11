package com.repository.listener.config

import android.content.Context

/**
 * Persists per-RC-session metadata that needs to survive Activity teardown
 * (e.g. contextPct chip value) so re-entering the same session reseeds the
 * toolbar from the last known value before any orchestrator transcript /
 * live broadcast arrives.
 */
object RcSessionMetaStore {
    private const val PREFS_NAME = "rc_session_meta"
    private const val KEY_PCT_PREFIX = "ctxPct_"
    private const val KEY_COST_PREFIX = "costUsd_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getContextPct(context: Context, sessionId: String): Int? {
        val v = prefs(context).getInt(KEY_PCT_PREFIX + sessionId, -1)
        return if (v < 0) null else v
    }

    fun setContextPct(context: Context, sessionId: String, pct: Int) {
        prefs(context).edit().putInt(KEY_PCT_PREFIX + sessionId, pct).apply()
    }

    fun getCostUsd(context: Context, sessionId: String): Double {
        val bits = prefs(context).getLong(KEY_COST_PREFIX + sessionId, java.lang.Double.doubleToRawLongBits(0.0))
        return java.lang.Double.longBitsToDouble(bits)
    }

    fun setCostUsd(context: Context, sessionId: String, cost: Double) {
        prefs(context).edit()
            .putLong(KEY_COST_PREFIX + sessionId, java.lang.Double.doubleToRawLongBits(cost))
            .apply()
    }

    fun clear(context: Context, sessionId: String) {
        prefs(context).edit()
            .remove(KEY_PCT_PREFIX + sessionId)
            .remove(KEY_COST_PREFIX + sessionId)
            .apply()
    }
}

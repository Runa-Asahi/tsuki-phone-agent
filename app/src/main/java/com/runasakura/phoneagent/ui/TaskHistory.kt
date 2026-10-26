package com.runasakura.phoneagent.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persisted task history (newest first, max 50). Contains goals/summaries only — never keys. */
object TaskHistory {
    data class Entry(val goal: String, val ok: Boolean, val summary: String, val time: Long)
    private const val PREF = "task_history"; private const val KEY = "items"; const val MAX = 50

    fun encode(list: List<Entry>): String = JSONArray().apply {
        list.take(MAX).forEach { put(JSONObject().put("g", it.goal).put("ok", it.ok).put("s", it.summary).put("t", it.time)) }
    }.toString()

    fun decode(raw: String?): List<Entry> = try {
        val a = JSONArray(raw ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { Entry(it.optString("g"), it.optBoolean("ok"), it.optString("s"), it.optLong("t")) }
            .filter { it.goal.isNotBlank() }
    } catch (_: Exception) { emptyList() }

    fun prepend(list: List<Entry>, e: Entry): List<Entry> = (listOf(e) + list).take(MAX)

    fun load(ctx: Context) = decode(ctx.getSharedPreferences(PREF, 0).getString(KEY, null))
    fun add(ctx: Context, goal: String, ok: Boolean, summary: String) {
        if (goal.isBlank()) return
        val p = ctx.getSharedPreferences(PREF, 0)
        p.edit().putString(KEY, encode(prepend(decode(p.getString(KEY, null)), Entry(goal, ok, summary.take(200), System.currentTimeMillis())))).apply()
    }
    fun clear(ctx: Context) = ctx.getSharedPreferences(PREF, 0).edit().clear().apply()
}

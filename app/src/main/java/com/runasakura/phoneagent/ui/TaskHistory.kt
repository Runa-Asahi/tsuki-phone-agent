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

    // Memo: the chat re-renders up to ~8×/s while a task runs; re-parse only when the stored string changed.
    @Volatile private var memo: Pair<String?, List<Entry>>? = null
    fun load(ctx: Context): List<Entry> {
        val raw = ctx.getSharedPreferences(PREF, 0).getString(KEY, null)
        memo?.let { if (it.first === raw || it.first == raw) return it.second }
        return decode(raw).also { memo = raw to it }
    }
    /** Main-thread safe: the parsed history if already loaded (app start preloads it), else empty. */
    fun peek(): List<Entry> = memo?.second.orEmpty()
    val loaded get() = memo != null
    /** Loads (prefs file + JSON parse) on the IO dispatcher. */
    suspend fun loadAsync(ctx: Context): List<Entry> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { load(ctx) }
    fun add(ctx: Context, goal: String, ok: Boolean, summary: String) {
        if (goal.isBlank()) return
        val p = ctx.getSharedPreferences(PREF, 0)
        val raw = encode(prepend(decode(p.getString(KEY, null)), Entry(goal, ok, summary.take(200), System.currentTimeMillis())))
        p.edit().putString(KEY, raw).apply(); memo = raw to decode(raw)
    }
    fun clear(ctx: Context) { memo = null to emptyList(); ctx.getSharedPreferences(PREF, 0).edit().clear().apply() }
}

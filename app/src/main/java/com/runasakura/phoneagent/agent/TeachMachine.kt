package com.runasakura.phoneagent.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure state machine for on-the-job teaching (unit-tested). Android glue lives in [TeachSession].
 *
 * Phases: Idle → Active (collecting dictate lines + demo/auto steps) → ReadyToCompress → Idle.
 * Cancel returns to Idle discarding everything. Persist via [encode]/[decode] so process death restores Active.
 */
object TeachMachine {
    enum class Phase { Idle, Active, ReadyToCompress }

    data class Step(
        val n: Int,
        val pkg: String,
        val labels: List<String>,
        val note: String?,
        val source: String,   // a11y | ocr | click | none | dictate
        val at: Long = 0L,
    ) {
        fun line(): String = buildString {
            append("$n. ")
            when (source) {
                "dictate" -> append("口述：").append(note.orEmpty())
                else -> {
                    append("包=$pkg")
                    if (labels.isNotEmpty()) append(" 可见[$source]：" + labels.take(12).joinToString("、") { it.take(14) })
                    if (!note.isNullOrBlank()) append(" 说明：$note")
                }
            }
        }
    }

    data class State(
        val phase: Phase = Phase.Idle,
        val goal: String = "",
        val pkgHint: String? = null,
        val dictations: List<String> = emptyList(),
        val steps: List<Step> = emptyList(),
        val startedAt: Long = 0L,
    ) {
        val active get() = phase == Phase.Active
        val ready get() = phase == Phase.ReadyToCompress
        val count get() = dictations.size + steps.size
        fun empty() = dictations.isEmpty() && steps.isEmpty()
    }

    fun begin(goal: String, pkgHint: String?, now: Long): State? {
        val g = goal.trim(); if (g.length < 2) return null
        return State(Phase.Active, g, pkgHint?.trim()?.ifBlank { null }, emptyList(), emptyList(), now)
    }

    fun appendDictate(s: State, text: String): State {
        if (!s.active) return s
        val t = text.trim(); if (t.length < 1) return s
        return s.copy(dictations = s.dictations + t.take(200))
    }

    fun addStep(s: State, pkg: String, labels: List<String>, note: String?, source: String, now: Long): State {
        if (!s.active) return s
        val p = pkg.trim(); if (p.isEmpty()) return s
        val n = s.steps.size + 1
        val step = Step(n, p, labels.filter { it.isNotBlank() }.distinct().take(16), note?.trim()?.takeIf { it.isNotBlank() }?.take(60), source, now)
        val hint = s.pkgHint ?: p
        return s.copy(steps = s.steps + step, pkgHint = hint)
    }

    /** Debounce helper: same pkg+label within [windowMs] is ignored. Pure. */
    fun shouldRecordClick(lastPkg: String?, lastLabel: String?, lastAt: Long, pkg: String, label: String, now: Long, windowMs: Long = 900L): Boolean {
        if (label.isBlank() && pkg.isBlank()) return false
        if (pkg == lastPkg && label == lastLabel && now - lastAt < windowMs) return false
        return true
    }

    fun finish(s: State): State? {
        if (!s.active) return null
        if (s.empty()) return null   // caller shows "还没有记录"
        return s.copy(phase = Phase.ReadyToCompress)
    }

    fun cancel(s: State): State = State()

    fun clearAfterCompress(s: State): State = State()

    /** Merge dictate + demo into one LLM / offline trace. Pure. */
    fun mergeTrace(s: State): String = buildString {
        if (s.dictations.isNotEmpty()) {
            appendLine("口述：")
            s.dictations.forEachIndexed { i, t -> appendLine("${i + 1}. $t") }
        }
        if (s.steps.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            appendLine("示范轨迹（${s.steps.size} 步）：")
            s.steps.forEach { appendLine(it.line()) }
        }
    }.trim()

    fun dominantPkg(steps: List<Step>): String? =
        if (steps.isEmpty()) null else steps.groupingBy { it.pkg }.eachCount().maxByOrNull { it.value }?.key

    fun encode(s: State): String = JSONObject()
        .put("phase", s.phase.name)
        .put("goal", s.goal)
        .put("pkg", s.pkgHint ?: "")
        .put("startedAt", s.startedAt)
        .put("dict", JSONArray(s.dictations))
        .put("steps", JSONArray().apply {
            s.steps.forEach { st ->
                put(JSONObject().put("n", st.n).put("pkg", st.pkg).put("note", st.note ?: "")
                    .put("src", st.source).put("at", st.at)
                    .put("labels", JSONArray(st.labels)))
            }
        }).toString()

    fun decode(raw: String?): State {
        if (raw.isNullOrBlank()) return State()
        return try {
            val o = JSONObject(raw)
            val phase = runCatching { Phase.valueOf(o.optString("phase", "Idle")) }.getOrDefault(Phase.Idle)
            val dict = o.optJSONArray("dict")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
            val stepsArr = o.optJSONArray("steps") ?: JSONArray()
            val steps = (0 until stepsArr.length()).mapNotNull { i ->
                val x = stepsArr.optJSONObject(i) ?: return@mapNotNull null
                val labels = x.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                Step(x.optInt("n", i + 1), x.optString("pkg"), labels, x.optString("note").ifBlank { null }, x.optString("src", "a11y"), x.optLong("at"))
            }
            State(phase, o.optString("goal"), o.optString("pkg").ifBlank { null }, dict, steps, o.optLong("startedAt"))
        } catch (_: Exception) { State() }
    }

    /** Status line for the coach / TeachActivity. Pure. */
    fun statusLine(s: State): String = when (s.phase) {
        Phase.Idle -> "未开始"
        Phase.Active -> "上岗中 · 口述 ${s.dictations.size} 句 · 示范 ${s.steps.size} 步 — 请说/打字或去目标应用操作后点「记录此步」"
        Phase.ReadyToCompress -> "记录完成，正在整理…"
    }
}

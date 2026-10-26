package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.flow.Screen
import com.runasakura.phoneagent.flow.Text
import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.screen.ScreenItem

/**
 * Structured, LLM-friendly observation: every element with size, 3x3 region and a role guess,
 * so a text-only model can tell a big button from a tiny checkbox label.
 */
object ObsFormat {
    private val rn = listOf("上", "中", "下"); private val cn = listOf("左", "中", "右")

    fun region(x: Int, y: Int, w: Int, h: Int) =
        rn[(y * 3 / maxOf(1, h)).coerceIn(0, 2)] + cn[(x * 3 / maxOf(1, w)).coerceIn(0, 2)]

    /** Role hint: checkboxes never close popups; close-like words usually do. */

    fun line(it: ScreenItem, w: Int, h: Int): String = buildString {
        append('[').append(it.id).append("] ").append(it.label.ifBlank { "(输入框)" })
        append(" (").append(it.cx).append(',').append(it.cy).append(')')
        if (it.bw > 0) append(" ").append(it.bw).append('x').append(it.bh)
        append(' ').append(region(it.cx, it.cy, w, h))
        if (it.edit) append(" edit")
    }

    fun dump(s: Screen, maxChars: Int = 1600): String {
        val sb = StringBuilder()
        for (it in s.items.take(50)) {
            val l = line(it, s.w, s.h)
            if (sb.length + l.length > maxChars) break
            sb.append(l).append('\n')
        }
        return sb.toString().trimEnd().ifBlank { "(空)" }
    }

    /** Is (x,y) on or near any recognised element? */
    fun nearAny(items: List<ScreenItem>, x: Int, y: Int, pad: Int = 120): Boolean = items.any {
        val hw = maxOf(it.bw / 2, 40) + pad; val hh = maxOf(it.bh / 2, 30) + pad
        kotlin.math.abs(it.cx - x) <= hw && kotlin.math.abs(it.cy - y) <= hh
    }
}

/** OCR-noise-robust screen diff: same-position re-reads and timers are NOT changes. */
object SemanticDiff {
    data class Result(val added: List<String>, val removed: List<String>) {
        val meaningful get() = added.isNotEmpty() || removed.isNotEmpty()
    }
    private val TIMER = Regex("^[\\d:：\\s时分秒天小吋リ/.,，()（）\\-A-Za-z]+$")
    private fun volatile(s: String) = s.length <= 1 || (s.any { it.isDigit() } && TIMER.matches(s)) ||
        (s.count { it.isDigit() } * 2 >= s.length)
    private fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        val x = a.toSet(); val y = b.toSet(); if (x.isEmpty() || y.isEmpty()) return false
        return (x intersect y).size.toDouble() / (x union y).size >= 0.5
    }
    private fun samePlace(a: ScreenItem, b: ScreenItem) =
        kotlin.math.abs(a.cx - b.cx) <= 90 && kotlin.math.abs(a.cy - b.cy) <= 50

    fun compare(prev: List<ScreenItem>, cur: List<ScreenItem>): Result {
        val p = prev.filter { !volatile(it.label.trim()) }; val c = cur.filter { !volatile(it.label.trim()) }
        val added = c.filter { a -> p.none { similar(it.label, a.label) || samePlace(it, a) } }
        val removed = p.filter { r -> c.none { similar(it.label, r.label) || samePlace(it, r) } }
        return Result(added.map { it.label.take(10) }, removed.map { it.label.take(10) })
    }
}

/** Remembers actions that produced no meaningful change; the same target is refused next time. */
class NoEffectGuard {
    private val dead = LinkedHashMap<String, String>()
    private fun normLabel(s: String) = Text.norm(s).replace(Regex("[^\\p{IsHan}A-Za-z0-9]"), "")

    fun key(a: AgentAction, label: String?): String? = when (a) {
        is AgentAction.Tap -> label?.let { "t:" + normLabel(it) } ?: a.x?.let { "c:${it / 150},${(a.y ?: 0) / 150}" }
        is AgentAction.Back -> "back"
        else -> null
    }

    fun record(a: AgentAction, label: String?, desc: String) { key(a, label)?.let { dead[it] = desc } }
    fun clear() = dead.clear()

    /** Error text if [a] repeats a known no-op (labels compared fuzzily: OCR rereads differ). */
    fun check(a: AgentAction, label: String?): String? {
        val k = key(a, label) ?: return null
        val hit = dead.entries.firstOrNull { (dk, _) ->
            dk == k || (dk.startsWith("t:") && k.startsWith("t:") && KeyMatchLite.sim(dk.drop(2), k.drop(2)) >= 0.6)
        } ?: return null
        return "「${hit.value}」之前做过且界面没有实质变化，禁止重复；换一个元素/位置/方式"
    }

    val size get() = dead.size
    fun deadLabels(): List<String> = dead.map { it.key }.filter { it.startsWith("t:") }.map { it.drop(2) }.distinct()

    fun summary(): String? = if (dead.isEmpty()) null else "已证实无效（不要再做）：" + dead.values.toList().takeLast(6).joinToString("；")
}

/** Facts learned during one task (from the model's "memory" field), deduplicated, newest kept. */
class TaskMemory(private val max: Int = 8) {
    private val facts = ArrayList<String>()
    fun add(f: String): Boolean {
        val t = f.trim(); if (t.length < 3) return false
        if (facts.any { KeyMatchLite.sim(it, t) >= 0.7 }) return false
        facts += t; while (facts.size > max) facts.removeAt(0); return true
    }
    fun all(): List<String> = facts.toList()
    fun text(): String? = if (facts.isEmpty()) null else facts.joinToString("；")
}

/** Reads the model's own goal_match judgement; only a clear leading "否/no" counts. */
object GoalMatch {
    fun saysNo(v: String?): Boolean { val t = v?.trim()?.lowercase() ?: return false; return t.startsWith("否") || t.startsWith("no") || t.startsWith("不一致") }
}

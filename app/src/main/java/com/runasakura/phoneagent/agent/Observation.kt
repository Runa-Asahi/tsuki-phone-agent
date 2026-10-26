package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.llm.ChatMessage

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

    /**
     * Element list for the model. Compression (no information the model can act on is dropped):
     *  - a label repeated ≥ [GROUP_MIN] times (list rows' 「播放」「关注」…) becomes ONE line listing every id+coordinate;
     *  - when the budget runs out, the tail is summarised with a count instead of silently disappearing.
     */
    fun dump(s: Screen, maxChars: Int = 1600): String {
        val groups = s.items.filter { !it.edit && it.label.isNotBlank() }.groupBy { it.label }.filterValues { it.size >= GROUP_MIN }
        val sb = StringBuilder(); val emitted = HashSet<String>(); var shown = 0
        for (it in s.items.take(MAX_ITEMS)) {
            val g = if (it.edit) null else groups[it.label]
            if (g != null && !emitted.add(it.label)) continue
            val l = if (g != null) "「${it.label}」×${g.size}：" + g.joinToString(" ") { e -> "[${e.id}](${e.cx},${e.cy})" } else line(it, s.w, s.h)
            if (sb.length + l.length > maxChars) break
            sb.append(l).append('\n'); shown += g?.size ?: 1
        }
        val hidden = s.items.size - shown
        if (hidden > 0 && sb.isNotEmpty()) sb.append("…另有 $hidden 个元素未列出（用 scroll_find 找，或 look 看图）")
        return sb.toString().trimEnd().ifBlank { "(空)" }
    }
    const val GROUP_MIN = 3
    const val MAX_ITEMS = 60

    /** Is (x,y) on or near any recognised element? */
    fun nearAny(items: List<ScreenItem>, x: Int, y: Int, pad: Int = 120): Boolean = items.any {
        val hw = maxOf(it.bw / 2, 40) + pad; val hh = maxOf(it.bh / 2, 30) + pad
        kotlin.math.abs(it.cx - x) <= hw && kotlin.math.abs(it.cy - y) <= hh
    }
}

/** OCR-noise-robust screen diff: same-position re-reads and timers are NOT changes. */
object SemanticDiff {
    /**
     * Non-text state changes that OCR/tree text diffs miss, from verified signals only:
     * the IME window (A11y windows) and the input-focused node (findFocus(FOCUS_INPUT)).
     */
    fun stateChange(imeBefore: Int?, imeAfter: Int?, focusBefore: Boolean, focusAfter: Boolean): String? = when {
        imeBefore == null && imeAfter != null -> "键盘已弹出，输入框已获得焦点，可以直接 type"
        imeBefore != null && imeAfter == null -> "键盘已收起"
        !focusBefore && focusAfter -> "输入框已获得焦点（系统确认），可以直接 type"
        focusBefore && !focusAfter -> "输入框失去焦点"
        else -> null
    }

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

    /**
     * [exact] = both reads came from the accessibility tree: text is exact, so there is no OCR jitter to forgive.
     * Fuzzy matching there made a scrolled list (「联系人1…4」→「联系人5…8」, same positions, similar text) look
     * unchanged, so a working swipe was recorded as a no-op and then refused.
     */
    fun compare(prev: List<ScreenItem>, cur: List<ScreenItem>, exact: Boolean = false): Result {
        val p = prev.filter { !volatile(it.label.trim()) }; val c = cur.filter { !volatile(it.label.trim()) }
        if (exact) {
            val pl = p.map { Text.norm(it.label) }.toSet(); val cl = c.map { Text.norm(it.label) }.toSet()
            return Result(c.filter { Text.norm(it.label) !in pl }.map { it.label.take(10) }, p.filter { Text.norm(it.label) !in cl }.map { it.label.take(10) })
        }
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

/**
 * Prompt-cache friendly step transcript, modelled on DeepSeek Harness compaction
 * (packages/compaction/compaction-basic: condense the oldest span into one checkpoint, keep recent turns;
 * compaction-tool-result-pruner: head/tail-trim oversized results; compaction-image-offload: drop old images).
 *
 * Between compactions the request is append-only (request N+1 = request N + answer + new observation), so the
 * provider prefix cache covers everything except the newest screen. When the transcript exceeds [MAX] messages,
 * one compaction starts a new series: older pairs become a structured checkpoint built from the model's own
 * scratchpad fields (state/action/expect/goal_match), and the retained pairs' screens are head/tail-pruned
 * and marked stale so old screens do not distract the model.
 */
class Transcript {
    var checkpoint: String? = null; private set
    private val turns = ArrayList<ChatMessage>()
    var series = 0; private set

    fun size() = turns.size
    fun note(text: String) { turns += ChatMessage("user", text) }

    /** @return the span that was folded into the checkpoint when this append triggered a compaction, else null. */
    fun append(obs: String, answer: String, progress: () -> String = { "" }): List<ChatMessage>? {
        turns += ChatMessage("user", obs); turns += ChatMessage("assistant", answer)
        return if (turns.size > MAX) compact(progress()) else null
    }

    /**
     * Optional model-written checkpoint (setting, default off) replacing the deterministic one. Only valid right
     * after a compaction — the series has just restarted, so no cached prefix is invalidated.
     */
    fun replaceCheckpoint(summary: String) { checkpoint = "<checkpoint>\n${summary.trim()}\n</checkpoint>" }

    fun window(): List<ChatMessage> = listOfNotNull(checkpoint?.let { ChatMessage("user", it) }) + turns

    fun clear() { turns.clear(); checkpoint = null; series++ }

    private fun compact(progress: String): List<ChatMessage> {
        val keepFrom = (turns.size - RETAIN_MSGS).coerceAtLeast(0).let { if (turns[it].role == "assistant") it + 1 else it }
        val old = turns.subList(0, keepFrom).toList()
        val kept = turns.subList(keepFrom, turns.size).map { m ->
            if (m.role == "user") ChatMessage("user", "（较早的屏幕，已过时，仅供参考）\n" + prune(m.content)) else m
        }
        val lines = collapse(old.filter { it.role == "assistant" }.mapNotNull { stepLine(it.content) })
        checkpoint = buildString {
            appendLine("<checkpoint>")
            appendLine("## 已做过的步骤（较早部分已压缩）")
            (checkpoint?.let { prev -> prev.lines().filter { it.startsWith("- ") } } ?: emptyList()).takeLast(12).forEach { appendLine(it) }
            lines.forEach { appendLine("- $it") }
            if (progress.isNotBlank()) appendLine("## 进度\n$progress")
            append("</checkpoint>")
        }
        turns.clear(); turns.addAll(kept); series++
        return old
    }

    companion object {
        const val MAX = 12
        const val RETAIN_MSGS = 4
        const val HEAD = 700
        const val TAIL = 200

        /** Head/tail trim like the harness tool-result pruner. */
        fun prune(text: String): String = if (text.length <= HEAD + TAIL + 20) text else
            text.take(HEAD) + "\n…（中间已省略）…\n" + text.takeLast(TAIL)

        /**
         * One line per earlier step from the model's own JSON scratchpad. node_id is deliberately omitted: e-ids are
         * renumbered every step, so an old "node_id=e3" in a checkpoint would point at a different element now.
         */
        fun stepLine(answer: String): String? = try {
            val o = org.json.JSONObject(answer.substring(answer.indexOf('{'), answer.lastIndexOf('}') + 1))
            val a = o.optJSONObject("action")
            val act = a?.let { x -> x.optString("type") + listOf("text", "x", "y", "url", "action", "name", "key", "app").mapNotNull { k -> x.opt(k)?.toString()?.takeIf { it.isNotBlank() }?.let { "$k=$it" } }.joinToString(",", " ") }
            listOfNotNull(o.optString("subgoal").takeIf { it.isNotBlank() }?.let { "[$it]" },
                o.optString("state").takeIf { it.isNotBlank() }?.let { "在「${it.take(40)}」" }, act?.let { "做了 $it" },
                o.optString("expect").takeIf { it.isNotBlank() }?.let { "预期 ${it.take(30)}" }).joinToString("，").ifBlank { null }
        } catch (_: Exception) { null }

        /** Identical consecutive lines (a repeated attempt) become one line with a count. */
        fun collapse(lines: List<String>): List<String> {
            val out = ArrayList<String>(); var n = 0
            for ((i, l) in lines.withIndex()) {
                n++
                if (i == lines.lastIndex || lines[i + 1] != l) { out += if (n > 1) "$l（重复 $n 次）" else l; n = 0 }
            }
            return out
        }
    }
}

/** Reads the model's own goal_match judgement; only a clear leading "否/no" counts. */
object GoalMatch {
    fun saysYes(v: String?): Boolean { val t = v?.trim()?.lowercase() ?: return false; return t.startsWith("是") || t.startsWith("yes") || t.startsWith("一致") }
    fun saysNo(v: String?): Boolean { val t = v?.trim()?.lowercase() ?: return false; return t.startsWith("否") || t.startsWith("no") || t.startsWith("不一致") }
}

/**
 * Advisory loop nudge after DeepSeek Harness packages/guard/repeat-tool-reminder: counts consecutive
 * identical actions (same type + canonical arguments) and reminds at 3, 5 and 8 repeats. It never blocks;
 * NoEffectGuard remains the hard stop for actions proven useless.
 */
class RepeatReminder(private val thresholds: List<Int> = listOf(3, 5, 8)) {
    private var lastKey: String? = null
    private var count = 0
    fun reset() { lastKey = null; count = 0 }
    /** [k] must be id-free (ActionDesc): e-ids are renumbered every step, so toString() would never repeat. */
    fun observe(k: String): String? {
        count = if (k == lastKey) count + 1 else 1; lastKey = k
        return when {
            count !in thresholds -> null
            count == thresholds.first() -> "提醒：同一动作已连续做了 $count 次。先分析上一次的结果，再决定是否继续。"
            else -> "提醒：动作 ${k.take(120)} 已连续 $count 次。换一种方法、收集更多信息（look/scroll_find），或结束任务（done/fail）。"
        }
    }
}

/**
 * Step-level reaction to a model failure that survived the client's own retries (harness error taxonomy:
 * route on the stable code, never on the message). Transient classes get a fresh observation (the screen
 * may have changed while the network was down) instead of ending the task; context overflow compacts the
 * transcript once; auth/quota/invalid requests stop immediately because repeating cannot help.
 */
object LlmRecovery {
    enum class Action { COMPACT_AND_RETRY, REOBSERVE, STOP }
    const val MAX_STEP_RETRIES = 2
    const val PAUSE_MS = 3000L
    fun decide(r: com.runasakura.phoneagent.llm.LlmResponse, streak: Int): Action {
        if (streak >= MAX_STEP_RETRIES) return Action.STOP
        val local = r.backend.startsWith("mnn") || r.backend.startsWith("local") || r.backend.startsWith("本机")
        return when (com.runasakura.phoneagent.llm.LlmErrors.codeOf(r)) {
            // A stalled on-device model will stall again: re-trying would cost minutes per attempt.
            com.runasakura.phoneagent.llm.LlmErrors.Code.TIMEOUT -> if (local) Action.STOP else Action.REOBSERVE
            com.runasakura.phoneagent.llm.LlmErrors.Code.CONTEXT_WINDOW_EXCEEDED -> Action.COMPACT_AND_RETRY
            in com.runasakura.phoneagent.llm.LlmErrors.RETRYABLE -> Action.REOBSERVE
            else -> Action.STOP
        }
    }
}

/** Loop recovery ladder (generic; no app knowledge): streak 3 → back out once, streak 4 → relaunch the target app. */
object Recovery {
    enum class Step { BACK_OUT, RELAUNCH }
    fun forLoop(streak: Int, targetPkg: String?): Step? = when {
        streak == 3 -> Step.BACK_OUT
        streak == 4 && !targetPkg.isNullOrBlank() -> Step.RELAUNCH
        else -> null
    }
}

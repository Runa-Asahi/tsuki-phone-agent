package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.flow.Text
import com.runasakura.phoneagent.model.AgentAction

/**
 * Shallow / deep hybrid reasoning for stuck tasks.
 *
 * - [Mode.SHALLOW]: the normal fast JSON step (default).
 * - [Mode.DEEP]: forced look + a short diagnosis prompt (why stuck, 3 distinct strategies, pick one not yet tried)
 *   + a hard blacklist of recently failed actions. Same model name; never auto-switches to a bigger model.
 *
 * Pure state machine — unit-tested. The loop feeds observations; [decide] returns the mode and a human reason.
 */
object ThinkMode {
    enum class Mode { SHALLOW, DEEP }

    data class Decision(val mode: Mode, val reason: String?)

    /** Triggers (any one is enough). Tuned so a healthy run stays shallow. */
    const val NO_PROGRESS_STEPS = 3
    const val SAME_ACTION_FAILS = 2
    const val REJECT_STREAK = 2
    const val BUDGET_HALF_NO_GOAL = 0.5
    const val SUBGOAL_OSCILLATION = 4
    const val DEEP_COOLDOWN = 2   // stay deep at least this many steps after entering

    data class State(
        var mode: Mode = Mode.SHALLOW,
        var reason: String? = null,
        var deepLeft: Int = 0,
        var noProgress: Int = 0,
        var sameFail: Int = 0,
        var rejectStreak: Int = 0,
        var lastKey: String? = null,
        var lastSubgoal: String? = null,
        var subgoalFlips: Int = 0,
        var goalSeen: Boolean = false,
        val blacklist: LinkedHashSet<String> = LinkedHashSet(),
    ) {
        fun reset() { mode = Mode.SHALLOW; reason = null; deepLeft = 0; noProgress = 0; sameFail = 0; rejectStreak = 0; lastKey = null; lastSubgoal = null; subgoalFlips = 0; goalSeen = false; blacklist.clear() }
    }

    /** Canonical action key for repeats / blacklist (id-free; coords bucketed to ±40 px). */
    fun actionKey(a: AgentAction, label: String?): String? = when (a) {
        is AgentAction.Tap -> label?.let {
            val n = OcrNormalize.word(it)
            "t:" + Text.norm(n).replace(Regex("[^\\p{IsHan}A-Za-z0-9]"), "")
        }
            ?: a.nodeId?.let { "n:$it" }
            ?: a.x?.let { "c:${it / 40},${(a.y ?: 0) / 40}" }
        is AgentAction.Back -> "back"
        is AgentAction.Key -> "k:${a.name}"
        else -> null
    }

    /**
     * Same target? Labels compare exactly; coordinate keys ("c:bx,by", 40 px buckets) also match the 8 neighbouring
     * buckets, so a retap 20–50 px away from a proven no-op (model jitter) is still caught.
     */
    fun sameKey(a: String, b: String): Boolean {
        if (a == b) return true
        if (!a.startsWith("c:") || !b.startsWith("c:")) return false
        val (ax, ay) = a.drop(2).split(',').map { it.toIntOrNull() ?: return false }
        val (bx, by) = b.drop(2).split(',').map { it.toIntOrNull() ?: return false }
        return kotlin.math.abs(ax - bx) <= 1 && kotlin.math.abs(ay - by) <= 1
    }

    fun banned(key: String?, blacklist: Collection<String>) = key != null && blacklist.any { sameKey(it, key) }

    fun onReject(s: State) { s.rejectStreak++ }
    fun onAccepted(s: State) { s.rejectStreak = 0 }

    fun onSubgoal(s: State, subgoal: String?, goalMatchYes: Boolean) {
        if (goalMatchYes) s.goalSeen = true
        if (subgoal.isNullOrBlank()) return
        if (s.lastSubgoal != null && s.lastSubgoal != subgoal) s.subgoalFlips++
        else if (s.lastSubgoal == null) s.subgoalFlips = 0
        s.lastSubgoal = subgoal
    }

    fun onOutcome(s: State, key: String?, progressed: Boolean) {
        if (progressed) {
            s.noProgress = 0; s.sameFail = 0; s.lastKey = null; s.subgoalFlips = 0
            if (s.mode == Mode.DEEP) { s.deepLeft = 0; s.mode = Mode.SHALLOW; s.reason = null }
            return
        }
        s.noProgress++
        if (key != null) {
            if (key == s.lastKey) s.sameFail++ else { s.sameFail = 1; s.lastKey = key }
            if (s.sameFail >= SAME_ACTION_FAILS) s.blacklist += key
        }
    }

    /** Force deep for one cycle (e.g. taught recipe's next target is not on screen — don't blind-tap). */
    fun forceDeep(s: State, reason: String) {
        s.mode = Mode.DEEP; s.reason = reason; s.deepLeft = maxOf(s.deepLeft, DEEP_COOLDOWN)
    }

    fun decide(s: State, step: Int, budget: Int, enabled: Boolean): Decision {
        if (!enabled) return Decision(Mode.SHALLOW, null)
        if (s.deepLeft > 0) { s.deepLeft--; s.mode = Mode.DEEP; return Decision(Mode.DEEP, s.reason) }
        val why = when {
            s.rejectStreak >= REJECT_STREAK -> "动作连续被拒 ${s.rejectStreak} 次"
            s.sameFail >= SAME_ACTION_FAILS -> "同一动作失败 ${s.sameFail} 次"
            s.noProgress >= NO_PROGRESS_STEPS -> "连续 ${s.noProgress} 步无实质进展"
            s.subgoalFlips >= SUBGOAL_OSCILLATION -> "小目标来回切换 ${s.subgoalFlips} 次"
            budget > 0 && step >= (budget * BUDGET_HALF_NO_GOAL).toInt() && !s.goalSeen && s.noProgress >= 2 ->
                "已用过半步数仍未接近目标"
            else -> null
        }
        return if (why != null) {
            s.mode = Mode.DEEP; s.reason = why; s.deepLeft = DEEP_COOLDOWN - 1
            Decision(Mode.DEEP, why)
        } else {
            if (s.mode == Mode.DEEP) { s.mode = Mode.SHALLOW; s.reason = null }
            Decision(Mode.SHALLOW, null)
        }
    }

    /** Extra user-message block when deep: forces look, bans blacklisted actions, asks for 3 strategies. */
    fun deepBlock(reason: String, blacklist: Collection<String>, recentTried: Collection<String>, canSee: Boolean = true): String = buildString {
        appendLine("⚠ 思考模式=深（触发：$reason）。先诊断再行动，不要重复浅规划的同一打法。")
        appendLine("1. 用 1–2 句说明卡住的原因（界面实际状态 vs 你以为的状态）。")
        appendLine("2. 列出 3 个彼此不同的下一步策略（例如：关弹窗找×/关闭/返回；换入口；look 后按截图坐标；勾选「不再弹出」后再关；滑动查找）。")
        appendLine("3. 选一个还没试过的策略，输出对应 action。")
        if (blacklist.isNotEmpty()) appendLine("禁止再做（已证实无效）：" + blacklist.toList().takeLast(8).joinToString("、"))
        if (recentTried.isNotEmpty()) appendLine("近期已尝试：" + recentTried.toList().takeLast(6).joinToString("、"))
        if (canSee) appendLine("若看不清无文字按钮/关闭钮：本步必须 look（会附图）。粉色/绿色大按钮往往是「领取/前往」主操作，不是关闭；关闭通常是右上角 × /「关闭」/「跳过」。")
        else appendLine("本次无法看截图：不要输出 look。用列表里的〔…〕候选 / 坐标探索 / back / 换入口；粉色/绿色大按钮多半是「领取/前往」，关闭优先 ×/关闭/跳过。")
        append("仍只输出一个 json；模型名不变。")
    }
}

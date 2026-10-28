package com.runasakura.phoneagent.agent

/**
 * Pure step history for the model fallback: remembers the last action and the previous
 * screen's labels, and renders a short screen-diff note so a 1–2B model can tell whether
 * its last action did anything (and stop repeating no-op taps).
 */
class StepHistory(private val maxLabels: Int = 6) {
    private var prev: Set<String>? = null
    private var lastAction: String? = null
    private var sameNoChange = 0
    private val actions = ArrayDeque<String>()

    val stuckCount: Int get() = sameNoChange

    /** Stop threshold: no-change repeats needed before the loop gives up. */
    val stuck: Boolean get() = sameNoChange >= STUCK_LIMIT

    companion object {
        const val STUCK_LIMIT = 4
        private val TIMER = Regex("^[\\d:：\\sA-Za-z时分秒天小吋.,，()（）\\-]+$")
        fun volatile(s: String) = s.any { it.isDigit() } && TIMER.matches(s)
        fun similar(a: String, b: String): Boolean {
            if (a == b) return true
            val x = a.toSet(); val y = b.toSet()
            return (x intersect y).size.toDouble() / (x union y).size >= 0.5
        }
        private val LOADING = Regex("加载中|正在加载|载入中|请稍候|请稍等|正在刷新|刷新中|Loading|loading|努力加载|拼命加载")
        fun isLoading(labels: Collection<String>) = labels.any { LOADING.containsMatchIn(it.trim()) && it.trim().length <= 12 }
    }

    /** Task-progress memory for the prompt: the last few actions in order. */
    fun progress(): String = if (actions.isEmpty()) "" else
        "已执行：" + actions.toList().takeLast(5).mapIndexed { i, a -> "${i + 1}.${a.take(40)}" }.joinToString(" ")

    private val failures = ArrayDeque<String>()
    private var lastFail: String? = null

    /**
     * Same target ≥3 times within the last 6 actions WITHOUT progress → loop. Progress = the action led to a screen
     * state not seen before in this task; paging a list with the same swipe, or a 「下一页」 button, keeps reaching
     * new states and is not a loop, while A→B→A cycles and no-op repeats are.
     */
    val looping: Boolean get() {
        val recent = actions.toList().zip(productive.toList()).takeLast(6)
            .filter { (a, p) -> !p && !a.startsWith("等待") }.map { it.first.substringBefore(" ✗") }
        return recent.groupingBy { it }.eachCount().any { it.value >= 3 }
    }
    private val productive = ArrayDeque<Boolean>()
    private val seenStates = HashSet<Set<String>>()

    /** Number of failed executions in the last 4 actions. */
    val recentFailures: Int get() = actions.toList().takeLast(4).count { it.contains(" ✗") }

    fun lastFailure(): String? = lastFail

    /** Strong hint once a loop is detected: forces a strategy change. */
    fun loopHint(): String? {
        if (!looping) return null
        val t = actions.toList().takeLast(6).map { it.substringBefore(" ✗") }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return "⚠ 循环：「$t」已做了多次但目标没有进展。必须换策略：关闭弹窗(找×/关闭/返回)、点其他入口、用截图/坐标，或滑动查找；不要再做「$t」。"
    }

    fun recordAction(desc: String, ok: Boolean = true) {
        val d = desc.take(80)
        lastAction = d
        lastFail = if (ok) null else d
        actions.addLast(d); productive.addLast(false)
        while (actions.size > 8) { actions.removeFirst(); productive.removeFirst() }
    }

    /** Call once per observation; returns a note for the prompt (null on the first step). */
    var isLoadingNow = false
        private set

    fun observe(labels: List<String>, exact: Boolean = false): String? {
        val cur = labels.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        isLoadingNow = isLoading(cur)
        val p = prev; prev = cur
        val newState = seenStates.add(cur.filterNot { volatile(it) }.toSet())
        val act = lastAction ?: return null
        lastAction = null
        if (p == null) return "上一步：$act"
        // OCR is noisy (same button read slightly differently each frame): compare fuzzily, ignore timers.
        // Tree text is exact: compare exactly (fuzzy matching hid list scrolls).
        val same: (String, String) -> Boolean = if (exact) { a, b -> a == b } else ::similar
        val added = cur.filter { c -> !volatile(c) && p.none { same(it, c) } }.take(maxLabels)
        val removed = p.filter { c -> !volatile(c) && cur.none { same(it, c) } }.take(maxLabels)
        if ((added.isNotEmpty() || removed.isNotEmpty()) && newState && productive.isNotEmpty()) { productive.removeLast(); productive.addLast(true) }
        if (isLoading(cur)) return "上一步：$act → 页面加载中（不计入无效次数）"
        return if (added.isEmpty() && removed.isEmpty()) {
            val n = actions.size
            sameNoChange = if (n >= 2 && actions[n - 1] == actions[n - 2]) sameNoChange + 1 else 1
            "上一步：$act → 界面无变化" + if (sameNoChange >= 2) "（已重复无效，换别的元素/滚动/返回）" else "，不要重复同一动作"
        } else {
            sameNoChange = 0
            buildString {
                append("上一步：").append(act).append(" → 界面变化")
                if (added.isNotEmpty()) append(" 新增:").append(added.joinToString("、") { it.take(10) })
                if (removed.isNotEmpty()) append(" 消失:").append(removed.joinToString("、") { it.take(10) })
            }
        }
    }
}

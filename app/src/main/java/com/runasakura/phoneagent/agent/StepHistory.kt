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
        private val LOADING = Regex("加载中|正在加载|载入中|请稍候|请稍等|正在刷新|刷新中|Loading|loading|努力加载|拼命加载")
        fun isLoading(labels: Collection<String>) = labels.any { LOADING.containsMatchIn(it.trim()) && it.trim().length <= 12 }
    }

    /** Task-progress memory for the prompt: the last few actions in order. */
    fun progress(): String = if (actions.isEmpty()) "" else
        "已执行：" + actions.toList().takeLast(5).mapIndexed { i, a -> "${i + 1}.${a.take(40)}" }.joinToString(" ")

    /** Same action issued ≥3 times in a row (even if the screen changed) → likely a loop. */
    val looping: Boolean get() = actions.size >= 3 && actions.takeLast(3).toSet().size == 1

    fun recordAction(desc: String) {
        val d = desc.take(80)
        lastAction = d
        actions.addLast(d); while (actions.size > 8) actions.removeFirst()
    }

    /** Call once per observation; returns a note for the prompt (null on the first step). */
    var isLoadingNow = false
        private set

    fun observe(labels: List<String>): String? {
        val cur = labels.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        isLoadingNow = isLoading(cur)
        val p = prev; prev = cur
        val act = lastAction ?: return null
        lastAction = null
        if (p == null) return "上一步：$act"
        val added = (cur - p).take(maxLabels)
        val removed = (p - cur).take(maxLabels)
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

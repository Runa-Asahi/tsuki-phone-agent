package com.runasakura.phoneagent.agent

/**
 * Cross-task, per-app learned memory: UI facts (e.g. "今日不再弹出是复选框"), actions that did nothing,
 * and step paths that reached the goal. Stored as plain lines (F:/P:) — no secrets, no screen dumps.
 */
class AppMemory(val facts: List<String>, val paths: List<String>) {
    fun encode(): String = (facts.map { "F:" + it.replace('\n', ' ') } + paths.map { "P:" + it.replace('\n', ' ') }).joinToString("\n")

    /** Text injected into the planner/step prompt; null when nothing learned yet. */
    fun hint(goal: String): String? {
        val p = paths.firstOrNull { it.substringBefore("=>").let { g -> KeyMatchLite.sim(g, goal) >= 0.6 } }
        val parts = listOfNotNull(
            facts.takeIf { it.isNotEmpty() }?.joinToString("；")?.let { "此应用以往经验：$it" },
            p?.let { "以往成功路线（同类目标）：${it.substringAfter("=>")}" })
        return parts.joinToString("\n").ifBlank { null }
    }

    /** A path for a near-identical goal: lets us skip the planner call. */
    fun knownPath(goal: String): String? = paths.firstOrNull { KeyMatchLite.sim(it.substringBefore("=>"), goal) >= 0.8 }?.substringAfter("=>")

    fun merge(newFacts: List<String>, goal: String?, path: String?): AppMemory {
        val f = ArrayList(facts)
        newFacts.map { it.trim() }.filter { it.length >= 3 }.forEach { n -> f.removeAll { KeyMatchLite.sim(it, n) >= 0.7 }; f += n }
        val ps = ArrayList(paths)
        if (goal != null && !path.isNullOrBlank()) { ps.removeAll { KeyMatchLite.sim(it.substringBefore("=>"), goal) >= 0.8 }; ps.add(0, "$goal=>${path.take(300)}") }
        return AppMemory(f.takeLast(MAX_FACTS), ps.take(MAX_PATHS))
    }

    companion object {
        const val MAX_FACTS = 10; const val MAX_PATHS = 4
        fun decode(s: String?): AppMemory {
            val lines = s.orEmpty().lines()
            return AppMemory(lines.filter { it.startsWith("F:") }.map { it.drop(2) }, lines.filter { it.startsWith("P:") }.map { it.drop(2) })
        }
    }
}

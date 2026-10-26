package com.runasakura.phoneagent.agent

/**
 * Cross-task, per-app learned memory (model-written facts and routes), with confidence and expiry so a wrong
 * lesson cannot stick forever:
 *  - every entry carries a confidence (times confirmed) and a last-confirmed timestamp;
 *  - a route that is reused and then fails loses confidence and is dropped at 0;
 *  - entries not confirmed for [TTL_MS] expire;
 *  - unconfirmed (confidence 1) entries are shown to the model as 「未验证」, and only a route confirmed at
 *    least twice may replace the planner call.
 * Storage: plain lines "F2:conf|ts|text" / "P2:conf|ts|goal=>route" (legacy "F:"/"P:" lines still decode).
 */
class AppMemory(val factEntries: List<Entry>, val pathEntries: List<Entry>) {
    data class Entry(val text: String, val conf: Int, val ts: Long)

    val facts: List<String> get() = factEntries.map { it.text }
    val paths: List<String> get() = pathEntries.map { it.text }

    fun encode(): String = (factEntries.map { "F2:${it.conf}|${it.ts}|" + it.text.replace('\n', ' ') } +
        pathEntries.map { "P2:${it.conf}|${it.ts}|" + it.text.replace('\n', ' ') }).joinToString("\n")

    private fun goalOf(p: Entry) = p.text.substringBefore("=>")
    private fun routeOf(p: Entry) = p.text.substringAfter("=>")

    /**
     * Retrieval score: relevance to the goal first (character overlap), then confidence, then recency — so a big
     * memory shows the few entries that matter for THIS goal instead of the globally most-confirmed ones.
     */
    fun score(e: Entry, goal: String, now: Long): Double {
        val rel = KeyMatchLite.sim(e.text, goal)
        val ageDays = if (e.ts == 0L) 30.0 else (now - e.ts) / 86_400_000.0
        return rel * 8 + minOf(e.conf, 4) * 0.25 - ageDays / 30.0
    }

    /** Text injected into planner/step prompts; null when nothing usable. */
    fun hint(goal: String, now: Long = System.currentTimeMillis(), maxFacts: Int = 5): String? {
        val live = expire(now)
        val fs = live.factEntries.sortedByDescending { score(it, goal, now) }.take(maxFacts).map { it.text + if (it.conf < 2) "（未验证）" else "" }
        val p = live.pathEntries.filter { KeyMatchLite.sim(goalOf(it), goal) >= 0.6 }.maxByOrNull { it.conf }
        val parts = listOfNotNull(
            fs.takeIf { it.isNotEmpty() }?.joinToString("；")?.let { "此应用以往经验：$it" },
            p?.let { "以往成功路线（同类目标，${if (it.conf >= 2) "已验证${it.conf}次" else "仅成功过1次，仅供参考"}）：${routeOf(it)}" })
        return parts.joinToString("\n").ifBlank { null }
    }

    /** A route for a near-identical goal confirmed at least twice: lets the loop skip the planner call. */
    fun knownPath(goal: String, now: Long = System.currentTimeMillis()): String? =
        expire(now).pathEntries.filter { it.conf >= 2 && KeyMatchLite.sim(goalOf(it), goal) >= 0.8 }.maxByOrNull { it.conf }?.let { routeOf(it) }

    fun merge(newFacts: List<String>, goal: String?, path: String?, now: Long = System.currentTimeMillis()): AppMemory {
        val f = ArrayList(expire(now).factEntries)
        newFacts.map { it.trim().take(MAX_CHARS) }.filter { it.length >= 3 }.forEach { n ->
            val i = f.indexOfFirst { KeyMatchLite.sim(it.text, n) >= 0.7 }
            val c = f.indexOfFirst { conflicts(it.text, n) }
            when {
                // Contradiction (「X 有效」 vs 「X 没反应」): the newer evidence replaces the old belief, unverified.
                c >= 0 -> f[c] = Entry(n, 1, now)
                i >= 0 -> f[i] = f[i].copy(text = n, conf = f[i].conf + 1, ts = now)
                else -> f += Entry(n, 1, now)
            }
        }
        val ps = ArrayList(expire(now).pathEntries)
        if (goal != null && !path.isNullOrBlank()) {
            val i = ps.indexOfFirst { KeyMatchLite.sim(goalOf(it), goal) >= 0.8 }
            val text = "$goal=>${path.take(300)}"
            if (i >= 0) { val old = ps.removeAt(i); ps.add(0, Entry(text, old.conf + 1, now)) } else ps.add(0, Entry(text, 1, now))
        }
        return AppMemory(f.sortedByDescending { it.conf }.take(MAX_FACTS), ps.take(MAX_PATHS))
    }

    /**
     * A failed task: unverified facts that were shown to the model this time lose their standing (conf 1 → dropped),
     * verified ones lose one point. Facts the model just (re)wrote are merged afterwards and survive.
     */
    fun decayShown(goal: String, now: Long = System.currentTimeMillis(), maxFacts: Int = 5): AppMemory {
        val live = expire(now)
        val shown = live.factEntries.sortedByDescending { score(it, goal, now) }.take(maxFacts).toSet()
        return AppMemory(live.factEntries.mapNotNull { if (it in shown) it.copy(conf = it.conf - 1).takeIf { e -> e.conf > 0 } else it }, live.pathEntries)
    }

    /** A reused route that ended in failure loses confidence (removed at 0). */
    fun penalize(goal: String, now: Long = System.currentTimeMillis()): AppMemory {
        val ps = expire(now).pathEntries.mapNotNull { if (KeyMatchLite.sim(goalOf(it), goal) >= 0.8) it.copy(conf = it.conf - 2).takeIf { e -> e.conf > 0 } else it }
        return AppMemory(expire(now).factEntries, ps)
    }

    private fun expire(now: Long): AppMemory =
        AppMemory(factEntries.filter { it.ts == 0L || now - it.ts < TTL_MS }, pathEntries.filter { it.ts == 0L || now - it.ts < TTL_MS })

    companion object {
        const val MAX_FACTS = 10; const val MAX_PATHS = 4; const val MAX_CHARS = 60
        /** Shared bucket (stored like an app) for cross-app UI strategies. */
        const val GLOBAL = "_patterns"
        private val NEG = Regex("无效|没反应|没用|不要|不能|无法|不会|失败|不行|没有用")

        /** Same subject, opposite polarity: one says it works, the other says it does not. */
        fun conflicts(a: String, b: String): Boolean {
            val na = NEG.containsMatchIn(a); val nb = NEG.containsMatchIn(b)
            if (na == nb) return false
            val sa = NEG.replace(a, ""); val sb = NEG.replace(b, "")
            return KeyMatchLite.sim(sa, sb) >= 0.6
        }
        val EMPTY = AppMemory(emptyList<Entry>(), emptyList<Entry>())
        fun of(facts: List<String>, paths: List<String>) = AppMemory(facts.map { Entry(it, 1, 0L) }, paths.map { Entry(it, 1, 0L) })
        const val TTL_MS = 30L * 24 * 3600 * 1000

        fun decode(s: String?): AppMemory {
            val f = ArrayList<Entry>(); val p = ArrayList<Entry>()
            s.orEmpty().lines().forEach { l ->
                when {
                    l.startsWith("F2:") || l.startsWith("P2:") -> {
                        val parts = l.drop(3).split('|', limit = 3)
                        if (parts.size == 3) {
                            val e = Entry(parts[2], parts[0].toIntOrNull() ?: 1, parts[1].toLongOrNull() ?: 0L)
                            if (l[0] == 'F') f += e else p += e
                        }
                    }
                    l.startsWith("F:") -> f += Entry(l.drop(2), 1, 0L)
                    l.startsWith("P:") -> p += Entry(l.drop(2), 1, 0L)
                }
            }
            return AppMemory(f, p)
        }
    }
}

package com.runasakura.phoneagent.agent

/** Pure helpers for the adaptive step budget and screen-aware repeat checks (unit-tested). */
object StepBudget {
    /** Progressing = among the last 6 screens at least 4 are different, not looping, not stuck. */
    fun progressing(recentSigs: List<Int>, loopStreak: Int, stuck: Boolean): Boolean =
        !stuck && loopStreak == 0 && recentSigs.size >= 6 && recentSigs.toSet().size >= 4

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val inter = a.count { it in b }; val union = a.size + b.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }
}

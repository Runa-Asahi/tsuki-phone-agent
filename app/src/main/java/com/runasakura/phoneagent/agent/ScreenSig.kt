package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.flow.Screen
import com.runasakura.phoneagent.flow.Text

/**
 * Fuzzy screen identity. [stable] = chrome that survives feed refreshes: short labels in the
 * top/bottom bars (titles, tabs, toolbar buttons). Dynamic feeds (抖音/B站) change the middle
 * every time, so when both screens have chrome we compare only that.
 */
data class ScreenSig(val pkg: String, val labels: Set<String>, val stable: Set<String>, val top: Set<String> = emptySet(), val confident: Boolean = true) {
    companion object {
        fun of(s: Screen): ScreenSig {
            val all = s.items.map { it to Text.norm(it.label) }.filter { it.second.isNotEmpty() }
            val stable = all.filter { (it, l) -> l.length <= 8 && (it.cy < s.h * 0.12 || it.cy > s.h * 0.88) }
                .map { it.second }.filterNot { Regex("^\\d{1,2}:\\d{2}$|^\\d+%?$").matches(it) }.toSet()
            val top = all.filter { (it, l) -> l.length <= 12 && it.cy < s.h * 0.12 }.map { it.second }
                .filterNot { Regex("^\\d{1,2}:\\d{2}$|^\\d+%?$").matches(it) }.toSet()
            return ScreenSig(s.pkg, all.map { it.second }.toSet(), stable, top, s.source == "tree")
        }

        private fun jaccard(a: Set<String>, b: Set<String>): Double =
            if (a.isEmpty() && b.isEmpty()) 1.0 else a.intersect(b).size.toDouble() / (a union b).size

        fun similar(a: ScreenSig, b: ScreenSig, min: Double = 0.6): Boolean {
            if (a.pkg != b.pkg) return false
            // Tab pages share the bottom bar, so the title bar must agree too when both have one.
            if (a.top.isNotEmpty() && b.top.isNotEmpty() && jaccard(a.top, b.top) < 0.5) return false
            if (a.stable.size >= 2 && b.stable.size >= 2) return jaccard(a.stable, b.stable) >= min
            return jaccard(a.labels, b.labels) >= min
        }
    }
}

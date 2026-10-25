package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.flow.Screen
import com.runasakura.phoneagent.flow.Text

/** Fuzzy screen identity: same package and ≥60% label overlap (tolerates clocks, badges, OCR jitter). */
data class ScreenSig(val pkg: String, val labels: Set<String>) {
    companion object {
        fun of(s: Screen) = ScreenSig(s.pkg, s.items.map { Text.norm(it.label) }.filter { it.isNotEmpty() }.toSet())
        fun similar(a: ScreenSig, b: ScreenSig, min: Double = 0.6): Boolean {
            if (a.pkg != b.pkg) return false
            if (a.labels.isEmpty() && b.labels.isEmpty()) return true
            val inter = a.labels.intersect(b.labels).size.toDouble()
            return inter / (a.labels union b.labels).size >= min
        }
    }
}

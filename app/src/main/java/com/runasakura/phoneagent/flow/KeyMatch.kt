package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.screen.ScreenItem

/**
 * Shared OCR-tolerant matcher for key UI buttons (WeChat on PPG-AN00 is tree=0 → OCR only).
 * Tiers: exact (after [Text.norm] + edge-junk strip) → short label containing the key → edit distance
 * (0 for 1-char keys, 1 for 2–3 chars, 2 for ≥4). Callers always constrain by screen region and
 * (for position fallbacks) by a verified anchor.
 */
object KeyMatch {
    private val EDGE_JUNK = "1<q|·-_.,:;'\"()[]【】「」●•"

    fun clean(label: String): String {
        val n = Text.norm(label)
        val t = n.trim { it in EDGE_JUNK }
        return if (t.isEmpty()) n else t
    }

    fun maxDist(key: String) = when { key.length <= 1 -> 0; key.length <= 3 -> 1; else -> 2 }

    /** True if [label] is (an OCR misreading of) the button [key]. */
    fun isKey(label: String, key: String, allowContains: Boolean = true): Boolean {
        val n = clean(label); val k = Text.norm(key)
        if (n == k) return true
        if (allowContains && n.length <= k.length + 2 && n.contains(k)) return true
        if (k.length >= 2 && kotlin.math.abs(n.length - k.length) <= 1 && Text.editDistance(n, k) <= maxDist(k)) return true
        return false
    }

    fun isAny(label: String, keys: Collection<String>, allowContains: Boolean = true) = keys.any { isKey(label, it, allowContains) }

    /** Best item for any of [keys] within [region]; exact beats fuzzy. */
    fun find(s: Screen, keys: Collection<String>, allowContains: Boolean = true, region: (ScreenItem) -> Boolean = { true }): ScreenItem? {
        val pool = s.items.filter(region)
        return pool.firstOrNull { p -> keys.any { Text.eq(p.label, it) } }
            ?: pool.firstOrNull { isAny(it.label, keys, allowContains) }
    }

    /** Item on the same row as [anchor] (|dy| ≤ [dy]) whose centre falls in grid column [col] of [cols]. */
    fun inGridCell(s: Screen, anchor: ScreenItem, col: Int, cols: Int = 4, dy: Int = 30): ScreenItem? {
        val cw = s.w / cols
        return s.items.filter { it !== anchor && kotlin.math.abs(it.cy - anchor.cy) <= dy && it.cx in (col * cw) until ((col + 1) * cw) }
            .minByOrNull { kotlin.math.abs(it.cx - (col * cw + cw / 2)) }
    }

    fun cellCenterX(w: Int, col: Int, cols: Int = 4) = w * (2 * col + 1) / (2 * cols)
}

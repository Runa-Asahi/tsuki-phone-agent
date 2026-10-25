package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.screen.ScreenItem

/**
 * Pure helpers for verifying typed text and bubbles (unit-tested).
 * Multi-line messages / emoji: OCR often returns one item per line, so we join labels
 * that sit on the same row as the send button (or in the message band).
 */
object InputVerify {
    /** True if the input row (near [send]) contains [message] after normalization. */
    fun inputContains(s: Screen, message: String, send: ScreenItem): Boolean {
        val m = Text.norm(message)
        if (m.isEmpty()) return false
        val row = (s.h * 0.04).toInt().coerceAtLeast(40)
        val band = s.items.filter {
            it !== send && !Text.eq(it.label, "发送") &&
                kotlin.math.abs(it.cy - send.cy) <= row &&
                (s.imeTop == null || it.cy < s.imeTop)
        }.sortedBy { it.cx }
        if (band.any { Text.norm(it.label).contains(m) }) return true
        // Multi-line: OCR/tree may split "你好\n在吗" into two labels on the same row / stacked
        val joined = Text.norm(band.joinToString("") { it.label })
        if (joined.contains(m)) return true
        // Stacked input (WeChat wraps): join labels within 2*row of send, left of send
        val stack = s.items.filter {
            it !== send && it.cx < send.cx && it.cy in (send.cy - row * 3)..(send.cy + row) &&
                (s.imeTop == null || it.cy < s.imeTop)
        }.sortedWith(compareBy({ it.cy }, { it.cx }))
        return Text.norm(stack.joinToString("") { it.label }).contains(m)
    }

    /**
     * Count message bubbles. Multi-line: count a bubble if the first non-blank line matches
     * and (single-line message OR enough of the remaining lines appear nearby).
     */
    fun countBubbles(s: Screen, message: String): Int {
        val bottom = minOf(s.imeTop ?: s.h, (s.h * 0.93).toInt())
        val top = (s.h * 0.1).toInt()
        val lines = message.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            val m = Text.norm(message)
            if (m.isEmpty()) return 0
            return s.items.count { !it.edit && Text.eq(it.label, message) && it.cy in (top + 1) until bottom }
        }
        val first = Text.norm(lines.first())
        val rest = lines.drop(1).map { Text.norm(it) }
        var count = 0
        val candidates = s.items.filter { !it.edit && Text.norm(it.label) == first && it.cy in (top + 1) until bottom }
        for (c in candidates) {
            if (rest.isEmpty()) { count++; continue }
            val near = s.items.filter {
                !it.edit && it.cy in (c.cy - (s.h * 0.02).toInt())..(c.cy + (s.h * 0.12).toInt()) &&
                    it.cy < bottom
            }
            val joined = Text.norm(near.sortedWith(compareBy({ it.cy }, { it.cx })).joinToString("") { it.label })
            if (rest.all { joined.contains(it) }) count++
        }
        return count
    }

    /** Prefer 联系人/好友 over 群聊 when both have the same exact name (still unique after prefer). */
    fun preferContactSection(hits: List<ScreenItem>, sections: Map<ScreenItem, String?>): List<ScreenItem> {
        if (hits.size <= 1) return hits
        val preferred = setOf("联系人", "好友", "最常使用", "功能", "设备")
        val demoted = setOf("群聊", "聊天记录", "公众号", "服务号", "小程序", "搜一搜")
        val good = hits.filter { sections[it]?.let { sec -> sec in preferred } == true }
        if (good.size == 1) return good
        val notGroup = hits.filter { sections[it]?.let { sec -> sec in demoted } != true }
        return if (notGroup.size == 1) notGroup else hits
    }
}

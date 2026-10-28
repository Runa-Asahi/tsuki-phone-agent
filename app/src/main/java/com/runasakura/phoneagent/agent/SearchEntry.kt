package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.flow.Screen
import com.runasakura.phoneagent.flow.Text
import com.runasakura.phoneagent.screen.ScreenItem

/**
 * Generic top-of-screen search-entry finder. Shopping apps (拼多多/淘宝/抖音) show a
 * hot-query placeholder ("泡面 拍照搜索") rather than the literal word 「搜索」.
 */
object SearchEntry {
    /** IME action key labels: 搜索 (incl. OCR 「搜素」, 「123 搜素」 merges), 前往, Go, Search. */
    fun isImeSubmit(label: String): Boolean {
        val n = com.runasakura.phoneagent.flow.Text.norm(label).lowercase()
        if (n in setOf("搜索", "搜素", "前往", "go", "search", "确定") || n.endsWith("搜索") || n.endsWith("搜素")) return true
        return com.runasakura.phoneagent.flow.KeyMatch.isAny(label, listOf("搜索", "前往"), allowContains = false)
    }

    private val SEARCH_WORDS = listOf("搜索", "搜一搜", "search", "查找", "searchbox", "search_box")
    private val CAMERA_ONLY = listOf("拍照搜索", "扫一扫", "扫码", "相机搜索", "拍照")
    private val NOT_ENTRY = listOf("首页", "消息", "购物车", "我的", "个人中心", "推荐", "发现", "分类")

    fun isSearchWord(label: String): Boolean {
        val n = Text.norm(label).lowercase()
        return SEARCH_WORDS.any { n.contains(Text.norm(it).lowercase()) }
    }

    fun isCameraOnly(label: String): Boolean {
        val n = Text.norm(label)
        if (!CAMERA_ONLY.any { n.contains(it) }) return false
        // "泡面 拍照搜索" is the bar (placeholder + camera hint), not the camera icon alone.
        val rest = CAMERA_ONLY.fold(n) { acc, w -> acc.replace(w, "") }
        return Text.norm(rest).length <= 1
    }

    /**
     * Rank candidates in the top ~12% of the screen. Prefers edit fields, then labels that
     * contain 搜索, then left/center tap targets that look like a placeholder bar
     * (anything except a pure camera icon / tab).
     */
    fun find(s: Screen): ScreenItem? {
        val top = (s.h * 0.12).toInt()
        val band = s.items.filter { it.cy < top && (it.tap || it.edit) && it.label.isNotBlank() }
            .filter { !NOT_ENTRY.any { t -> Text.eq(it.label, t) } }
        if (band.isEmpty()) return null
        band.firstOrNull { it.edit }?.let { return it }
        band.firstOrNull { isSearchWord(it.label) && !isCameraOnly(it.label) }?.let { return it }
        // Shopping-app hot-query bar: left/center, not the camera icon on the right. A placeholder bar is wide;
        // when the width is known, small chips (an ad's 「跳过 3」, a back arrow) are not search entries.
        val bar = band.filter { it.bw == 0 || it.bw >= s.w * 0.35 }
        return bar
            .filter { it.cx < s.w * 0.75 && !isCameraOnly(it.label) }
            .minByOrNull { it.cy * 10_000 + it.cx }
            ?: bar.firstOrNull { !isCameraOnly(it.label) }
    }

    fun dumpTop(s: Screen, n: Int = 20): String =
        s.items.sortedBy { it.cy }.take(n).joinToString(" | ") { "${it.label.take(18)}@${it.cy}${if (it.edit) "/edit" else if (it.tap) "/tap" else ""}" }
}

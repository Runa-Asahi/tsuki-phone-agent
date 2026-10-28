package com.runasakura.phoneagent.flow

/**
 * WeChat/QQ search-page detection that survives OCR merging, e.g. on PPG-AN00 OCR read
 * 「くQ搜索本地或网络结果 | 最近在搜 | … | QwERTYuo P」 (back arrow + magnifier glued to the
 * placeholder, no 「取消」 button in WeChat 8.0.5x).
 */
object SearchPage {
    private val STRONG = listOf("搜索本地", "网络结果", "最近在搜", "搜索历史", "搜索指定内容", "搜一搜")
    private val KEYBOARD_ROW = Regex("^[qwertyuiopasdfghjklzxcvbnm0-9 ]{6,}$", RegexOption.IGNORE_CASE)

    /** Strong evidence only (safe to use for "still on the search page" after tapping a result). */
    fun strong(s: Screen): Boolean {
        val top = s.h * 0.15
        if (s.items.any { it.cy < top && Text.eq(it.label, "取消") }) return true
        return s.items.any { item -> STRONG.any { k -> Text.norm(item.label).contains(k) && (item.cy < top || k == "最近在搜" || k == "搜索历史") } }
    }

    fun isSearchPage(s: Screen): Boolean {
        if (strong(s)) return true
        val top = s.h * 0.15
        if (s.items.any { it.edit && it.cy < top }) return true
        val keyboard = s.imeTop != null || s.items.any { KEYBOARD_ROW.matches(Text.norm(it.label)) && it.cy > s.h * 0.55 }
        return keyboard && s.items.any { it.cy < top && Text.norm(it.label).contains("搜索") }
    }

    /** The search field's tap point: edit node, else the top-band label carrying 搜索/placeholder text. */
    fun field(s: Screen): Pair<Int, Int>? {
        val top = s.h * 0.15
        s.items.firstOrNull { it.edit && it.cy < top }?.let { return it.cx to it.cy }
        s.items.firstOrNull { it.cy < top && (Text.norm(it.label).contains("搜索") || STRONG.any { k -> it.label.contains(k) }) }
            ?.let { return it.cx to it.cy }
        return null
    }
}

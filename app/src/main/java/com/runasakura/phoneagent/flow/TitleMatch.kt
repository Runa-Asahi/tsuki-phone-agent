package com.runasakura.phoneagent.flow

/**
 * Chat-title matching resilient to OCR noise, without allowing shorter-name false positives
 * (「王小」 must not match title 「王小明」) and without treating 「王小明2」 as 「王小明」.
 *
 * Allowed after [Text.norm]:
 * - exact equal
 * - group style: contact + (digits)   e.g. 周末聚餐(8)
 * - trailing punctuation only: · • . …
 * - leading chrome: ‹ < ← 返回
 *
 * Deliberately NOT allowed: trailing digits (could be part of the remark name).
 */
object TitleMatch {
    private val LEAD = Regex("^(?:‹|<|←|返回)+")
    private val GROUP = Regex("^\\((\\d{1,4})\\)$")
    private val TRAIL_JUNK = Regex("^[·.•…]+$")

    fun clean(raw: String): String = Text.norm(LEAD.replace(raw, ""))

    fun matches(titleRaw: String, contactRaw: String): Boolean {
        val title = clean(titleRaw)
        val contact = Text.norm(contactRaw)
        if (contact.isEmpty() || title.isEmpty()) return false
        if (title == contact) return true
        if (title.startsWith(contact)) {
            val rest = title.substring(contact.length)
            if (rest.isEmpty()) return true
            if (GROUP.matches(rest)) return true
            if (TRAIL_JUNK.matches(rest)) return true
        }
        return false
    }

    fun findMatching(titles: List<String>, contact: String): String? =
        titles.firstOrNull { matches(it, contact) }
}

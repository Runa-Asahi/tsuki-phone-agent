package com.runasakura.phoneagent.agent

/** Pure: does a subgoal target look like a web address (by URL syntax, no site list)? → https URL or null. */
object WebAddress {
    private val HOST = Regex("^(https?://)?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}(:\\d{2,5})?(/\\S*)?$", RegexOption.IGNORE_CASE)
    fun normalize(t: String): String? {
        val s = t.trim().trim('「', '」', '"', '\'', '“', '”')
        if (s.length > 200 || s.any { it.isWhitespace() } || !HOST.matches(s)) return null
        return if (s.startsWith("http", true)) s else "https://$s"
    }
}

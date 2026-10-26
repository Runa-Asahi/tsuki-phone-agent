package com.runasakura.phoneagent.agent

/** Pure: does a subgoal target look like a web address (by URL syntax, no site list)? → https URL or null. */
object WebAddress {
    private val HOST = Regex("^(https?://)?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}(:\\d{2,5})?(/\\S*)?$", RegexOption.IGNORE_CASE)
    private val SCHEME = Regex("^https?://", RegexOption.IGNORE_CASE)

    fun normalize(t: String): String? {
        val s = t.trim().trim('「', '」', '"', '\'', '“', '”', '《', '》')
        if (s.length > 200 || s.any { it.isWhitespace() } || !HOST.matches(s)) return null
        return if (SCHEME.containsMatchIn(s)) s else "https://$s"
    }

    /** True when [pkg] handles https VIEW intents (i.e. is a browser) — generic capability check, no app list. */
    fun isBrowser(pm: android.content.pm.PackageManager, pkg: String): Boolean = runCatching {
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"))
            .addCategory(android.content.Intent.CATEGORY_BROWSABLE).setPackage(pkg)
        pm.queryIntentActivities(i, 0).isNotEmpty()
    }.getOrDefault(false)
}

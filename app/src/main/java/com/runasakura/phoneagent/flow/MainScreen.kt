package com.runasakura.phoneagent.flow

/**
 * Root-screen detection for WeChat/QQ that survives OCR quirks seen on PPG-AN00:
 * tab labels merged into one line (「微信通讯录发现我」), unread badges (「微信3」「微信(12)」),
 * confusables, a banner pushing things around.
 */
object MainScreen {
    private val BADGE = Regex("^\\(?\\d{1,3}\\)?\\+?$")

    /** Tabs visible in the bottom 15%: exact, tab+badge, or contained in a merged tab-row label. */
    fun tabsSeen(s: Screen, app: AppProfile): Set<String> {
        val bottom = s.items.filter { it.cy > s.h * 0.85 }.map { Text.nameKey(it.label) }
        val seen = mutableSetOf<String>()
        for (tab in app.mainTabs) {
            val k = Text.nameKey(tab)
            for (b in bottom) {
                val hit = b == k ||
                    (b.startsWith(k) && BADGE.matches(b.removePrefix(k))) ||
                    // merged row: only trust containment when ≥2 tab names share the label
                    (b.length > k.length && b.contains(k) && app.mainTabs.count { o -> o != tab && b.contains(Text.nameKey(o)) } >= 1) ||
                    // OCR misread of a single tab label (「通讯彔」「发視」), badge stripped; 1-char tabs stay exact
                    (k.length >= 2 && KeyMatch.isKey(b.replace(Regex("\\(?\\d{1,3}\\)?\\+?$"), ""), tab, allowContains = false))
                if (hit) { seen += tab; break }
            }
        }
        return seen
    }

    /** Title row 「微信」/「微信(12)」 in the top 12%. */
    fun titleSeen(s: Screen, app: AppProfile): Boolean = s.items.any {
        it.cy < s.h * 0.12 && Text.nameKey(it.label).let { k -> k == Text.nameKey(app.chatTab) ||
            (k.startsWith(Text.nameKey(app.chatTab)) && BADGE.matches(k.removePrefix(Text.nameKey(app.chatTab)))) }
    }

    fun isMain(s: Screen, app: AppProfile): Boolean {
        if (s.pkg != app.pkg) return false
        val tabs = tabsSeen(s, app).size
        return tabs >= app.minTabs || (tabs >= 2 && titleSeen(s, app))
    }

    /** A root (tabbed) screen of the app — pressing back here would exit the app. */
    fun tabBarVisible(s: Screen, app: AppProfile): Boolean = s.pkg == app.pkg && tabsSeen(s, app).size >= 2

    /** Where the chat tab is (label if seen, else bottom-left slot). */
    fun chatTabPoint(s: Screen, app: AppProfile): Pair<Int, Int> {
        val k = Text.nameKey(app.chatTab)
        s.items.firstOrNull { it.cy > s.h * 0.85 && Text.nameKey(it.label).let { b -> b == k || (b.startsWith(k) && BADGE.matches(b.removePrefix(k))) } }
            ?.let { return it.cx to it.cy }
        val y = s.items.filter { it.cy > s.h * 0.85 }.maxByOrNull { it.cy }?.cy ?: (s.h * 0.965).toInt()
        return (s.w * 0.125).toInt() to y
    }
}

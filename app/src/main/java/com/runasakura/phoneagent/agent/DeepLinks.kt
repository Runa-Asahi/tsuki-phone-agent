package com.runasakura.phoneagent.agent

/**
 * Runtime shortcut discovery — nothing per-app is hard-coded: the app's own manifest (exported pages) and intent
 * filters (custom URI schemes/hosts) are read on the device and handed to the model, which decides whether a direct
 * entry fits the goal. Opening goes through the existing, gated actions (open_url / shell am start).
 */
object DeepLinks {
    data class Info(val uris: List<String>, val pages: List<String>)

    private val SKIP = setOf("http", "https", "file", "content", "package", "android-app", "intent", "data")

    /** `dumpsys package` intent-filter blocks → "scheme://host" (or "scheme:") entries, deduplicated. */
    fun parseDumpsys(text: String, max: Int = 12): List<String> {
        val out = LinkedHashSet<String>()
        var schemes = ArrayList<String>(); var hosts = ArrayList<String>()
        fun flush() {
            for (sc in schemes) if (sc.lowercase() !in SKIP) { if (hosts.isEmpty()) out += "$sc://" else hosts.forEach { out += "$sc://$it" } }
            schemes = ArrayList(); hosts = ArrayList()
        }
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            when {
                l.startsWith("Scheme:") -> l.substringAfter('"').substringBefore('"').takeIf { it.isNotBlank() }?.let { schemes += it }
                l.startsWith("Authority:") -> l.substringAfter('"').substringBefore('"').takeIf { it.isNotBlank() && it != "*" }?.let { hosts += it }
                l.startsWith("Action:") || l.isEmpty() || Regex("^[0-9a-f]{6,} ").containsMatchIn(l) -> flush()
            }
        }
        flush()
        return out.take(max)
    }

    /** Readable page names from exported activity classes: "com.x.ui.GameModeSelectActivity" → "GameModeSelect". */
    fun pageNames(classes: List<String>, max: Int = 20): List<Pair<String, String>> =
        classes.map { it to it.substringAfterLast('.').removeSuffix("Activity").removeSuffix("Act") }
            .filter { it.second.length in 3..40 && !it.second.contains('$') && !Regex("(?i)^(main|splash|launcher|proxy|bridge|dummy|transparent|empty)").containsMatchIn(it.second) }
            .distinctBy { it.second }.take(max)

    fun describe(pkg: String, info: Info, shell: Boolean): String {
        val pages = pageNames(info.pages)
        if (info.uris.isEmpty() && pages.isEmpty()) return "「$pkg」没有发现可直达的入口（或读取不到），请在界面里操作。"
        return buildString {
            append("「$pkg」运行时发现的直达入口（由你判断是否合适；不确定就在界面里操作）：")
            if (info.uris.isNotEmpty()) append("深链 ").append(info.uris.joinToString("、")).append("（用 open_url{url,package}）；")
            if (pages.isNotEmpty()) {
                append("页面 ").append(pages.joinToString("、") { it.second })
                append(if (shell) "（用 shell{cmd:\"am start -n $pkg/完整类名\"}；完整类名：" + pages.take(8).joinToString("、") { it.first } + "）" else "（打开页面需 Shizuku）")
            }
        }
    }
}

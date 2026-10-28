package com.runasakura.phoneagent.agent

import org.json.JSONObject

/**
 * Optional per-app UI hints from a DATA file (resources/app_hints.json). They are soft context for the model —
 * "可能已过时，以屏幕为准" — never code branches; apps not in the file work exactly the same way.
 */
object AppHints {
    @Volatile private var cache: Map<String, Pair<String, List<String>>>? = null

    fun load(json: String): Map<String, Pair<String, List<String>>> = try {
        val o = JSONObject(json)
        o.keys().asSequence().filter { !it.startsWith("_") }.associateWith { k ->
            val e = o.getJSONObject(k)
            val arr = e.optJSONArray("hints")
            e.optString("name") to (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) }
        }
    } catch (_: Exception) { emptyMap() }

    private fun all(): Map<String, Pair<String, List<String>>> = cache ?: run {
        val text = try { AppHints::class.java.classLoader?.getResourceAsStream("app_hints.json")?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
        load(text ?: "{}").also { cache = it }
    }

    /** One compact line block for [pkg], or null. */
    fun forPackage(pkg: String, table: Map<String, Pair<String, List<String>>> = all()): String? =
        table[pkg]?.takeIf { it.second.isNotEmpty() }?.let { (name, hints) -> "「$name」参考提示（可能已过时，以屏幕为准）：" + hints.joinToString("；") }
}

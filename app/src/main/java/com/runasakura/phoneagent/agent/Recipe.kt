package com.runasakura.phoneagent.agent

/**
 * A multi-step procedure the user taught (dictate or demonstrate), compressed by the cloud LLM into a reusable
 * recipe. Stored alongside skills in AppMemory (kind 'R'); semantic / slot-safe retrieval picks it up for similar goals.
 *
 * Format (one line): goal=>pre:…|step:…|step:…|tip:…
 * Pure encode/decode — unit-tested.
 */
data class Recipe(
    val goal: String,
    val steps: List<String>,
    val pre: List<String> = emptyList(),
    val tips: List<String> = emptyList(),
    val conf: Int = 1,
    /** Parameter names extracted from the teach session (e.g. 联系人, 关键词); shown in learning UI. */
    val slots: List<String> = emptyList(),
) {
    fun encode(): String = buildString {
        append(goal.replace("=>", "→").replace('|', '/'))
        append("=>")
        val parts = ArrayList<String>()
        pre.forEach { parts += "pre:" + clean(it) }
        steps.forEach { parts += "step:" + clean(it) }
        tips.forEach { parts += "tip:" + clean(it) }
        slots.forEach { parts += "slot:" + clean(it) }
        append(parts.joinToString("|"))
    }

    fun suggestion(done: List<String>, screenLabels: List<String>): String {
        val next = steps.drop(done.count { d -> steps.any { Skill.same(it, d) } }).firstOrNull()
        val tag = if (conf >= 2) "已验证${conf}次" else "用户教授·未验证"
        return buildString {
            append("用户教过的流程（$tag）：")
            if (pre.isNotEmpty()) append("前置「${pre.joinToString("；")}」；")
            append(steps.mapIndexed { i, s -> "${i + 1}.$s" }.joinToString(" → "))
            if (next != null) {
                val t = Regex("「([^」]{1,24})」").find(next)?.groupValues?.get(1)
                append("；下一步建议：$next")
                if (t != null) append(if (screenLabels.any { Similarity.score(it, t) >= 0.8 || it.contains(t) }) "（目标在当前屏幕）" else "（当前屏幕未见「$t」）")
            }
            if (tips.isNotEmpty()) append("；注意：" + tips.joinToString("；"))
            append("（优先按此流程，以当前屏幕为准；某步无效就换法，不死磕）")
        }
    }

    companion object {
        private fun clean(s: String) = s.trim().replace('|', '/').replace("=>", "→").take(60)
        fun decode(text: String): Recipe? {
            val g = text.substringBefore("=>", "").trim(); val body = text.substringAfter("=>", "")
            if (g.isBlank() || body.isBlank()) return null
            val pre = ArrayList<String>(); val steps = ArrayList<String>(); val tips = ArrayList<String>(); val slots = ArrayList<String>()
            for (p in body.split('|')) when {
                p.startsWith("pre:") -> pre += p.removePrefix("pre:").trim()
                p.startsWith("step:") -> steps += p.removePrefix("step:").trim()
                p.startsWith("tip:") -> tips += p.removePrefix("tip:").trim()
                p.startsWith("slot:") -> slots += p.removePrefix("slot:").trim()
                p.isNotBlank() -> steps += p.trim()   // legacy: bare steps
            }
            return if (steps.isEmpty()) null else Recipe(g, steps.filter { it.isNotEmpty() }, pre.filter { it.isNotEmpty() }, tips.filter { it.isNotEmpty() }, slots = slots.filter { it.isNotEmpty() })
        }

        /** Parse the cloud LLM's JSON compression of a teach session. */
        fun parsePlan(content: String): Recipe? = try {
            val o = org.json.JSONObject(content.substring(content.indexOf('{'), content.lastIndexOf('}') + 1))
            fun arr(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.length in 2..60 } }.orEmpty()
            val goal = o.optString("goal").trim().take(60)
            val steps = arr("steps")
            if (goal.length < 2 || steps.isEmpty()) null
            else Recipe(goal, steps.take(12), arr("pre").take(3), arr("tips").take(4), slots = arr("slots").take(4))
        } catch (_: Exception) { null }

        /** Offline / no-cloud fallback: each non-empty content line becomes a step. Pure. */
        fun offlineSteps(trace: String): List<String> = trace.lines().map { it.trim() }
            .filter { it.length in 2..120 }
            .filterNot { it.startsWith("口述：") && it.length <= 4 || it.startsWith("示范轨迹") }
            .map { it.replace(Regex("""^\d+[．.、)]\s*"""), "") }
            .map { it.removePrefix("口述：").trim() }
            .filter { it.isNotBlank() && !it.startsWith("示范") }
            .take(12)

        /**
         * Pure: does the current screen look like the recipe's next step is available?
         * Missing a quoted target that isn't on screen → the loop should go deep instead of blind-tapping.
         */
        fun nextMissing(recipe: Recipe, done: List<String>, screenLabels: List<String>): String? {
            val next = recipe.steps.drop(done.count { d -> recipe.steps.any { Skill.same(it, d) } }).firstOrNull() ?: return null
            val t = Regex("「([^」]{1,24})」").find(next)?.groupValues?.get(1) ?: return null
            val hit = screenLabels.any { Similarity.score(it, t) >= 0.8 || it.contains(t) || t.contains(it) }
            return if (hit) null else t
        }
    }
}

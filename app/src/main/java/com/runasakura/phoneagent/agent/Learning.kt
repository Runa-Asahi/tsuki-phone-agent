package com.runasakura.phoneagent.agent

/**
 * Lexical similarity for memory retrieval: character-bigram Dice (order-aware, CJK-friendly) blended with
 * unigram overlap, after normalising punctuation/quotes and dropping filler words. Cheap and deterministic
 * (no embedding model on the phone); 「打开收藏夹」 ≈ 「看看我的收藏夹」 but ≠ 「打开设置」.
 */
object Similarity {
    private val FILLER = Regex("请|帮我|帮忙|一下|看看|我的|那个|这个|然后|并且|吧|呢|啊|了")
    private val PUNCT = Regex("[\\s\\p{P}\\p{S}「」『』“”‘’]+")

    fun norm(s: String): String = PUNCT.replace(FILLER.replace(s.lowercase(), ""), "")

    private fun bigrams(s: String): Set<String> = if (s.length < 2) setOf(s) else (0 until s.length - 1).map { s.substring(it, it + 2) }.toSet()

    fun score(a: String, b: String): Double {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val ba = bigrams(x); val bb = bigrams(y)
        val dice = 2.0 * (ba intersect bb).size / (ba.size + bb.size)
        val ua = x.toSet(); val ub = y.toSet()
        val uni = (ua intersect ub).size.toDouble() / (ua union ub).size
        val contain = if (x.contains(y) || y.contains(x)) 0.25 else 0.0
        return minOf(1.0, 0.6 * dice + 0.4 * uni + contain)
    }
}

/**
 * A learned procedure (macro): the effective steps of a successful run, e.g. 点击「我的」 → 点击「我的收藏」.
 * It is never executed blindly — each step the model gets the next suggested step and decides itself
 * (verification stays with the model); following/deviating moves the cursor.
 */
data class Skill(val goal: String, val steps: List<String>, val conf: Int = 1) {
    fun encode() = goal.replace("=>", "→") + "=>" + steps.joinToString(" ⟶ ")

    /** Index of the next step after the ones already done (matched in order), or null when finished. */
    fun nextIndex(done: List<String>): Int? {
        var i = 0
        for (d in done) if (i < steps.size && same(steps[i], d)) i++
        return i.takeIf { it < steps.size }
    }

    /** Suggestion line for the step prompt: next step, and whether its target is on the current screen. */
    fun suggestion(done: List<String>, screenLabels: List<String>): String? {
        val i = nextIndex(done) ?: return null
        val step = steps[i]
        val target = TARGET.find(step)?.groupValues?.get(1)
        val onScreen = target != null && screenLabels.any { Similarity.score(it, target) >= 0.8 || it.contains(target) }
        val tag = if (conf >= 2) "已验证${conf}次" else "仅成功过1次"
        return "可复用技能（$tag，第${i + 1}/${steps.size}步）：$step" + when {
            target == null -> ""
            onScreen -> "（目标在当前屏幕上）"
            else -> "（当前屏幕上没看到「$target」，先核对界面，不符就按实际情况操作）"
        } + "；完整步骤：" + steps.joinToString(" → ")
    }

    companion object {
        const val MIN_SIM = 0.62
        private val TARGET = Regex("「([^」]{1,24})」")
        private val NOISE = Regex("^(等待|看图|look|读取剪贴板|查找已安装应用)")

        fun same(a: String, b: String) = Similarity.norm(a) == Similarity.norm(b) || Similarity.score(a, b) >= 0.9

        fun decode(text: String): Skill? {
            val g = text.substringBefore("=>", ""); val st = text.substringAfter("=>", "")
            val steps = st.split(" ⟶ ").map { it.trim() }.filter { it.isNotEmpty() }
            return if (g.isBlank() || steps.isEmpty()) null else Skill(g, steps)
        }

        /** Drop waits/looks/failed steps and immediate repeats; cap length. */
        private val BACK = Regex("^(返回|按返回键|back)$", RegexOption.IGNORE_CASE)
        fun normalize(steps: List<String>): List<String> {
            val out = ArrayList<String>()
            for (s in steps.map { it.trim() }) {
                if (s.isEmpty() || NOISE.containsMatchIn(s) || s.contains("✗")) continue
                if (out.lastOrNull()?.let { same(it, s) } == true) continue
                // "X → 返回" is a detour that was undone: neither step belongs to the route.
                if (BACK.matches(s) && out.isNotEmpty()) { out.removeAt(out.lastIndex); continue }
                out += s.take(40)
            }
            return out.take(12)
        }
    }
}

/** Failure-cause attribution from the loop's own signals — turned into a lesson and shown to the next run. */
object FailureCause {
    enum class Kind(val zh: String) {
        USER_STOP("用户中途停止"), USER_DECLINED("用户拒绝确认"), NEEDS_USER("缺少信息/需人工"), LOOP("原地打转（重复同一操作）"),
        NOT_FOUND("找不到目标元素"), EXEC("动作执行失败"), MODEL("模型输出无效/超时"), MAX_STEPS("步数用完"), BLOCKED("安全限制"), OTHER("其他")
    }

    fun attribute(summary: String, lastError: String?, looping: Boolean, needsUser: Boolean): Kind = when {
        summary.contains("急停") || summary.contains("已取消") -> Kind.USER_STOP
        summary.contains("用户取消确认") -> Kind.USER_DECLINED
        needsUser -> Kind.NEEDS_USER
        summary.contains("敏感应用") || summary.contains("注入") -> Kind.BLOCKED
        looping -> Kind.LOOP
        summary.contains("最大步数") -> Kind.MAX_STEPS
        lastError?.let { it.contains("找不到") || it.contains("不在当前列表") || it.contains("未找到") } == true || summary.contains("找不到") -> Kind.NOT_FOUND
        lastError != null -> Kind.EXEC
        summary.contains("超时") || summary.contains("无法解析") || summary.contains("模型") -> Kind.MODEL
        else -> Kind.OTHER
    }

    /** One short lesson line (stored as an app fact) or null when the cause teaches nothing about the app. */
    fun lesson(kind: Kind, goal: String, lastStep: String?): String? = when (kind) {
        Kind.LOOP -> lastStep?.let { "「${goal.take(12)}」时反复「${it.take(16)}」无效，要换路线" }
        Kind.NOT_FOUND -> lastStep?.let { "「${goal.take(12)}」：${it.take(18)} 找不到，先搜索或换入口" }
        Kind.EXEC -> lastStep?.let { "${it.take(18)} 执行失败过，可换坐标/长按/先聚焦" }
        Kind.MAX_STEPS -> "「${goal.take(14)}」步骤很多，优先找直达入口/搜索"
        else -> null
    }
}

/** Online adaptation: concrete alternatives for an action that just failed (generic, by action kind). */
object Alternatives {
    fun forFailure(a: com.runasakura.phoneagent.model.AgentAction, error: String?): String? = when (a) {
        is com.runasakura.phoneagent.model.AgentAction.Tap -> if (error?.contains("不在当前列表") == true || error?.contains("找不到") == true)
            "可选替代：按最新 e 编号重点 / scroll_find 找目标 / 用搜索入口 / look 后按坐标" else "可选替代：点元素中心坐标 / long_press / 先 back 关闭遮挡再点"
        is com.runasakura.phoneagent.model.AgentAction.Type -> "可选替代：先 tap 输入框获得焦点再 type / clear 后重输 / set_clipboard + paste"
        is com.runasakura.phoneagent.model.AgentAction.Launch -> "可选替代：list_apps 查包名后 launch{package} / home 后在桌面点图标"
        is com.runasakura.phoneagent.model.AgentAction.Key -> "可选替代：点界面上的搜索/发送按钮代替回车"
        is com.runasakura.phoneagent.model.AgentAction.OpenUri, is com.runasakura.phoneagent.model.AgentAction.Intent -> "可选替代：打开应用后在界面里操作"
        is com.runasakura.phoneagent.model.AgentAction.Shell -> "可选替代：用界面操作（quick_settings / 设置页）完成"
        else -> null
    }

    /** "X 不行，Y 可以" — recorded when a different action on the same target works right after a failure. */
    fun recovered(target: String, failed: String, worked: String): String = "「${target.take(12)}」：${failed.take(10)}不行，${worked.take(14)}可以"
}

/** User corrections become lessons: answers to the agent's questions and user stops/declines. */
object Corrections {
    private val QA = Regex("（你问：(.{1,60}?)；我答：(.{1,60}?)）")
    private val LEGACY = Regex("（补充：(.{1,60}?)）")

    fun fromGoal(goal: String): String? =
        QA.find(goal)?.let { "问过「${it.groupValues[1].take(24)}」，用户答「${it.groupValues[2].take(20)}」" }
            ?: LEGACY.find(goal)?.let { "用户补充过：${it.groupValues[1].take(30)}" }

    fun fromOutcome(summary: String, lastStep: String?): String? = when {
        summary.contains("用户取消确认") -> lastStep?.let { "用户拒绝过：${it.take(24)}（再做前先问清楚）" }
        summary.contains("急停") -> lastStep?.let { "用户在「${it.take(20)}」时手动停止" }
        else -> null
    }
}

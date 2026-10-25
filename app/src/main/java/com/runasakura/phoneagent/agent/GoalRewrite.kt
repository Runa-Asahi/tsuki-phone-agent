package com.runasakura.phoneagent.agent

/**
 * Canonicalise common one-shot app phrasings into the deterministic 「打开APP，子目标」 form:
 *  「在淘宝搜机械键盘」「B站上搜索凉宫春日」「打开拼多多搜索X」「用抖音搜一下X」→ 打开APP，搜索X
 *  「打开小红书首页/我的/消息」 stay as-is (handled by Open subgoals).
 * Messaging/call goals are never rewritten.
 */
object GoalRewrite {
    val APPS = listOf("淘宝", "京东", "拼多多", "抖音", "快手", "B站", "b站", "哔哩哔哩", "bilibili", "小红书", "美团",
        "饿了么", "大众点评", "高德地图", "高德", "百度地图", "网易云音乐", "网易云", "QQ音乐", "知乎", "微博", "百度", "闲鱼",
        "支付宝", "得物", "唯品会", "携程", "12306", "爱奇艺", "优酷", "腾讯视频")
    private val APP_RE = APPS.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private val SEARCH = Regex("^(?:请)?(?:帮我)?(?:在|用|去|打开|上)?($APP_RE)(?:App|app|应用)?(?:上|里|里面|中)?(?:并|然后|，|,|\\s)*(?:搜索|搜一下|搜|查找|查一下|找一下)[：:\\s]*(.+)$")

    fun normalize(goal: String): String {
        val g = goal.trim()
        if (g.contains("发消息") || g.contains("通话") || g.contains("电话")) return g
        val m = SEARCH.find(g) ?: return g
        val q = m.groupValues[2].trim().trimEnd('。', '.', '!', '！')
        if (q.isBlank()) return g
        return "打开${m.groupValues[1]}，搜索$q"
    }
}

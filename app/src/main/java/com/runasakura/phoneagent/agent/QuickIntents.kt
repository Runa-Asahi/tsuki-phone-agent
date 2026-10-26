package com.runasakura.phoneagent.agent

/** Small, general goal parsers whose execution is a sub-skill (the verifier/model still own completion). */
object QuickIntents {
    data class Alarm(val hour: Int, val minute: Int, val label: String)
    private val CN = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10)
    private fun num(s: String): Int? { return s.toIntOrNull() ?: when {
        s.isEmpty() -> null
        s == "十" -> 10
        s.startsWith("十") -> 10 + (CN[s[1]] ?: return null)
        s.endsWith("十") -> (CN[s[0]] ?: return null) * 10
        s.length == 3 && s[1] == '十' -> (CN[s[0]] ?: return null) * 10 + (CN[s[2]] ?: return null)
        s.length == 1 -> CN[s[0]]
        else -> null
    } }
    private val ALARM = Regex("(?:定|设|设置|订|建)?(?:一个|个)?(?:明天|今天|明早|今晚)?(早上|上午|中午|下午|晚上|凌晨)?([0-9零一二两三四五六七八九十]{1,3})[点:：时]([0-9零一二两三四五六七八九十]{1,3}分?|半|一刻|三刻)?(?:的)?闹钟")

    fun alarm(goal: String): Alarm? {
        val m = ALARM.find(goal) ?: return null
        var h = num(m.groupValues[2]) ?: return null
        val mm = m.groupValues[3].removeSuffix("分").let { when (it) { "" -> 0; "半" -> 30; "一刻" -> 15; "三刻" -> 45; else -> num(it) ?: return null } }
        when (m.groupValues[1]) { "下午", "晚上" -> if (h < 12) h += 12; "中午" -> if (h < 11) h += 12 }
        if (h !in 0..23 || mm !in 0..59) return null
        return Alarm(h, mm, "Tsuki")
    }

    /** 「打开蓝牙」「关闭WLAN」: a toggle, not just opening the page. Returns (page keyword, on). */
    private val TOGGLE = Regex("^(?:请)?(?:帮我)?(打开|开启|开一下|关闭|关掉|关一下)(蓝牙|wlan|wifi|wi-fi|无线网|位置|定位|gps|移动数据|飞行模式|热点|个人热点|nfc|深色模式)(?:开关)?$", RegexOption.IGNORE_CASE)
    fun toggle(goal: String): Pair<String, Boolean>? {
        val m = TOGGLE.find(goal.trim()) ?: return null
        return m.groupValues[2] to m.groupValues[1].let { it.startsWith("打开") || it.startsWith("开") }
    }

    /** 「看看王泓凯最后一条消息」「王泓凯发了什么」 */
    private val READ = Regex("^(?:请)?(?:帮我)?(?:打开微信)?[，,]?(?:看看|看一下|读一下|查看|看)?(.{1,20}?)(?:的)?(?:最后一条|最新|最近)(?:一条)?(?:消息|聊天|回复)(?:是什么|说了啥|说了什么)?$|^(?:请)?(?:帮我)?(?:看看)?(.{1,20}?)(?:刚才|刚刚)?(?:发了什么|说了什么|回了什么)$")
    fun readLast(goal: String): String? {
        val m = READ.find(goal.trim()) ?: return null
        return (m.groupValues[1].ifBlank { m.groupValues[2] }).trim().removePrefix("微信").removePrefix("给").ifBlank { null }
    }
}

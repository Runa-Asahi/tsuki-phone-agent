package com.runasakura.phoneagent.ui

/**
 * Process-wide, bounded step log for the main screen. Lives outside the Activity so lines
 * that arrive while the user is inside WeChat (Activity paused) are not lost, and capped so
 * the TextView never grows without bound during long runs.
 */
object UiLog {
    enum class Kind { STEP, OK, ERR, WARN, NORMAL }
    const val CAP = 400
    private val lines = ArrayDeque<String>()

    @Synchronized fun add(line: String) {
        lines.addLast(line)
        while (lines.size > CAP) lines.removeFirst()
    }
    @Synchronized fun clear() = lines.clear()
    @Synchronized fun snapshot(): List<String> = lines.toList()

    fun kind(line: String): Kind {
        val l = line.trimStart()
        return when {
            l.startsWith("——") || l.startsWith("启动任务") -> Kind.STEP
            l.contains("✗") || l.contains("⚠") || l.contains("❌") || l.contains("失败") || l.contains("错误") || l.contains("已断开") || l.contains("超时") -> Kind.ERR
            l.contains("✓") || l.contains("✅") || l.startsWith("已完成") || l.startsWith("已发送") || l.contains("全部通过") -> Kind.OK
            l.startsWith("提示") || l.contains("等待") || l.contains("急停") || l.contains("取消") -> Kind.WARN
            else -> Kind.NORMAL
        }
    }
}

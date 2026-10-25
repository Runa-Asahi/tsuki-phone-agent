package com.runasakura.phoneagent.agent

/**
 * Parse WeChat / messaging natural-language goals.
 * Examples:
 *  - 打开微信
 *  - 打开微信给李四风发送你好
 *  - 给李四风发微信说你好
 */
data class MessagingGoal(
    val openOnly: Boolean,
    val contact: String?,
    val message: String?,
    val appHint: String = "微信"
)

object MessagingGoals {
    fun parse(goal: String): MessagingGoal? {
        val g = goal.trim()
        if (g.isEmpty()) return null

        // 打开微信给X发送Y / 打开微信给X发Y
        Regex("^打开微信给(.+?)发送(.+)$").find(g)?.let {
            return MessagingGoal(false, it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        Regex("^打开微信给(.+?)发(.+)$").find(g)?.let {
            return MessagingGoal(false, it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        Regex("^打开微信[，,、\\s]*给(.+?)[发送发](.+)$").find(g)?.let {
            return MessagingGoal(false, it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        // 给X发微信说Y / 给X发送微信Y
        Regex("^给(.+?)发微信说(.+)$").find(g)?.let {
            return MessagingGoal(false, it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        Regex("^给(.+?)发微信[发送发](.+)$").find(g)?.let {
            return MessagingGoal(false, it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        Regex("^用微信给(.+?)发送(.+)$").find(g)?.let {
            return MessagingGoal(false, it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        Regex("^微信给(.+?)发(.+)$").find(g)?.let {
            return MessagingGoal(false, it.groupValues[1].trim(), it.groupValues[2].trim())
        }

        if (g == "打开微信" || g == "打开WeChat" || g.equals("open wechat", true)) {
            return MessagingGoal(true, null, null)
        }
        if (g.startsWith("打开微信") && !g.contains("给") && !g.contains("发")) {
            return MessagingGoal(true, null, null)
        }
        return null
    }
}

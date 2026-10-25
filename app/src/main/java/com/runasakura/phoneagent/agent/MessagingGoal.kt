package com.runasakura.phoneagent.agent

/** Parsed messaging goal. [app] = "微信" | "QQ" | null (unknown → WeChat default). */
data class MessagingGoal(
    val openOnly: Boolean,
    val contact: String?,
    val message: String?,
    val app: String = "微信"
) {
    val packageName: String get() = if (app.equals("QQ", true)) MessagingGoals.QQ else MessagingGoals.WECHAT
}

object MessagingGoals {
    const val WECHAT = "com.tencent.mm"
    const val QQ = "com.tencent.mobileqq"

    // [打开/用][微信|QQ][，]{给|向|跟|对}X{发送|发消息说|发消息|发|说}[：:]Y
    private val FULL = Regex(
        "^(?:请)?(?:帮我)?(?:打开|用|在|去)?\\s*(微信|WeChat|QQ|qq)?\\s*(?:里|上)?[，,、\\s]*(?:并|然后)?\\s*" +
            "(?:给|向|跟|对)(.+?)(?:发送消息|发消息说|发消息|发送|发微信说|发微信|发QQ说|发QQ|发|说)[：:，,\\s]*(.+)$",
        RegexOption.IGNORE_CASE
    )

    fun parse(goal: String): MessagingGoal? {
        val g = goal.trim()
        FULL.find(g)?.let { m ->
            val appRaw = m.groupValues[1]
            val app = when {
                appRaw.equals("QQ", true) || g.contains("QQ", true) -> "QQ"
                else -> "微信"
            }
            val contact = m.groupValues[2].trim().removeSuffix("发")
            val msg = m.groupValues[3].trim()
            if (contact.isNotBlank() && msg.isNotBlank()) return MessagingGoal(false, contact, msg, app)
        }
        return null
    }
}

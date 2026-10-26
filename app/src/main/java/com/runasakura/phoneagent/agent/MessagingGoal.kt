package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.flow.Text

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

    // [打开/用][微信|QQ][，]{给|向|跟|对}X{发送|发消息说|发消息|发|说}[：:]Y  (Y may be multi-line)
    private val FULL = Regex(
        "^(?:请)?(?:帮我)?(?:打开|用|在|去)?\\s*(微信|WeChat|QQ|qq)?\\s*(?:里|上)?[，,、\\s]*(?:并|然后)?\\s*" +
            "(?:给|向|跟|对)(.+?)(?:发送消息|发消息说|发消息|发送|发微信说|发微信|发QQ说|发QQ|发|说)[：:，,\\s]*(.+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
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

    /** Reject empty / whitespace-only contact or message (safety). */
    fun validate(contact: String?, message: String?): String? = when {
        contact.isNullOrBlank() -> "联系人为空，拒绝发送"
        message.isNullOrBlank() -> "消息内容为空，拒绝发送"
        Text.norm(contact).isEmpty() -> "联系人无效，拒绝发送"
        else -> null
    }
}

/** Semantic message content: 「一个表情包」 is a request for a sticker/emoji, not literal text. */
object MessageContent {
    private val STICKER = Regex("^(?:发|发送)?(?:一|1)?(?:个|张|波)?(?:可爱的|好玩的|搞笑的|随便的?)?(?:表情包|表情|动图|贴图|emoji|表情符号)(?:吧|呗|过去|给他|给她)?[。！!]*$", RegexOption.IGNORE_CASE)
    private val MEDIA = Regex("^(?:一|1)?(?:张|个|段)?(?:图片|照片|视频|文件|语音|位置|红包)[。！!]*$")
    fun isSticker(msg: String) = STICKER.matches(msg.trim())
    /** Non-text content we don't do deterministically (photo/file/红包…) → model/human. */
    fun isOtherMedia(msg: String) = MEDIA.matches(msg.trim())
}

/** 「给X打语音/视频电话」 (WeChat only). */
data class CallGoal(val contact: String, val video: Boolean)

object CallGoals {
    private val A = Regex(
        "^(?:请)?(?:帮我)?(?:打开|用|在)?\\s*(?:微信)?\\s*(?:里|上)?[，,、\\s]*(?:并|然后)?\\s*(?:给|跟|和|与|向)(.+?)" +
            "(?:打|拨打|拨|发起|打个|打一个|进行)?(?:个|一个|一通)?\\s*(语音|视频)(?:电话|通话)?\\s*[。.!！]?$"
    )
    private val B = Regex("^(?:请)?(?:帮我)?(?:用)?(?:微信)?\\s*(?:语音|视频)(?:呼叫|打给|拨给)(.+?)\\s*$")

    fun parse(goal: String): CallGoal? {
        val g = goal.trim()
        if (g.contains("QQ", true)) return null
        A.find(g)?.let { m ->
            val c = m.groupValues[1].trim().removeSuffix("打").removeSuffix("拨").trim()
            if (c.isNotBlank() && c.length <= 30) return CallGoal(c, m.groupValues[2] == "视频")
        }
        B.find(g)?.let { m ->
            val c = m.groupValues[1].trim()
            if (c.isNotBlank()) return CallGoal(c, g.contains("视频"))
        }
        return null
    }
}

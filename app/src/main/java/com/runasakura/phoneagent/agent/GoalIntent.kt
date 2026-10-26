package com.runasakura.phoneagent.agent

import org.json.JSONObject

/**
 * Structured goal produced by the model (cloud first, local otherwise); regex parsers are only the fallback.
 * Anti-hallucination: recipient and literal text must literally occur in the user's goal.
 */
data class GoalIntent(
    val kind: String,              // message | call | read | alarm | toggle | app | other
    val app: String? = null,
    val recipient: String? = null,
    val contentType: String = "none", // text | sticker | image | file | none
    val content: String? = null,
    val video: Boolean = false,
    val steps: List<String> = emptyList(),
    val hour: Int? = null, val minute: Int? = null,
    val source: String = "规则"
) {
    fun describe() = buildString {
        append(kind)
        app?.let { append(" 应用=").append(it) }
        recipient?.let { append(" 对象=").append(it) }
        if (contentType != "none") append(" 内容类型=").append(contentType)
        content?.let { append(" 内容=").append(it.take(30)) }
        if (steps.isNotEmpty()) append(" 步骤=").append(steps.joinToString("→"))
        append("（").append(source).append("）")
    }
}

object IntentParser {
    const val PROMPT = "把用户的手机任务解析成 JSON，不要执行。字段：" +
        "kind(message发消息|call打电话|read看最后一条消息|alarm设闹钟|toggle开关系统功能|app在某应用里做事|other)，" +
        "app(应用名，可空)，recipient(联系人，原样照抄目标中的写法)，content_type(text|sticker表情/表情包|image|file|none)，" +
        "content(要发送的文字，原样照抄；表情包等非文字填null)，video(视频通话true)，hour,minute(闹钟24小时制)，" +
        "steps(在应用里还要做的步骤，简短中文数组，不含打开应用)。只输出 JSON。"

    fun parseJson(raw: String, goal: String): GoalIntent? = try {
        val t = raw.trim()
        val o = JSONObject(t.substring(t.indexOf('{'), t.lastIndexOf('}') + 1))
        fun str(k: String) = o.optString(k, "").trim().takeIf { it.isNotEmpty() && it != "null" }
        val kind = str("kind") ?: return null
        var ct = str("content_type") ?: "none"
        // Belt and braces: 「一个表情包」 is never literal text even if the model says so.
        if (ct == "text" && str("content")?.let { MessageContent.isSticker(it) } == true) ct = "sticker"
        val steps = o.optJSONArray("steps")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotBlank() } } ?: emptyList()
        val gi = GoalIntent(kind, str("app"), str("recipient"), ct, if (ct == "text") str("content") else null, o.optBoolean("video", false), steps.take(6),
            if (o.has("hour")) o.optInt("hour") else null, if (o.has("minute")) o.optInt("minute") else null, "模型")
        if (grounded(gi, goal)) gi else null
    } catch (e: Exception) { null }

    /** Reject hallucinated recipients/text: they must appear in the goal (emoji-normalised). */
    fun grounded(i: GoalIntent, goal: String): Boolean {
        val g = com.runasakura.phoneagent.flow.Text.emoji(goal)
        fun inGoal(s: String?) = s == null || g.contains(com.runasakura.phoneagent.flow.Text.emoji(s))
        return when (i.kind) {
            "message" -> i.recipient != null && inGoal(i.recipient) &&
                (i.contentType != "text" || (i.content != null && inGoal(i.content)))
            "call", "read" -> i.recipient != null && inGoal(i.recipient)
            "alarm" -> i.hour in 0..23 && (i.minute ?: 0) in 0..59
            else -> true
        }
    }

    /** Fallback: the old deterministic parsers, expressed as an intent. */
    fun ruleBased(goal: String): GoalIntent? {
        QuickIntents.alarm(goal)?.let { return GoalIntent("alarm", "时钟", hour = it.hour, minute = it.minute) }
        QuickIntents.readLast(goal)?.let { return GoalIntent("read", "微信", recipient = it) }
        CallGoals.parse(goal)?.let { return GoalIntent("call", "微信", recipient = it.contact, video = it.video) }
        MessagingGoals.parse(goal)?.let { m ->
            if (m.contact != null && m.message != null) {
                val ct = when { MessageContent.isSticker(m.message) -> "sticker"; MessageContent.isOtherMedia(m.message) -> "image"; else -> "text" }
                return GoalIntent("message", m.app, m.contact, ct, if (ct == "text") m.message else null)
            }
        }
        return null
    }

    /** Canonical goal for the generic path: 「打开APP，步骤1，步骤2」. */
    fun canonicalGoal(i: GoalIntent): String? {
        val app = i.app ?: return null
        if (i.kind != "app" && i.kind != "other") return null
        return "打开$app" + if (i.steps.isEmpty()) "" else "，" + i.steps.joinToString("，")
    }
}

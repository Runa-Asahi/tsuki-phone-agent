package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.model.UiSnapshot
import com.runasakura.phoneagent.prefs.AgentPreferences

enum class SafetyDecision {
    ALLOW,
    REQUIRE_CONFIRM,
    BLOCK_HUMAN,
}

data class SafetyResult(
    val decision: SafetyDecision,
    val reason: String = ""
)

class SafetyGate(private val prefs: AgentPreferences) {

    private val confirmKeywords = listOf(
        "发送", "发给", "付款", "支付", "转账", "删除", "卸载", "授权",
        "开通", "确认支付", "立即购买", "提交订单", "发帖", "发布",
        "send", "pay", "transfer", "delete", "uninstall", "purchase"
    )

    private val otpKeywords = listOf("验证码", "短信验证", "动态码", "OTP", "verification code")

    fun check(action: AgentAction, snapshot: UiSnapshot?, userGoal: String): SafetyResult {
        val pkg = snapshot?.packageName.orEmpty()
        if (pkg.isNotBlank() && prefs.sensitivePackages.any { pkg.startsWith(it) || it == pkg }) {
            return SafetyResult(SafetyDecision.BLOCK_HUMAN, "敏感应用包名：$pkg，请人工操作")
        }

        val tree = snapshot?.compressedText.orEmpty()
        if (otpKeywords.any { tree.contains(it, ignoreCase = true) }) {
            return SafetyResult(SafetyDecision.BLOCK_HUMAN, "检测到验证码/登录页，停止自动点击")
        }

        when (action) {
            is AgentAction.AskConfirm -> return SafetyResult(SafetyDecision.REQUIRE_CONFIRM, action.reason)
            is AgentAction.HumanTakeover -> return SafetyResult(SafetyDecision.BLOCK_HUMAN, action.reason)
            is AgentAction.Type -> {
                val g = userGoal + action.text
                if (confirmKeywords.any { g.contains(it, ignoreCase = true) } &&
                    (userGoal.contains("发") || userGoal.contains("发送") || userGoal.contains("send", true))
                ) {
                    // typing alone ok; send confirmation handled by tap near send
                }
            }
            is AgentAction.Tap, is AgentAction.LongPress -> {
                val label = when (action) {
                    is AgentAction.Tap -> action.text.orEmpty()
                    else -> ""
                }
                val context = "$userGoal $label $tree".take(2000)
                if (confirmKeywords.any { context.contains(it, ignoreCase = true) } &&
                    looksLikeCommit(context)
                ) {
                    return SafetyResult(SafetyDecision.REQUIRE_CONFIRM, "疑似发送/支付/删除类操作，需确认")
                }
            }
            else -> Unit
        }
        return SafetyResult(SafetyDecision.ALLOW)
    }

    private fun looksLikeCommit(context: String): Boolean {
        val triggers = listOf("发送", "付款", "支付", "转账", "删除", "卸载", "确认", "提交", "发布", "send", "pay")
        return triggers.any { context.contains(it, ignoreCase = true) }
    }

    fun goalNeedsConfirm(goal: String): Boolean =
        confirmKeywords.any { goal.contains(it, ignoreCase = true) }
}

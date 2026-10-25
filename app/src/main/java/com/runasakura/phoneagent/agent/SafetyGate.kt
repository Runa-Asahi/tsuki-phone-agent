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

    private val sendButtonLabels = listOf("发送", "Send", "傳送", "发送(S)")

    private val otpKeywords = listOf("验证码", "短信验证", "动态码", "OTP", "verification code")

    /** Packages that must never be auto-driven (payments). WeChat is NOT here. */
    private val hardBlock = setOf(
        "com.eg.android.AlipayGphone",
        "com.unionpay",
        "cmb.pb",
        "com.chinamworld.main",
        "com.android.bankabc",
        "com.icbc",
        "com.chinamworld.bocmbci"
    )

    fun check(action: AgentAction, snapshot: UiSnapshot?, userGoal: String): SafetyResult {
        val pkg = snapshot?.packageName.orEmpty()
        val blocked = (prefs.sensitivePackages + hardBlock)
            .filter { it != "com.tencent.mm" && !it.startsWith("com.tencent.mm") }
        if (pkg.isNotBlank() && blocked.any { pkg.startsWith(it) || it == pkg }) {
            return SafetyResult(SafetyDecision.BLOCK_HUMAN, "敏感应用包名：$pkg，请人工操作")
        }

        val tree = snapshot?.compressedText.orEmpty()
        if (otpKeywords.any { tree.contains(it, ignoreCase = true) }) {
            return SafetyResult(SafetyDecision.BLOCK_HUMAN, "检测到验证码/登录页，停止自动点击")
        }

        when (action) {
            is AgentAction.AskConfirm ->
                return SafetyResult(SafetyDecision.REQUIRE_CONFIRM, action.reason)
            is AgentAction.HumanTakeover ->
                return SafetyResult(SafetyDecision.BLOCK_HUMAN, action.reason)
            is AgentAction.Tap -> {
                val label = action.text.orEmpty()
                if (isSendLike(label) || isSendLike(label) && userGoal.contains("发")) {
                    return SafetyResult(
                        SafetyDecision.REQUIRE_CONFIRM,
                        "即将点击「发送」，确认后才会真正发出"
                    )
                }
                val context = "$userGoal $label $tree".take(1500)
                if (confirmKeywords.any { context.contains(it, ignoreCase = true) } &&
                    looksLikeCommit(label.ifBlank { context })
                ) {
                    return SafetyResult(SafetyDecision.REQUIRE_CONFIRM, "疑似发送/支付/删除类操作，需确认")
                }
            }
            is AgentAction.LongPress -> {
                val context = "$userGoal $tree".take(1500)
                if (looksLikeCommit(context)) {
                    return SafetyResult(SafetyDecision.REQUIRE_CONFIRM, "疑似敏感长按，需确认")
                }
            }
            else -> Unit
        }
        return SafetyResult(SafetyDecision.ALLOW)
    }

    fun isSendLike(label: String): Boolean =
        sendButtonLabels.any { label.equals(it, true) || label.trim() == it }

    private fun looksLikeCommit(context: String): Boolean {
        val triggers = listOf("发送", "付款", "支付", "转账", "删除", "卸载", "提交", "发布", "send", "pay")
        return triggers.any { context.contains(it, ignoreCase = true) }
    }

    fun goalNeedsConfirm(goal: String): Boolean =
        confirmKeywords.any { goal.contains(it, ignoreCase = true) }
}

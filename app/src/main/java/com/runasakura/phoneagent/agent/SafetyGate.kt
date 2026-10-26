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
        "确认转账", "立即付款", "确认付款", "红包", "借钱", "同意协议",
        "开通权限", "允许本次", "确认还款", "提现", "兑换",
        "send", "pay", "transfer", "delete", "uninstall", "purchase"
    )

    companion object {
        private val CALL_KEYS = listOf("视频通话", "语音通话", "音视频通话", "拨打", "呼叫", "拨号", "打电话")
        /** Model-fallback taps on call buttons always need confirmation (OCR-fuzzy, e.g. 「初麵通话」). */
        fun isCallLike(label: String): Boolean {
            val n = com.runasakura.phoneagent.flow.KeyMatch.clean(label)
            if (n.isBlank() || n.length > 8) return false
            return n.contains("通话") || CALL_KEYS.any { com.runasakura.phoneagent.flow.KeyMatch.isKey(n, it) }
        }
    }

    private val sendButtonLabels = listOf(
        "发送", "Send", "傳送", "发送(S)", "送出", "发消息", "Send Message"
    )
    private val payButtonLabels = listOf(
        "确认支付", "立即支付", "确认付款", "付款", "确认转账", "立即转账", "转账", "Pay", "确认"
    )

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
            is AgentAction.OpenUri -> return PhoneTools.uriNeedsConfirm(action.uri)?.let { SafetyResult(SafetyDecision.REQUIRE_CONFIRM, it) }
                ?: SafetyResult(SafetyDecision.ALLOW)
            is AgentAction.Intent -> return PhoneTools.intentConfirm(action.action, action.data)?.let { SafetyResult(SafetyDecision.REQUIRE_CONFIRM, it) }
                ?: SafetyResult(SafetyDecision.ALLOW)
            is AgentAction.Global -> if (action.name == "power_dialog" || action.name == "lock_screen")
                return SafetyResult(SafetyDecision.REQUIRE_CONFIRM, "将打开${if (action.name == "lock_screen") "锁屏" else "电源菜单"}")
            is AgentAction.AskConfirm ->
                return if (ConfirmPolicy.isSensitive(action))
                    SafetyResult(SafetyDecision.REQUIRE_CONFIRM, action.reason)
                else SafetyResult(SafetyDecision.ALLOW, "忽略非敏感 ask_confirm")
            is AgentAction.HumanTakeover ->
                return SafetyResult(SafetyDecision.BLOCK_HUMAN, action.reason)
            is AgentAction.Tap -> {
                val label = action.text.orEmpty()
                // Resolve label from node_id against the dump when text is blank
                val resolved = label.ifBlank {
                    val id = action.nodeId.orEmpty()
                    if (id.isNotBlank() && tree.contains("[$id]")) {
                        tree.lineSequence().firstOrNull { it.contains("[$id]") }
                            ?.substringAfter("] ")?.substringBefore(" (")?.trim().orEmpty()
                    } else ""
                }
                if (isSendLike(resolved) || isSendLike(label)) {
                    return SafetyResult(SafetyDecision.REQUIRE_CONFIRM, "即将点击「发送」，确认后才会真正发出")
                }
                if (isCallLike(resolved) || isCallLike(label)) {
                    return SafetyResult(SafetyDecision.REQUIRE_CONFIRM, "即将发起语音/视频通话或拨号，确认后才会拨出")
                }
                if (payButtonLabels.any { resolved.equals(it, true) || label.equals(it, true) }) {
                    return SafetyResult(SafetyDecision.REQUIRE_CONFIRM, "即将点击支付/转账类按钮，确认后才会执行")
                }
                val context = "$userGoal $label $resolved $tree".take(1500)
                if (confirmKeywords.any { context.contains(it, ignoreCase = true) } &&
                    looksLikeCommit(resolved.ifBlank { label.ifBlank { context } })
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
        val triggers = listOf(
            "发送", "付款", "支付", "转账", "删除", "卸载", "提交", "发布",
            "红包", "提现", "借钱", "send", "pay", "transfer"
        )
        return triggers.any { context.contains(it, ignoreCase = true) }
    }

    fun goalNeedsConfirm(goal: String): Boolean =
        confirmKeywords.any { goal.contains(it, ignoreCase = true) }
}

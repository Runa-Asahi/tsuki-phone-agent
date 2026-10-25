package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.model.AgentAction

/** ask_confirm is only legitimate for send / pay / delete (or an explicit pendingAction of that kind). */
object ConfirmPolicy {
    private val SENSITIVE = listOf("发送", "發送", "支付", "付款", "转账", "轉賬", "删除", "刪除", "红包", "提现", "确认付款", "确认支付")

    fun isSensitive(action: AgentAction.AskConfirm): Boolean {
        action.pendingAction?.let { p ->
            if (p is AgentAction.Tap && SENSITIVE.any { (p.text ?: "").contains(it) }) return true
            if (p is AgentAction.Type) return false
        }
        val r = action.reason
        return SENSITIVE.any { r.contains(it) }
    }
}

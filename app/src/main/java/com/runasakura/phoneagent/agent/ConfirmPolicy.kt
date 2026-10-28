package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.model.AgentAction

/** ask_confirm is only legitimate for send / pay / delete (or an explicit pendingAction of that kind). */
object ConfirmPolicy {
    private val SENSITIVE = listOf("发送", "發送", "支付", "付款", "转账", "轉賬", "删除", "刪除", "红包", "提现", "确认付款", "确认支付",
        "通话", "拨打", "呼叫", "打电话", "下单", "购买", "提交订单", "卸载",
        "重启", "关机", "清除数据", "清空数据", "恢复出厂")

    fun isSensitive(action: AgentAction.AskConfirm): Boolean {
        action.pendingAction?.let { p ->
            if (p is AgentAction.Tap && SENSITIVE.any { (p.text ?: "").contains(it) }) return true
            if (p is AgentAction.Type) return false
            if (p is AgentAction.Shell) return com.runasakura.phoneagent.shell.ShellCommand.parse(p.cmd).let { it is com.runasakura.phoneagent.shell.ShellCommand.Parsed.Ok && com.runasakura.phoneagent.shell.ShellCommand.confirmReason(it.argv) != null }
        }
        val r = action.reason
        return SENSITIVE.any { r.contains(it) }
    }
}

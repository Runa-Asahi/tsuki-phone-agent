package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.flow.Device
import com.runasakura.phoneagent.flow.Screen
import com.runasakura.phoneagent.screen.ScreenItem
import com.runasakura.phoneagent.flow.Text

/**
 * Conservative auto-handler for runtime-permission prompts. Only taps
 * "while using"/"this time only" style buttons; never "始终允许", never on any screen
 * that mentions payment, passwords, installs or device admin.
 */
object PermissionDialog {
    private val SAFE = listOf("仅使用期间允许", "使用期间允许", "使用时允许", "仅在使用中允许", "仅在使用该应用时允许", "本次运行允许", "仅本次允许", "仅此一次", "本次允许")
    private val DANGER = listOf("支付", "付款", "转账", "密码", "红包", "安装", "设备管理", "root", "无障碍")
    private val PROMPT = listOf("权限", "访问", "允许", "位置信息", "相机", "麦克风")

    fun pick(s: Screen): ScreenItem? {
        val labels = s.items.map { Text.norm(it.label) }
        if (labels.none { l -> PROMPT.any { l.contains(it) } }) return null
        if (labels.any { l -> DANGER.any { l.contains(it, ignoreCase = true) } }) return null
        return s.items.firstOrNull { Text.norm(it.label) in SAFE }
    }

    suspend fun dismissIfPresent(d: Device, log: (String) -> Unit): Boolean {
        val hit = pick(d.screen()) ?: return false
        log("检测到权限弹窗，选择「${hit.label}」")
        d.tap(hit); d.afterAction(1000)
        return true
    }
}

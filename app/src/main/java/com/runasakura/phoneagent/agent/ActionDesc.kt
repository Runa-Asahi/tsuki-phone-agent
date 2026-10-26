package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.screen.ScreenItem

/** Stable, id-free description of an action (ids change every step, so loops are detected by target). */
object ActionDesc {
    val GLOBAL_ZH = mapOf("notifications" to "下拉通知栏", "quick_settings" to "打开快捷设置", "recents" to "最近任务",
        "power_dialog" to "电源菜单", "lock_screen" to "锁屏", "take_screenshot" to "截屏", "split_screen" to "分屏",
        "back" to "返回", "home" to "桌面")
    val KEY_ZH = mapOf("enter" to "键盘回车/搜索", "volume_up" to "音量+", "volume_down" to "音量-", "mute" to "静音", "unmute" to "取消静音")
    fun of(a: AgentAction, label: String? = null): String = when (a) {
        is AgentAction.Tap -> "点击" + ((label ?: a.text)?.let { "「${it.take(16)}」" } ?: a.x?.let { "(${a.x},${a.y})" } ?: a.nodeId.orEmpty())
        is AgentAction.LongPress -> "长按" + (a.x?.let { "(${a.x},${a.y})" } ?: a.nodeId.orEmpty())
        is AgentAction.Type -> "输入「${a.text.take(16)}」"
        is AgentAction.Swipe -> "滑动(${a.x1},${a.y1}→${a.x2},${a.y2})"
        is AgentAction.Launch -> "打开${a.appName ?: a.packageName ?: ""}"
        is AgentAction.Wait -> "等待${a.ms}ms"
        AgentAction.Back -> "返回"; AgentAction.Home -> "桌面"; AgentAction.Recents -> "最近任务"
        is AgentAction.Global -> GLOBAL_ZH[a.name] ?: "系统面板 ${a.name}"
        is AgentAction.OpenUri -> "打开链接 ${a.uri.take(40)}"
        is AgentAction.Intent -> "打开系统页 ${a.action.substringAfterLast('.')}"
        is AgentAction.SetClipboard -> "复制「${a.text.take(16)}」到剪贴板"
        AgentAction.ReadClipboard -> "读取剪贴板"
        is AgentAction.Key -> KEY_ZH[a.name] ?: "按键 ${a.name}"
        is AgentAction.ScrollFind -> "滚动查找「${a.text.take(16)}」"
        else -> a.toString().take(40)
    }
}

/** Fuzzy label lookup used when the model names a target by text (tolerates OCR misreads). */
object KeyMatchLite {
    fun sim(a: String, b: String): Double {
        val x = a.filter { !it.isWhitespace() }.toSet(); val y = b.filter { !it.isWhitespace() }.toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return (x intersect y).size.toDouble() / (x union y).size
    }
    fun best(items: List<ScreenItem>, text: String, min: Double = 0.5): ScreenItem? =
        items.map { it to sim(it.label, text) }.filter { it.second >= min }.maxByOrNull { it.second }?.first
}

package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.screen.ScreenItem

/** Stable, id-free description of an action (ids change every step, so loops are detected by target). */
object ActionDesc {
    fun of(a: AgentAction, label: String? = null): String = when (a) {
        is AgentAction.Tap -> "点击" + ((label ?: a.text)?.let { "「${it.take(16)}」" } ?: a.x?.let { "(${a.x},${a.y})" } ?: a.nodeId.orEmpty())
        is AgentAction.LongPress -> "长按" + (a.x?.let { "(${a.x},${a.y})" } ?: a.nodeId.orEmpty())
        is AgentAction.Type -> "输入「${a.text.take(16)}」"
        is AgentAction.Swipe -> "滑动(${a.x1},${a.y1}→${a.x2},${a.y2})"
        is AgentAction.Launch -> "打开${a.appName ?: a.packageName ?: ""}"
        is AgentAction.Wait -> "等待${a.ms}ms"
        AgentAction.Back -> "返回"; AgentAction.Home -> "桌面"; AgentAction.Recents -> "最近任务"
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

package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.flow.Text
import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.screen.ScreenItem

/** Strict validation of model actions against the current [e#] list. Returns an error or null. */
object ActionValidator {
    fun validate(a: AgentAction, items: List<ScreenItem>, w: Int, h: Int): String? = when (a) {
        is AgentAction.Tap -> when {
            !a.nodeId.isNullOrBlank() ->
                if (items.none { it.id == a.nodeId }) "node_id ${a.nodeId} 不存在，只能用列表里的 e 编号" else null
            !a.text.isNullOrBlank() ->
                if (items.none { Text.eq(it.label, a.text) || Text.norm(it.label).contains(Text.norm(a.text)) })
                    "屏幕上没有文字「${a.text}」" else null
            a.x != null && a.y != null -> if (a.x !in 0 until w || a.y !in 0 until h) "坐标超出屏幕" else null
            else -> "tap 需要 node_id"
        }
        is AgentAction.LongPress ->
            if (!a.nodeId.isNullOrBlank() && items.none { it.id == a.nodeId }) "node_id ${a.nodeId} 不存在" else null
        is AgentAction.Type -> when {
            a.text.isBlank() -> "type 需要 text"
            !a.nodeId.isNullOrBlank() && items.none { it.id == a.nodeId } -> "node_id ${a.nodeId} 不存在"
            else -> null
        }
        is AgentAction.Swipe ->
            if (listOf(a.x1, a.x2).any { it !in 0 until w } || listOf(a.y1, a.y2).any { it !in 0 until h }) "滑动坐标超出屏幕" else null
        is AgentAction.Launch -> if (a.packageName.isNullOrBlank() && a.appName.isNullOrBlank()) "launch 需要 app" else null
        is AgentAction.Fail ->
            if (a.reason.startsWith("Unknown action") || a.reason.startsWith("Cannot parse") || a.reason.startsWith("LLM returned empty"))
                "无法解析：${a.reason.take(40)}" else null
        else -> null
    }
}

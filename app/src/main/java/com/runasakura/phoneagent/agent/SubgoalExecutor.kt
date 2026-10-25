package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.flow.Device
import com.runasakura.phoneagent.flow.Screen
import com.runasakura.phoneagent.flow.Text
import com.runasakura.phoneagent.screen.ScreenItem

/**
 * Deterministic executor for split subgoals (进入/打开/点击 X, 发送/输入 Y) over [Device]
 * (tree or OCR). Order: 文字匹配 → 滚动(≤3) → 应用内搜索. Returns FAILED so the caller
 * may use the model as a last resort.
 */
class SubgoalExecutor(
    private val d: Device,
    private val onLog: (String) -> Unit,
    private val onConfirm: suspend (String) -> Boolean
) {
    enum class Status { OK, FAILED, CANCELLED }

    suspend fun run(sub: Subgoal): Status = when (sub) {
        is Subgoal.Open -> if (open(sub.target)) Status.OK else Status.FAILED
        is Subgoal.Send -> send(sub.text, sub.send)
        is Subgoal.Unknown -> Status.FAILED
    }

    /** Pick the best on-screen item for [target]: exact first, else shortest containing label. */
    fun pick(s: Screen, target: String, below: Int = 0): ScreenItem? {
        val pool = s.items.filter { !it.edit && it.cy > below && (s.imeTop == null || it.cy < s.imeTop) }
        val t = Text.norm(target)
        pool.firstOrNull { Text.norm(it.label) == t }?.let { return it }
        return pool.filter { Text.norm(it.label).contains(t) && it.label.length <= target.length + 8 }
            .minByOrNull { it.label.length }
    }

    suspend fun open(target: String): Boolean {
        onLog("子目标：进入「$target」")
        for (i in 0..3) {
            val s = d.screen()
            if (s.isEmpty()) { onLog("✗ 界面为空（树/OCR 都读不到）"); return false }
            val hit = pick(s, target, below = (s.h * 0.04).toInt())
            if (hit != null) {
                d.tap(hit); d.sleep(900)
                onLog(if (i == 0) "✓ 文字匹配（${s.source}）：${hit.label}" else "✓ 滚动 $i 次后找到：${hit.label}")
                return true
            }
            if (i < 3) d.scroll(true)
        }
        repeat(3) { d.scroll(false) }
        if (searchAndOpen(target)) { onLog("✓ 应用内搜索"); return true }
        onLog("✗ 未找到「$target」")
        return false
    }

    private suspend fun searchAndOpen(target: String): Boolean {
        var s = d.screen()
        val search = s.items.firstOrNull { it.cy < s.h * 0.2 && (Text.eq(it.label, "搜索") || it.label.startsWith("搜索")) }
            ?: return false
        d.tap(search); d.sleep(900)
        s = d.screen()
        val edit = s.items.firstOrNull { it.edit && it.cy < s.h * 0.2 }
        if (!d.type(target, edit?.cx, edit?.cy)) { onLog("搜索框输入失败"); return false }
        d.sleep(1300)
        s = d.screen()
        val hit = pick(s, target, below = (s.h * 0.11).toInt()) ?: return false
        d.tap(hit); d.sleep(900)
        return true
    }

    suspend fun send(text: String, reallySend: Boolean): Status {
        onLog("子目标：${if (reallySend) "发送" else "输入"}「$text」")
        var s = d.screen()
        val edit = s.items.lastOrNull { it.edit }
        if (!d.type(text, edit?.cx ?: (s.w * 0.45).toInt(), edit?.cy ?: (s.h * 0.952).toInt())) {
            onLog("✗ 输入失败"); return Status.FAILED
        }
        onLog("✓ 已输入（尚未发送）")
        if (!reallySend) return Status.OK
        d.sleep(600)
        s = d.screen()
        val btn = s.items.filter { Text.eq(it.label, "发送") && (s.imeTop == null || it.cy < s.imeTop) }.maxByOrNull { it.cy }
            ?: run { onLog("✗ 未找到发送按钮，未发送"); return Status.FAILED }
        val title = s.items.filter { it.cy < s.h * 0.12 }.joinToString(" ") { it.label }.ifBlank { "未知" }
        if (!onConfirm("即将发送：「$text」\n当前页面标题：$title\n应用：${s.pkg}\n确认后才会点击发送。")) {
            onLog("用户取消发送"); return Status.CANCELLED
        }
        d.tap(btn)
        onLog("✓ 已点击发送")
        d.sleep(600)
        return Status.OK
    }
}

package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.a11y.AgentAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Deterministic executor for split subgoals: 进入/打开/点击 X, 发送/输入 Y.
 * Order: 文字匹配 → 滚动 → 应用内搜索; returns false so the caller can use the model.
 */
class SubgoalExecutor(
    private val a11y: AgentAccessibilityService,
    private val onLog: (String) -> Unit,
    private val onConfirm: suspend (String) -> Boolean
) {
    enum class Status { OK, FAILED, CANCELLED }

    suspend fun waitSettle(quietMs: Long = 400, maxMs: Long = 1500) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxMs) {
            if (System.currentTimeMillis() - a11y.lastEventAt >= quietMs) return
            delay(100)
        }
    }

    /** Wait (≤2s) for a non-trivial tree, e.g. right after launching WeChat. */
    suspend fun waitForTree(minText: Int = 3, maxMs: Long = 2000): Int {
        val start = System.currentTimeMillis()
        var n = 0
        while (System.currentTimeMillis() - start < maxMs) {
            n = a11y.screenItems().count { it.label.isNotBlank() }
            if (n >= minText) break
            delay(250)
        }
        onLog("界面文本节点数=$n")
        return n
    }

    suspend fun run(sub: Subgoal, goal: String): Status = when (sub) {
        is Subgoal.Open -> if (open(sub.target)) Status.OK else Status.FAILED
        is Subgoal.Send -> send(sub.text, sub.send)
        is Subgoal.Unknown -> Status.FAILED
    }

    suspend fun open(target: String): Boolean {
        waitSettle()
        onLog("子目标：进入「$target」")
        if (tapText(target)) { onLog("✓ 文字匹配"); return true }
        val (w, h) = a11y.screenSize()
        for (i in 1..3) {
            coroutineContext.ensureActive()
            a11y.swipe(w / 2, (h * 0.72).toInt(), w / 2, (h * 0.32).toInt(), 350)
            delay(500); waitSettle()
            if (tapText(target)) { onLog("✓ 滚动 $i 次后找到"); return true }
        }
        // scroll back near the top before searching
        repeat(3) { a11y.swipe(w / 2, (h * 0.32).toInt(), w / 2, (h * 0.78).toInt(), 250); delay(250) }
        if (searchAndOpen(target)) { onLog("✓ 应用内搜索"); return true }
        onLog("✗ 未找到「$target」")
        return false
    }

    private suspend fun tapText(target: String): Boolean {
        val n = a11y.findVisibleByText(target) ?: return false
        val ok = a11y.tapNode(n)
        if (ok) { delay(600); waitSettle() }
        return ok
    }

    private suspend fun searchAndOpen(target: String): Boolean {
        val (w, h) = a11y.screenSize()
        val search = a11y.findVisibleByText("搜索", allowEditable = true)
            ?: a11y.findByContentDesc("搜索")
        val opened = if (search != null) a11y.tapNode(search) else {
            // top-right search icon heuristic (WeChat ~ x=1057,y≈200 on 1264x2800)
            onLog("未见搜索节点，点右上角搜索位")
            a11y.tap((w * 0.836).toInt(), (h * 0.071).toInt())
        }
        if (!opened) return false
        delay(700); waitSettle()
        if (!a11y.typeText(target, a11y.firstVisibleEditable())) {
            onLog("搜索框输入失败"); return false
        }
        delay(1000); waitSettle()
        val hit = a11y.screenItems().firstOrNull { !it.edit && it.label.contains(target, true) }
        val node = hit?.ref as? android.view.accessibility.AccessibilityNodeInfo ?: return false
        val ok = a11y.tapNode(node)
        if (ok) { delay(700); waitSettle() }
        return ok
    }

    suspend fun send(text: String, reallySend: Boolean): Status {
        waitSettle()
        onLog("子目标：${if (reallySend) "发送" else "输入"}「$text」")
        val edit = a11y.firstVisibleEditable()
        if (edit == null) { onLog("✗ 未找到输入框"); return Status.FAILED }
        if (!a11y.typeText(text, edit)) { onLog("✗ 输入失败"); return Status.FAILED }
        onLog("✓ 已输入（尚未发送）")
        if (!reallySend) return Status.OK
        delay(400); waitSettle()
        if (!onConfirm("即将发送：「$text」\n前台：${a11y.foregroundPackage()}\n确认后才会点击发送。")) {
            onLog("用户取消发送"); return Status.CANCELLED
        }
        val btn = a11y.findVisibleByText("发送") ?: a11y.findByContentDesc("发送")
        if (btn == null || !a11y.tapNode(btn)) { onLog("✗ 未找到发送按钮"); return Status.FAILED }
        onLog("✓ 已点击发送")
        delay(500)
        return Status.OK
    }
}

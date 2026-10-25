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
    enum class Status { OK, FAILED, CANCELLED, DISCONNECTED }

    /** Screen before each successful subgoal, newest last (for 撤销 / wrong-page recovery). */
    private val trail = ArrayDeque<ScreenSig>()

    suspend fun run(sub: Subgoal): Status {
        if (!d.alive()) { onLog("✗ 无障碍服务已断开"); return Status.DISCONNECTED }
        if (sub == Subgoal.Undo) return undo()
        val before = d.screen()
        val r = runInner(sub).let { if (it != Status.OK && !d.alive()) Status.DISCONNECTED else it }
        when (r) {
            Status.OK -> { trail.addLast(ScreenSig.of(before)); while (trail.size > 10) trail.removeFirst() }
            Status.FAILED -> {
                // Wrong-page detection: a failed step that left the app we were in → go back.
                val now = d.screen()
                val rec = recoveryDecision(before, now, sub)
                if (rec == Recovery.SKIP_LOW_CONFIDENCE) onLog("前台包名仅来自 OCR/低置信度（${now.pkg}），不自动返回")
                if (rec == Recovery.BACK) {
                    onLog("失败后跑到了 ${now.pkg}，尝试回到 ${before.pkg}")
                    backUntil(ScreenSig.of(before))
                }
            }
            else -> Unit
        }
        return r
    }

    enum class Recovery { NONE, BACK, SKIP_LOW_CONFIDENCE }

    companion object {
        /** After a failed subgoal: go back only if we provably left the app (both observations from the a11y tree). */
        fun recoveryDecision(before: Screen, now: Screen, sub: Subgoal): Recovery = when {
            before.pkg.isEmpty() || now.pkg == before.pkg || sub is Subgoal.Home -> Recovery.NONE
            before.source != "tree" || now.source != "tree" -> Recovery.SKIP_LOW_CONFIDENCE
            else -> Recovery.BACK
        }
    }

    private suspend fun undo(): Status {
        val target = trail.removeLastOrNull() ?: run { onLog("✗ 没有可撤销的步骤"); return Status.FAILED }
        onLog("子目标：撤销上一步")
        return if (backUntil(target)) { onLog("✓ 已回到上一步的界面"); Status.OK }
        else { onLog("✗ 返回后未能确认回到上一步界面"); Status.FAILED }
    }

    /** Press back (≤3) until the screen resembles [target]. */
    private suspend fun backUntil(target: ScreenSig): Boolean {
        if (ScreenSig.similar(ScreenSig.of(d.screen()), target)) return true
        repeat(3) {
            d.back(); d.afterAction(1200)
            if (ScreenSig.similar(ScreenSig.of(d.screen()), target)) return true
        }
        return false
    }

    private suspend fun runInner(sub: Subgoal): Status = when (sub) {
        Subgoal.Undo -> Status.FAILED
        is Subgoal.Open -> if (open(sub.target)) Status.OK else Status.FAILED
        is Subgoal.Send -> send(sub.text, sub.send)
        is Subgoal.Unknown -> Status.FAILED
        is Subgoal.Scroll -> scroll(sub.forward)
        Subgoal.Back -> changed("返回") { d.back() }
        Subgoal.Home -> { d.home(); d.afterAction(1500); onLog("✓ 已回到桌面"); Status.OK }
        is Subgoal.Search -> if (searchFor(sub.query)) Status.OK else Status.FAILED
        is Subgoal.LongPress -> longPress(sub.target)
        is Subgoal.TypeInto -> typeInto(sub.field, sub.text)
        is Subgoal.ScrollTo -> if (scrollTo(sub.target) != null) Status.OK else Status.FAILED
    }

    private suspend fun longPress(target: String): Status {
        onLog("子目标：长按「$target」")
        val s = d.screen()
        val hit = pick(s, target, below = (s.h * 0.04).toInt()) ?: scrollTo(target)
            ?: run { onLog("✗ 未找到「$target」"); return Status.FAILED }
        val before = sig(d.screen())
        d.longPress(hit); d.afterAction(1500)
        return if (sig(d.screen()) != before) { onLog("✓ 已长按「${hit.label}」"); Status.OK }
        else { onLog("✗ 长按后界面无变化"); Status.FAILED }
    }

    /** 在X输入Y: find a field by edit label/placeholder or nearby label, type, verify text shows. */
    private suspend fun typeInto(field: String, text: String): Status {
        onLog("子目标：在「$field」输入「$text」")
        var s = d.screen()
        val f = Text.norm(field)
        val target = s.items.firstOrNull { it.edit && Text.norm(it.label).contains(f) }
            ?: s.items.firstOrNull { Text.norm(it.label).contains(f) }
            ?: run { onLog("✗ 未找到输入框「$field」"); return Status.FAILED }
        for (attempt in 0..2) {
            if (!d.type(text, target.cx, target.cy, attempt)) continue
            d.afterAction(1200)
            s = d.screen()
            if (s.items.any { Text.norm(it.label).contains(Text.norm(text)) }) {
                onLog("✓ 已在「$field」输入"); return Status.OK
            }
        }
        onLog("✗ 输入后未在屏幕上看到文字"); return Status.FAILED
    }

    /** Scroll (≤8) until [target] is visible; stops early at list end. Returns the item. */
    suspend fun scrollTo(target: String, maxScrolls: Int = 8): ScreenItem? {
        var s = d.screen()
        pick(s, target, below = (s.h * 0.04).toInt())?.let { return it }
        var still = 0
        for (i in 1..maxScrolls) {
            val before = sig(s)
            d.scroll(true); d.afterAction(1200)
            s = d.screen()
            pick(s, target, below = (s.h * 0.04).toInt())?.let { onLog("✓ 滑动 $i 次后看到「${it.label}」"); return it }
            var waits = 0
            while (StepHistory.isLoading(s.items.map { it.label }) && waits++ < 4) { d.afterAction(2000); s = d.screen() }
            if (waits > 0) {
                still = 0
                pick(s, target, below = (s.h * 0.04).toInt())?.let { onLog("✓ 加载后看到「${it.label}」"); return it }
                continue
            }
            if (sig(s) == before) { if (++still >= 2) { onLog("已到底，未见「$target」"); return null } } else still = 0
        }
        return null
    }

    private fun sig(s: Screen) = s.pkg + "|" + s.items.joinToString("|") { it.label }

    /** Run [act] and verify the screen changed (postcondition). */
    private suspend fun changed(name: String, act: suspend () -> Unit): Status {
        val before = sig(d.screen())
        act(); d.afterAction(1500)
        val after = d.screen()
        return if (sig(after) != before) { onLog("✓ $name"); Status.OK }
        else { onLog("✗ $name 后界面无变化"); Status.FAILED }
    }

    private suspend fun scroll(forward: Boolean): Status {
        val before = d.screen()
        if (before.isEmpty()) { onLog("✗ 界面为空"); return Status.FAILED }
        d.scroll(forward); d.afterAction(1200)
        val after = d.screen()
        return if (sig(after) != sig(before)) { onLog("✓ ${if (forward) "上滑" else "下滑"}"); Status.OK }
        else { onLog("⚠ 滑动后内容未变化（可能已到${if (forward) "底" else "顶"}）"); Status.OK }
    }

    /** 搜索X：open the in-app search, type X, verify it shows, submit nothing else. */
    suspend fun searchFor(q: String): Boolean {
        onLog("子目标：搜索「$q」")
        var s = d.screen()
        val entry = s.items.firstOrNull { it.cy < s.h * 0.2 && (it.edit || Text.norm(it.label).startsWith("搜索")) }
        if (entry == null) { onLog("✗ 未找到搜索入口"); return false }
        d.tap(entry); d.afterAction(1500)
        for (attempt in 0..2) {
            s = d.screen()
            val edit = s.items.firstOrNull { it.edit && it.cy < s.h * 0.2 }
            if (!d.type(q, edit?.cx ?: entry.cx, edit?.cy ?: entry.cy, attempt)) continue
            d.afterAction(1500)
            s = d.screen()
            if (s.items.any { it.cy < s.h * 0.2 && Text.norm(it.label).contains(Text.norm(q)) }) {
                onLog("✓ 已输入搜索词"); return true
            }
        }
        onLog("✗ 搜索词未出现在搜索框"); return false
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
                d.tap(hit); d.afterAction(1500)
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
        d.tap(search); d.afterAction(1500)
        s = d.screen()
        val edit = s.items.firstOrNull { it.edit && it.cy < s.h * 0.2 }
        if (!d.type(target, edit?.cx, edit?.cy)) { onLog("搜索框输入失败"); return false }
        d.afterAction(1500)
        s = d.screen()
        val hit = pick(s, target, below = (s.h * 0.11).toInt()) ?: return false
        d.tap(hit); d.afterAction(1500)
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

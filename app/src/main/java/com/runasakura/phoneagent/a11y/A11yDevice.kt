package com.runasakura.phoneagent.a11y

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.runasakura.phoneagent.flow.Device
import com.runasakura.phoneagent.flow.Screen
import com.runasakura.phoneagent.flow.Text
import com.runasakura.phoneagent.ocr.OcrEngine
import com.runasakura.phoneagent.screen.RawNode
import com.runasakura.phoneagent.screen.ScreenItem
import com.runasakura.phoneagent.screen.ScreenItems
import com.runasakura.phoneagent.util.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Real phone implementation of [Device]. Observation = accessibility tree (all app windows,
 * refreshed, polled) → falls back to screenshot + on-device OCR when the tree has < 3 texts
 * (WeChat ≥ 8.0.52 hides its tree from non-whitelisted services).
 * Create a fresh instance per task: it holds per-task caches only.
 */
class A11yDevice(
    private val s: AgentAccessibilityService,
    private val log: (String) -> Unit
) : Device {
    /** Packages that returned an empty tree in this task → poll shorter before OCR. */
    private val blindPkgs = HashSet<String>()
    private var lastShotAt = 0L

    // ---------- observation ----------

    private fun appWindows(): List<AccessibilityWindowInfo> = try {
        s.windows.orEmpty().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
    } catch (_: Exception) { emptyList() }

    fun foregroundPackage(): String {
        val ws = appWindows()
        val w = ws.firstOrNull { it.isActive || it.isFocused } ?: ws.firstOrNull()
        val p = try { w?.root?.packageName?.toString() } catch (_: Exception) { null }
        return p ?: s.foregroundPackage()
    }

    private fun imeTop(): Int? = try {
        s.windows.orEmpty().filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .map { Rect().also { r -> it.getBoundsInScreen(r) }.top }.minOrNull()
    } catch (_: Exception) { null }

    private fun toRaw(n: AccessibilityNodeInfo, depth: Int, budget: IntArray): RawNode {
        budget[0]--
        val r = Rect(); n.getBoundsInScreen(r)
        val kids = mutableListOf<RawNode>()
        if (depth < 45) for (i in 0 until n.childCount) {
            if (budget[0] <= 0) break
            val c = n.getChild(i) ?: continue
            if (!c.isVisibleToUser) continue
            kids += toRaw(c, depth + 1, budget)
        }
        return RawNode(
            n.text?.toString().orEmpty(), n.contentDescription?.toString().orEmpty(),
            n.viewIdResourceName.orEmpty().substringAfterLast('/'),
            r.left, r.top, r.right, r.bottom,
            n.isClickable || n.isLongClickable, n.isEditable, n.isScrollable, kids, n
        )
    }

    private fun treeScreen(): Screen {
        val (w, h) = s.screenSize()
        val ws = appWindows()
        val roots = ws.mapNotNull { win ->
            try { win.root?.also { it.refresh() } } catch (_: Exception) { null }
        }.ifEmpty { listOfNotNull(s.rootInActiveWindow) }
        val budget = intArrayOf(1500)
        val items = ScreenItems.leaves(roots.map { toRaw(it, 0, budget) }, w, h)
        return Screen(foregroundPackage(), w, h, items, if (items.isEmpty()) "none" else "tree", imeTop())
    }

    private fun dimen(name: String): Int {
        val id = s.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) s.resources.getDimensionPixelSize(id) else 0
    }

    private suspend fun ocrScreen(pkg: String): Screen? {
        val (w, h) = s.screenSize()
        val wait = 400 - (System.currentTimeMillis() - lastShotAt)
        if (wait > 0) delay(wait) // takeScreenshot is rate limited (~3/s)
        coroutineContext.ensureActive()
        var bmp = withContext(Dispatchers.IO) { s.takeScreenshotBitmap() }
        if (bmp == null) { delay(450); bmp = withContext(Dispatchers.IO) { s.takeScreenshotBitmap() } }
        lastShotAt = System.currentTimeMillis()
        if (bmp == null) { log("截图失败（系统拒绝或频率限制）"); return null }
        coroutineContext.ensureActive()
        // Crop status bar and gesture/nav bar (clock, battery, nav hints are noise)
        val top = dimen("status_bar_height").coerceIn(0, bmp.height / 10)
        val bottom = (bmp.height - dimen("navigation_bar_height").coerceIn(0, bmp.height / 10)).coerceAtLeast(top + 1)
        val crop = try { android.graphics.Bitmap.createBitmap(bmp, 0, top, bmp.width, bottom - top) } catch (_: Exception) { bmp }
        val off = if (crop === bmp) 0 else top
        // Screenshot may differ from logical screen size: scale back to screen coordinates
        val sx = w.toFloat() / bmp.width; val sy = h.toFloat() / bmp.height
        val lines = OcrEngine.recognizeLines(crop, off) ?: return null
        val scaled = lines.map {
            com.runasakura.phoneagent.ocr.OcrLine(it.text, (it.l * sx).toInt(), (it.t * sy).toInt(), (it.r * sx).toInt(), (it.b * sy).toInt())
        }
        val ignore = setOf(s.getString(com.runasakura.phoneagent.R.string.fab_stop))
        val items = com.runasakura.phoneagent.ocr.OcrPost.process(scaled, (top * sy).toInt(), (bottom * sy).toInt() + 1, ignore)
        return Screen(pkg, w, h, items, if (items.isEmpty()) "none" else "ocr", imeTop())
    }

    /** Settle (≤1.5s), then tree (poll ≤3s, ≤0.6s for known-blind apps), else OCR. */
    override suspend fun screen(): Screen {
        coroutineContext.ensureActive()
        s.awaitSettle()
        val start = System.currentTimeMillis()
        var t = treeScreen()
        val budget = if (t.pkg in blindPkgs) 600L else 3000L
        while (t.textCount < 3 && System.currentTimeMillis() - start < budget) {
            delay(300); t = treeScreen()
        }
        if (t.textCount >= 3) {
            Diagnostics.observe(t.pkg, "tree", t.textCount)
            return t
        }
        blindPkgs += t.pkg
        val o = ocrScreen(t.pkg)
        val best = if (o != null && o.textCount > t.textCount) o else t
        Diagnostics.observe(best.pkg, best.source + "(tree=${t.textCount})", best.textCount)
        if (best.source == "ocr") log("界面树仅 ${t.textCount} 项文字，已用 OCR：${best.textCount} 行")
        return best
    }

    // ---------- actions ----------

    /** Never let an injected tap land on our own emergency-stop button. */
    private fun avoidOverlay(x: Int, y: Int): Pair<Int, Int> {
        val r = com.runasakura.phoneagent.overlay.EmergencyStopOverlay.bounds ?: return x to y
        return if (r.contains(x, y)) (r.right + 12) to y else x to y
    }

    override suspend fun tap(item: ScreenItem): Boolean = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val n = item.ref as? AccessibilityNodeInfo
        val (x, y) = avoidOverlay(item.cx, item.cy)
        // Coordinates are what the user sees; prefer a real gesture (works for obfuscated trees too).
        s.tap(x, y) || (n != null && s.tapNode(n))
    }

    override suspend fun tapXY(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val (ax, ay) = avoidOverlay(x, y); s.tap(ax, ay)
    }

    override suspend fun type(text: String, hintX: Int?, hintY: Int?, attempt: Int): Boolean {
        coroutineContext.ensureActive()
        var node = s.focusedInput()
        if (node == null && hintX != null && hintY != null) {
            withContext(Dispatchers.IO) { s.tap(hintX, hintY) }
            afterAction(800)
            node = s.focusedInput()
        }
        suspend fun setText(): Boolean {
            val n = node ?: return false
            if (!n.isEditable || !s.setText(n, text)) return false
            delay(250); n.refresh()
            return Text.norm(n.text?.toString().orEmpty()) == Text.norm(text)
        }
        suspend fun paste(): Boolean {
            val n = node ?: s.focusedInput() ?: return false
            s.clearText(n)
            return s.pasteText(n, text).also { if (it) log("输入方式：剪贴板粘贴") }
        }
        fun ime(): Boolean = s.imeCommit(text).also { if (it) log("输入方式：无障碍输入法通道") }
        return when (attempt) {
            0 -> setText() || ime() || paste()
            1 -> paste() || ime()
            else -> ime() || paste()
        }
    }

    override suspend fun home() { s.home(); delay(150) }

    override suspend fun afterAction(maxMs: Long) {
        val t0 = System.currentTimeMillis()
        // wait for the first UI event caused by the action…
        while (s.lastEventAt < t0 && System.currentTimeMillis() - t0 < maxMs) delay(50)
        // …then for ~300ms of quiet (bounded by the remaining budget)
        val left = (maxMs - (System.currentTimeMillis() - t0)).coerceAtLeast(150)
        s.awaitSettle(300, left)
    }

    override suspend fun back() { s.back(); delay(150) }

    override suspend fun sleep(ms: Long) = delay(ms)

    override suspend fun scroll(forward: Boolean) {
        val (w, h) = s.screenSize()
        // stay well away from the bottom gesture-navigation strip and the top status bar
        val a = (h * 0.70).toInt(); val b = (h * 0.30).toInt()
        withContext(Dispatchers.IO) { if (forward) s.swipe(w / 2, a, w / 2, b, 350) else s.swipe(w / 2, b, w / 2, a, 350) }
        delay(500)
    }

    private suspend fun waitFg(pkg: String, ms: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < ms) {
            if (foregroundPackage() == pkg) return true
            delay(200)
        }
        return false
    }

    /** Always brings [pkg] to front (even if running in background), verifying via windows. */
    override suspend fun bringToFront(pkg: String, label: String): Boolean {
        if (foregroundPackage() == pkg) return true
        val pm = s.packageManager
        val launch = pm.getLaunchIntentForPackage(pkg)
        if (launch == null) { log("未安装或无启动入口：$pkg"); }
        // 1) launcher intent, bring existing task forward
        launch?.let {
            try {
                s.startActivity(Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                if (waitFg(pkg, 3500)) { log("启动方式：Intent"); return true }
            } catch (e: Exception) { Log.w(TAG, "start intent: ${e.message}") }
        }
        // 2) PendingIntent with explicit BAL opt-in (Android 14+ background-start rules)
        launch?.let {
            try {
                val pi = PendingIntent.getActivity(
                    s, pkg.hashCode(), Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                if (Build.VERSION.SDK_INT >= 34) {
                    val opts = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    pi.send(s, 0, null, null, null, null, opts.toBundle())
                } else pi.send()
                if (waitFg(pkg, 3500)) { log("启动方式：PendingIntent"); return true }
            } catch (e: Exception) { Log.w(TAG, "pending intent: ${e.message}") }
        }
        // 3) home screen + tap the icon by its label (tree or OCR), checking up to 4 launcher pages
        log("Intent 未能把「$label」带到前台，改为回桌面点击图标")
        s.home(); delay(1200)
        val (w, h) = s.screenSize()
        for (page in 0..4) {
            val sc = screen()
            val icon = sc.items.firstOrNull { Text.eq(it.label, label) && it.cy > h * 0.08 }
            if (icon != null) {
                tap(icon)
                if (waitFg(pkg, 4000)) { log("启动方式：桌面图标"); return true }
            }
            withContext(Dispatchers.IO) { s.swipe((w * 0.85).toInt(), h / 2, (w * 0.15).toInt(), h / 2, 250) }
            delay(700)
        }
        log("✗ 无法把「$label」带到前台")
        return false
    }

    companion object { private const val TAG = "A11yDevice" }
}

package com.runasakura.phoneagent.a11y

import com.runasakura.phoneagent.util.TaskTiming
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
private val BLIND: MutableSet<String> = java.util.Collections.synchronizedSet(hashSetOf("com.tencent.mm"))

class A11yDevice(
    private val s: AgentAccessibilityService,
    private val log: (String) -> Unit
) : Device {
    /** Packages that returned an empty tree in this task → poll shorter before OCR. */
    /** Process-wide: packages whose a11y tree is empty on this device (PPG-AN00 WeChat) — skip the 1.5 s tree poll. */
    private val blindPkgs get() = BLIND
    private var lastShotAt = 0L
    /** Cached OCR result until the accessibility event clock advances (screen change). */
    private var ocrCache: Screen? = null
    private var ocrCacheEventAt = -1L
    private var ocrCachePkg = ""
    /** Bumped on every mutating action so OCR cache cannot outlive a gesture WeChat didn't emit events for. */
    private var actionGen = 0
    private var ocrCacheGen = -1

    private fun invalidateOcr(reason: String = "") {
        if (ocrCache != null) {
            ocrCache = null; ocrCacheGen = -1
            if (reason.isNotBlank()) Log.d(TAG, "OCR cache invalidated: $reason")
        }
        actionGen++
    }

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

    /** Wall-clock deadline for one tree walk: each getChild is an IPC; a stale/blocked window can cost ~25ms each. */
    private var walkDeadline = 0L

    private fun toRaw(n: AccessibilityNodeInfo, depth: Int, budget: IntArray): RawNode {
        budget[0]--
        if (System.currentTimeMillis() > walkDeadline) budget[0] = 0
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
        // Split-screen: only read the focused/active application window (avoid mixing both apps)
        val preferred = ws.firstOrNull { it.isFocused } ?: ws.firstOrNull { it.isActive }
        val pkgs = ws.mapNotNull { try { it.root?.packageName?.toString() } catch (_: Exception) { null } }.toSet()
        val use = if (pkgs.size > 1 && preferred != null) listOf(preferred) else ws
        val fg = foregroundPackage()
        // Only trust roots that belong to the foreground app. WeChat ≥8.0.52 returns no root for
        // its own window, and a leftover launcher window would otherwise be read as "WeChat"
        // (PPG-AN00 self-test: 「活动日报 | Chrome | Google」 reported as WeChat tree).
        val roots = TreeRoots.select(fg, use.mapNotNull { win ->
            try { win.root?.also { it.refresh() } } catch (_: Exception) { null }
        }.ifEmpty { listOfNotNull(s.rootInActiveWindow) }) { it.packageName?.toString() }
        val budget = intArrayOf(1500)
        walkDeadline = System.currentTimeMillis() + 1500
        val items = ScreenItems.leaves(roots.map { toRaw(it, 0, budget) }, w, h)
        return Screen(fg, w, h, items, if (items.isEmpty()) "none" else "tree", imeTop())
    }

    private fun dimen(name: String): Int {
        val id = s.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) s.resources.getDimensionPixelSize(id) else 0
    }

    /**
     * Honor PPG-AN00 (1264x2800) gesture nav: navigation_bar_height is often 0.
     * Soft nav bar ~48–56dp; gesture home indicator ~16–24dp of chrome but swipe zone taller.
     * Use dimen when present, else ~3.2% top / 2.8% bottom of the bitmap (≈90 / 78 px at 2800).
     */
    private fun cropBands(bmpH: Int): Pair<Int, Int> =
        com.runasakura.phoneagent.ocr.CropBands.compute(bmpH, dimen("status_bar_height"), dimen("navigation_bar_height"))

    private suspend fun ocrScreen(pkg: String): Screen? {
        // Reuse OCR until a new accessibility event (UI change) arrives
        if (ocrCache != null && ocrCachePkg == pkg &&
            ocrCacheEventAt == s.lastEventAt && ocrCacheGen == actionGen
        ) {
            log("OCR 缓存命中（界面未变）")
            return ocrCache
        }
        val (w, h) = s.screenSize()
        val wait = 340 - (System.currentTimeMillis() - lastShotAt)
        if (wait > 0) delay(wait)
        coroutineContext.ensureActive()
        val t0 = System.currentTimeMillis()
        var bmp = withContext(Dispatchers.IO) { s.takeScreenshotBitmap() }
        if (bmp == null) { delay(450); bmp = withContext(Dispatchers.IO) { s.takeScreenshotBitmap() } }
        lastShotAt = System.currentTimeMillis()
        if (bmp == null) { log("截图失败（系统拒绝或频率限制）"); return null }
        coroutineContext.ensureActive()
        val (top, bottom) = cropBands(bmp.height)
        val crop = try { android.graphics.Bitmap.createBitmap(bmp, 0, top, bmp.width, bottom - top) } catch (_: Exception) { bmp }
        val off = if (crop === bmp) 0 else top
        // Half resolution for Chinese OCR speed (scale coords back). Cap long side at 720.
        val longSide = maxOf(crop.width, crop.height)
        val scaleDown = if (longSide > 960) 960f / longSide else 1f
        val small = if (scaleDown < 0.99f) {
            android.graphics.Bitmap.createScaledBitmap(
                crop, (crop.width * scaleDown).toInt().coerceAtLeast(1),
                (crop.height * scaleDown).toInt().coerceAtLeast(1), true
            )
        } else crop
        // Mild contrast stretch helps dark-mode WeChat (pure Kotlin, no ColorMatrix GPU needed)
        val ready = enhanceContrast(small)
        val lines = try { TaskTiming.time("OCR识别") { OcrEngine.recognizeLines(ready, 0) } } finally {
            // Free intermediates promptly (each full-res ARGB frame is ~14 MB on PPG-AN00).
            for (b in listOf(ready, small, crop)) if (b !== bmp && !b.isRecycled) b.recycle()
        }
        val bw = bmp.width; val bh = bmp.height; val swd = small.width; val sht = small.height
        if (lines == null) { bmp.recycle(); return null }
        val inv = if (scaleDown < 0.99f) 1f / scaleDown else 1f
        val globalBmp = lines.map {
            com.runasakura.phoneagent.ocr.OcrLine(it.text, (it.l * inv).toInt(), (it.t * inv).toInt() + off,
                (it.r * inv).toInt(), (it.b * inv).toInt() + off)
        }
        // Detail bands are NOT run here (speed): flows call refineScreen() only when a key target is missing.
        keepShot(bmp, globalBmp, top, bottom, pkg)
        val detail = emptyMap<Pair<Int, Int>, List<com.runasakura.phoneagent.ocr.OcrLine>>()
        val sx = w.toFloat() / bw
        val sy = h.toFloat() / bh
        val scaled = globalBmp.map {
            com.runasakura.phoneagent.ocr.OcrLine(it.text, (it.l * sx).toInt(), (it.t * sy).toInt(), (it.r * sx).toInt(), (it.b * sy).toInt())
        }
        val ignore = setOf(s.getString(com.runasakura.phoneagent.R.string.fab_stop))
        val items = com.runasakura.phoneagent.ocr.OcrPost.process(
            scaled, (top * sy).toInt(), (bottom * sy).toInt() + 1, ignore
        )
        val ms = System.currentTimeMillis() - t0
        log("OCR ${ms}ms（截图${bw}x${bh}→识别${swd}x${sht}）文字=${items.size}")
        val out = Screen(pkg, w, h, items, if (items.isEmpty()) "none" else "ocr", imeTop())
        ocrCache = out; ocrCacheEventAt = s.lastEventAt; ocrCachePkg = pkg; ocrCacheGen = actionGen
        return out
    }

    // Last screenshot kept (one frame) so refineScreen() can re-OCR bands without a new capture.
    private var shotBmp: android.graphics.Bitmap? = null
    private var shotLines: List<com.runasakura.phoneagent.ocr.OcrLine> = emptyList()
    private var shotTop = 0; private var shotBottom = 0; private var shotPkg = ""
    private var shotGen = -1; private var shotEvent = -1L
    private var refined: Screen? = null

    private fun keepShot(bmp: android.graphics.Bitmap, lines: List<com.runasakura.phoneagent.ocr.OcrLine>, top: Int, bottom: Int, pkg: String) {
        shotBmp?.let { if (it !== bmp && !it.isRecycled) it.recycle() }
        shotBmp = bmp; shotLines = lines; shotTop = top; shotBottom = bottom; shotPkg = pkg
        shotGen = actionGen; shotEvent = s.lastEventAt; refined = null
    }

    /**
     * Near-native re-OCR of the title bar + bottom area of the CURRENT frame, merged into the global pass.
     * Only called by flows when a key target (call entry, sheet option, 发送, +, tabs…) was not found.
     */
    override suspend fun refineScreen(): Screen {
        val cur = screen()
        if (cur.source != "ocr") return cur
        refined?.let { if (shotGen == actionGen && shotEvent == s.lastEventAt) return it }
        val bmp = shotBmp
        if (bmp == null || bmp.isRecycled || shotGen != actionGen || shotEvent != s.lastEventAt) return cur
        val t0 = System.currentTimeMillis()
        val detail = LinkedHashMap<Pair<Int, Int>, List<com.runasakura.phoneagent.ocr.OcrLine>>()
        for (band in com.runasakura.phoneagent.ocr.OcrBands.detailBands(shotTop, shotBottom)) {
            coroutineContext.ensureActive()
            detail[band] = ocrRegion(bmp, band.first, band.second) ?: emptyList()
        }
        val merged = com.runasakura.phoneagent.ocr.OcrBands.merge(shotLines, detail)
        val (w, h) = s.screenSize()
        val sx = w.toFloat() / bmp.width; val sy = h.toFloat() / bmp.height
        val scaled = merged.map { com.runasakura.phoneagent.ocr.OcrLine(it.text, (it.l * sx).toInt(), (it.t * sy).toInt(), (it.r * sx).toInt(), (it.b * sy).toInt()) }
        val ignore = setOf(s.getString(com.runasakura.phoneagent.R.string.fab_stop))
        val items = com.runasakura.phoneagent.ocr.OcrPost.process(scaled, (shotTop * sy).toInt(), (shotBottom * sy).toInt() + 1, ignore)
        log("OCR 细节区重识别 ${System.currentTimeMillis() - t0}ms 文字=${items.size}")
        val out = Screen(shotPkg, w, h, items, "ocr", imeTop())
        refined = out; ocrCache = out; lastScreen = out
        return out
    }

    /** OCR one horizontal band of [bmp] at ≤1280px long side; returns lines in bitmap coords. */
    private suspend fun ocrRegion(bmp: android.graphics.Bitmap, y0: Int, y1: Int): List<com.runasakura.phoneagent.ocr.OcrLine>? {
        val a = y0.coerceIn(0, bmp.height - 1); val b = y1.coerceIn(a + 1, bmp.height)
        val band = try { android.graphics.Bitmap.createBitmap(bmp, 0, a, bmp.width, b - a) } catch (_: Exception) { return null }
        val long = maxOf(band.width, band.height)
        val k = if (long > 1280) 1280f / long else 1f
        val sc = if (k < 0.99f) android.graphics.Bitmap.createScaledBitmap(band, (band.width * k).toInt().coerceAtLeast(1),
            (band.height * k).toInt().coerceAtLeast(1), true) else band
        val ready = enhanceContrast(sc)
        val res = try { TaskTiming.time("OCR细节区") { OcrEngine.recognizeLines(ready, 0) } } finally {
            for (x in listOf(ready, sc, band)) if (x !== bmp && !x.isRecycled) x.recycle()
        } ?: return null
        val inv = 1f / k
        return res.map { com.runasakura.phoneagent.ocr.OcrLine(it.text, (it.l * inv).toInt(), (it.t * inv).toInt() + a,
            (it.r * inv).toInt(), (it.b * inv).toInt() + a) }
    }

    /** Linear contrast stretch on luminance — helps dark-mode UI without changing hue much. */
    private fun enhanceContrast(src: android.graphics.Bitmap): android.graphics.Bitmap {
        if (src.config == null) return src
        val w = src.width; val h = src.height
        // Sample a grid to find lo/hi (full scan is too slow)
        var lo = 255; var hi = 0
        val stepX = (w / 32).coerceAtLeast(1); val stepY = (h / 32).coerceAtLeast(1)
        val px = IntArray(1)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                src.getPixels(px, 0, 1, x, y, 1, 1)
                val c = px[0]
                val l = ((c shr 16 and 0xff) * 30 + (c shr 8 and 0xff) * 59 + (c and 0xff) * 11) / 100
                if (l < lo) lo = l; if (l > hi) hi = l
                x += stepX
            }
            y += stepY
        }
        val mid = (lo + hi) / 2
        if (hi - lo < 40 || mid > 110) return src // flat or already bright (light theme)
        val out = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true) ?: return src
        val row = IntArray(w)
        for (yy in 0 until h) {
            out.getPixels(row, 0, w, 0, yy, w, 1)
            for (i in row.indices) {
                val c = row[i]
                val a = c ushr 24
                val r = c shr 16 and 0xff; val g = c shr 8 and 0xff; val b = c and 0xff
                fun stretch(v: Int) = ((v - lo) * 255 / (hi - lo)).coerceIn(0, 255)
                row[i] = (a shl 24) or (stretch(r) shl 16) or (stretch(g) shl 8) or stretch(b)
            }
            out.setPixels(row, 0, w, 0, yy, w, 1)
        }
        return out
    }

    /** Settle (≤1.5s), then tree (poll ≤3s, ≤0.6s for known-blind apps), else OCR. */
    private var lastScreen: Screen? = null
    private var lastScreenEvent = -1L
    private var lastScreenGen = -1
    private var lastScreenAt = 0L

    override suspend fun screen(): Screen {
        coroutineContext.ensureActive()
        // Fast path: no action and no UI event since the last read → same screen; skip settle + re-read.
        lastScreen?.let { ls ->
            if (lastScreenGen == actionGen && lastScreenEvent == s.lastEventAt &&
                System.currentTimeMillis() - lastScreenAt < 3000) { TaskTiming.count("读屏复用"); return ls }
        }
        val r = TaskTiming.time("读屏") { screenFresh() }
        lastScreen = r; lastScreenEvent = s.lastEventAt; lastScreenGen = actionGen; lastScreenAt = System.currentTimeMillis()
        return r
    }

    private suspend fun screenFresh(): Screen {
        TaskTiming.time("等待界面稳定") { s.awaitSettle(300, 1200) }
        val start = System.currentTimeMillis()
        var t = treeScreen()
        // Known-blind apps skip polling; others poll ≤0.9 s (a fresh launch rarely builds its tree later than that).
        val budget = if (t.pkg in blindPkgs) 0L else 900L
        while (t.textCount < 3 && System.currentTimeMillis() - start < budget) {
            delay(300); t = treeScreen()
        }
        if (t.textCount >= 3) {
            val ms = System.currentTimeMillis() - start
            Diagnostics.observe(t.pkg, "tree", t.textCount)
            log("读屏 ${ms}ms 来源=tree 文字=${t.textCount}")
            return t
        }
        blindPkgs += t.pkg
        val o = ocrScreen(t.pkg)
        val best = if (o != null && o.textCount > t.textCount) o else t
        val ms = System.currentTimeMillis() - start
        Diagnostics.observe(best.pkg, best.source + "(tree=${t.textCount})", best.textCount)
        log("读屏 ${ms}ms 来源=${best.source} 文字=${best.textCount}" +
            if (best.source == "ocr") "（树仅 ${t.textCount}）" else "")
        return best
    }

    // ---------- actions ----------

    /** Never let an injected tap land on our own emergency-stop button. */
    private fun avoidOverlay(x: Int, y: Int): Pair<Int, Int> {
        val r = com.runasakura.phoneagent.overlay.EmergencyStopOverlay.bounds ?: return x to y
        return if (r.contains(x, y)) (r.right + 12) to y else x to y
    }

    override suspend fun tap(item: ScreenItem): Boolean = withContext(Dispatchers.IO) {
        invalidateOcr("tap")
        coroutineContext.ensureActive()
        val n = item.ref as? AccessibilityNodeInfo
        val (x, y) = avoidOverlay(item.cx, item.cy)
        // Coordinates are what the user sees; prefer a real gesture (works for obfuscated trees too).
        s.tap(x, y) || (n != null && s.tapNode(n))
    }

    override fun alive(): Boolean = AgentAccessibilityService.require() === s

    override suspend fun longPress(item: ScreenItem): Boolean = withContext(Dispatchers.IO) {
        invalidateOcr("longPress")
        coroutineContext.ensureActive()
        val (x, y) = avoidOverlay(item.cx, item.cy)
        s.longPress(x, y)
    }

    override suspend fun tapXY(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        invalidateOcr("tapXY")
        coroutineContext.ensureActive()
        val (ax, ay) = avoidOverlay(x, y); s.tap(ax, ay)
    }

    override suspend fun type(text: String, hintX: Int?, hintY: Int?, attempt: Int): Boolean {
        invalidateOcr("type")
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
            val n = node ?: s.focusedInput()
            if (n != null && s.pasteText(n, text)) { log("输入方式：剪贴板粘贴（节点）"); return true }
            return s.imePaste(text).also { if (it) log("输入方式：剪贴板粘贴（输入法通道）") }
        }
        fun ime(): Boolean = s.imeCommit(text).also { if (it) log("输入方式：无障碍输入法通道 commitText") }
        // Each attempt uses ONE method (callers clear the field between attempts, so a method
        // that "silently worked" can never stack duplicates like 八嘎八嘎八嘎).
        return when (attempt) {
            0 -> setText().also { if (it) log("输入方式：SET_TEXT") } || ime()
            1 -> paste()
            else -> setText().also { if (it) log("输入方式：SET_TEXT") } || ime()
        }
    }

    override suspend fun fieldText(): String? =
        s.imeFieldText() ?: s.focusedInput()?.let { n -> n.refresh(); n.text?.toString() }

    override suspend fun clearField(): Boolean {
        invalidateOcr("clear")
        val n = s.focusedInput()
        val ok = (n != null && s.clearText(n)) || s.imeClear()
        delay(200)
        return ok
    }

    override suspend fun home() { invalidateOcr("home"); s.home(); delay(150) }

    override suspend fun afterAction(maxMs: Long) = TaskTiming.time("动作后等待") { afterActionInner(maxMs) }

    private suspend fun afterActionInner(maxMs: Long) {
        val t0 = System.currentTimeMillis()
        // wait for the first UI event caused by the action…
        while (s.lastEventAt < t0 && System.currentTimeMillis() - t0 < maxMs) delay(50)
        // …then for ~300ms of quiet (bounded by the remaining budget)
        val left = (maxMs - (System.currentTimeMillis() - t0)).coerceAtLeast(150)
        s.awaitSettle(250, left)
    }

    override suspend fun back() { invalidateOcr("back"); s.back(); delay(150) }

    override suspend fun sleep(ms: Long) { lastScreen = null; delay(ms) }
    override suspend fun invalidateObservation() { invalidateOcr("external") }

    override suspend fun scroll(forward: Boolean) {
        invalidateOcr("scroll")
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
    override suspend fun bringToFront(pkg: String, label: String, force: Boolean): Boolean {
        invalidateOcr("bringToFront")
        if (!force && foregroundPackage() == pkg) {
            log("已在前台：$pkg")
            return true
        }
        if (force) log("强制重新打开 $label（重置任务栈）")
        val pm = s.packageManager
        val launch = pm.getLaunchIntentForPackage(pkg)
        if (launch == null) { log("未安装或无启动入口：$pkg"); }
        // 1) launcher intent, bring existing task forward
        launch?.let {
            try {
                var flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                if (force) flags = flags or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                s.startActivity(Intent(it).addFlags(flags))
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

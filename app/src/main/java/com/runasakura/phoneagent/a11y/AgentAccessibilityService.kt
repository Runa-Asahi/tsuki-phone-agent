package com.runasakura.phoneagent.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.runasakura.phoneagent.model.UiSnapshot
import com.runasakura.phoneagent.screen.RawNode
import com.runasakura.phoneagent.screen.ScreenItem
import com.runasakura.phoneagent.screen.ScreenItems
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * Hands of the phone agent: dump UI tree + perform gestures / global actions.
 * Optimized for Honor MagicOS 10 / PPG-AN00 (2800x1264).
 */
class AgentAccessibilityService : AccessibilityService() {

    @Volatile
    var lastPackage: String = ""
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityService connected")
        sendBroadcast(Intent(ACTION_A11Y_STATE).setPackage(packageName).putExtra(EXTRA_CONNECTED, true))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        event.packageName?.toString()?.let { lastPackage = it }
        lastEventAt = System.currentTimeMillis()
    }

    @Volatile
    var lastEventAt: Long = 0L
        private set

    @Volatile
    var lastItems: List<ScreenItem> = emptyList()

    /** Roots of all interactive windows; application windows first, then the active one. */
    fun windowRoots(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        try {
            val ws = windows.orEmpty().sortedBy { if (it.type == AccessibilityWindowInfo.TYPE_APPLICATION) 0 else 1 }
            for (w in ws) {
                if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                    w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                val r = w.root ?: continue
                if (r.packageName?.toString()?.startsWith("com.runasakura.phoneagent") == true &&
                    w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue // our overlay
                out += r
            }
        } catch (e: Exception) {
            Log.w(TAG, "windows() failed: ${e.message}")
        }
        if (out.isEmpty()) rootInActiveWindow?.let { out += it }
        return out
    }

    private fun toRaw(n: AccessibilityNodeInfo, depth: Int, budget: IntArray): RawNode {
        budget[0]--
        val r = Rect(); n.getBoundsInScreen(r)
        val kids = mutableListOf<RawNode>()
        if (depth < 35) {
            for (i in 0 until n.childCount) {
                if (budget[0] <= 0) break
                val c = n.getChild(i) ?: continue
                if (!c.isVisibleToUser) continue
                kids += toRaw(c, depth + 1, budget)
            }
        }
        return RawNode(
            text = n.text?.toString().orEmpty(),
            desc = n.contentDescription?.toString().orEmpty(),
            viewId = n.viewIdResourceName.orEmpty().substringAfterLast('/'),
            l = r.left, t = r.top, r = r.right, b = r.bottom,
            clickable = n.isClickable || n.isLongClickable,
            editable = n.isEditable,
            scrollable = n.isScrollable,
            children = kids,
            ref = n
        )
    }

    fun screenItems(): List<ScreenItem> {
        val (w, h) = screenSize()
        val budget = intArrayOf(600)
        val raws = windowRoots().map { toRaw(it, 0, budget) }
        return ScreenItems.build(raws, w, h).also { lastItems = it }
    }

    /** Find a visible on-screen node whose label contains [text] (exact match preferred). */
    fun findVisibleByText(text: String, allowEditable: Boolean = false): AccessibilityNodeInfo? {
        val items = screenItems()
        val cands = items.filter { it.label.contains(text, true) && (allowEditable || !it.edit) }
        val best = cands.minByOrNull { if (it.label == text) 0 else it.label.length }
        return best?.ref as? AccessibilityNodeInfo
    }

    fun firstVisibleEditable(): AccessibilityNodeInfo? =
        screenItems().firstOrNull { it.edit }?.ref as? AccessibilityNodeInfo

    /** JPEG base64 of the screen, downscaled (for VL models). */
    fun screenshotJpegBase64(maxSide: Int = 896, marks: List<com.runasakura.phoneagent.screen.ScreenItem>? = null, screenW: Int = 0, screenH: Int = 0): String? {
        val bmp = takeScreenshotBitmap() ?: return null
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        var scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        if (!marks.isNullOrEmpty() && screenW > 0) runCatching {
            // Set-of-Mark: draw numbered boxes matching the e-ids of the text list.
            val m = scaled.copy(Bitmap.Config.ARGB_8888, true)
            val c = android.graphics.Canvas(m)
            // Thin outline + translucent tag placed OUTSIDE (above) the box so the element itself stays visible.
            val stroke = android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.5f; color = 0xCCFF2D55.toInt() }
            val tag = android.graphics.Paint().apply { color = 0xB3FF2D55.toInt() }
            val txt = android.graphics.Paint().apply { color = 0xFFFFFFFF.toInt(); textSize = maxOf(12f, m.width / 70f); isFakeBoldText = true; isAntiAlias = true }
            com.runasakura.phoneagent.agent.SetOfMark.boxes(marks, screenW, screenH, m.width, m.height).forEach { b ->
                c.drawRect(b.l.toFloat(), b.t.toFloat(), b.r.toFloat(), b.b.toFloat(), stroke)
                val w = txt.measureText(b.id) + 6
                val th = txt.textSize + 4
                val top = if (b.t - th >= 0) b.t - th else b.b.toFloat() // above the box, else below it
                c.drawRect(b.l.toFloat(), top, b.l + w, top + th, tag)
                c.drawText(b.id, b.l + 3f, top + txt.textSize, txt)
            }
            scaled = m
        }
        val os = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, os)
        return android.util.Base64.encodeToString(os.toByteArray(), android.util.Base64.NO_WRAP)
    }

    /** Fallback text entry: clipboard + ACTION_PASTE on the node. */
    fun pasteText(node: AccessibilityNodeInfo, text: String): Boolean = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("agent", text))
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    } catch (e: Exception) {
        Log.w(TAG, "paste failed: ${e.message}"); false
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        sendBroadcast(Intent(ACTION_A11Y_STATE).setPackage(packageName).putExtra(EXTRA_CONNECTED, false))
        super.onDestroy()
    }

    fun isReady(): Boolean = true

    /** Wait until no accessibility events for [quietMs] (max [maxMs]). */
    /** Returns true when the UI went quiet, false when it was still animating at [maxMs]. */
    suspend fun awaitSettle(quietMs: Long = 400, maxMs: Long = 1500): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxMs) {
            if (System.currentTimeMillis() - lastEventAt >= quietMs) return true
            kotlinx.coroutines.delay(50)
        }
        return false
    }

    /** Clear per-task caches so a new task never reuses stale nodes/ids/packages. */
    fun resetTaskState() {
        lastItems = emptyList()
        lastPackage = ""
    }

    /**
     * Send-confirmation drawn by the service itself as TYPE_ACCESSIBILITY_OVERLAY: needs no
     * SYSTEM_ALERT_WINDOW and is not subject to background-activity-start rules, so it reliably
     * appears above WeChat/QQ. NOT_FOCUSABLE keeps the chat's keyboard/input untouched.
     * Returns null if the window could not be added (caller falls back to an Activity).
     */
    suspend fun confirmOverlay(message: String): Boolean? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val main = Handler(Looper.getMainLooper())
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        var view: android.view.View? = null
        val answered = AtomicBoolean(false)
        fun remove() { view?.let { v -> main.post { try { wm.removeView(v) } catch (_: Exception) {} } }; view = null }
        fun finish(ok: Boolean?) {
            if (!answered.compareAndSet(false, true)) return
            activeConfirm = null
            remove()
            if (cont.isActive) cont.resume(ok) {}
        }
        main.post {
            try {
                val d = resources.displayMetrics.density
                val box = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (12 * d).toInt())
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.WHITE); cornerRadius = 16 * d
                    }
                }
                box.addView(android.widget.TextView(this).apply {
                    text = getString(com.runasakura.phoneagent.R.string.confirm_title)
                    textSize = 18f; setTextColor(android.graphics.Color.BLACK)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                box.addView(android.widget.TextView(this).apply {
                    text = message; textSize = 15f; setTextColor(android.graphics.Color.DKGRAY)
                    setPadding(0, (10 * d).toInt(), 0, (14 * d).toInt())
                })
                val row = android.widget.LinearLayout(this).apply { gravity = android.view.Gravity.END }
                fun btn(label: Int, ok: Boolean) = android.widget.Button(this).apply {
                    text = getString(label)
                    setOnClickListener { finish(ok) }
                }
                row.addView(btn(com.runasakura.phoneagent.R.string.confirm_no, false))
                row.addView(btn(com.runasakura.phoneagent.R.string.confirm_yes, true))
                box.addView(row)
                val lp = android.view.WindowManager.LayoutParams(
                    (resources.displayMetrics.widthPixels * 0.88).toInt(),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply { gravity = android.view.Gravity.CENTER; dimAmount = 0.5f }
                try {
                    wm.addView(box, lp)
                } catch (e: Exception) {
                    if (!android.provider.Settings.canDrawOverlays(this)) throw e
                    Log.w(TAG, "a11y overlay failed (${e.message}), using TYPE_APPLICATION_OVERLAY")
                    lp.type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    wm.addView(box, lp)
                }
                view = box
            } catch (e: Exception) {
                Log.w(TAG, "confirm overlay failed: ${e.message}")
                finish(null)
            }
        }
        activeConfirm = { ok -> finish(ok) }
        cont.invokeOnCancellation { answered.set(true); activeConfirm = null; remove() }
    }

    /** Self-test helper: can we add/remove an accessibility overlay window? */
    fun overlayWorks(): Boolean = overlayProbe().first

    /**
     * (ok, detail). Previously posted to the main looper and then blocked the caller with a
     * latch — when the self-test ran on the main thread this deadlocked for 2s and always
     * reported FAIL. Now runs inline on the main thread and reports the real exception.
     */
    fun overlayProbe(): Pair<Boolean, String> {
        val latch = CountDownLatch(1); val ok = AtomicBoolean(false)
        val err = java.util.concurrent.atomic.AtomicReference("超时")
        val body = Runnable {
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                val v = android.view.View(this)
                wm.addView(v, android.view.WindowManager.LayoutParams(1, 1,
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    android.graphics.PixelFormat.TRANSLUCENT))
                wm.removeView(v); ok.set(true); err.set("TYPE_ACCESSIBILITY_OVERLAY 可用")
            } catch (e: Throwable) { err.set("${e.javaClass.simpleName}: ${e.message}") } finally { latch.countDown() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) body.run()
        else { Handler(Looper.getMainLooper()).post(body); latch.await(2, TimeUnit.SECONDS) }
        return ok.get() to err.get()
    }

    fun imeChannelEnabled(): Boolean = Build.VERSION.SDK_INT >= 33 &&
        (serviceInfo?.flags ?: 0) and android.accessibilityservice.AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR != 0

    fun focusedInput(): AccessibilityNodeInfo? = try {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
    } catch (_: Exception) { null }

    /**
     * Type through the accessibility input-method channel (API 33+, flagInputMethodEditor).
     * Works even when the app hides its node tree, as long as a text field has input focus.
     */
    /** Full text of the focused field via the a11y IME connection (works with WeChat's hidden tree). */
    fun imeFieldText(): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        return try {
            val ic = inputMethod?.currentInputConnection ?: return null
            val st = ic.getSurroundingText(4000, 4000, 0) ?: return null
            st.text?.toString()
        } catch (e: Exception) { Log.w(TAG, "imeFieldText failed: ${e.message}"); null }
    }

    /** Clear the focused field through the IME connection: delete everything around the cursor. */
    fun imeClear(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            val ic = inputMethod?.currentInputConnection ?: return false
            ic.performContextMenuAction(android.R.id.selectAll)
            ic.commitText("", 1, null)
            ic.deleteSurroundingText(4000, 4000)
            true
        } catch (e: Exception) { Log.w(TAG, "imeClear failed: ${e.message}"); false }
    }

    /** Paste [text] through the IME connection (clipboard + context-menu paste). */
    fun imePaste(text: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            val ic = inputMethod?.currentInputConnection ?: return false
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("agent", text))
            ic.performContextMenuAction(android.R.id.paste); true
        } catch (e: Exception) { Log.w(TAG, "imePaste failed: ${e.message}"); false }
    }

    fun imeCommit(text: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            val ic = inputMethod?.currentInputConnection ?: return false
            ic.commitText(text, 1, null)
            true
        } catch (e: Exception) {
            Log.w(TAG, "imeCommit failed: ${e.message}"); false
        }
    }

    /** Current foreground package (active window root, else last event). */
    fun foregroundPackage(): String {
        try {
            val ws = windows.orEmpty().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val w = ws.firstOrNull { it.isActive || it.isFocused } ?: ws.firstOrNull()
            w?.root?.packageName?.toString()?.let { return it }
        } catch (_: Exception) {
        }
        return try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
            ?: lastPackage.ifEmpty { "unknown" }
    }

    fun dumpUiTree(@Suppress("UNUSED_PARAMETER") maxNodes: Int = 120): UiSnapshot {
        val (w, h) = screenSize()
        val items = try { screenItems() } catch (e: Exception) { emptyList() }
        val pkg = foregroundPackage()
        val text = items.joinToString("\n") { it.format() }
        return UiSnapshot(
            packageName = pkg,
            screenWidth = w,
            screenHeight = h,
            tree = null,
            compressedText = text.ifBlank { "(空)" },
            nodeCount = items.size,
            lowConfidence = ScreenItems.textCount(items) < 3
        )
    }

    /**
     * Compact dump for small/VL models: only interactive / labeled nodes, hard char cap.
     */
    fun dumpInteractiveCompact(@Suppress("UNUSED_PARAMETER") maxLines: Int = 40, @Suppress("UNUSED_PARAMETER") maxChars: Int = 1200): UiSnapshot = dumpUiTree()

    /** Generic launch of any installed app by package (MagicOS-friendly NEW_TASK + label fallback). */
    fun launchPackage(pkg: String, label: String? = null): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(i)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "launchPackage $pkg failed: ${e.message}")
            }
        }
        return label != null && launchByLabel(label)
    }

    fun launchWeChat(): Boolean {
        val pkg = WECHAT_PACKAGE
        // Prefer launcher intent; retry by label
        if (launchApp(pkg)) return true
        if (launchByLabel("微信")) return true
        if (launchByLabel("WeChat")) return true
        return try {
            val i = packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i != null) {
                startActivity(i)
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "launchWeChat failed", e)
            false
        }
    }

    fun isWeChatForeground(packageName: String = foregroundPackage()): Boolean =
        packageName == WECHAT_PACKAGE || packageName.startsWith("$WECHAT_PACKAGE.")

    fun findByContentDesc(desc: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findDesc(root, desc)
    }

    private fun findDesc(node: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        val d = node.contentDescription?.toString().orEmpty()
        if (d.contains(desc, ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            findDesc(c, desc)?.let { return it }
        }
        return null
    }


    fun findNodeById(id: String): AccessibilityNodeInfo? {
        if (id.startsWith("e")) {
            (lastItems.firstOrNull { it.id == id }?.ref as? AccessibilityNodeInfo)?.let { return it }
            return (screenItems().firstOrNull { it.id == id }?.ref as? AccessibilityNodeInfo)
        }
        val root = rootInActiveWindow ?: return null
        val counter = intArrayOf(0)
        return findBySyntheticId(root, id, counter)
    }

    private fun findBySyntheticId(
        node: AccessibilityNodeInfo,
        target: String,
        counter: IntArray
    ): AccessibilityNodeInfo? {
        val current = "n${counter[0]}"
        if (current == target) return node
        counter[0]++
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findBySyntheticId(child, target, counter)
            if (found != null) return found
        }
        return null
    }

    fun findByText(text: String): AccessibilityNodeInfo? {
        findVisibleByText(text, allowEditable = true)?.let { return it }
        val root = rootInActiveWindow ?: return null
        val list = root.findAccessibilityNodeInfosByText(text)
        return list?.firstOrNull { it.isClickable || it.isEditable }
            ?: list?.firstOrNull()
    }

    // Every injected gesture avoids our own floating stop button (a tap there used to cancel the task).
    fun tap(x: Int, y: Int): Boolean = clearOfOverlay(com.runasakura.phoneagent.overlay.OverlayAvoid.hits(x, y, overlayRect())) { gestureClick(x, y, 50) }

    fun longPress(x: Int, y: Int): Boolean = clearOfOverlay(com.runasakura.phoneagent.overlay.OverlayAvoid.hits(x, y, overlayRect())) { gestureClick(x, y, 600) }

    /** Exact coordinates; when the gesture would touch our stop button, the button steps aside for its duration. */
    private fun clearOfOverlay(hits: Boolean, g: () -> Boolean): Boolean {
        val ov = com.runasakura.phoneagent.overlay.EmergencyStopOverlay.active
        return if (hits && ov != null) ov.withPassThrough(g) else g()
    }

    private fun overlayRect(): IntArray? = com.runasakura.phoneagent.overlay.EmergencyStopOverlay.bounds?.let { intArrayOf(it.left, it.top, it.right, it.bottom) }

    fun doubleTap(x: Int, y: Int): Boolean {
        if (!gestureClick(x, y, 40)) return false
        Thread.sleep(80)
        return gestureClick(x, y, 40)
    }

    fun tapNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        var p = node.parent
        while (p != null) {
            if (p.isClickable) return p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            p = p.parent
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        return tap(r.centerX(), r.centerY())
    }

    fun longPressNode(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        return longPress(r.centerX(), r.centerY())
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean =
        clearOfOverlay(com.runasakura.phoneagent.overlay.OverlayAvoid.pathHits(x1, y1, x2, y2, overlayRect())) { swipeRaw(x1, y1, x2, y2, durationMs) }

    private fun swipeRaw(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50, 2000))
        return dispatchGestureSync(GestureDescription.Builder().addStroke(stroke).build())
    }

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        var target: AccessibilityNodeInfo? = node
        if (!node.isEditable) {
            target = null
            var p = node
            // try self then focusable editable under root
            if (node.isEditable) target = node
        }
        val editable = when {
            node.isEditable -> node
            else -> findFocusedEditable() ?: node
        }
        editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun clearText(node: AccessibilityNodeInfo?): Boolean {
        val editable = node ?: findFocusedEditable() ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun typeText(text: String, node: AccessibilityNodeInfo? = null): Boolean {
        val editable = when {
            node != null && node.isEditable -> node
            node != null -> {
                // climb for editable or click then find
                var p: AccessibilityNodeInfo? = node
                var found: AccessibilityNodeInfo? = null
                while (p != null) {
                    if (p.isEditable) { found = p; break }
                    p = p.parent
                }
                found ?: findFocusedEditable()
            }
            else -> findFocusedEditable()
        } ?: firstVisibleEditable() ?: return false
        if (setText(editable, text)) return true
        return pasteText(editable, text)
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findEditable(root)
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            findEditable(c)?.let { return it }
        }
        return null
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun global(id: Int): Boolean = performGlobalAction(id)

    /** ACTION_VIEW on a URI/deep link (optionally pinned to a package). */
    fun openUri(uri: String, pkg: String?): Boolean = try {
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!pkg.isNullOrBlank()) i.setPackage(pkg)
        startActivity(i); true
    } catch (_: Exception) { false }

    fun fireIntent(action: String, data: String?): Boolean = try {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!data.isNullOrBlank()) i.data = android.net.Uri.parse(data)
        startActivity(i); true
    } catch (_: Exception) { false }

    fun setClipboard(text: String): Boolean = try {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("agent", text)); true
    } catch (_: Exception) { false }

    /** Android 10+ only lets the focused app or IME read the clipboard; an a11y service usually gets null. */
    fun readClipboard(): String? = try {
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
    } catch (_: Exception) { null }

    /** IME "enter/search/go" on the input-focused field (API 30+). */
    fun imeEnter(): Boolean {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return if (android.os.Build.VERSION.SDK_INT >= 30)
            f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) else false
    }

    fun volume(name: String): Boolean = try {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val dir = when (name) { "volume_up" -> android.media.AudioManager.ADJUST_RAISE; "volume_down" -> android.media.AudioManager.ADJUST_LOWER
            "mute" -> android.media.AudioManager.ADJUST_MUTE; else -> android.media.AudioManager.ADJUST_UNMUTE }
        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, android.media.AudioManager.FLAG_SHOW_UI); true
    } catch (_: Exception) { false }

    /** True when an editable node holds input focus (verified, not guessed). */
    fun editableFocused(): Boolean = try {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true
    } catch (_: Exception) { false }

    fun launchApp(packageName: String): Boolean {
        // Honor/MagicOS: system Settings often needs ACTION_SETTINGS, not launcher intent
        if (isSettingsPackage(packageName) || packageName.contains("settings", ignoreCase = true)) {
            if (launchSystemSettings()) return true
        }
        val pm = packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                startActivity(intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "launchApp failed $packageName", e)
                false
            }
        }
        // Fallbacks for Honor settings package names
        for (pkg in SETTINGS_PACKAGES) {
            if (pkg == packageName) continue
            val i = pm.getLaunchIntentForPackage(pkg) ?: continue
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(i)
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    fun launchByLabel(appName: String): Boolean {
        if (appName.contains("设置") || appName.contains("settings", ignoreCase = true)) {
            if (launchSystemSettings()) return true
        }
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        // Prefer system settings over our own in-app "设置" menu label
        val match = apps.firstOrNull {
            val label = it.loadLabel(pm).toString()
            label.contains(appName, ignoreCase = true) &&
                !it.activityInfo.packageName.startsWith("com.runasakura.phoneagent")
        } ?: apps.firstOrNull {
            it.loadLabel(pm).toString().contains(appName, ignoreCase = true)
        } ?: return false
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(match.activityInfo.packageName, match.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "launchByLabel failed", e)
            false
        }
    }

    /** 时钟: standard AlarmClock intent (the clock app shows/creates it; SKIP_UI keeps it quick). */
    fun setAlarm(hour: Int, minute: Int, label: String): Boolean = try {
        startActivity(Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
            .putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
            .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { Log.w(TAG, "set alarm failed: ${e.message}"); false }

    /** Open a specific settings page by intent action (read-only navigation). */
    fun launchSettingsAction(action: String): Boolean = try {
        startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { Log.w(TAG, "settings action $action failed: ${e.message}"); false }

    fun launchSystemSettings(): Boolean {
        val attempts = listOf(
            Intent(Settings.ACTION_SETTINGS),
            Intent(Settings.ACTION_SETTINGS).setPackage("com.android.settings"),
            Intent(Settings.ACTION_SETTINGS).setPackage("com.hihonor.settings"),
            Intent(Settings.ACTION_SETTINGS).setPackage("com.huawei.systemmanager")
        )
        for (base in attempts) {
            try {
                val i = Intent(base).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                Log.i(TAG, "launchSystemSettings ok via ${base.action} pkg=${base.`package`}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "launchSystemSettings attempt failed: ${e.message}")
            }
        }
        for (pkg in SETTINGS_PACKAGES) {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                Log.i(TAG, "launchSystemSettings via launcher $pkg")
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    fun isSettingsForeground(packageName: String): Boolean {
        val p = packageName.lowercase()
        return isSettingsPackage(packageName) ||
            p.contains("settings") ||
            p == "com.android.settings" ||
            p.startsWith("com.hihonor") && p.contains("setting")
    }

    private fun isSettingsPackage(packageName: String): Boolean =
        SETTINGS_PACKAGES.any { it.equals(packageName, ignoreCase = true) }

    private val shotExecutor by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }

    fun takeScreenshotBitmap(timeoutMs: Long = 4000): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        val ref = AtomicReference<Bitmap?>()
        val main = Handler(Looper.getMainLooper())
        val executor = shotExecutor
        main.post {
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val hw = screenshot.hardwareBuffer
                                val color = screenshot.colorSpace
                                ref.set(Bitmap.wrapHardwareBuffer(hw, color)?.copy(Bitmap.Config.ARGB_8888, false))
                                hw.close()
                            } catch (e: Exception) {
                                Log.e(TAG, "screenshot wrap failed", e)
                            } finally {
                                latch.countDown()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "screenshot failed code=$errorCode")
                            latch.countDown()
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot error", e)
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return ref.get()
    }

    private fun gestureClick(x: Int, y: Int, durationMs: Long): Boolean {
        val (w, h) = screenSize()
        val cx = min(max(x, 1), w - 1)
        val cy = min(max(y, 1), h - 1)
        val path = Path().apply { moveTo(cx.toFloat(), cy.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGestureSync(GestureDescription.Builder().addStroke(stroke).build())
    }

    private fun dispatchGestureSync(gesture: GestureDescription, timeoutMs: Long = 3000): Boolean {
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val posted = AtomicBoolean(false)
        Handler(Looper.getMainLooper()).post {
            posted.set(true)
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok.set(true)
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    ok.set(false)
                    latch.countDown()
                }
            }, null)
        }
        // wait briefly for post
        var waited = 0L
        while (!posted.get() && waited < 500) {
            Thread.sleep(10)
            waited += 10
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return ok.get()
    }

    fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    fun centerOfBounds(bounds: String): Pair<Int, Int>? {
        val p = bounds.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (p.size != 4) return null
        return (p[0] + p[2]) / 2 to (p[1] + p[3]) / 2
    }

    companion object {
        /** Resolves the confirm overlay currently on screen (used by the inline chat card). */
        @Volatile var activeConfirm: ((Boolean) -> Unit)? = null
        /** Enabled in system settings (may be rebinding even if no instance is attached yet). */
        fun isEnabledInSettings(ctx: android.content.Context): Boolean = enabledIn(
            try { android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) } catch (_: Exception) { null },
            ctx.packageName
        )
        fun enabledIn(setting: String?, pkg: String): Boolean =
            setting?.split(':')?.any { it.substringBefore('/').equals(pkg, true) } == true
        private const val TAG = "AgentA11y"
        const val ACTION_A11Y_STATE = "com.runasakura.phoneagent.A11Y_STATE"
        const val EXTRA_CONNECTED = "connected"

        const val WECHAT_PACKAGE = "com.tencent.mm"

        val SETTINGS_PACKAGES = listOf(
            "com.android.settings",
            "com.hihonor.settings",
            "com.huawei.systemmanager",
            "com.hihonor.systemmanager"
        )

        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        fun require(): AgentAccessibilityService? = instance
    }
}

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
import com.runasakura.phoneagent.model.UiNode
import com.runasakura.phoneagent.model.UiSnapshot
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
    private var lastPackage: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityService connected")
        sendBroadcast(Intent(ACTION_A11Y_STATE).putExtra(EXTRA_CONNECTED, true))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        event.packageName?.toString()?.let { lastPackage = it }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        sendBroadcast(Intent(ACTION_A11Y_STATE).putExtra(EXTRA_CONNECTED, false))
        super.onDestroy()
    }

    fun isReady(): Boolean = true

    /** Current foreground package (active window root, else last event). */
    fun foregroundPackage(): String =
        try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
            ?: lastPackage.ifEmpty { "unknown" }

    fun dumpUiTree(maxNodes: Int = 120): UiSnapshot {
        val root = rootInActiveWindow
        val (w, h) = screenSize()
        if (root == null) {
            return UiSnapshot(
                packageName = lastPackage.ifEmpty { "unknown" },
                screenWidth = w,
                screenHeight = h,
                tree = null,
                compressedText = "(empty tree)",
                nodeCount = 0,
                lowConfidence = true
            )
        }
        val pkg = root.packageName?.toString() ?: lastPackage
        val counter = intArrayOf(0)
        val tree = buildNode(root, counter, maxNodes, depth = 0)
        val sb = StringBuilder()
        flatten(tree, sb, 0)
        val text = sb.toString().take(8000)
        val low = counter[0] < 3 || text.length < 20
        return UiSnapshot(
            packageName = pkg,
            screenWidth = w,
            screenHeight = h,
            tree = tree,
            compressedText = text,
            nodeCount = counter[0],
            lowConfidence = low
        )
    }

    /**
     * Compact dump for small/VL models: only interactive / labeled nodes, hard char cap.
     */
    fun dumpInteractiveCompact(maxLines: Int = 40, maxChars: Int = 1800): UiSnapshot {
        val full = dumpUiTree(maxNodes = 80)
        val lines = full.compressedText.lineSequence()
            .map { it.trimEnd() }
            .filter { line ->
                line.contains(" click") || line.contains(" edit") ||
                    line.contains(" scroll") ||
                    Regex("""\[[n\d]+\]\s+\S+""").containsMatchIn(line)
            }
            .filter { line ->
                // drop pure layout chrome without label
                val label = line.substringAfter("] ").substringBefore(" (")
                label.isNotBlank() && label !in listOf(
                    "FrameLayout", "LinearLayout", "ViewGroup", "View",
                    "RelativeLayout", "ContentFrameLayout", "action_bar_root", "content"
                )
            }
            .take(maxLines)
            .toList()
        val compact = lines.joinToString("\n").take(maxChars)
        return full.copy(
            compressedText = compact.ifBlank { full.compressedText.take(maxChars) },
            nodeCount = lines.size
        )
    }

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

    private fun buildNode(
        node: AccessibilityNodeInfo,
        counter: IntArray,
        maxNodes: Int,
        depth: Int
    ): UiNode? {
        if (counter[0] >= maxNodes || depth > 18) return null
        val id = "n${counter[0]}"
        counter[0]++
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val children = mutableListOf<UiNode>()
        for (i in 0 until node.childCount) {
            if (counter[0] >= maxNodes) break
            val child = node.getChild(i) ?: continue
            try {
                buildNode(child, counter, maxNodes, depth + 1)?.let { children.add(it) }
            } finally {
                // child recycled by framework on newer APIs; avoid double recycle
            }
        }
        return UiNode(
            id = id,
            text = node.text?.toString().orEmpty().take(80),
            contentDesc = node.contentDescription?.toString().orEmpty().take(80),
            className = node.className?.toString()?.substringAfterLast('.').orEmpty(),
            viewId = node.viewIdResourceName.orEmpty().substringAfterLast('/'),
            bounds = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
            clickable = node.isClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            checked = if (node.isCheckable) node.isChecked else null,
            children = children
        )
    }

    private fun flatten(node: UiNode?, sb: StringBuilder, depth: Int) {
        if (node == null) return
        val indent = "  ".repeat(depth)
        val label = listOf(node.text, node.contentDesc, node.viewId)
            .firstOrNull { it.isNotBlank() } ?: node.className
        val flags = buildString {
            if (node.clickable) append(" click")
            if (node.editable) append(" edit")
            if (node.scrollable) append(" scroll")
            node.checked?.let { append(if (it) " checked" else " unchecked") }
        }
        sb.append(indent).append("[").append(node.id).append("] ")
            .append(label).append(" (").append(node.bounds).append(")")
            .append(flags).append('\n')
        node.children.forEach { flatten(it, sb, depth + 1) }
    }

    fun findNodeById(id: String): AccessibilityNodeInfo? {
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
        val root = rootInActiveWindow ?: return null
        val list = root.findAccessibilityNodeInfosByText(text)
        return list?.firstOrNull { it.isClickable || it.isEditable }
            ?: list?.firstOrNull()
    }

    fun tap(x: Int, y: Int): Boolean = gestureClick(x, y, 50)

    fun longPress(x: Int, y: Int): Boolean = gestureClick(x, y, 600)

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

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
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
        } ?: return false
        return setText(editable, text)
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

    /** Preferred way to open system Settings on Honor MagicOS / Android. */
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

    fun takeScreenshotBitmap(timeoutMs: Long = 4000): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        val ref = AtomicReference<Bitmap?>()
        val main = Handler(Looper.getMainLooper())
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
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

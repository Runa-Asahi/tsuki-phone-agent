package com.runasakura.phoneagent.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.runasakura.phoneagent.R

class EmergencyStopOverlay(
    private val context: Context,
    private val onStop: () -> Unit,
    private val onPause: (() -> Boolean)? = null,
    private val onOpen: (() -> Unit)? = null,
) {
    private var orb: TsukiOrb.Orb? = null
    private var label: android.widget.TextView? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Codex-style live progress next to the stop button while operating other apps. */
    fun setProgress(text: String) {
        val t = text.replace('\n', ' ').take(28)
        main.post { val s = t.ifBlank { "运行中" }; orb?.setText(s) ?: label?.let { it.text = s } }
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var button: android.view.View? = null
    private var lp: WindowManager.LayoutParams? = null

    /**
     * Run [block] (an injected gesture) with the overlay invisible and untouchable, then restore it.
     * Taps keep their exact coordinates instead of being shifted next to the button.
     */
    fun <T> withPassThrough(block: () -> T): T {
        // Depth-counted: a nested gesture must not make the button reappear while the outer one is still running.
        if (passDepth.getAndIncrement() == 0) setPassThrough(true)
        try { return block() } finally { if (passDepth.decrementAndGet() == 0) setPassThrough(false) }
    }
    private val passDepth = java.util.concurrent.atomic.AtomicInteger(0)

    private fun setPassThrough(on: Boolean) {
        val v = button ?: return; val p = lp ?: return
        val apply = {
            try {
                p.flags = if (on) p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                v.alpha = if (on) 0f else if (orb != null) TsukiOrb.opacity(context) / 100f else 1f
                wm.updateViewLayout(v, p)
            } catch (_: Exception) { }
        }
        // On the main thread a latch would deadlock (the posted block could never run before the wait ends).
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { apply(); return }
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post { apply(); latch.countDown() }
        if (!latch.await(400, java.util.concurrent.TimeUnit.MILLISECONDS))
            android.util.Log.w("EmergencyStop", "overlay pass-through=$on not applied within 400ms (main thread busy)")
        if (on) Thread.sleep(60) // let the window manager apply the flag before input arrives
    }

    /** Re-dock the pill to the other half of the screen (pure placement in [OverlayAvoid.dockY]). */
    fun moveAwayFrom(targetY: Int) {
        val v = button ?: return; val p = lp ?: return
        main.post {
            try { orb?.expand(false); p.y = OverlayAvoid.dockY(targetY, context.resources.displayMetrics.heightPixels); wm.updateViewLayout(v, p) } catch (_: Exception) { }
        }
    }

    fun show() {
        if (button != null) return
        val a11y = context is android.accessibilityservice.AccessibilityService
        if (!a11y && !android.provider.Settings.canDrawOverlays(context)
        ) {
            return // optional; notification stop still works
        }
        // The Tsuki orb (default) or the classic compact pill (orb turned off in Settings).
        val o = if (TsukiOrb.enabled(context)) TsukiOrb.build(context, onStop, onPause, onOpen) else null
        val (box, lbl) = if (o != null) o.root to o.label else OverlayPill.build(context, onStop, onPause)
        val type = if (a11y) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Left edge, ~40% height: away from title bars, search icons (WeChat ~x1057,y200),
            // chat input bars and the gesture-navigation area at the bottom.
            gravity = Gravity.TOP or Gravity.START
            x = (8 * context.resources.displayMetrics.density).toInt()
            y = (context.resources.displayMetrics.heightPixels * 0.40).toInt()
        }
        try {
            val upd = { try { wm.updateViewLayout(box, params) } catch (_: Exception) {} }
            if (o != null) TsukiOrb.attach(o, params, onStop) { upd() } else OverlayPill.enableDrag(box, params) { upd() }
            wm.addView(box, params)
            button = box; label = lbl; lp = params; orb = o; active = this
            box.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                bounds = android.graphics.Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
            }
        } catch (_: Exception) {
            // no overlay permission
        }
    }

    fun hide() {
        button?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        button = null; label = null; lp = null; orb = null
        bounds = null; if (active === this) active = null
    }

    companion object {
        /** Screen bounds of the visible stop button (gesture taps must avoid it). */
        @Volatile var bounds: android.graphics.Rect? = null
        @Volatile var active: EmergencyStopOverlay? = null
    }
}

/** Pure helper: does a gesture point (or a swipe path's bounding box) touch the overlay? */
object OverlayAvoid {
    const val PAD = 8
    /** Top of the pill after stepping aside: lower third when the target was in the upper half, else upper part. */
    fun dockY(targetY: Int, screenH: Int): Int = if (targetY < screenH / 2) (screenH * 0.68).toInt() else (screenH * 0.14).toInt()
    fun hits(x: Int, y: Int, r: IntArray?): Boolean =
        r != null && x >= r[0] - PAD && x <= r[2] + PAD && y >= r[1] - PAD && y <= r[3] + PAD
    fun pathHits(x1: Int, y1: Int, x2: Int, y2: Int, r: IntArray?): Boolean {
        if (r == null) return false
        val steps = 12
        return (0..steps).any { i -> hits(x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps, r) }
    }
}

/**
 * Compact floating pill shown over other apps while a task runs: live dot + current step + pause + stop.
 * Built separately from the window logic so it can be rendered in JVM screenshot tests.
 */
object OverlayPill {
    fun build(context: Context, onStop: () -> Unit, onPause: (() -> Boolean)?): Pair<LinearLayout, TextView> {
        val d = context.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(10), px(5), px(5), px(5))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xE6111113.toInt()); cornerRadius = 999f * d }
            elevation = 6 * d
        }
        val dot = View(context).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFF5BD68A.toInt()) } }
        box.addView(dot, LinearLayout.LayoutParams(px(8), px(8)))
        val lbl = TextView(context).apply {
            textSize = 12f; setTextColor(android.graphics.Color.WHITE); maxWidth = px(150); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END; text = "运行中"
        }
        box.addView(lbl, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8); marginEnd = px(8) })
        fun circle(icon: Int, bg: Int, desc: String, click: (ImageView) -> Unit) = ImageView(context).apply {
            setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            setPadding(px(7), px(7), px(7), px(7)); contentDescription = desc
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(bg) }
            setOnClickListener { click(this) }
        }
        if (onPause != null) box.addView(circle(R.drawable.ic_pause, 0x33FFFFFF, "暂停") { v ->
            val paused = onPause.invoke(); v.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause); v.contentDescription = if (paused) "继续" else "暂停"
        }, LinearLayout.LayoutParams(px(30), px(30)))
        box.addView(circle(R.drawable.ic_stop, 0xFFDC2626.toInt(), context.getString(R.string.fab_stop)) { onStop() },
            LinearLayout.LayoutParams(px(30), px(30)).apply { marginStart = px(6) })
        return box to lbl
    }

    /** Vertical drag on the pill body (buttons still click) so it can be moved off whatever it covers. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun enableDrag(box: View, p: WindowManager.LayoutParams, apply: () -> Unit) {
        var startY = 0f; var startPos = 0
        box.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { startY = e.rawY; startPos = p.y; false }
                android.view.MotionEvent.ACTION_MOVE -> {
                    // Clamp to the screen: a fling past the bottom edge would otherwise lose the stop button off-screen.
                    val maxY = (box.resources.displayMetrics.heightPixels - box.height).coerceAtLeast(0)
                    p.y = (startPos + (e.rawY - startY)).toInt().coerceIn(0, maxY); apply(); true }
                else -> false
            }
        }
    }
}

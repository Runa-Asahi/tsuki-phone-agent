package com.runasakura.phoneagent.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.runasakura.phoneagent.R
import com.runasakura.phoneagent.ui.MoonSkyView
import com.runasakura.phoneagent.ui.TsukiTheme
import kotlin.math.abs

/**
 * The Tsuki orb: a small moon that floats over other apps while a task runs. It replaces the old stop pill
 * (one overlay, same window plumbing: pass-through during gestures, bounds excluded from OCR/taps).
 *  - tap: expand/collapse a quick panel (current step, pause/resume, stop, open Tsuki)
 *  - long-press: emergency stop (haptic)
 *  - drag anywhere, released it snaps to the nearest screen edge
 *  - while running: rotating progress arc + soft pulse; paused: static amber arc
 */
object TsukiOrb {
    /** Settings, in the same "ui" prefs as the palette. */
    fun enabled(ctx: Context) = ctx.getSharedPreferences("ui", 0).getBoolean("orb", true)
    fun setEnabled(ctx: Context, on: Boolean) = ctx.getSharedPreferences("ui", 0).edit().putBoolean("orb", on).apply()
    /** Idle opacity 40..100 %; touching it is always fully opaque. */
    fun opacity(ctx: Context) = ctx.getSharedPreferences("ui", 0).getInt("orb_alpha", 90).coerceIn(40, 100)
    fun setOpacity(ctx: Context, v: Int) = ctx.getSharedPreferences("ui", 0).edit().putInt("orb_alpha", v.coerceIn(40, 100)).apply()

    /** Pure: x of the window after a release, snapped to the nearer edge with a margin. */
    fun snapX(centerX: Int, width: Int, screenW: Int, margin: Int): Int =
        if (centerX < screenW / 2) margin else (screenW - width - margin).coerceAtLeast(margin)

    /** Pure: step number for the ring label from a progress line like "第 4 步 · 点击…". */
    fun stepOf(text: String): Int? = Regex("第\\s*(\\d+)\\s*步").find(text)?.groupValues?.get(1)?.toIntOrNull()

    class Orb(val root: LinearLayout, val label: TextView, val orb: OrbView, val panel: LinearLayout) {
        fun setText(t: String) {
            label.text = t; orb.step = stepOf(t); orb.paused = t.startsWith("已暂停")
            orb.contentDescription = "Tsuki：$t。点按展开，长按急停"
        }
        private val collapse = Runnable { expand(false) }
        /** The panel folds back after 6 s so it never covers the app the agent is working in for long. */
        fun expand(open: Boolean) {
            panel.visibility = if (open) View.VISIBLE else View.GONE
            root.removeCallbacks(collapse); if (open) root.postDelayed(collapse, 6000)
        }
        val expanded get() = panel.visibility == View.VISIBLE
    }

    fun build(ctx: Context, onStop: () -> Unit, onPause: (() -> Boolean)?, onOpen: (() -> Unit)?, onVoice: (() -> Unit)? = null): Orb {
        val d = ctx.resources.displayMetrics.density; fun px(v: Int) = (v * d).toInt()
        val k = TsukiTheme.current(ctx)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val orb = OrbView(ctx, k)
        root.addView(orb, LinearLayout.LayoutParams(px(52), px(52)))
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            setPadding(px(12), px(6), px(6), px(6)); elevation = 6 * d
            background = GradientDrawable().apply { setColor(k.surface or (0xF2 shl 24)); cornerRadius = 999f * d; setStroke(px(1), k.outline) }
        }
        val lbl = TextView(ctx).apply {
            textSize = 12f; setTextColor(k.onSurface); maxWidth = px(160); maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END; text = "运行中"
        }
        panel.addView(lbl, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(6) })
        fun circle(icon: Int, bg: Int, fg: Int, desc: String, click: (ImageView) -> Unit) = ImageView(ctx).apply {
            setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(fg)
            setPadding(px(9), px(9), px(9), px(9)); contentDescription = desc
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
            setOnClickListener { click(this) }
        }
        val lp = { LinearLayout.LayoutParams(px(40), px(40)).apply { marginStart = px(4) } }  // ≥40dp targets
        if (onPause != null) panel.addView(circle(R.drawable.ic_pause, k.accentContainer, k.onSurface, "暂停") { v ->
            val paused = onPause.invoke(); v.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause); v.contentDescription = if (paused) "继续" else "暂停"
        }, lp())
        if (onVoice != null) panel.addView(circle(R.drawable.ic_mic, k.accentContainer, k.onSurface, "语音下达下一个任务") { onVoice() }, lp())
        if (onOpen != null) panel.addView(circle(R.drawable.ic_open_app, k.accentContainer, k.onSurface, "打开 Tsuki") { onOpen() }, lp())
        panel.addView(circle(R.drawable.ic_stop, k.crimson, 0xFFFFFFFF.toInt(), ctx.getString(R.string.fab_stop)) { onStop() }, lp())
        root.addView(panel, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(6) })
        orb.contentDescription = "Tsuki 运行中。点按展开，长按急停"
        return Orb(root, lbl, orb, panel)
    }

    /**
     * Touch handling on the orb: tap toggles the panel, long-press stops, drag moves (both axes, clamped),
     * release snaps to the nearest edge. Buttons inside the panel keep their own clicks.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attach(o: Orb, p: WindowManager.LayoutParams, onStop: () -> Unit, apply: () -> Unit) {
        val v = o.orb; val ctx = v.context
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop; val longMs = ViewConfiguration.getLongPressTimeout().toLong()
        val idle = opacity(ctx) / 100f; o.root.alpha = idle
        var sx = 0f; var sy = 0f; var px0 = 0; var py0 = 0; var moved = false; var fired = false
        val longPress = Runnable { fired = true; v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); onStop() }
        v.setOnTouchListener { _, e ->
            val dm = ctx.resources.displayMetrics
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; px0 = p.x; py0 = p.y; moved = false; fired = false; o.root.alpha = 1f; v.postDelayed(longPress, longMs) }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) { moved = true; v.removeCallbacks(longPress) }
                    if (moved) {
                        p.x = (px0 + dx).toInt().coerceIn(0, (dm.widthPixels - o.root.width).coerceAtLeast(0))
                        p.y = (py0 + dy).toInt().coerceIn(0, (dm.heightPixels - o.root.height).coerceAtLeast(0)); apply()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress); o.root.alpha = idle
                    if (moved) { p.x = snapX(p.x + v.width / 2, v.width, dm.widthPixels, (8 * dm.density).toInt()); apply() }
                    else if (!fired && e.actionMasked == MotionEvent.ACTION_UP) { o.expand(!o.expanded); v.performClick(); apply() }
                }
            }
            true
        }
    }

    /** The moon itself: themed disk + crescent, glow pulse and a progress arc. */
    class OrbView(ctx: Context, private val k: TsukiTheme.Colors) : View(ctx) {
        var step: Int? = null; set(v) { field = v; invalidate() }
        var paused = false; set(v) { field = v; invalidate() }
        private val motion = !TsukiTheme.reduceMotion(ctx)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arc = RectF()
        private var t = 0f
        init { isClickable = true; isFocusable = true }
        override fun performClick(): Boolean { super.performClick(); return true }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val cx = w / 2; val cy = height / 2f; val r = w * 0.36f; val d = resources.displayMetrics.density
            val pulse = if (motion && !paused) 0.5f + 0.5f * kotlin.math.sin(t * 2.4f) else 0.5f
            paint.shader = RadialGradient(cx, cy, w / 2, k.glow, 0, Shader.TileMode.CLAMP); paint.alpha = (150 + 100 * pulse).toInt()
            c.drawCircle(cx, cy, w / 2, paint); paint.shader = null; paint.alpha = 255
            paint.shader = android.graphics.LinearGradient(0f, cy - r, 0f, cy + r, k.skyTop, k.skyBottom, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, paint); paint.shader = null
            paint.style = Paint.Style.STROKE; paint.strokeWidth = d; paint.color = k.outline; c.drawCircle(cx, cy, r, paint); paint.style = Paint.Style.FILL
            paint.color = k.accent
            if (k.fullMoon) MoonSkyView.fullMoon(c, cx, cy, r * 0.56f, paint, 0.5f + 0.5f * pulse) else MoonSkyView.crescent(c, cx - r * 0.08f, cy, r * 0.55f, paint)
            // progress arc around the disk
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * d; paint.strokeCap = Paint.Cap.ROUND
            arc.set(cx - r - 3 * d, cy - r - 3 * d, cx + r + 3 * d, cy + r + 3 * d)
            paint.color = if (paused) 0xFFE8B04A.toInt() else k.accent
            c.drawArc(arc, if (paused) -90f else (t * 220f) % 360f, if (paused) 360f else 100f, false, paint)
            paint.style = Paint.Style.FILL
            step?.let { s ->   // small step badge
                val br = 8 * d; val bx = cx + r * 0.8f; val by = cy - r * 0.8f
                paint.color = k.crimson; c.drawCircle(bx, by, br, paint)
                paint.color = 0xFFFFFFFF.toInt(); paint.textSize = 10 * d; paint.textAlign = Paint.Align.CENTER; paint.isFakeBoldText = true
                c.drawText(if (s > 99) "99" else "$s", bx, by + 3.5f * d, paint)
            }
            if (motion && !paused && isShown) { val ms = com.runasakura.phoneagent.ui.TsukiTheme.frameMs(context); t += ms / 1000f; postInvalidateDelayed(ms) }
        }
    }
}

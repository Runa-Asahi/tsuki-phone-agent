package com.runasakura.phoneagent.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import androidx.core.content.ContextCompat
import com.runasakura.phoneagent.R

class EmergencyStopOverlay(
    private val context: Context,
    private val onStop: () -> Unit,
    private val onPause: (() -> Boolean)? = null
) {
    private var label: android.widget.TextView? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Codex-style live progress next to the stop button while operating other apps. */
    fun setProgress(text: String) {
        val t = text.replace('\n', ' ').take(28)
        main.post { label?.let { it.text = t; it.visibility = if (t.isBlank()) android.view.View.GONE else android.view.View.VISIBLE } }
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var button: android.view.View? = null

    fun show() {
        if (button != null) return
        val a11y = context is android.accessibilityservice.AccessibilityService
        if (!a11y && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(context)
        ) {
            return // optional; notification stop still works
        }
        val btn = Button(context).apply {
            text = context.getString(R.string.fab_stop)
            setBackgroundColor(ContextCompat.getColor(context, R.color.stop_red))
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            setOnClickListener { onStop() }
            minWidth = 0; minimumWidth = 0
            textSize = 12f
            setPadding(12, 8, 12, 8)
        }
        val d = context.resources.displayMetrics.density
        val box = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xCC202124.toInt()); cornerRadius = 10 * d }
        }
        val lbl = android.widget.TextView(context).apply {
            textSize = 10f; setTextColor(android.graphics.Color.WHITE); maxWidth = (110 * d).toInt(); maxLines = 2
            visibility = android.view.View.GONE
        }
        box.addView(lbl)
        val row = android.widget.LinearLayout(context)
        if (onPause != null) row.addView(Button(context).apply {
            text = "暂停"; textSize = 12f; minWidth = 0; minimumWidth = 0; setPadding(12, 8, 12, 8)
            setOnClickListener { text = if (onPause.invoke()) "继续" else "暂停" }
        })
        row.addView(btn)
        box.addView(row)
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
            x = 0
            y = (context.resources.displayMetrics.heightPixels * 0.40).toInt()
        }
        try {
            wm.addView(box, params)
            button = box; label = lbl
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
        button = null; label = null
        bounds = null
    }

    companion object {
        /** Screen bounds of the visible stop button (gesture taps must avoid it). */
        @Volatile var bounds: android.graphics.Rect? = null
    }
}

/** Pure helper: move a tap that would land on the overlay to just outside it (right side, else below). */
object OverlayAvoid {
    const val PAD = 16
    fun point(x: Int, y: Int, r: IntArray?): Pair<Int, Int> {
        if (r == null) return x to y
        val (l, t, rr, b) = r.toList()
        if (x < l || x > rr || y < t || y > b) return x to y
        return (rr + PAD) to y
    }
}

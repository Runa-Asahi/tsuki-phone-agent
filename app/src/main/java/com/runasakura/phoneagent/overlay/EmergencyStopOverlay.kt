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
    private val onStop: () -> Unit
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var button: Button? = null

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
            wm.addView(btn, params)
            button = btn
            btn.post {
                val loc = IntArray(2); btn.getLocationOnScreen(loc)
                bounds = android.graphics.Rect(loc[0], loc[1], loc[0] + btn.width, loc[1] + btn.height)
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
        button = null
        bounds = null
    }

    companion object {
        /** Screen bounds of the visible stop button (gesture taps must avoid it). */
        @Volatile var bounds: android.graphics.Rect? = null
    }
}

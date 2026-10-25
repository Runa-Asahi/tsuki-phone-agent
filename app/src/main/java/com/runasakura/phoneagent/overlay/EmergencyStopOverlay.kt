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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(context)
        ) {
            return // optional; notification stop still works
        }
        val btn = Button(context).apply {
            text = context.getString(R.string.fab_stop)
            setBackgroundColor(ContextCompat.getColor(context, R.color.stop_red))
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            setOnClickListener { onStop() }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 200
        }
        try {
            wm.addView(btn, params)
            button = btn
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
    }
}

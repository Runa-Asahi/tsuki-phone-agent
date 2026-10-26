package com.runasakura.phoneagent.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.runasakura.phoneagent.PhoneAgentApp
import com.runasakura.phoneagent.R

/**
 * Optional low-priority foreground service: keeps the process (and the accessibility service living in it) from being
 * reclaimed by MagicOS background cleaning. It also checks the service every minute and self-heals when possible.
 */
class KeepAliveService : Service() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            Thread { com.runasakura.phoneagent.a11y.A11ySelfHeal.heal(this@KeepAliveService, "保活巡检") }.start()
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val open = PendingIntent.getActivity(this, 1, Intent(this, com.runasakura.phoneagent.ui.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(this, PhoneAgentApp.CHANNEL_AGENT)
                .setSmallIcon(R.drawable.ic_agent).setContentTitle(getString(R.string.keepalive_title))
                .setContentText(getString(R.string.keepalive_text)).setOngoing(true).setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN).setContentIntent(open).build()
            if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, n)
            handler.removeCallbacks(tick); handler.post(tick)
        } catch (e: Throwable) { android.util.Log.w("KeepAlive", "start failed", e); stopSelf() }
        return START_STICKY
    }

    override fun onDestroy() { handler.removeCallbacks(tick); super.onDestroy() }

    companion object {
        private const val ID = 4207
        private const val PREFS = "agent_backend"
        fun isOn(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("keepalive", false)
        fun setOn(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("keepalive", on).apply()
            if (on) start(ctx) else ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        }
        fun start(ctx: Context) = try { ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java)); true } catch (_: Throwable) { false }
    }
}

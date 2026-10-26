package com.runasakura.phoneagent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.runasakura.phoneagent.prefs.AgentPreferences

class PhoneAgentApp : Application() {
    lateinit var prefs: AgentPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        com.runasakura.phoneagent.ui.TsukiTheme.init(this)
        instance = this
        prefs = AgentPreferences(this)
        createNotificationChannels()
        installCrashGuard()
        // MagicOS may have switched the accessibility service off after a kill: heal it (and restore keep-alive).
        Thread {
            com.runasakura.phoneagent.a11y.A11ySelfHeal.heal(this, "应用启动")
            if (com.runasakura.phoneagent.service.KeepAliveService.isOn(this)) com.runasakura.phoneagent.service.KeepAliveService.start(this)
        }.start()
    }

    /**
     * The accessibility service lives in this process: any uncaught exception kills it and MagicOS then disables the
     * service. Exceptions on worker threads are logged and swallowed (the thread dies, the process lives); the main
     * thread's still go to the platform handler (its Looper cannot continue).
     */
    private fun installCrashGuard() {
        val platform = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { CrashGuard.record(this, t.name, e) } catch (_: Throwable) {}
            if (CrashGuard.shouldSwallow(t === android.os.Looper.getMainLooper().thread, e)) return@setDefaultUncaughtExceptionHandler
            platform?.uncaughtException(t, e)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AGENT,
                getString(R.string.channel_agent),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_agent_desc)
                setShowBadge(false)
            }
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                getString(R.string.channel_alert),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    companion object {
        const val CHANNEL_AGENT = "agent_running"
        const val CHANNEL_ALERT = "agent_alert"
        lateinit var instance: PhoneAgentApp
            private set
    }
}

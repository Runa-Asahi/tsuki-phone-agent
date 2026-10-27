package com.runasakura.phoneagent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.runasakura.phoneagent.prefs.AgentPreferences

class PhoneAgentApp : Application() {
    lateinit var prefs: AgentPreferences
        private set

    /** Memory pressure: drop the warm on-device model (≈1–2 GB) unless it is mid-inference; it reloads on next use. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        val heavy = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        if (heavy && com.runasakura.phoneagent.llm.EmbeddedMnn.state == com.runasakura.phoneagent.llm.EmbeddedMnn.State.READY &&
            !com.runasakura.phoneagent.service.AgentForegroundService.isRunning()) com.runasakura.phoneagent.llm.EmbeddedMnn.release()
        // The embedder (~250 MB) reloads in ~1 s; drop it off the main thread (it may be mid-encode).
        if (heavy && !com.runasakura.phoneagent.service.AgentForegroundService.isRunning()) Thread { com.runasakura.phoneagent.llm.TextEmbedder.release() }.start()
    }

    override fun onCreate() {
        super.onCreate()
        com.runasakura.phoneagent.ui.TsukiTheme.init(this)
        instance = this
        prefs = AgentPreferences(this)
        createNotificationChannels()
        installCrashGuard()
        // Shizuku audit + UiAutomation gate: while our accessibility service is connected or enabled, never let a
        // `uiautomator`-style command run (it makes the system unbind us).
        val app = this
        com.runasakura.phoneagent.shell.ShellLog.a11yActive = {
            com.runasakura.phoneagent.a11y.AgentAccessibilityService.instance != null ||
                com.runasakura.phoneagent.a11y.AgentAccessibilityService.isEnabledInSettings(app)
        }
        com.runasakura.phoneagent.shell.ShellLog.sink = { com.runasakura.phoneagent.util.Diagnostics.log(it) }
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

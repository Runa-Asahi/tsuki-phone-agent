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
        val running = com.runasakura.phoneagent.service.AgentForegroundService.isRunning()
        if (heavy && com.runasakura.phoneagent.llm.EmbeddedMnn.state == com.runasakura.phoneagent.llm.EmbeddedMnn.State.READY && !running)
            com.runasakura.phoneagent.llm.EmbeddedMnn.release()
        // The embedder (~250 MB) is only needed for a moment at task start/end and reloads in ~1–2 s: drop it on any
        // pressure signal, UI hidden included, even during a task (on the housekeeping thread; it may be mid-encode).
        @Suppress("DEPRECATION")
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            com.runasakura.phoneagent.util.Idle.exec.execute { com.runasakura.phoneagent.llm.TextEmbedder.release() }
    }

    @Volatile private var visible = 0

    override fun onCreate() {
        super.onCreate()
        com.runasakura.phoneagent.ui.TsukiTheme.init(this)
        instance = this
        prefs = AgentPreferences(this)
        // Main-thread I/O audit: getSharedPreferences() starts loading the file on its own thread and returns at once,
        // so touching the files the first screen needs here overlaps their disk read with startup; the encrypted key
        // store (Keystore, slowest) and the task history JSON are built on a worker.
        for (f in listOf("ui", "task_history")) getSharedPreferences(f, 0)
        Thread({ runCatching { prefs.warmSecure() }; runCatching { com.runasakura.phoneagent.ui.TaskHistory.load(this) }
            runCatching { com.runasakura.phoneagent.voice.LocalAsr.refresh(this) }
            runCatching { com.runasakura.phoneagent.agent.TeachSession.restore(this) } }, "tsuki-warm").start()
        createNotificationChannels()
        installCrashGuard()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) { visible++ }
            override fun onActivityStopped(a: android.app.Activity) { visible = (visible - 1).coerceAtLeast(0) }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        com.runasakura.phoneagent.util.Idle.start(this) { visible > 0 }
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

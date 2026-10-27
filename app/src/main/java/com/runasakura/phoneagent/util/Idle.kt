package com.runasakura.phoneagent.util

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One shared low-priority thread for housekeeping (idle model unloading, storage janitor). Bounded by design:
 * nothing in Tsuki spawns ad-hoc threads for periodic work.
 */
object Idle {
    val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tsuki-idle").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    @Volatile private var started = false

    /** Policy (pure, unit-tested): how long a loaded model may sit unused before it is unloaded. */
    fun llmIdleMs(appVisible: Boolean, taskRunning: Boolean): Long? = when {
        taskRunning -> null              // never during a task
        appVisible -> 10 * 60_000L       // user may start a task soon: keep warm a while
        else -> 2 * 60_000L              // app in background: give the memory back quickly
    }

    fun start(app: android.app.Application, appVisible: () -> Boolean) {
        if (started) return; started = true
        exec.scheduleWithFixedDelay({
            runCatching {
                val running = com.runasakura.phoneagent.service.AgentForegroundService.isRunning()
                com.runasakura.phoneagent.llm.TextEmbedder.releaseIfIdle()
                llmIdleMs(appVisible(), running)?.let { com.runasakura.phoneagent.llm.EmbeddedMnn.releaseIfIdle(it) }
            }
        }, 30, 30, TimeUnit.SECONDS)
        exec.schedule({ runCatching { StorageJanitor.autoClean(app) } }, 8, TimeUnit.SECONDS)
    }
}

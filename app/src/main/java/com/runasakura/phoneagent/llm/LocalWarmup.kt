package com.runasakura.phoneagent.llm

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keep-warm + health for the embedded model: load it in the background (never on a step's critical path) and run
 * a one-token probe so the first real call does not pay the load. Health = loaded + last probe latency.
 */
object LocalWarmup {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile var probeMs: Long? = null; private set
    @Volatile private var running = false

    fun kick(ctx: Context, log: (String) -> Unit = {}) {
        val dir = ModelStore.selected(ctx) ?: run { log("本机模型：未安装（设置 → 本机模型 下载或导入），本次只用云端"); return }
        if (!EmbeddedMnn.available) { log("本机模型：推理库不可用"); return }
        if (running || (EmbeddedMnn.state == EmbeddedMnn.State.READY && EmbeddedMnn.loadedDir == dir.absolutePath)) return
        running = true
        scope.launch {
            try {
                val t0 = System.currentTimeMillis()
                if (!EmbeddedMnn.ensureLoaded(ctx, dir)) { log("本机模型加载失败：${EmbeddedMnn.lastError}"); return@launch }
                EmbeddedMnn.chat(ctx, listOf(ChatMessage("user", "hi")), 1)
                probeMs = System.currentTimeMillis() - t0
                log("本机模型已预热：${dir.name}（加载 ${EmbeddedMnn.loadMs}ms）")
            } catch (e: Throwable) { log("本机模型预热失败：${e.message}") } finally { running = false }
        }
    }

    fun status(ctx: Context): String {
        val dir = ModelStore.selected(ctx) ?: return "未安装"
        return when (EmbeddedMnn.state) {
            EmbeddedMnn.State.READY -> "已就绪 · ${dir.name}" + (EmbeddedMnn.lastLatencyMs.takeIf { it > 0 }?.let { " · 上次 ${it}ms" } ?: "")
            EmbeddedMnn.State.BUSY -> "推理中 · ${dir.name}"
            EmbeddedMnn.State.LOADING -> "加载中 · ${dir.name}"
            EmbeddedMnn.State.FAILED -> "失败：${EmbeddedMnn.lastError}"
            EmbeddedMnn.State.NONE -> "已安装（未加载）· ${dir.name}"
        }
    }
}

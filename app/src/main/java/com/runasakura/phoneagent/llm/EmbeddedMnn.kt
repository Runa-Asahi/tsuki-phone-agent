package com.runasakura.phoneagent.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * On-device LLM inside Tsuki's own process (MNN LLM engine via JNI, libtsuki_llm.so → libMNN.so).
 * No MNN Chat dependency: the model runs in the agent's foreground-service process, so MagicOS's throttling of a
 * backgrounded MNN Chat does not apply. One model is kept warm in memory; it is released on memory pressure.
 */
object EmbeddedMnn {
    @JvmStatic private external fun nativeLoad(config: String, extra: String): Long
    @JvmStatic private external fun nativeChat(h: Long, roles: Array<String>, contents: Array<String>, maxTokens: Int): String
    @JvmStatic private external fun nativeCancel(h: Long)
    @JvmStatic private external fun nativeRelease(h: Long)

    /** Native libs present and loadable (false on JVM tests / non-arm64). */
    val available: Boolean by lazy { runCatching { System.loadLibrary("tsuki_llm"); true }.getOrDefault(false) }

    enum class State { NONE, LOADING, READY, BUSY, FAILED }
    @Volatile var state = State.NONE; private set
    @Volatile var loadedDir: String? = null; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var loadMs = 0L; private set
    /** Rolling decode speed estimate (chars/s) for honest status lines. */
    @Volatile var lastLatencyMs = 0L; private set
    @Volatile private var handle = 0L
    private val loadLock = Mutex()

    /** Engine options: CPU with 4 threads, low-memory int4 dequant, mmap weights to the app cache (less RAM pressure). */
    fun extraConfig(tmp: String) = org.json.JSONObject().apply {
        put("backend_type", "cpu"); put("thread_num", 4); put("precision", "low"); put("memory", "low")
        put("use_mmap", true); put("tmp_path", tmp); put("max_new_tokens", 512)
    }.toString()

    suspend fun ensureLoaded(ctx: Context, dir: File): Boolean = loadLock.withLock {
        if (handle != 0L && loadedDir == dir.absolutePath) return true
        if (!available) { lastError = "本机推理库不可用（仅支持 arm64）"; state = State.FAILED; return false }
        val cfg = File(dir, "config.json"); if (!cfg.isFile) { lastError = "模型目录缺少 config.json"; state = State.FAILED; return false }
        // Switching models: stop any running generation and free the old session only once it has returned.
        if (handle != 0L) { cancel(); chatLock.withLock { releasePending = false; freeNow() } }
        state = State.LOADING
        val t0 = System.currentTimeMillis()
        val tmp = File(ctx.cacheDir, "mnn_mmap").apply { mkdirs() }.absolutePath
        val h = withContext(Dispatchers.Default) { runCatching { nativeLoad(cfg.absolutePath, extraConfig(tmp)) }.getOrDefault(0L) }
        loadMs = System.currentTimeMillis() - t0
        if (h == 0L) { state = State.FAILED; lastError = "模型加载失败（文件不完整或内存不足）"; return false }
        handle = h; loadedDir = dir.absolutePath; state = State.READY; lastError = null; true
    }

    /** One native session ⇒ one generation at a time (the step brain and the vision assist share it). */
    private val chatLock = Mutex()
    @Volatile private var releasePending = false

    /** Messages → plain-text reply. Images are written to cache and referenced with MNN's <img>path</img> tag. */
    suspend fun chat(ctx: Context, messages: List<ChatMessage>, maxTokens: Int): String = chatLock.withLock { withContext(Dispatchers.Default) {
        val h = handle; check(h != 0L) { "model not loaded" }
        val roles = ArrayList<String>(); val contents = ArrayList<String>()
        for ((i, m) in messages.withIndex()) {
            roles += if (m.role == "system" || m.role == "assistant") m.role else "user"
            val img = m.imageBase64?.let { b64 ->
                val f = File(ctx.cacheDir, "mnn_img_$i.jpg"); f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT)); "<img>${f.absolutePath}</img>"
            }
            contents += (img ?: "") + m.content
        }
        state = State.BUSY
        val t0 = System.currentTimeMillis()
        try { nativeChat(h, roles.toTypedArray(), contents.toTypedArray(), maxTokens) }
        finally { lastLatencyMs = System.currentTimeMillis() - t0; if (state == State.BUSY) state = State.READY
            if (releasePending) { releasePending = false; freeNow() } }
    } }

    /** Stop the current generation within one token (step deadline / 急停). */
    fun cancel() { val h = handle; if (h != 0L) runCatching { nativeCancel(h) } }

    /** Never frees the session under a running generation: a busy model is cancelled and freed when that call returns. */
    fun release() {
        if (state == State.BUSY) { releasePending = true; cancel(); return }
        freeNow()
    }

    private fun freeNow() {
        val h = handle; handle = 0L; loadedDir = null
        if (h != 0L) runCatching { nativeRelease(h) }
        if (state != State.FAILED) state = State.NONE
    }
}

/** LlmClient over [EmbeddedMnn]: cancellable (coroutine cancel → native cancel), never blocks another backend. */
class EmbeddedMnnClient(private val ctx: Context, private val dir: () -> File?, private val maxTokens: Int = 384) : LlmClient {
    override val name: String = "tsuki_local"
    override suspend fun chat(messages: List<ChatMessage>, temperature: Double): LlmResponse {
        val t0 = System.currentTimeMillis()
        val d = dir() ?: return LlmResponse("", name, 0, "未安装本机模型（设置 → 本机模型 下载或导入）", errorCode = "NOT_CONFIGURED")
        if (!EmbeddedMnn.ensureLoaded(ctx, d)) return LlmResponse("", name, System.currentTimeMillis() - t0, EmbeddedMnn.lastError, errorCode = "UNAVAILABLE")
        return try {
            // A step deadline / 急停 cancels this coroutine; the native loop then stops within one token.
            @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
            val hook = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { c -> if (c != null) EmbeddedMnn.cancel() }
            val out = try {
                EmbeddedMnn.chat(ctx, messages, maxTokens).let { o -> if (MnnText.clean(o).isBlank()) EmbeddedMnn.chat(ctx, messages, maxTokens) else o }   // one retry on an empty turn
            } finally { hook?.dispose() }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val text = MnnText.clean(out)
            // Two empty turns usually mean the engine is in an error state: drop it so the next call reloads cleanly.
            if (text.isBlank()) { EmbeddedMnn.release(); return LlmResponse("", name, System.currentTimeMillis() - t0, "本机模型返回空内容（已重置，下次重新加载）", errorCode = "EMPTY_RESPONSE") }
            LlmResponse(text, name, System.currentTimeMillis() - t0)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Throwable) { LlmResponse("", name, System.currentTimeMillis() - t0, "本机推理失败：${e.message}", errorCode = "UNAVAILABLE") }
    }
}

/** Pure post-processing of raw model text (unit-tested): drop <think> blocks and end markers. */
object MnnText {
    fun clean(s: String): String = s.replace(Regex("(?s)<think>.*?</think>"), "").replace("<eop>", "").replace("<|im_end|>", "").trim()
}

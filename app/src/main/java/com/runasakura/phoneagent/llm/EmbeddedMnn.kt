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
 *
 * Backends (this build): CPU (always) and OpenCL (Adreno / Mali — Snapdragon 8 Elite has Adreno). QNN/NPU are
 * NOT compiled in. We probe OpenCL once and persist the winner; a failed OpenCL load falls back to CPU.
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
    @Volatile var lastLatencyMs = 0L; private set
    /** Backend that successfully loaded last time: "opencl" or "cpu". */
    @Volatile var backend: String = "cpu"; private set
    @Volatile private var handle = 0L
    private val loadLock = Mutex()

    /** CPU threads: leave 1–2 cores for the UI / a11y; clamp 2..6. OpenCL uses 64 (MNN buffer mode). */
    fun cpuThreads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

    /**
     * Engine options. [backendType] is "cpu" or "opencl".
     * use_mmap + use_cached_mmap: weights stay on disk-backed pages (less RAM churn on reload).
     * precision/memory low: int4 weight path already in the MNN model files.
     * reuse_kv: kept false because every turn ships a full new ChatMessages list and we reset;
     * enabling it without a shared-prefix path does not speed single-shot assist calls.
     */
    fun extraConfig(tmp: String, backendType: String): String = org.json.JSONObject().apply {
        put("backend_type", backendType)
        put("thread_num", if (backendType == "opencl") 64 else cpuThreads())
        put("precision", "low"); put("memory", "low")
        put("use_mmap", true); put("use_cached_mmap", true); put("tmp_path", tmp)
        put("max_new_tokens", 512); put("reuse_kv", false)
    }.toString()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("local_model", 0)
    fun preferredBackend(ctx: Context): String = prefs(ctx).getString("backend", "auto") ?: "auto"
    fun setPreferredBackend(ctx: Context, b: String) = prefs(ctx).edit().putString("backend", b).apply()

    suspend fun ensureLoaded(ctx: Context, dir: File): Boolean = loadLock.withLock {
        if (handle != 0L && loadedDir == dir.absolutePath) return true
        if (!available) { lastError = "本机推理库不可用（仅支持 arm64）"; state = State.FAILED; return false }
        val cfg = File(dir, "config.json"); if (!cfg.isFile) { lastError = "模型目录缺少 config.json"; state = State.FAILED; return false }
        if (handle != 0L) { cancel(); chatLock.withLock { releasePending = false; freeNow() } }
        state = State.LOADING
        val t0 = System.currentTimeMillis()
        val tmp = File(ctx.cacheDir, "mnn_mmap").apply { mkdirs() }.absolutePath
        val pref = preferredBackend(ctx)
        val lastGood = prefs(ctx).getString("backend_last_good", null)
        // Probe order: pinned → last-good first under auto → OpenCL then CPU.
        val order = when (pref) {
            "cpu" -> listOf("cpu")
            "opencl" -> listOf("opencl", "cpu")
            else -> listOfNotNull(lastGood, "opencl", "cpu").distinct()
        }
        var h = 0L; var used = "cpu"; var err: String? = null
        for (b in order) {
            h = withContext(Dispatchers.Default) {
                runCatching { nativeLoad(cfg.absolutePath, extraConfig(tmp, b)) }.getOrDefault(0L)
            }
            if (h != 0L) { used = b; break }
            err = "backend=$b failed"
            if (b == "opencl") { lastError = "OpenCL 不可用，回退 CPU"; prefs(ctx).edit().putString("backend_last_good", "cpu").apply() }
        }
        loadMs = System.currentTimeMillis() - t0
        if (h == 0L) { state = State.FAILED; lastError = "模型加载失败（文件不完整或内存不足${err?.let { "；$it" } ?: ""}）"; return false }
        handle = h; loadedDir = dir.absolutePath; backend = used; state = State.READY; lastError = null
        prefs(ctx).edit().putString("backend_last_good", used).apply()
        true
    }

    private val chatLock = Mutex()
    @Volatile private var releasePending = false

    /**
     * Messages → plain-text reply. Images are written to cache and referenced with MNN's <img>path</img> tag.
     * [retryEmpty]: step-brain retries one empty turn; assist path sets false so a blank answer costs one call, not two.
     */
    suspend fun chat(ctx: Context, messages: List<ChatMessage>, maxTokens: Int, retryEmpty: Boolean = true, maxChars: Int = 6_000): String =
        chatLock.withLock { withContext(Dispatchers.Default) {
            val h = handle; check(h != 0L) { "model not loaded" }
            val roles = ArrayList<String>(); val contents = ArrayList<String>()
            for ((i, m) in messages.withIndex()) {
                roles += if (m.role == "system" || m.role == "assistant") m.role else "user"
                val img = m.imageBase64?.let { b64 ->
                    val f = File(ctx.cacheDir, "mnn_img_$i.jpg"); f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT)); "<img>${f.absolutePath}</img>"
                }
                // Cap text fed to the local model: long agent transcripts dominate prefill cost.
                // Assist uses a tight cap; the offline brain keeps more context.
                val text = if (m.content.length > maxChars) m.content.take(maxChars) + "…" else m.content
                contents += (img ?: "") + text
            }
            state = State.BUSY
            val t0 = System.currentTimeMillis()
            try {
                var out = nativeChat(h, roles.toTypedArray(), contents.toTypedArray(), maxTokens)
                if (retryEmpty && MnnText.clean(out).isBlank()) {
                    out = nativeChat(h, roles.toTypedArray(), contents.toTypedArray(), maxTokens)
                }
                out
            } finally {
                lastLatencyMs = System.currentTimeMillis() - t0
                if (state == State.BUSY) state = State.READY
                if (releasePending) { releasePending = false; freeNow() }
                // Drop decoded JPEG temps so cache does not grow across assist calls.
                ctx.cacheDir.listFiles()?.forEach { if (it.name.startsWith("mnn_img_")) it.delete() }
            }
        } }

    fun cancel() { val h = handle; if (h != 0L) runCatching { nativeCancel(h) } }

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

/** LlmClient over [EmbeddedMnn]. [assist] uses fewer tokens and never retries empty turns. */
class EmbeddedMnnClient(
    private val ctx: Context,
    private val dir: () -> File?,
    private val maxTokens: Int = 384,
    private val assist: Boolean = false,
) : LlmClient {
    override val name: String = if (assist) "tsuki_local_assist" else "tsuki_local"
    override suspend fun chat(messages: List<ChatMessage>, temperature: Double): LlmResponse {
        val t0 = System.currentTimeMillis()
        val d = dir() ?: return LlmResponse("", name, 0, "未安装本机模型（设置 → 本机模型 下载或导入）", errorCode = "NOT_CONFIGURED")
        if (!EmbeddedMnn.ensureLoaded(ctx, d)) return LlmResponse("", name, System.currentTimeMillis() - t0, EmbeddedMnn.lastError, errorCode = "UNAVAILABLE")
        return try {
            @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
            val hook = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { c -> if (c != null) EmbeddedMnn.cancel() }
            val out = try {
                EmbeddedMnn.chat(ctx, messages, maxTokens, retryEmpty = !assist, maxChars = if (assist) 1_200 else 6_000)
            } finally { hook?.dispose() }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val text = MnnText.clean(out)
            if (text.isBlank()) {
                if (!assist) EmbeddedMnn.release()
                return LlmResponse("", name, System.currentTimeMillis() - t0, "本机模型返回空内容" + if (!assist) "（已重置，下次重新加载）" else "", errorCode = "EMPTY_RESPONSE")
            }
            LlmResponse(text, name, System.currentTimeMillis() - t0)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Throwable) { LlmResponse("", name, System.currentTimeMillis() - t0, "本机推理失败：${e.message}", errorCode = "UNAVAILABLE") }
    }
}

object MnnText {
    fun clean(s: String): String = s.replace(Regex("(?s)<think>.*?</think>"), "").replace("<eop>", "").replace("<|im_end|>", "").trim()
}

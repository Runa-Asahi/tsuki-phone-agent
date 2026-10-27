package com.runasakura.phoneagent.llm

import android.content.Context
import com.runasakura.phoneagent.agent.Relevance
import com.runasakura.phoneagent.agent.SemanticIndex
import java.io.File

/**
 * On-device sentence embedding for lesson retrieval: MNN's Embedding engine (already inside libMNN.so, no new runtime)
 * running MNN/bge-large-zh-MNN from ModelScope (~217 MB, 1024-d, Chinese-first, workable English).
 *
 * Loaded lazily on the first task that needs it, CPU only (short texts; ~30–90 ms each on a desktop core), and
 * released after [IDLE_MS] or on memory pressure. Absent model / non-arm64 → [Relevance] stays on bigram similarity.
 */
object TextEmbedder {
    @JvmStatic private external fun nativeLoad(config: String, extra: String): Long
    @JvmStatic private external fun nativeEmbed(h: Long, text: String): FloatArray?
    @JvmStatic private external fun nativeRelease(h: Long)

    const val REPO = "MNN/bge-large-zh-MNN"
    const val LABEL = "bge-large-zh（语义检索，约 217 MB）"
    const val MODEL_ID = "bge-large-zh-v2"  // bump if the model files / prefix change (old vectors are then ignored)
    /** bge's retrieval instruction for queries (documents are encoded as-is). */
    const val QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："
    /** Calibration: cos ≤ LO → unrelated (0), cos ≥ HI → same meaning (1). Measured on the bge MNN export. */
    const val LO = 0.55; const val HI = 0.80
    const val IDLE_MS = 60_000L

    fun dir(ctx: Context) = File(ctx.filesDir, "embed/" + REPO.substringAfterLast('/'))
    fun isInstalled(d: File): Boolean = File(d, "config.json").isFile && File(d, "embedding.mnn").isFile &&
        (d.listFiles()?.none { it.name.endsWith(".part") } ?: false)
    fun installed(ctx: Context) = isInstalled(dir(ctx))
    fun sizeMb(ctx: Context) = dir(ctx).walk().filter { it.isFile }.sumOf { it.length() } / 1_000_000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("local_model", 0)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("semantic_on", true)
    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("semantic_on", on).apply(); if (!on) { Relevance.index = null; release() } }
    fun llmRerank(ctx: Context) = prefs(ctx).getBoolean("llm_rerank", false)
    fun setLlmRerank(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("llm_rerank", on).apply()

    @Volatile private var handle = 0L
    @Volatile var lastError: String? = null; private set
    @Volatile var loadMs = 0L; private set
    @Volatile private var lastUse = 0L
    private val lock = Any()

    private fun load(ctx: Context): Long = synchronized(lock) {
        if (handle != 0L) return handle
        if (!EmbeddedMnn.available) { lastError = "本机推理库不可用（仅 arm64）"; return 0L }
        val d = dir(ctx); if (!isInstalled(d)) { lastError = "未安装语义模型"; return 0L }
        val t0 = System.currentTimeMillis()
        val extra = org.json.JSONObject().apply {
            // precision "normal" = fp32 compute. "low" would run fp16 on ARMv8.2 cores; a 24-layer BERT in fp16 risks
            // overflow/NaN in attention/LayerNorm and the vectors are cached forever, so accuracy wins over ~2× speed
            // (texts are short and each is encoded once). memory "low" keeps the weights int8/bf16-compact.
            put("backend_type", "cpu"); put("thread_num", 4); put("precision", "normal"); put("memory", "low")
            put("use_mmap", true); put("tmp_path", File(ctx.cacheDir, "mnn_mmap").apply { mkdirs() }.absolutePath)
        }.toString()
        handle = runCatching { nativeLoad(File(d, "config.json").absolutePath, extra) }.getOrDefault(0L)
        loadMs = System.currentTimeMillis() - t0
        lastError = if (handle == 0L) "语义模型加载失败" else null
        // Self-check: a probe must give a finite unit vector; otherwise the build/model is unusable → stay on bigram.
        if (handle != 0L) {
            val v = runCatching { nativeEmbed(handle, "闹钟") }.getOrNull()
            if (!probeOk(v)) { runCatching { nativeRelease(handle) }; handle = 0L; lastError = "语义模型自检失败（输出异常），已改用字符相似度"; broken = true }
            else dim = v!!.size
        }
        lastUse = System.currentTimeMillis()
        handle
    }

    @Volatile var broken = false; private set
    @Volatile var dim = 0; private set
    @Volatile var avgEncodeMs = 0L; private set
    /** Pure: finite, non-empty, L2 norm ≈ 1 (the JNI normalises). */
    fun probeOk(v: FloatArray?): Boolean = v != null && v.isNotEmpty() && v.all { it.isFinite() } &&
        kotlin.math.abs(kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }) - 1.0) < 0.01

    fun embed(ctx: Context, text: String, query: Boolean): FloatArray? {
        if (broken) return null
        val h = load(ctx); if (h == 0L) return null
        val t0 = System.currentTimeMillis()
        lastUse = System.currentTimeMillis()
        val v = synchronized(lock) { if (handle == 0L) null else runCatching { nativeEmbed(handle, (if (query) QUERY_PREFIX else "") + text.take(300)) }.getOrNull() }
        val ms = System.currentTimeMillis() - t0; avgEncodeMs = if (avgEncodeMs == 0L) ms else (avgEncodeMs * 3 + ms) / 4
        return v?.takeIf { probeOk(it) }
    }

    val loaded get() = handle != 0L
    /** Called by the idle reaper ([com.runasakura.phoneagent.util.Idle]); never blocks behind an encode. */
    fun releaseIfIdle(idleMs: Long = IDLE_MS) { if (handle != 0L && System.currentTimeMillis() - lastUse > idleMs) release() }

    fun release() = synchronized(lock) { if (handle != 0L) { runCatching { nativeRelease(handle) }; handle = 0L } }

    /** Install / remove the global semantic index according to what is on disk. Cheap; call off the main thread. */
    fun attach(ctx: Context): SemanticIndex? {
        val app = ctx.applicationContext
        // No System.loadLibrary here (this may run on the main thread): the ABI check is enough; the first encode loads
        // the native lib on the caller's background thread and returns null if that fails.
        val idx = if (enabled(app) && installed(app) && "arm64-v8a" in android.os.Build.SUPPORTED_ABIS)
            (Relevance.index?.takeIf { it.modelId == MODEL_ID } ?: SemanticIndex(MODEL_ID, { t, q -> embed(app, t, q) },
                File(app.filesDir, "embed/vectors_$MODEL_ID.bin"), LO, HI))
        else null
        Relevance.index = idx
        return idx
    }

    fun delete(ctx: Context) { release(); Relevance.index = null; dir(ctx).deleteRecursively(); File(ctx.filesDir, "embed/vectors_$MODEL_ID.bin").delete() }
}

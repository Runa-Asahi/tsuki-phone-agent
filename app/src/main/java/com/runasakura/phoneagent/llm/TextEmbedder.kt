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
    const val MODEL_ID = "bge-large-zh-v1"  // bump if the model files / prefix change (old vectors are then ignored)
    /** bge's retrieval instruction for queries (documents are encoded as-is). */
    const val QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："
    /** Calibration: cos ≤ LO → unrelated (0), cos ≥ HI → same meaning (1). Measured on the bge MNN export. */
    const val LO = 0.55; const val HI = 0.80
    private const val IDLE_MS = 120_000L

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
            put("backend_type", "cpu"); put("thread_num", 4); put("precision", "low"); put("memory", "low")
            put("use_mmap", true); put("tmp_path", File(ctx.cacheDir, "mnn_mmap").apply { mkdirs() }.absolutePath)
        }.toString()
        handle = runCatching { nativeLoad(File(d, "config.json").absolutePath, extra) }.getOrDefault(0L)
        loadMs = System.currentTimeMillis() - t0
        lastError = if (handle == 0L) "语义模型加载失败" else null
        scheduleIdleRelease()
        handle
    }

    fun embed(ctx: Context, text: String, query: Boolean): FloatArray? {
        val h = load(ctx); if (h == 0L) return null
        lastUse = System.currentTimeMillis()
        return synchronized(lock) { if (handle == 0L) null else runCatching { nativeEmbed(handle, (if (query) QUERY_PREFIX else "") + text.take(300)) }.getOrNull() }
    }

    private fun scheduleIdleRelease() {
        val t = Thread {
            while (true) {
                Thread.sleep(15_000)
                synchronized(lock) { if (handle == 0L) return@Thread; if (System.currentTimeMillis() - lastUse > IDLE_MS) { nativeRelease(handle); handle = 0L; return@Thread } }
            }
        }
        t.isDaemon = true; t.name = "tsuki-embed-idle"; t.start()
    }

    fun release() = synchronized(lock) { if (handle != 0L) { runCatching { nativeRelease(handle) }; handle = 0L } }

    /** Install / remove the global semantic index according to what is on disk. Cheap; call off the main thread. */
    fun attach(ctx: Context): SemanticIndex? {
        val app = ctx.applicationContext
        val idx = if (enabled(app) && installed(app) && EmbeddedMnn.available)
            (Relevance.index?.takeIf { it.modelId == MODEL_ID } ?: SemanticIndex(MODEL_ID, { t, q -> embed(app, t, q) },
                File(app.filesDir, "embed/vectors_$MODEL_ID.bin"), LO, HI))
        else null
        Relevance.index = idx
        return idx
    }

    fun delete(ctx: Context) { release(); Relevance.index = null; dir(ctx).deleteRecursively(); File(ctx.filesDir, "embed/vectors_$MODEL_ID.bin").delete() }
}

package com.runasakura.phoneagent.util

import android.content.Context
import com.runasakura.phoneagent.llm.EmbeddedMnn
import com.runasakura.phoneagent.llm.ModelStore
import com.runasakura.phoneagent.llm.TextEmbedder
import java.io.File

/**
 * Storage hygiene: what Tsuki keeps on disk, and safe cleanup.
 *
 * Safe (automatic, at most once a day, off the main thread): image temps for the local model, abandoned `.part`
 * downloads older than [STALE_PART_MS] (never while a download runs), half-created model folders, `.tmp` leftovers.
 * Manual ("一键清理"): also MNN's weight mmap cache (only when no model is loaded) and the lesson-vector cache
 * (recomputed on demand). Installed models are never removed here — they are deleted explicitly in Settings.
 */
object StorageJanitor {
    const val STALE_PART_MS = 3L * 86_400_000
    data class Usage(val models: Long, val embedModel: Long, val vectors: Long, val mmapCache: Long, val temp: Long, val partial: Long, val asrModel: Long = 0) {
        val total get() = models + embedModel + vectors + mmapCache + temp + partial + asrModel
    }
    data class Plan(val delete: List<File>, val bytes: Long)

    private fun size(f: File): Long = if (!f.exists()) 0L else if (f.isFile) f.length() else f.walk().filter { it.isFile }.sumOf { it.length() }
    private fun parts(root: File) = if (!root.isDirectory) emptySequence() else root.walk().filter { it.isFile && it.name.endsWith(".part") }

    fun usage(ctx: Context): Usage {
        val models = ModelStore.root(ctx); val embedRoot = File(ctx.filesDir, "embed")
        val asrRoot = com.runasakura.phoneagent.voice.LocalAsr.root(ctx)
        val partial = parts(models).sumOf { it.length() } + parts(embedRoot).sumOf { it.length() } + parts(asrRoot).sumOf { it.length() }
        val vectors = embedRoot.listFiles()?.filter { it.isFile && it.name.startsWith("vectors_") }?.sumOf { it.length() } ?: 0L
        return Usage(size(models) - parts(models).sumOf { it.length() }, size(TextEmbedder.dir(ctx)) - parts(embedRoot).sumOf { it.length() },
            vectors, size(File(ctx.cacheDir, "mnn_mmap")), temps(ctx).sumOf { it.length() }, partial, size(asrRoot) - parts(asrRoot).sumOf { it.length() })
    }

    private fun temps(ctx: Context): List<File> = ctx.cacheDir.listFiles()?.filter { it.isFile && (it.name.startsWith("mnn_img_") || it.name.endsWith(".tmp")) }.orEmpty() +
        (File(ctx.filesDir, "embed").listFiles()?.filter { it.isFile && it.name.endsWith(".tmp") }.orEmpty())

    /**
     * Pure planner (unit-tested): which files to delete. [downloading] protects every `.part`; [manual] adds caches
     * that are only safe when nothing is loaded ([modelLoaded] false).
     */
    fun plan(temps: List<File>, parts: List<File>, emptyModelDirs: List<File>, mmap: File?, vectors: List<File>,
             now: Long, downloading: Boolean, manual: Boolean, modelLoaded: Boolean): Plan {
        val out = ArrayList<File>()
        out += temps
        if (!downloading) {
            out += parts.filter { manual || now - it.lastModified() > STALE_PART_MS }
            out += emptyModelDirs
        }
        if (manual && !modelLoaded && mmap != null) out += mmap
        if (manual) out += vectors
        return Plan(out, out.sumOf { size(it) })
    }

    private fun emptyModelDirs(ctx: Context): List<File> = (ModelStore.root(ctx).listFiles().orEmpty().toList() + File(ctx.filesDir, "embed").listFiles().orEmpty() + com.runasakura.phoneagent.voice.LocalAsr.root(ctx).listFiles().orEmpty())
        .filter { d -> d.isDirectory && d.walk().none { it.isFile } }

    fun clean(ctx: Context, manual: Boolean): Long {
        val downloading = com.runasakura.phoneagent.service.ModelDownloadService.state.value.running
        val loaded = EmbeddedMnn.loadedDir != null || TextEmbedder.loaded || com.runasakura.phoneagent.service.AgentForegroundService.isRunning()
        val embedRoot = File(ctx.filesDir, "embed")
        val p = plan(temps(ctx), (parts(ModelStore.root(ctx)) + parts(embedRoot) + parts(com.runasakura.phoneagent.voice.LocalAsr.root(ctx))).toList(), emptyModelDirs(ctx), File(ctx.cacheDir, "mnn_mmap").takeIf { it.exists() },
            embedRoot.listFiles()?.filter { it.isFile && it.name.startsWith("vectors_") }.orEmpty(),
            System.currentTimeMillis(), downloading, manual, loaded)
        if (manual && p.delete.any { it.name.startsWith("vectors_") }) { TextEmbedder.release(); com.runasakura.phoneagent.agent.Relevance.index = null }
        p.delete.forEach { runCatching { it.deleteRecursively() } }
        if (manual) TextEmbedder.attach(ctx)
        return p.bytes
    }

    /** Daily automatic pass (safe items only), started from Application.onCreate on a background thread. */
    fun autoClean(ctx: Context) {
        val sp = ctx.getSharedPreferences("ui", 0); val now = System.currentTimeMillis()
        if (now - sp.getLong("janitor_at", 0) < 86_400_000) return
        sp.edit().putLong("janitor_at", now).apply()
        runCatching { clean(ctx, manual = false) }
    }

    fun mb(b: Long) = if (b >= 1_000_000_000) "%.1f GB".format(b / 1e9) else "${b / 1_000_000} MB"
}

package com.runasakura.phoneagent.llm

import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * On-device models for [EmbeddedMnn]: one-tap download from ModelScope (public, anonymous — no token),
 * resumable + sha256, import a folder, and auto-detect a model the user already copied out of MNN Chat.
 * Models live in filesDir/models/<name>/.
 *
 * ModelScope: public repos need no login. File list:
 *   GET https://modelscope.cn/api/v1/models/<owner>/<name>/repo/files?Revision=master&Recursive=true
 * Blobs (CDN, preferred):
 *   GET https://modelscope.cn/models/<owner>/<name>/resolve/master/<path>
 * API fallback:
 *   GET https://modelscope.cn/api/v1/models/<owner>/<name>/repo?Revision=master&FilePath=<path>
 */
object ModelStore {
    const val DEFAULT_REPO = "MNN/Qwen3-VL-2B-Instruct-MNN"
    const val DEFAULT_LABEL = "Qwen3-VL-2B（推荐，约 1.5 GB，可看图）"
    data class RemoteFile(val path: String, val size: Long, val sha256: String?)
    data class Progress(val done: Long, val total: Long, val file: String, val bps: Long = 0)
    /** A complete MNN LLM folder found outside the app (Downloads / Documents / …). */
    data class Found(val dir: File, val where: String)

    fun root(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun dirFor(ctx: Context, repo: String) = File(root(ctx), repo.substringAfterLast('/'))
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("local_model", 0)
    fun selected(ctx: Context): File? = prefs(ctx).getString("dir", null)?.let(::File)?.takeIf { isComplete(it) }
        ?: installed(ctx).firstOrNull()
    fun select(ctx: Context, dir: File) = prefs(ctx).edit().putString("dir", dir.absolutePath).apply()
    fun installed(ctx: Context): List<File> = root(ctx).listFiles()?.filter { isComplete(it) }?.sortedBy { it.name }.orEmpty()
    /** A usable MNN LLM folder: config.json + llm.mnn + weights (a .part file means an unfinished download). */
    fun isComplete(d: File): Boolean = d.isDirectory && File(d, "config.json").isFile && File(d, "llm.mnn").isFile &&
        (d.listFiles()?.none { it.name.endsWith(".part") } ?: false)
    fun sizeOf(d: File): Long = d.walk().filter { it.isFile }.sumOf { it.length() }
    private val sizeCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()
    /** [sizeOf] memoised by folder mtime (Settings shows it on every resume). */
    fun sizeOfCached(d: File): Long { val m = d.lastModified(); sizeCache[d.absolutePath]?.takeIf { it.first == m }?.let { return it.second }
        return sizeOf(d).also { sizeCache[d.absolutePath] = m to it } }
    fun isVision(d: File): Boolean = File(d, "visual.mnn").isFile
    /** Bytes already on disk for an in-progress download of [repo] (for the status line before resume). */
    fun partialBytes(ctx: Context, repo: String = DEFAULT_REPO): Long {
        val dir = dirFor(ctx, repo); if (!dir.isDirectory) return 0L
        return dir.walk().filter { it.isFile }.sumOf { it.length() }
    }

    /** Pure: ModelScope `repo/files` JSON → files to fetch (README/.gitattributes skipped). */
    fun parseFiles(json: String): List<RemoteFile> {
        val arr = org.json.JSONObject(json).optJSONObject("Data")?.optJSONArray("Files") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("Type") == "blob" }
            .map { RemoteFile(it.optString("Path"), it.optLong("Size"), it.optString("Sha256").ifBlank { null }) }
            .filterNot { it.path.startsWith(".") || it.path.endsWith(".md") || it.path.contains('/') }
    }

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                    .header("User-Agent", "TsukiPhoneAgent/0.11 (Android; ModelScope public download)")
                    .build())
            }.build()
    }
    private fun api(repo: String) = "https://modelscope.cn/api/v1/models/$repo/repo"
    private fun cdn(repo: String, path: String) = "https://modelscope.cn/models/$repo/resolve/master/${Uri.encode(path).replace("%2F", "/")}"

    /**
     * Scan common user-visible folders for a complete MNN model (e.g. one copied out of MNN Chat).
     * MNN Chat's own private filesDir is not readable by other apps — only paths the user can see.
     */
    fun findExisting(ctx: Context): List<Found> {
        val out = ArrayList<Found>()
        val seen = HashSet<String>()
        fun add(d: File, where: String) {
            val c = runCatching { d.canonicalPath }.getOrNull() ?: return
            if (c in seen || !isComplete(d)) return
            // Skip our own install dir.
            if (c.startsWith(root(ctx).canonicalPath)) return
            seen += c; out += Found(d, where)
        }
        fun walkShallow(root: File?, where: String, depth: Int = 2) {
            if (root == null || !root.isDirectory || depth < 0) return
            add(root, where)
            root.listFiles()?.forEach { f ->
                if (f.isDirectory) {
                    add(f, where)
                    if (depth > 0 && (f.name.contains("MNN", true) || f.name.contains("Qwen", true) ||
                            f.name.contains("model", true) || f.name.contains("llm", true))) walkShallow(f, where, depth - 1)
                    else if (depth > 0) f.listFiles()?.forEach { c -> if (c.isDirectory) add(c, where) }
                }
            }
        }
        val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        walkShallow(pub, "下载")
        walkShallow(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "文档")
        walkShallow(ctx.getExternalFilesDir(null)?.parentFile?.parentFile /* Android/data */, "应用共享", 1)
        // Common alternate roots some file managers use.
        walkShallow(File("/storage/emulated/0/Download"), "下载")
        walkShallow(File("/storage/emulated/0/Documents"), "文档")
        walkShallow(File("/sdcard/Download"), "下载")
        return out.distinctBy { it.dir.canonicalPath }
    }

    /** Resumable download (HTTP Range on <file>.part); every file is sha256-checked before it is renamed into place. */
    suspend fun download(ctx: Context, repo: String = DEFAULT_REPO, into: File = dirFor(ctx, repo), selectWhenDone: Boolean = true,
                         onProgress: (Progress) -> Unit): File = withContext(Dispatchers.IO) {
        val list = http.newCall(Request.Builder().url("${api(repo)}/files?Revision=master&Recursive=true").build()).execute().use {
            check(it.isSuccessful) { "获取文件列表失败 HTTP ${it.code}（公开模型无需登录；若持续失败请检查网络）" }; parseFiles(it.body!!.string())
        }
        check(list.any { it.path == "config.json" }) { "这个仓库不是 MNN 模型（缺少 config.json）" }
        val dir = into.apply { mkdirs() }
        val total = list.sumOf { it.size }
        val canon = dir.canonicalPath + File.separator
        check(list.all { File(dir, it.path).canonicalPath.startsWith(canon) }) { "文件列表含非法路径" }
        var done = list.sumOf { f -> File(dir, f.path).takeIf { it.isFile && it.length() == f.size }?.length() ?: 0L }
        val need = total - done + (200L shl 20)
        check(dir.usableSpace >= need) { "存储空间不足：还需约 ${need shr 20} MB" }
        val t0 = System.currentTimeMillis(); var lastDone = done; var lastT = t0
        for (f in list) {
            val out = File(dir, f.path); if (out.isFile && out.length() == f.size) continue
            out.parentFile?.mkdirs()
            val part = File(dir, f.path + ".part")
            // Prefer the CDN resolve URL; fall back to the API endpoint if it fails.
            val urls = listOf(cdn(repo, f.path), "${api(repo)}?Revision=master&FilePath=${Uri.encode(f.path)}")
            var ok = false
            for (u in urls) {
                val baseline = if (part.isFile) part.length() else 0L   // rewind here if this attempt fails
                val req = Request.Builder().url(u).apply { if (baseline > 0) header("Range", "bytes=$baseline-") }.build()
                try {
                    http.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) return@use
                        val append = baseline > 0 && r.code == 206
                        // Some CDNs ignore Range and return 200: restart the part.
                        val start = if (append) baseline else 0L
                        if (!append && part.exists()) part.delete()
                        java.io.FileOutputStream(part, append).use { o ->
                            val buf = ByteArray(1 shl 16); val ins = r.body!!.byteStream(); var cur = start
                            while (true) {
                                coroutineContext.ensureActive(); val n = ins.read(buf); if (n < 0) break; o.write(buf, 0, n); cur += n
                                val now = System.currentTimeMillis()
                                if (now - lastT > 500 || cur - lastDone > (2L shl 20)) {
                                    val bps = if (now > lastT) ((done + cur - start - lastDone) * 1000L) / (now - lastT).coerceAtLeast(1) else 0L
                                    lastDone = done + cur - start; lastT = now
                                    onProgress(Progress(done + cur - start, total, f.path, bps))
                                }
                            }
                            done += cur - start
                        }
                        ok = true
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) {
                    // Roll the .part back so the next URL (or a retry) does not append onto a torn write.
                    if (part.isFile) {
                        if (baseline <= 0L) part.delete()
                        else if (part.length() > baseline) java.io.RandomAccessFile(part, "rw").use { it.setLength(baseline) }
                    }
                }
                if (ok) break
            }
            check(ok) { "下载 ${f.path} 失败（CDN 与 API 均不可用）" }
            f.sha256?.let { want -> check(sha256(part).equals(want, true)) { part.delete(); "${f.path} 校验失败，已删除，请重试" } }
            check(part.renameTo(out)) { "无法写入 ${f.path}" }
            onProgress(Progress(done, total, f.path))
        }
        if (selectWhenDone) select(ctx, dir); dir
    }

    /** Import a folder picked with ACTION_OPEN_DOCUMENT_TREE (e.g. a model copied out of MNN Chat). */
    suspend fun import(ctx: Context, tree: Uri, onProgress: (Progress) -> Unit): File = withContext(Dispatchers.IO) {
        val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, tree) ?: error("无法读取所选文件夹")
        val files = doc.listFiles().filter { it.isFile }
        check(files.any { it.name == "config.json" } && files.any { it.name == "llm.mnn" }) { "所选文件夹里没有 config.json / llm.mnn，不是 MNN 模型目录" }
        val dir = File(root(ctx), (doc.name ?: "imported").replace(Regex("[^\\w.-]"), "_")).apply { mkdirs() }
        val total = files.sumOf { it.length() }; var done = 0L
        for (f in files) {
            val part = File(dir, f.name + ".part")
            ctx.contentResolver.openInputStream(f.uri)!!.use { i -> part.outputStream().use { o -> val b = ByteArray(1 shl 16)
                while (true) { coroutineContext.ensureActive(); val n = i.read(b); if (n < 0) break; o.write(b, 0, n); done += n } } }
            part.renameTo(File(dir, f.name!!)); onProgress(Progress(done, total, f.name!!))
        }
        select(ctx, dir); dir
    }

    fun delete(ctx: Context, d: File) { if (EmbeddedMnn.loadedDir == d.absolutePath) EmbeddedMnn.release(); d.deleteRecursively() }

    fun sha256(f: File): String { val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) } }
}

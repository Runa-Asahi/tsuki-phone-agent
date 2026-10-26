package com.runasakura.phoneagent.llm

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * On-device models for [EmbeddedMnn]: download from ModelScope (resumable, sha256-verified), import a folder
 * (e.g. copied out of MNN Chat), list, select, delete. Models live in filesDir/models/<name>/.
 */
object ModelStore {
    const val DEFAULT_REPO = "MNN/Qwen3-VL-2B-Instruct-MNN"
    data class RemoteFile(val path: String, val size: Long, val sha256: String?)
    data class Progress(val done: Long, val total: Long, val file: String)

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
    fun isVision(d: File): Boolean = File(d, "visual.mnn").isFile

    /** Pure: ModelScope `repo/files` JSON → files to fetch (README/.gitattributes skipped). */
    fun parseFiles(json: String): List<RemoteFile> {
        val arr = org.json.JSONObject(json).optJSONObject("Data")?.optJSONArray("Files") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("Type") == "blob" }
            .map { RemoteFile(it.optString("Path"), it.optLong("Size"), it.optString("Sha256").ifBlank { null }) }
            .filterNot { it.path.startsWith(".") || it.path.endsWith(".md") || it.path.contains('/') }
    }

    private val http by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }
    private fun api(repo: String) = "https://modelscope.cn/api/v1/models/$repo/repo"

    /** Resumable download (HTTP Range on <file>.part); every file is sha256-checked before it is renamed into place. */
    suspend fun download(ctx: Context, repo: String = DEFAULT_REPO, onProgress: (Progress) -> Unit): File = withContext(Dispatchers.IO) {
        val list = http.newCall(Request.Builder().url("${api(repo)}/files?Revision=master&Recursive=true").build()).execute().use {
            check(it.isSuccessful) { "获取文件列表失败 HTTP ${it.code}" }; parseFiles(it.body!!.string())
        }
        check(list.any { it.path == "config.json" }) { "这个仓库不是 MNN LLM 模型（缺少 config.json）" }
        val dir = dirFor(ctx, repo).apply { mkdirs() }
        val total = list.sumOf { it.size }
        var done = list.sumOf { f -> File(dir, f.path).takeIf { it.isFile && it.length() == f.size }?.length() ?: 0L }
        for (f in list) {
            val out = File(dir, f.path); if (out.isFile && out.length() == f.size) continue
            val part = File(dir, f.path + ".part")
            val have = if (part.isFile) part.length() else 0L
            val req = Request.Builder().url("${api(repo)}?Revision=master&FilePath=${Uri.encode(f.path)}").apply { if (have > 0) header("Range", "bytes=$have-") }.build()
            http.newCall(req).execute().use { r ->
                check(r.isSuccessful) { "下载 ${f.path} 失败 HTTP ${r.code}" }
                val append = have > 0 && r.code == 206
                java.io.FileOutputStream(part, append).use { o ->
                    val buf = ByteArray(1 shl 16); val ins = r.body!!.byteStream(); var last = 0L
                    var cur = if (append) have else 0L
                    while (true) { coroutineContext.ensureActive(); val n = ins.read(buf); if (n < 0) break; o.write(buf, 0, n); cur += n
                        if (cur - last > (4 shl 20)) { last = cur; onProgress(Progress(done + cur, total, f.path)) } }
                    done += cur
                }
            }
            f.sha256?.let { want -> check(sha256(part).equals(want, true)) { part.delete(); "${f.path} 校验失败，已删除，请重试" } }
            check(part.renameTo(out)) { "无法写入 ${f.path}" }
            onProgress(Progress(done, total, f.path))
        }
        select(ctx, dir); dir
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

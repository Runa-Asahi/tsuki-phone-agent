package com.runasakura.phoneagent.llm

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.runasakura.phoneagent.shell.PrivilegedShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where an already-downloaded MNN model can come from, given Android 16 scoped storage:
 *  - Direct File access to Download/Documents only shows this app's own files (others' model files are hidden),
 *    so a plain scan is almost always empty — that was the "未找到" bug.
 *  - SAF folder picker: works everywhere; we search the picked folder up to 3 levels deep and persist the grant.
 *  - Shizuku (shell identity): can read /sdcard (incl. Android/data) and /data/local/tmp/mnn_models — MNN Chat's
 *    "local models" folder — and stream files to us with `cat`.
 *  - MNN Chat's own downloads live in its private /data/data/<pkg>/files/.mnnmodels: no other app and not the shell
 *    can read that (only root). The honest answer there is "download the same model from ModelScope".
 */
object ModelSources {
    data class Candidate(val label: String, val where: String, val bytes: Long, val files: List<Pair<String, Long>>,
                         val shellDir: String? = null, val uris: Map<String, Uri> = emptyMap(), val local: File? = null)

    /** MNN Chat's shell-readable local model folder and other likely spots (shell identity). */
    val SHELL_ROOTS = listOf("/data/local/tmp/mnn_models", "/sdcard/Download", "/sdcard/Documents", "/sdcard/mnn_models",
        "/sdcard/Android/data/com.alibaba.mnnllm.android", "/sdcard/Android/data/com.alibaba.mnnllm.android.release")

    /** Pure: `find … -name llm.mnn` output → unique model directories. */
    fun dirsFromFind(out: String): List<String> = out.lineSequence().map { it.trim() }.filter { it.endsWith("/llm.mnn") }
        .map { it.removeSuffix("/llm.mnn") }.distinct().toList()

    /** Pure: `stat -c '%s %n'` lines → (name, size) for regular files directly in [dir]. */
    fun filesFromStat(out: String, dir: String): List<Pair<String, Long>> = out.lineSequence().mapNotNull { l ->
        val sp = l.trim().indexOf(' '); if (sp <= 0) return@mapNotNull null
        val size = l.trim().substring(0, sp).toLongOrNull() ?: return@mapNotNull null
        val path = l.trim().substring(sp + 1); if (!path.startsWith("$dir/")) return@mapNotNull null
        val name = path.removePrefix("$dir/"); if (name.contains('/') || name.isBlank() || name == "*") null else name to size
    }.toList()

    fun looksComplete(names: Collection<String>) = "config.json" in names && "llm.mnn" in names && names.none { it.endsWith(".part") }

    /** Shizuku: find complete model folders in [SHELL_ROOTS]. */
    fun scanShell(sh: PrivilegedShell): List<Candidate> {
        val found = ArrayList<Candidate>()
        val roots = SHELL_ROOTS
        val r = sh.exec(listOf("find") + roots + listOf("-maxdepth", "6", "-name", "llm.mnn"), 15_000)
        for (dir in dirsFromFind(r.text)) {
            // stat every entry of the folder; the path goes in as $1 so nothing is interpreted by the shell.
            val st = sh.exec(listOf("sh", "-c", "stat -c '%s %n' \"$1\"/*", "tsuki", dir), 6_000)
            val files = filesFromStat(st.text, dir)
            if (!looksComplete(files.map { it.first })) continue
            val where = when { dir.startsWith("/data/local/tmp") -> "MNN Chat 本地模型目录"; dir.contains("/Android/data/") -> "MNN Chat 外部目录"; else -> "手机存储" }
            found += Candidate(dir.substringAfterLast('/'), where, files.sumOf { it.second }, files, shellDir = dir)
        }
        return found
    }

    /** SAF: the picked folder itself, or model folders up to 3 levels below it (users often pick "Download"). */
    suspend fun scanTree(ctx: Context, tree: Uri): List<Candidate> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return@withContext emptyList()
        val out = ArrayList<Candidate>()
        fun visit(d: DocumentFile, depth: Int) {
            val kids = d.listFiles()
            val files = kids.filter { it.isFile }
            if (looksComplete(files.mapNotNull { it.name })) {
                out += Candidate(d.name ?: "model", "所选文件夹", files.sumOf { it.length() }, files.map { (it.name ?: "") to it.length() },
                    uris = files.associate { (it.name ?: "") to it.uri })
                return
            }
            if (depth > 0) kids.filter { it.isDirectory && it.name?.startsWith(".") != true }.forEach { visit(it, depth - 1) }
        }
        visit(root, 3); out
    }

    /** Copy a candidate into filesDir/models/<name> (resumable per file via size check) and select it. */
    suspend fun adopt(ctx: Context, c: Candidate, onProgress: (ModelStore.Progress) -> Unit): File = withContext(Dispatchers.IO) {
        // Same model already installed (e.g. imported earlier under another name): just select it, no second copy.
        ModelStore.duplicateOf(ctx, c.files)?.let { ModelStore.select(ctx, it); return@withContext it }
        val dir = File(ModelStore.root(ctx), c.label.replace(Regex("[^\\w.-]"), "_")).apply { mkdirs() }
        val need = c.bytes - dir.walk().filter { it.isFile }.sumOf { it.length() } + (100L shl 20)
        check(dir.usableSpace >= need) { "存储空间不足：还需约 ${need shr 20} MB" }
        var done = 0L
        for ((name, size) in c.files) {
            val out = File(dir, name)
            if (out.isFile && out.length() == size) { done += size; continue }
            val part = File(dir, "$name.part"); part.delete()
            val input: java.io.InputStream = when {
                c.shellDir != null -> com.runasakura.phoneagent.shell.ShizukuShell.openStream(listOf("cat", "${c.shellDir}/$name"))
                c.uris.isNotEmpty() -> ctx.contentResolver.openInputStream(c.uris[name] ?: error("找不到 $name")) ?: error("无法读取 $name")
                c.local != null -> File(c.local, name).inputStream()
                else -> error("没有可用的读取方式")
            }
            input.use { i -> part.outputStream().use { o ->
                val b = ByteArray(1 shl 16); var last = 0L
                while (true) { coroutineContext.ensureActive(); val n = i.read(b); if (n < 0) break; o.write(b, 0, n); done += n
                    if (done - last > (4L shl 20)) { last = done; onProgress(ModelStore.Progress(done, c.bytes, name)) } }
            } }
            check(part.length() == size) { part.delete(); "$name 复制不完整（${part.length()}/$size），请重试" }
            check(part.renameTo(out)) { "无法写入 $name" }
            onProgress(ModelStore.Progress(done, c.bytes, name))
        }
        check(ModelStore.isComplete(dir)) { "复制后模型不完整" }
        ModelStore.select(ctx, dir); dir
    }

    /** Model folders this app can see directly (its own Download files; rarely anything under scoped storage). */
    fun scanDirect(ctx: Context): List<Candidate> = ModelStore.findExisting(ctx).map { f ->
        val files = f.dir.listFiles()?.filter { it.isFile }.orEmpty()
        Candidate(f.dir.name, f.where, files.sumOf { it.length() }, files.map { it.name to it.length() }, local = f.dir)
    }

    /** Set by the UI right before starting [com.runasakura.phoneagent.service.ModelDownloadService] in adopt mode. */
    @Volatile var pending: Candidate? = null

    /** Last SAF folder the user granted (persisted) for one-tap rescans. */
    fun lastTree(ctx: Context): Uri? = ctx.getSharedPreferences("local_model", 0).getString("tree", null)?.let(Uri::parse)
        ?.takeIf { u -> ctx.contentResolver.persistedUriPermissions.any { it.uri == u && it.isReadPermission } }
    fun rememberTree(ctx: Context, u: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        ctx.getSharedPreferences("local_model", 0).edit().putString("tree", u.toString()).apply()
    }
}

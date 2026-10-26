package com.runasakura.phoneagent.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.runasakura.phoneagent.PhoneAgentApp
import com.runasakura.phoneagent.R
import com.runasakura.phoneagent.llm.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Foreground download / import of an on-device model (1.5 GB) that survives leaving Settings. Resumable. */
class ModelDownloadService : Service() {
    data class State(val running: Boolean, val done: Long = 0, val total: Long = 0, val msg: String = "")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) { job?.cancel(); state.value = State(false, msg = "已取消（下次可续传）"); stopSelf(); return START_NOT_STICKY }
        startFg("准备中…", 0, 0)
        if (job?.isActive == true) return START_NOT_STICKY
        val tree = intent?.getStringExtra(EXTRA_TREE)?.let(Uri::parse)
        val repo = intent?.getStringExtra(EXTRA_REPO) ?: ModelStore.DEFAULT_REPO
        job = scope.launch {
            state.value = State(true, msg = if (tree != null) "导入中…" else "下载中…")
            try {
                val cb: (ModelStore.Progress) -> Unit = { p ->
                    state.value = State(true, p.done, p.total, p.file)
                    startFg(p.file, p.done, p.total)
                }
                val d = if (tree != null) ModelStore.import(this@ModelDownloadService, tree, cb) else ModelStore.download(this@ModelDownloadService, repo, cb)
                state.value = State(false, msg = "完成：${d.name}（${ModelStore.sizeOf(d) / 1_000_000} MB）")
                com.runasakura.phoneagent.llm.LocalWarmup.kick(applicationContext)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { state.value = State(false, msg = "失败：${e.message}（再点一次可续传）") }
            finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun startFg(text: String, done: Long, total: Long) {
        val n = NotificationCompat.Builder(this, PhoneAgentApp.CHANNEL_AGENT).setSmallIcon(R.drawable.ic_agent)
            .setContentTitle("Tsuki 正在准备本机模型").setContentText(text).setOngoing(true).setSilent(true)
            .setProgress(1000, if (total > 0) (done * 1000 / total).toInt() else 0, total <= 0).build()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
        } catch (_: Throwable) { runCatching {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                android.os.Build.VERSION.SDK_INT < 33) NotificationManagerCompat.from(this).notify(ID, n) } }
    }

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }

    companion object {
        private const val ID = 4311
        const val EXTRA_TREE = "tree"; const val EXTRA_REPO = "repo"; const val ACTION_CANCEL = "cancel"
        val state = MutableStateFlow(State(false))
        fun download(ctx: Context) = ctx.startForegroundService(Intent(ctx, ModelDownloadService::class.java))
        fun import(ctx: Context, tree: Uri) = ctx.startForegroundService(Intent(ctx, ModelDownloadService::class.java).putExtra(EXTRA_TREE, tree.toString()))
        fun cancel(ctx: Context) = ctx.startService(Intent(ctx, ModelDownloadService::class.java).setAction(ACTION_CANCEL))
    }
}

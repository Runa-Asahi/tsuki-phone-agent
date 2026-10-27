package com.runasakura.phoneagent.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.runasakura.phoneagent.PhoneAgentApp
import com.runasakura.phoneagent.prefs.AgentPreferences
import com.runasakura.phoneagent.R
import com.runasakura.phoneagent.agent.AgentLoop
import com.runasakura.phoneagent.agent.SafetyGate
import com.runasakura.phoneagent.llm.LlmRouter
import com.runasakura.phoneagent.llm.LocalMnnClient
import com.runasakura.phoneagent.overlay.EmergencyStopOverlay
import com.runasakura.phoneagent.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.coroutineContext

class AgentForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
        kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.e(TAG, "uncaught in agent scope", e); com.runasakura.phoneagent.util.Diagnostics.log("未捕获异常：$e") })
    private var job: Job? = null
    private val stopFlag = AtomicBoolean(false)
    private val pauseFlag = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    private var overlay: EmergencyStopOverlay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                requestStop(intent.getStringExtra(EXTRA_REASON) ?: "通知栏「停止」")
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> { togglePause(); return START_NOT_STICKY }
            ACTION_START -> {
                val goal = intent.getStringExtra(EXTRA_GOAL).orEmpty()
                sessionContext = intent.getStringExtra(EXTRA_CONTEXT)
                if (goal.isBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIF_ID, buildNotification("准备中…", running = true))
                begin(goal)
            }
        }
        return START_NOT_STICKY
    }

    private fun begin(goal: String) {
        com.runasakura.phoneagent.util.Diagnostics.reset(goal)
        com.runasakura.phoneagent.util.TaskTiming.reset()
        if (com.runasakura.phoneagent.a11y.AgentAccessibilityService.require() == null &&
            !com.runasakura.phoneagent.a11y.AgentAccessibilityService.isEnabledInSettings(this)) {
            log("无障碍服务未开启：系统设置里本应用的无障碍开关是关闭的（可能被系统省电清理或手动关闭；安装新版本后也需要重新打开）")
            broadcastStatus("stopped", "无障碍服务未开启：请到系统设置→辅助功能→已安装的服务中重新打开 Tsuki")
            // Toast from service context
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    this,
                    "无障碍服务已断开，请重新开启后重试",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            starting.set(false)
            stopSelf()
            return
        }
        // Cancel any previous loop (and its in-flight MNN call) before starting a new one
        job?.let {
            if (it.isActive) {
                log("取消上一个任务及其 MNN 请求")
                it.cancel()
            }
        }
        pendingConfirm?.invoke(false)
        pendingConfirm = null
        overlay?.hide()
        overlay = null
        starting.set(true); stopReason = null
        stopFlag.set(false)
        // Prefer the accessibility overlay (no SYSTEM_ALERT_WINDOW needed)
        val overlayCtx: android.content.Context = com.runasakura.phoneagent.a11y.AgentAccessibilityService.require() ?: this
        pauseFlag.set(false)
        overlay = EmergencyStopOverlay(overlayCtx, { requestStop("悬浮窗「急停」被点击") }, { togglePause() }, {
            startActivity(android.content.Intent(this, com.runasakura.phoneagent.ui.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }).also { it.show() }
        val prefs = PhoneAgentApp.instance.prefs
        // Local engine: embedded MNN inside Tsuki (default; runs in this foreground-service process) or MNN Chat's API.
        val embedded = prefs.localEngine == AgentPreferences.LOCAL_EMBEDDED
        val app = applicationContext
        val local: com.runasakura.phoneagent.llm.LlmClient = if (embedded)
            com.runasakura.phoneagent.llm.EmbeddedMnnClient(app, { com.runasakura.phoneagent.llm.ModelStore.selected(app) })
            else LocalMnnClient(prefs.mnnBaseUrl, prefs.mnnApiKey, prefs.mnnModel)
        if (embedded && com.runasakura.phoneagent.llm.RoutePolicy.usesLocal(prefs.routeMode)) com.runasakura.phoneagent.llm.LocalWarmup.kick(app) { log(it) }
        val cloud = com.runasakura.phoneagent.llm.CloudProviders.clientFor(prefs)
        cloud?.onRetry = { code, n, wait ->
            log("云端${code.zh}，${"%.1f".format(wait / 1000.0)}s 后自动重试（$n/3）")
        }
        val brain = com.runasakura.phoneagent.llm.CloudProviders.describe(prefs.routeMode, prefs.cloudProvider,
            prefs.cloudBaseUrl, prefs.cloudModel, prefs.cloudApiKey, prefs.mnnModel)
        com.runasakura.phoneagent.util.Diagnostics.model = brain
        log("大脑：$brain" + if (cloud == null && prefs.routeMode != AgentPreferences.ROUTE_LOCAL_ONLY) "（云端未就绪）" else "")
        val router = LlmRouter({ prefs.routeMode }, local, cloud).also { r -> r.log = { log(it) } }
        // Lesson retrieval: on-device embedding index when the semantic model is installed (else bigram fallback).
        com.runasakura.phoneagent.llm.TextEmbedder.attach(app)
        com.runasakura.phoneagent.agent.Relevance.llmRerank = com.runasakura.phoneagent.llm.TextEmbedder.llmRerank(app)
        router.vision = com.runasakura.phoneagent.llm.CloudProviders.visionClientFor(prefs)
        val visionDir = com.runasakura.phoneagent.llm.ModelStore.selected(app)?.takeIf { com.runasakura.phoneagent.llm.ModelStore.isVision(it) }
        val eye = com.runasakura.phoneagent.llm.VisionChoice.pick(prefs.routeMode, visionDir != null, com.runasakura.phoneagent.llm.EmbeddedMnn.available, prefs.visionLocal)
        when (eye) {
            com.runasakura.phoneagent.llm.VisionChoice.Source.EMBEDDED -> {
                router.visionSkill = com.runasakura.phoneagent.llm.EmbeddedMnnClient(app, { com.runasakura.phoneagent.llm.ModelStore.selected(app) }, maxTokens = 96, assist = true)
                // A vision-only role still needs the model warm, even when the text engine is MNN Chat.
                if (!embedded) com.runasakura.phoneagent.llm.LocalWarmup.kick(app) { log(it) }
            }
            com.runasakura.phoneagent.llm.VisionChoice.Source.MNN_CHAT ->
                router.visionSkill = LocalMnnClient(prefs.mnnBaseUrl, prefs.mnnApiKey, prefs.visionLocalModel.ifBlank { prefs.mnnModel }, maxTokens = 96)
            com.runasakura.phoneagent.llm.VisionChoice.Source.NONE -> {}
        }
        router.visionSkillLabel = when (eye) { com.runasakura.phoneagent.llm.VisionChoice.Source.EMBEDDED -> visionDir?.name
            com.runasakura.phoneagent.llm.VisionChoice.Source.MNN_CHAT -> prefs.visionLocalModel.ifBlank { prefs.mnnModel }; else -> null }
        router.visionSkillSource = eye
        log("看图技能：" + eye.label + (router.visionSkillLabel?.let { "（$it）" } ?: "") +
            if (eye == com.runasakura.phoneagent.llm.VisionChoice.Source.MNN_CHAT && visionDir == null) "；安装内置视觉模型后会自动改用内置" else "")
        if (router.vision != null) log("视觉辅助：${prefs.visionModel}（截图${if (prefs.allowCloudScreenshot) "已允许" else "未允许，视觉不会使用"}）")
        val loop = AgentLoop(
            prefs = prefs,
            router = router,
            safety = SafetyGate(prefs),
            onLog = { msg ->
                log(msg)
                updateNotification(msg.take(60))
                progressFor(msg)?.let { overlay?.setProgress(it) }
            },
            onConfirm = { reason -> askConfirm(reason) },
            isStopped = { stopFlag.get() },
            isPaused = { pauseFlag.get() },
            historyText = {
                com.runasakura.phoneagent.ui.TaskHistory.load(this).take(10).joinToString("\n") {
                    "${if (it.ok) "成功" else "失败"} | ${it.goal} | ${it.summary}" } +
                    "\n--- 最近诊断日志 ---\n" + com.runasakura.phoneagent.util.Diagnostics.export("", "").lines()
                        .filterNot { l -> l.contains("key=") || l.contains("LLM raw") }.joinToString("\n").takeLast(4000)
            }
        ).also { it.sessionContext = sessionContext }
        val previous = job
        job = scope.launch(Dispatchers.Default) {
            previous?.join()
            val tw = System.currentTimeMillis()
            com.runasakura.phoneagent.ocr.OcrEngine.warmUp()
            (System.currentTimeMillis() - tw).let { if (it > 300) log("OCR 预热 ${it}ms") }
            broadcastStatus("running", "开始：$goal")
            if (!com.runasakura.phoneagent.llm.RoutePolicy.usesLocal(prefs.routeMode)) {
                log("仅云端：跳过本机 MNN 检测")
            } else if (local !is LocalMnnClient) {
                log("本机模型：" + com.runasakura.phoneagent.llm.LocalWarmup.status(app) + "（Tsuki 内置，后台预热，不阻塞任务）")
                com.runasakura.phoneagent.util.Diagnostics.model = "$brain | 本机=" + (com.runasakura.phoneagent.llm.ModelStore.selected(app)?.name ?: "未安装")
            } else try {
                // Bounded: a backgrounded MNN Chat answered /v1/models after 75 s on PPG-AN00 (00:29:51 → 00:31:06).
                val live = kotlinx.coroutines.withTimeoutOrNull(3_000) { local.probe() }
                val m = live ?: (kotlinx.coroutines.withTimeoutOrNull(1_000) { local.resolveModel() } ?: "未响应")
                log(if (live != null) "MNN 当前模型：$live" else if (cloud != null) "本机 MNN 未连接：本次由云端模型 ${prefs.cloudModel} 负责" else "本机 MNN 未连接，且未配置云端模型：需要模型判断的步骤会失败")
                com.runasakura.phoneagent.util.Diagnostics.model = "$brain | MNN=$m"
                if (m.contains("reason", true) || m.contains("think", true) || m.contains("distill", true)) {
                    log("提示：推理(Reasoning)模型会输出长思考，建议换 Qwen3 1.7B/4B 或 Qwen2.5 Instruct（MNN）")
                }
            } catch (e: Exception) {
                log("读取 /v1/models 失败：${e.message}")
            }
            val result = try {
                kotlinx.coroutines.withTimeoutOrNull(TASK_TIMEOUT_MS) { loop.run(goal) }
                    ?: AgentLoop.Result(false, 0, "任务超时（${TASK_TIMEOUT_MS / 60000} 分钟），已停止")
            } catch (e: kotlinx.coroutines.CancellationException) {
                AgentLoop.Result(false, 0, "已停止（${stopReason ?: "任务被取消"}）")
            } catch (e: Throwable) {
                Log.e(TAG, "loop error", e)
                AgentLoop.Result(false, 0, if (e is OutOfMemoryError) "内存不足，已停止" else e.message ?: e.toString())
            }
            log("模型统计：${router.summary()}")
            log("耗时分解：${com.runasakura.phoneagent.util.TaskTiming.summary()}")
            com.runasakura.phoneagent.util.Diagnostics.log("结果: success=${result.success} ${result.summary}")
            broadcastStatus(
                if (result.success) "done" else "stopped",
                "结束(${result.steps}步): ${if (result.needsUser) com.runasakura.phoneagent.ui.ChatSession.ASK_MARK else ""}${result.summary}"
            )
            if (job == coroutineContext[Job]) {
                overlay?.hide()
                overlay = null
                starting.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private var sessionContext: String? = null
    private var stepLabel = ""

    /** Short overlay label: current step + latest action, never raw model output. */
    private fun progressFor(msg: String): String? = when {
        msg.startsWith("—— 步骤") -> { stepLabel = msg.removePrefix("—— 步骤 ").substringBefore("/").let { "第${it}步" }; "$stepLabel 思考中…" }
        msg.startsWith("action:") -> "$stepLabel ${com.runasakura.phoneagent.ui.ActionText.describe(msg.substringAfter(":").trim())}"
        msg.startsWith("正在理解任务") -> "理解任务…"
        msg.startsWith("通话流程") || msg.startsWith("表情流程") || msg.startsWith("匹配应用") || msg.startsWith("子目标") -> msg.take(20)
        msg.startsWith("⏸") -> "已暂停"
        else -> null
    }

    private fun togglePause(): Boolean {
        val p = !pauseFlag.get(); pauseFlag.set(p)
        com.runasakura.phoneagent.ui.ChatSession.setPaused(p)
        broadcastStatus("paused", if (p) "1" else "0")
        overlay?.setProgress(if (p) "已暂停" else "继续…")
        return p
    }

    private suspend fun askConfirm(reason: String): Boolean {
        com.runasakura.phoneagent.util.Diagnostics.log("[确认] $reason")
        broadcastStatus("confirm-overlay", reason)
        updateNotification("等待确认: ${reason.take(40)}")
        overlay?.setProgress("等待你确认")
        // Preferred: accessibility overlay drawn by the service (works on top of any app)
        com.runasakura.phoneagent.a11y.AgentAccessibilityService.require()?.confirmOverlay(reason)?.let {
            log(if (it) "用户确认" else "用户拒绝")
            return it
        }
        log("无障碍浮窗不可用，改用确认页")
        return askConfirmActivity(reason)
    }

    private suspend fun askConfirmActivity(reason: String): Boolean =
        suspendCancellableCoroutine { cont ->
            pendingConfirm = { ok ->
                if (cont.isActive) cont.resume(ok)
                pendingConfirm = null
            }
            broadcastStatus("confirm", reason)
            updateNotification("等待确认: ${reason.take(40)}")
            // Show on top of the chat app (MainActivity is paused there and misses broadcasts)
            try {
                val i = android.content.Intent(this, com.runasakura.phoneagent.ui.ConfirmActivity::class.java)
                    .putExtra(com.runasakura.phoneagent.ui.ConfirmActivity.EXTRA_MSG, reason)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                val starter = com.runasakura.phoneagent.a11y.AgentAccessibilityService.require()
                (starter ?: this).startActivity(i)
            } catch (e: Exception) {
                Log.w(TAG, "confirm activity failed: ${e.message}")
            }
        }

    @Volatile private var stopReason: String? = null

    fun requestStop(reason: String = "用户停止") {
        stopReason = reason
        log("停止原因：$reason")
        stopFlag.set(true)
        starting.set(false)
        job?.cancel() // cancels the in-flight OkHttp call (CloudClient uses awaitResponse → Call.cancel())
        job = null
        pendingConfirm?.invoke(false)
        pendingConfirm = null
        broadcastStatus("stopped", "急停")
        overlay?.hide()
        overlay = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(text: String, running: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, AgentForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, PhoneAgentApp.CHANNEL_AGENT)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentIntent(open)
            .setOngoing(running)
            .addAction(0, getString(R.string.action_emergency_stop), stopPi)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        mgr.notify(NOTIF_ID, buildNotification(text, true))
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        com.runasakura.phoneagent.util.Diagnostics.log(msg)
        broadcastStatus("log", msg)
    }

    private fun broadcastStatus(type: String, message: String) {
        when (type) { "running" -> running = true; "done", "stopped" -> running = false }
        if (type == "log") com.runasakura.phoneagent.ui.ChatSession.onLog(message)
        else if (com.runasakura.phoneagent.ui.ChatSession.onStatus(type, message))
            com.runasakura.phoneagent.ui.TaskHistory.add(this, com.runasakura.phoneagent.ui.ChatSession.lastGoal.orEmpty(), type == "done", message.substringAfter("): ", message))
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_MESSAGE, message)
        )
    }

    override fun onDestroy() {
        if (job?.isActive == true) { stopReason = stopReason ?: "服务被系统结束"; log("停止原因：$stopReason") }
        running = false
        stopFlag.set(true)
        job?.cancel()
        scope.cancel()
        overlay?.hide()
        super.onDestroy()
    }

    companion object {
        @Volatile private var running = false
        fun isRunning() = running
        const val TASK_TIMEOUT_MS = 8 * 60_000L
        private const val TAG = "AgentFgs"
        const val NOTIF_ID = 42
        const val ACTION_START = "com.runasakura.phoneagent.START"
        const val ACTION_STOP = "com.runasakura.phoneagent.STOP"
        const val EXTRA_REASON = "stop_reason"
        const val ACTION_PAUSE = "com.runasakura.phoneagent.PAUSE"
        const val EXTRA_CONTEXT = "context"
        const val ACTION_STATUS = "com.runasakura.phoneagent.STATUS"
        const val ACTION_CONFIRM_RESULT = "com.runasakura.phoneagent.CONFIRM_RESULT"
        const val EXTRA_GOAL = "goal"
        const val EXTRA_TYPE = "type"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_OK = "ok"

        @Volatile
        var pendingConfirm: ((Boolean) -> Unit)? = null

        fun start(context: Context, goal: String, followUp: String? = null) {
            com.runasakura.phoneagent.ui.ChatSession.startTask(goal)
            val i = Intent(context, AgentForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_GOAL, goal)
                .putExtra(EXTRA_CONTEXT, followUp)
            context.startForegroundService(i)
        }

        fun stop(context: Context, reason: String = "App 内「停止」按钮") {
            val i = Intent(context, AgentForegroundService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_REASON, reason)
            context.startService(i)
        }

        fun pause(context: Context) {
            context.startService(Intent(context, AgentForegroundService::class.java).setAction(ACTION_PAUSE))
        }

        /** First answer wins: inline chat card, overlay or ConfirmActivity. */
        fun deliverConfirm(ok: Boolean) {
            com.runasakura.phoneagent.a11y.AgentAccessibilityService.activeConfirm?.invoke(ok)
            pendingConfirm?.invoke(ok)
            pendingConfirm = null
        }
    }
}

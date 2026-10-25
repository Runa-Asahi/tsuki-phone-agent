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
import com.runasakura.phoneagent.R
import com.runasakura.phoneagent.agent.AgentLoop
import com.runasakura.phoneagent.agent.SafetyGate
import com.runasakura.phoneagent.llm.CloudClient
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val stopFlag = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    private var overlay: EmergencyStopOverlay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                requestStop()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val goal = intent.getStringExtra(EXTRA_GOAL).orEmpty()
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
        starting.set(true)
        stopFlag.set(false)
        overlay = EmergencyStopOverlay(this) { requestStop() }.also { it.show() }
        val prefs = PhoneAgentApp.instance.prefs
        val local = LocalMnnClient(prefs.mnnBaseUrl, prefs.mnnApiKey, prefs.mnnModel)
        val cloud = if (prefs.cloudBaseUrl.isNotBlank()) {
            CloudClient(prefs.cloudBaseUrl, prefs.cloudApiKey, prefs.cloudModel)
        } else null
        val router = LlmRouter(prefs, local, cloud)
        val loop = AgentLoop(
            prefs = prefs,
            router = router,
            safety = SafetyGate(prefs),
            onLog = { msg ->
                log(msg)
                updateNotification(msg.take(60))
            },
            onConfirm = { reason -> askConfirm(reason) },
            isStopped = { stopFlag.get() }
        )
        val previous = job
        job = scope.launch(Dispatchers.Default) {
            previous?.join()
            broadcastStatus("running", "开始：$goal")
            try {
                val m = local.resolveModel()
                log("MNN 当前模型：${m.ifBlank { "(未知)" }}")
                com.runasakura.phoneagent.util.Diagnostics.model = m
                if (m.contains("reason", true) || m.contains("think", true) || m.contains("distill", true)) {
                    log("提示：推理(Reasoning)模型会输出长思考，建议换 Qwen3 1.7B/4B 或 Qwen2.5 Instruct（MNN）")
                }
            } catch (e: Exception) {
                log("读取 /v1/models 失败：${e.message}")
            }
            val result = try {
                loop.run(goal)
            } catch (e: kotlinx.coroutines.CancellationException) {
                AgentLoop.Result(false, 0, "已取消")
            } catch (e: Exception) {
                Log.e(TAG, "loop error", e)
                AgentLoop.Result(false, 0, e.message ?: "error")
            }
            com.runasakura.phoneagent.util.Diagnostics.log("结果: success=${result.success} ${result.summary}")
            broadcastStatus(
                if (result.success) "done" else "stopped",
                "结束(${result.steps}步): ${result.summary}"
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

    private suspend fun askConfirm(reason: String): Boolean =
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

    fun requestStop() {
        stopFlag.set(true)
        starting.set(false)
        job?.cancel() // cancels in-flight OkHttp call via coroutine cancellation
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
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_MESSAGE, message)
        )
    }

    override fun onDestroy() {
        stopFlag.set(true)
        job?.cancel()
        scope.cancel()
        overlay?.hide()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AgentFgs"
        const val NOTIF_ID = 42
        const val ACTION_START = "com.runasakura.phoneagent.START"
        const val ACTION_STOP = "com.runasakura.phoneagent.STOP"
        const val ACTION_STATUS = "com.runasakura.phoneagent.STATUS"
        const val ACTION_CONFIRM_RESULT = "com.runasakura.phoneagent.CONFIRM_RESULT"
        const val EXTRA_GOAL = "goal"
        const val EXTRA_TYPE = "type"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_OK = "ok"

        @Volatile
        var pendingConfirm: ((Boolean) -> Unit)? = null

        fun start(context: Context, goal: String) {
            val i = Intent(context, AgentForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_GOAL, goal)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, AgentForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }

        fun deliverConfirm(ok: Boolean) {
            pendingConfirm?.invoke(ok)
            pendingConfirm = null
        }
    }
}

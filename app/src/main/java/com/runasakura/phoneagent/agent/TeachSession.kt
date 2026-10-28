package com.runasakura.phoneagent.agent

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.runasakura.phoneagent.a11y.AgentAccessibilityService
import com.runasakura.phoneagent.ui.TsukiTheme
import com.runasakura.phoneagent.util.Diagnostics
import com.runasakura.phoneagent.voice.VoiceInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android shell around [TeachMachine]: floating coach bar, voice dictate into the teach log (never starts a task),
 * a11y click auto-steps, SharedPreferences persistence across process death / a11y rebind.
 */
object TeachSession {
    private const val PREF = "teach_session"
    private const val KEY = "state"

    @Volatile private var state: TeachMachine.State = TeachMachine.State()
    private var bar: android.view.View? = null
    private var wm: WindowManager? = null
    private var titleView: TextView? = null
    private var voice: VoiceInput? = null
    private var lastClickPkg: String? = null
    private var lastClickLabel: String? = null
    private var lastClickAt = 0L
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(TeachMachine.State) -> Unit>()

    val active get() = state.active
    val ready get() = state.ready
    fun snapshot(): TeachMachine.State = state
    fun statusLine(): String = TeachMachine.statusLine(state)

    fun addListener(l: (TeachMachine.State) -> Unit) { listeners += l; l(state) }
    fun removeListener(l: (TeachMachine.State) -> Unit) { listeners -= l }

    /** Load persisted session (call from Application / TeachActivity). Restores coach bar if still Active. */
    fun restore(ctx: Context) {
        val loaded = TeachMachine.decode(prefs(ctx).getString(KEY, null))
        state = loaded
        if (loaded.active) {
            Diagnostics.log("教授：从持久化恢复（口述 ${loaded.dictations.size} · 示范 ${loaded.steps.size}）")
            val app = ctx.applicationContext
            main.post { ensureBar(app); notifyUi() }
        } else if (loaded.ready) {
            Diagnostics.log("教授：发现待整理会话，打开整理页")
            val app = ctx.applicationContext
            main.post { openCompress(app) }
        }
    }

    fun canStartOverlay(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    /**
     * Enter Active. Requires overlay permission (coach bar). Returns false if goal too short or no overlay.
     * Does NOT start an agent task.
     */
    fun begin(ctx: Context, goal: String, pkgHint: String? = null): Boolean {
        val next = TeachMachine.begin(goal, pkgHint, System.currentTimeMillis()) ?: return false
        if (!canStartOverlay(ctx)) {
            Diagnostics.log("教授：开始失败（无悬浮窗权限）")
            return false
        }
        releaseVoice()
        state = next
        persist(ctx)
        Diagnostics.log("教授：开始「${state.goal}」— 等待口述或示范")
        ensureBar(ctx.applicationContext)
        if (bar == null) {
            Diagnostics.log("教授：开始失败（教练条未显示）")
            state = TeachMachine.State(); prefs(ctx).edit().remove(KEY).apply()
            return false
        }
        notifyUi()
        Toast.makeText(ctx, "上岗已开始：请口述步骤（点「说一步」）或去目标应用操作后点「记录此步」", Toast.LENGTH_LONG).show()
        return true
    }

    fun appendDictate(ctx: Context, text: String) {
        if (!state.active) return
        val before = state.dictations.size
        state = TeachMachine.appendDictate(state, text)
        if (state.dictations.size == before) return
        persist(ctx)
        Diagnostics.log("教授：收到口述 #${state.dictations.size}「${text.trim().take(40)}」")
        refreshBar()
        notifyUi()
        Toast.makeText(ctx, "已记下口述（共 ${state.dictations.size} 句）", Toast.LENGTH_SHORT).show()
    }

    fun mark(ctx: Context) {
        if (!state.active) return
        scope.launch {
            val captured = withContext(Dispatchers.Default) { captureScreen() }
            if (captured == null) {
                Toast.makeText(ctx, "请先打开要教的应用（当前像是桌面或 Tsuki）", Toast.LENGTH_LONG).show()
                Diagnostics.log("教授：记录此步失败（无前台目标包）")
                return@launch
            }
            state = TeachMachine.addStep(state, captured.first, captured.second, null, captured.third, System.currentTimeMillis())
            persist(ctx)
            Diagnostics.log("教授：记录步骤 ${state.steps.size} 包=${captured.first} 源=${captured.third} 标签=${captured.second.take(6)}")
            refreshBar(); notifyUi()
            Toast.makeText(ctx, "已记示范第 ${state.steps.size} 步", Toast.LENGTH_SHORT).show()
        }
    }

    /** Called from accessibility TYPE_VIEW_CLICKED while teaching (skipped for our own package). */
    fun onUserClick(ctx: Context, pkg: String, label: String) {
        if (!state.active) return
        if (AppResolver.isAgentOrBrain(pkg)) return
        val now = System.currentTimeMillis()
        if (!TeachMachine.shouldRecordClick(lastClickPkg, lastClickLabel, lastClickAt, pkg, label, now)) return
        lastClickPkg = pkg; lastClickLabel = label; lastClickAt = now
        val note = label.ifBlank { null }?.let { "点击「$it」" }
        state = TeachMachine.addStep(state, pkg, listOfNotNull(label.takeIf { it.isNotBlank() }), note, "click", now)
        persist(ctx)
        Diagnostics.log("教授：自动记录点击 步骤${state.steps.size} 包=$pkg 「${label.take(20)}」")
        main.post { refreshBar(); notifyUi() }
    }

    fun finish(ctx: Context) {
        if (!state.active) return
        releaseVoice()
        if (state.empty()) {
            Diagnostics.log("教授：完成被拒绝（尚无口述也无示范）")
            Toast.makeText(ctx, "还没有记录：请先「说一步」或「记录此步」", Toast.LENGTH_LONG).show()
            return
        }
        val next = TeachMachine.finish(state) ?: return
        state = next
        persist(ctx)
        hideBar()
        Diagnostics.log("教授：完成（口述 ${state.dictations.size} · 示范 ${state.steps.size}）→ 整理")
        openCompress(ctx.applicationContext)
        notifyUi()
    }

    fun cancel(ctx: Context) {
        releaseVoice()
        Diagnostics.log("教授：取消")
        state = TeachMachine.cancel(state)
        prefs(ctx).edit().remove(KEY).apply()
        hideBar()
        notifyUi()
        Toast.makeText(ctx, "已取消上岗", Toast.LENGTH_SHORT).show()
    }

    /** After compress succeeded or failed permanently: clear ReadyToCompress. */
    fun clearCompressed(ctx: Context) {
        state = TeachMachine.clearAfterCompress(state)
        prefs(ctx).edit().remove(KEY).apply()
        notifyUi()
    }

    fun mergeTrace(): String = TeachMachine.mergeTrace(state)
    fun dominantPkg(): String? = TeachMachine.dominantPkg(state.steps) ?: state.pkgHint

    // ---- voice on coach bar (into teach log, never ChatSession / agent task) ----
    fun toggleDictateVoice(ctx: Context) {
        if (!state.active) return
        if (voice?.listening == true) { voice?.stop(); return }
        if (!VoiceInput.hasMicPermission(ctx)) {
            // Ask via transparent activity; on grant user taps 说一步 again.
            runCatching {
                ctx.startActivity(Intent(ctx, com.runasakura.phoneagent.ui.MicPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
            Toast.makeText(ctx, "请允许麦克风后，再点「说一步」", Toast.LENGTH_LONG).show()
            Diagnostics.log("教授：等待口述 — 需要麦克风权限")
            return
        }
        if (!VoiceInput.available(ctx) && !com.runasakura.phoneagent.voice.LocalAsr.installed(ctx)) {
            Toast.makeText(ctx, "没有语音识别：可在设置下载本地语音模型，或在上岗页打字追加口述", Toast.LENGTH_LONG).show()
            Diagnostics.log("教授：等待口述 — 无识别引擎")
            return
        }
        Diagnostics.log("教授：等待口述…")
        Toast.makeText(ctx, "请说步骤…说完再点一次或停顿结束", Toast.LENGTH_SHORT).show()
        val app = ctx.applicationContext
        main.post {
            releaseVoice()
            val v = VoiceInput(app, object : VoiceInput.Listener {
                override fun onListening(on: Boolean) { refreshBar() }
                override fun onPartial(text: String) {}
                override fun onFinal(text: String) {
                    appendDictate(app, text)
                    releaseVoice()
                }
                override fun onError(message: String) {
                    Diagnostics.log("教授：口述识别失败 $message")
                    Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
                    releaseVoice(); refreshBar()
                }
            })
            v.endpointStops = true
            voice = v
            if (!v.start()) releaseVoice()
            refreshBar()
        }
    }

    private fun releaseVoice() {
        val v = voice; voice = null
        if (v != null) main.post { runCatching { v.destroy() } }
    }

    private fun captureScreen(): Triple<String, List<String>, String>? {
        val a11y = AgentAccessibilityService.require()
        val pkg = a11y?.foregroundPackage()?.takeIf { it.isNotBlank() && !AppResolver.isAgentOrBrain(it) }
            ?: state.pkgHint
        if (pkg.isNullOrBlank()) return null
        var source = "none"; var labels = emptyList<String>()
        runCatching {
            a11y?.let { svc ->
                val items = com.runasakura.phoneagent.a11y.ServicePhone(svc).screenItems()
                if (items.isNotEmpty()) {
                    labels = items.map { it.label }.filter { it.isNotBlank() }.distinct().take(16)
                    source = if (items.all { it.id.startsWith("o") || it.id.isBlank() }) "ocr" else "a11y"
                }
            }
        }
        return Triple(pkg, labels, source)
    }

    private fun openCompress(ctx: Context) {
        ctx.startActivity(Intent(ctx, com.runasakura.phoneagent.ui.TeachActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(com.runasakura.phoneagent.ui.TeachActivity.EXTRA_COMPRESS, true))
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, 0)
    private fun persist(ctx: Context) {
        prefs(ctx).edit().putString(KEY, TeachMachine.encode(state)).apply()
    }

    private fun notifyUi() { val s = state; listeners.forEach { runCatching { it(s) } } }

    private fun ensureBar(ctx: Context) {
        if (bar != null) { refreshBar(); return }
        if (!canStartOverlay(ctx)) return
        showBar(ctx)
    }

    private fun showBar(ctx: Context) {
        val k = TsukiTheme.current(ctx)
        val dens = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * dens).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat(); setColor(k.surface); setStroke(dp(1), k.outline)
            }
            elevation = dp(8).toFloat()
        }
        val title = TextView(ctx).apply {
            textSize = 13f; setTextColor(k.onSurface); paint.isFakeBoldText = true
        }
        box.addView(title); titleView = title
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun b(t: String, on: () -> Unit) = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = t; textSize = 11f; minHeight = dp(40); minimumWidth = dp(64); isAllCaps = false
            setOnClickListener { on() }
        }
        row.addView(b("说一步") { toggleDictateVoice(ctx) })
        row.addView(b("记录此步") { mark(ctx) })
        row.addView(b("完成") { finish(ctx) })
        row.addView(b("取消") { cancel(ctx) })
        box.addView(row)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(48) }
        val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { w.addView(box, lp) }.onFailure {
            Diagnostics.log("教授：教练条显示失败 ${it.message}")
            Toast.makeText(ctx, "无法显示上岗教练条（需要悬浮窗权限）", Toast.LENGTH_LONG).show()
            return
        }
        wm = w; bar = box; refreshBar()
        Diagnostics.log("教授：教练条已显示")
    }

    private fun refreshBar() {
        val listening = voice?.listening == true
        titleView?.text = buildString {
            append("上岗中 · ${state.goal.take(14)}")
            append(" · 口述${state.dictations.size}/示范${state.steps.size}")
            append(if (listening) " · 正在听…" else " · 说一步或记录此步")
        }
    }

    private fun hideBar() {
        releaseVoice()
        val v = bar; val w = wm; bar = null; wm = null; titleView = null
        if (v != null && w != null) runCatching { w.removeView(v) }
    }
}

package com.runasakura.phoneagent.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.runasakura.phoneagent.PhoneAgentApp
import com.runasakura.phoneagent.R
import com.runasakura.phoneagent.agent.AppMemory
import com.runasakura.phoneagent.agent.Recipe
import com.runasakura.phoneagent.agent.TeachMachine
import com.runasakura.phoneagent.agent.TeachSession
import com.runasakura.phoneagent.prefs.AgentPreferences
import com.runasakura.phoneagent.util.Diagnostics
import com.runasakura.phoneagent.voice.VoiceInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * On-the-job training UI. The real session lives in [TeachSession] (persisted + coach bar).
 * This screen: set goal →「开始上岗」→ wait for dictate (mic / type) and/or demo →「完成」compresses both.
 */
class TeachActivity : AppCompatActivity() {
    private val prefs get() = PhoneAgentApp.instance.prefs
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = TsukiTheme.color(this, id)

    private lateinit var goalIn: EditText
    private lateinit var stepsIn: EditText
    private lateinit var pkgIn: EditText
    private lateinit var log: TextView
    private lateinit var status: TextView
    private lateinit var btnStart: MaterialButton
    private var pageVoice: VoiceInput? = null
    private var compressing = false

    private val onSession: (TeachMachine.State) -> Unit = { s -> runOnUiThread { render(s) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)); fitsSystemWindows = true }
        val bar = com.google.android.material.appbar.MaterialToolbar(this).apply {
            title = getString(R.string.teach_title); setTitleTextColor(col(R.color.text_primary))
            setNavigationIcon(R.drawable.ic_tool_back); navigationIcon?.setTint(col(R.color.text_primary)); setNavigationOnClickListener { finish() }
        }
        root.addView(bar)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        body.addView(hint(getString(R.string.teach_guide)))
        status = hint("").also { body.addView(it) }
        goalIn = field(getString(R.string.teach_goal_hint)); body.addView(goalIn)
        pkgIn = field(getString(R.string.teach_pkg_hint)); body.addView(pkgIn)
        stepsIn = field("打字追加口述（每行一句，点「追加口述」；或点麦克风 / 教练条「说一步」）", minLines = 3); body.addView(stepsIn)

        val row0 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnStart = btn("开始上岗") { startOrFinish() }.also { row0.addView(it) }
        row0.addView(btn("说一步") { listenHere() })
        row0.addView(btn("追加口述") { appendTyped() })
        body.addView(row0)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(btn("记录此步") {
            if (!TeachSession.active) Toast.makeText(this, "请先点「开始上岗」", Toast.LENGTH_SHORT).show()
            else TeachSession.mark(this)
        })
        row1.addView(btn("完成并整理") {
            if (!TeachSession.active && !TeachSession.ready) Toast.makeText(this, "请先开始上岗并记录", Toast.LENGTH_SHORT).show()
            else if (TeachSession.active) TeachSession.finish(this)
            else maybeCompress()
        })
        row1.addView(btn("取消") {
            if (TeachSession.active || TeachSession.ready) TeachSession.cancel(this) else finish()
        })
        body.addView(row1)

        log = hint(getString(R.string.teach_log_empty)).also { body.addView(it) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        TsukiTheme.applyBars(this)

        intent.getStringExtra(EXTRA_GOAL)?.takeIf { it.isNotBlank() }?.let { goalIn.setText(it) }
        intent.getStringExtra(EXTRA_PKG)?.takeIf { it.isNotBlank() }?.let { pkgIn.setText(it) }

        TeachSession.addListener(onSession)
        TeachSession.restore(this)
        if (intent.getBooleanExtra(EXTRA_COMPRESS, false) || TeachSession.ready) {
            intent.removeExtra(EXTRA_COMPRESS)
            maybeCompress()
        }
        // Optional: auto-begin when launched with a goal and EXTRA_AUTO_START (menu with filled goal).
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false) && !TeachSession.active && !TeachSession.ready) {
            intent.removeExtra(EXTRA_AUTO_START)
            goalIn.text?.toString()?.trim()?.takeIf { it.length >= 2 }?.let { startSession() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_COMPRESS, false) || TeachSession.ready) {
            intent.removeExtra(EXTRA_COMPRESS); maybeCompress()
        }
    }

    override fun onDestroy() {
        TeachSession.removeListener(onSession)
        pageVoice?.destroy(); pageVoice = null
        super.onDestroy()
    }

    private fun render(s: TeachMachine.State) {
        if (isDestroyed) return
        status.text = TeachMachine.statusLine(s)
        btnStart.text = when {
            s.active -> "完成并整理"
            else -> "开始上岗"
        }
        if (s.goal.isNotBlank() && goalIn.text.isNullOrBlank()) goalIn.setText(s.goal)
        s.pkgHint?.takeIf { pkgIn.text.isNullOrBlank() }?.let { pkgIn.setText(it) }
        log.text = when {
            s.phase == TeachMachine.Phase.Idle -> getString(R.string.teach_log_empty)
            else -> buildString {
                append(TeachMachine.statusLine(s)); append("\n\n")
                append(TeachMachine.mergeTrace(s).ifBlank { "（还没有内容，请口述或记录此步）" })
            }
        }
    }

    private fun startOrFinish() {
        when {
            TeachSession.active -> TeachSession.finish(this)
            TeachSession.ready -> maybeCompress()
            else -> startSession()
        }
    }

    private fun startSession() {
        val goal = goalIn.text.toString().trim()
        if (goal.length < 2) { Toast.makeText(this, "请先填写要教的目标", Toast.LENGTH_SHORT).show(); return }
        if (!TeachSession.canStartOverlay(this)) {
            MaterialAlertDialogBuilder(this).setTitle("需要悬浮窗权限")
                .setMessage("上岗教练条会浮在其他应用上方：一边操作一边「记录此步」或「说一步」。没有它就无法示范。")
                .setPositiveButton("去开启") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }.setNegativeButton("取消", null).show()
            return
        }
        if (!TeachSession.begin(this, goal, pkgIn.text.toString().trim().ifBlank { null })) {
            Toast.makeText(this, "无法开始上岗", Toast.LENGTH_SHORT).show(); return
        }
        status.text = "已开始：请说步骤，或切换到目标应用操作。教练条在屏幕上方。"
        // Keep TeachActivity alive briefly so user sees status; then background so they can open the target app.
        window.decorView.postDelayed({
            if (!isDestroyed && TeachSession.active) moveTaskToBack(true)
        }, 1200)
    }

    private fun appendTyped() {
        if (!TeachSession.active) { Toast.makeText(this, "请先点「开始上岗」", Toast.LENGTH_SHORT).show(); return }
        val lines = stepsIn.text?.toString().orEmpty().lines().map { it.trim() }.filter { it.length >= 1 }
        if (lines.isEmpty()) { Toast.makeText(this, "请先在输入框写一句口述", Toast.LENGTH_SHORT).show(); return }
        lines.forEach { TeachSession.appendDictate(this, it) }
        stepsIn.setText(""); Diagnostics.log("教授：打字追加口述 ${lines.size} 句")
    }

    private fun listenHere() {
        if (!TeachSession.active) { Toast.makeText(this, "请先点「开始上岗」— 开始后才会等待你的话", Toast.LENGTH_LONG).show(); return }
        if (pageVoice?.listening == true) { pageVoice?.stop(); return }
        if (!VoiceInput.hasMicPermission(this)) {
            startActivity(Intent(this, MicPermissionActivity::class.java))
            Toast.makeText(this, "请允许麦克风后再次点「说一步」", Toast.LENGTH_LONG).show()
            return
        }
        Diagnostics.log("教授：页内等待口述…")
        status.text = "正在听…请说出步骤"
        Toast.makeText(this, "请说步骤…", Toast.LENGTH_SHORT).show()
        pageVoice?.destroy()
        pageVoice = VoiceInput(this, object : VoiceInput.Listener {
            override fun onListening(on: Boolean) { if (!on && !isDestroyed) status.text = TeachSession.statusLine() }
            override fun onPartial(text: String) { if (!isDestroyed) status.text = "听：$text" }
            override fun onFinal(text: String) {
                TeachSession.appendDictate(this@TeachActivity, text)
                pageVoice?.destroy(); pageVoice = null
            }
            override fun onError(message: String) {
                Toast.makeText(this@TeachActivity, message, Toast.LENGTH_SHORT).show()
                Diagnostics.log("教授：页内口述失败 $message")
            }
        }).also { it.endpointStops = true; it.start() }
    }

    private fun maybeCompress() {
        if (compressing) return
        val s = TeachSession.snapshot()
        if (!s.ready && !s.active) return
        // If still active but user opened compress intent, finish first.
        if (s.active) { TeachSession.finish(this); return }
        val goal = s.goal.ifBlank { goalIn.text.toString().trim() }.ifBlank { "用户教授" }
        val pkg = TeachSession.dominantPkg() ?: pkgIn.text.toString().trim().ifBlank { null }
        val trace = TeachSession.mergeTrace()
        if (trace.isBlank()) {
            Toast.makeText(this, "没有可整理的内容", Toast.LENGTH_SHORT).show()
            TeachSession.clearCompressed(this); return
        }
        compressing = true
        status.text = "正在整理成可复用流程…"
        Diagnostics.log("教授：开始整理 trace=${trace.length}字")
        compressAndSave(goal, pkg, trace)
    }

    private fun compressAndSave(goal: String, pkg: String?, trace: String) {
        val cloud = com.runasakura.phoneagent.llm.CloudProviders.clientFor(prefs)
        if (cloud == null) {
            val steps = Recipe.offlineSteps(trace)
            if (steps.isEmpty()) {
                // Still persist something usable: each dictate line + step notes.
                val fallback = TeachSession.snapshot().dictations + TeachSession.snapshot().steps.map { it.note ?: it.line() }
                if (fallback.isEmpty()) {
                    Toast.makeText(this, "整理失败：没有可用步骤，请再教一次", Toast.LENGTH_LONG).show()
                    compressing = false; return
                }
                persist(pkg, Recipe(goal, fallback.map { it.take(60) }.take(12)))
            } else persist(pkg, Recipe(goal, steps.take(12)))
            return
        }
        lifecycleScope.launch {
            val router = com.runasakura.phoneagent.llm.LlmRouter({ AgentPreferences.ROUTE_CLOUD_ONLY }, cloud, cloud)
            val recipe = withContext(Dispatchers.IO) { router.compressTeach(goal, pkg, trace) }
            if (recipe == null) {
                Diagnostics.log("教授：LLM 整理失败，改离线落库")
                val steps = Recipe.offlineSteps(trace).ifEmpty {
                    TeachSession.snapshot().dictations + TeachSession.snapshot().steps.mapNotNull { it.note?.takeIf { n -> n.isNotBlank() } ?: it.labels.firstOrNull() }
                }
                if (steps.isEmpty()) {
                    Toast.makeText(this@TeachActivity, "整理失败（网络或模型不可用），记录仍保留，可稍后重试完成", Toast.LENGTH_LONG).show()
                    compressing = false
                    // Keep ReadyToCompress so user can retry.
                    return@launch
                }
                persist(pkg, Recipe(goal, steps.map { it.take(60) }.take(12)))
            } else persist(pkg, recipe)
        }
    }

    private fun persist(pkg: String?, recipe: Recipe) {
        val key = pkg?.takeIf { it.isNotBlank() } ?: AppMemory.GLOBAL
        val m = AppMemory.decode(prefs.appMemory(key)).learnRecipe(recipe)
        prefs.setAppMemory(key, m.encode())
        TeachSession.clearCompressed(this)
        compressing = false
        Diagnostics.log("教授：整理完成 目标=${recipe.goal} 步骤=${recipe.steps.size} 存于=$key")
        status.text = "已学会 · ${recipe.steps.size} 步"
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.teach_learned_title))
            .setMessage(buildString {
                append("目标：${recipe.goal}\n")
                if (recipe.pre.isNotEmpty()) append("前置：${recipe.pre.joinToString("；")}\n")
                append("步骤：\n"); recipe.steps.forEachIndexed { i, s -> append("${i + 1}. $s\n") }
                if (recipe.tips.isNotEmpty()) append("失败提示：${recipe.tips.joinToString("；")}\n")
                if (recipe.slots.isNotEmpty()) append("可变输入：${recipe.slots.joinToString("、")}\n")
                append("\n保存在「${if (key == AppMemory.GLOBAL) "通用" else key}」。")
            })
            .setPositiveButton(getString(R.string.teach_try_now)) { _, _ ->
                ChatSession.startTask(recipe.goal)
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_START_GOAL, recipe.goal))
                finish()
            }
            .setNeutralButton(getString(R.string.teach_view_learning)) { _, _ ->
                startActivity(Intent(this, LearningActivity::class.java)); finish()
            }
            .setNegativeButton(getString(R.string.teach_keep_teaching), null)
            .show()
    }

    private fun field(hint: String, minLines: Int = 1) = EditText(this).apply {
        this.hint = hint; setMinLines(minLines); setTextColor(col(R.color.text_primary)); setHintTextColor(col(R.color.text_tertiary))
        setBackgroundColor(col(R.color.card_bg)); setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }
    private fun hint(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(col(R.color.text_secondary)); setPadding(0, dp(4), 0, dp(4)) }
    private fun btn(t: String, on: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
        text = t; setTextColor(col(R.color.primary)); minHeight = dp(48); gravity = Gravity.CENTER; isAllCaps = false; setOnClickListener { on() }
    }

    companion object {
        const val EXTRA_GOAL = "goal"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_COMPRESS = "compress"
        const val EXTRA_AUTO_START = "auto_start"
        @Deprecated("use EXTRA_COMPRESS") const val EXTRA_TRACE = "trace"
    }
}

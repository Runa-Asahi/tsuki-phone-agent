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
import com.runasakura.phoneagent.agent.TeachSession
import com.runasakura.phoneagent.prefs.AgentPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * On-the-job training for Tsuki (phone counterpart of Grok Bot's "learn from demonstration").
 *
 * 1. Name the job (goal). 2. Either dictate the steps, or demonstrate: a floating coach bar follows the user
 * into any app so they can「记录此步」on each key screen (works for tree=0 games too). 3. LLM compresses the
 * trajectory into a reusable [Recipe] (goal template, preconditions, step skeleton, failure tips; inputs like
 * {联系人} are parameterized). 4. Offer「立刻试一次」. Stored in the learning library for semantic retrieval.
 */
class TeachActivity : AppCompatActivity(), TeachSession.Listener {
    private val prefs get() = PhoneAgentApp.instance.prefs
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = TsukiTheme.color(this, id)

    private lateinit var goalIn: EditText
    private lateinit var stepsIn: EditText
    private lateinit var pkgIn: EditText
    private lateinit var log: TextView
    private lateinit var status: TextView
    private lateinit var btnDemo: MaterialButton

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
        stepsIn = field(getString(R.string.teach_steps_hint), minLines = 5); body.addView(stepsIn)
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(btn(getString(R.string.teach_dictate)) { saveDictate() })
        btnDemo = btn(getString(R.string.teach_demo_start)) { toggleDemo() }.also { row1.addView(it) }
        body.addView(row1)
        log = hint(getString(R.string.teach_log_empty)).also { body.addView(it) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        TsukiTheme.applyBars(this)
        intent.getStringExtra(EXTRA_GOAL)?.takeIf { it.isNotBlank() }?.let { goalIn.setText(it) }
        intent.getStringExtra(EXTRA_PKG)?.takeIf { it.isNotBlank() }?.let { pkgIn.setText(it) }
        intent.getStringExtra(EXTRA_TRACE)?.takeIf { it.isNotBlank() }?.let { trace ->
            log.text = trace
            compressAndSave(goalIn.text.toString().trim().ifBlank { "用户示范" },
                pkgIn.text.toString().trim().ifBlank { null }, "示范轨迹：\n$trace")
            intent.removeExtra(EXTRA_TRACE)
        }
        // Resume an in-flight session (user may have backgrounded us to operate the target app).
        TeachSession.snapshot()?.let { onStep(it); btnDemo.text = getString(R.string.teach_demo_finish) }
    }

    override fun onDestroy() {
        // Do NOT cancel an active demo when leaving — the coach bar must survive so the user can operate the target app.
        // Cancellation is explicit (coach「取消」) or when the activity is finishing AND no session (already saved).
        super.onDestroy()
    }

    private fun toggleDemo() {
        if (TeachSession.active) { TeachSession.finish(); return }
        val goal = goalIn.text.toString().trim()
        if (goal.length < 2) { Toast.makeText(this, "请先填写要教的目标", Toast.LENGTH_SHORT).show(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            MaterialAlertDialogBuilder(this).setTitle("需要悬浮窗权限")
                .setMessage("上岗示范时教练条会浮在其他应用上方，方便你一边操作一边「记录此步」（游戏无障碍树时尤其需要）。")
                .setPositiveButton("去开启") { _, _ -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                .setNegativeButton("取消", null).show()
            return
        }
        val ok = TeachSession.start(this, goal, pkgIn.text.toString().trim().ifBlank { null },
            noteSupplier = { stepsIn.text?.toString()?.trim()?.lines()?.lastOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("口述") } },
            listener = this)
        if (!ok) { Toast.makeText(this, "无法开始示范", Toast.LENGTH_SHORT).show(); return }
        btnDemo.text = getString(R.string.teach_demo_finish)
        status.text = "上岗中：请切换到目标应用，每完成一个关键画面点悬浮条「记录此步」。"
        Toast.makeText(this, "教练条已浮起，去目标应用操作吧", Toast.LENGTH_LONG).show()
        // Leave TeachActivity so the user can open the target app; the bar stays.
        moveTaskToBack(true)
    }

    override fun onStep(s: TeachSession.State) {
        if (isDestroyed) return
        log.text = "已记录 ${s.steps.size} 步：\n" + s.steps.joinToString("\n") { it.line() }
        status.text = "上岗中 · 已记 ${s.steps.size} 步"
        if (pkgIn.text.isBlank()) s.pkgHint?.let { pkgIn.setText(it) }
    }

    override fun onFinished(s: TeachSession.State) {
        if (isDestroyed) return
        btnDemo.text = getString(R.string.teach_demo_start)
        status.text = "示范结束，正在整理…"
        if (pkgIn.text.isBlank()) TeachSession.dominantPkg(s.steps)?.let { pkgIn.setText(it) }
        compressAndSave(s.goal, pkgIn.text.toString().trim().ifBlank { TeachSession.dominantPkg(s.steps) },
            "示范轨迹（${s.steps.size} 步）：\n" + s.steps.joinToString("\n") { it.line() })
    }

    override fun onCancelled() {
        if (isDestroyed) return
        btnDemo.text = getString(R.string.teach_demo_start)
        status.text = "已取消示范"
    }

    private fun saveDictate() {
        val goal = goalIn.text.toString().trim()
        val steps = stepsIn.text.toString().trim()
        if (goal.length < 2 || steps.length < 2) { Toast.makeText(this, "请填写目标和步骤", Toast.LENGTH_SHORT).show(); return }
        compressAndSave(goal, pkgIn.text.toString().trim().ifBlank { null }, "口述：\n$steps")
    }

    private fun compressAndSave(goal: String, pkg: String?, trace: String) {
        val cloud = com.runasakura.phoneagent.llm.CloudProviders.clientFor(prefs)
        if (cloud == null) {
            val steps = Recipe.offlineSteps(trace)
            if (steps.isEmpty()) { Toast.makeText(this, "需要云端模型来整理步骤（设置里填写 API Key），或每行写清一步", Toast.LENGTH_LONG).show(); return }
            persist(pkg, Recipe(goal, steps.take(12)))
            return
        }
        Toast.makeText(this, "正在整理成可复用流程…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val router = com.runasakura.phoneagent.llm.LlmRouter({ AgentPreferences.ROUTE_CLOUD_ONLY }, cloud, cloud)
            val recipe = withContext(Dispatchers.IO) { router.compressTeach(goal, pkg, trace) }
            if (recipe == null) Toast.makeText(this@TeachActivity, "整理失败（网络或模型不可用）", Toast.LENGTH_LONG).show()
            else persist(pkg, recipe)
        }
    }

    private fun persist(pkg: String?, recipe: Recipe) {
        val key = pkg?.takeIf { it.isNotBlank() } ?: AppMemory.GLOBAL
        val m = AppMemory.decode(prefs.appMemory(key)).learnRecipe(recipe)
        prefs.setAppMemory(key, m.encode())
        status.text = "已学会 · ${recipe.steps.size} 步"
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.teach_learned_title))
            .setMessage(buildString {
                append("目标：${recipe.goal}\n")
                if (recipe.pre.isNotEmpty()) append("前置：${recipe.pre.joinToString("；")}\n")
                append("步骤：\n"); recipe.steps.forEachIndexed { i, s -> append("${i + 1}. $s\n") }
                if (recipe.tips.isNotEmpty()) append("失败提示：${recipe.tips.joinToString("；")}\n")
                if (recipe.slots.isNotEmpty()) append("可变输入：${recipe.slots.joinToString("、")}\n")
                append("\n保存在「${if (key == AppMemory.GLOBAL) "通用" else key}」。下次相似目标会优先走这条流程。")
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
        text = t; setTextColor(col(R.color.primary)); minHeight = dp(48); gravity = Gravity.CENTER; setOnClickListener { on() }
    }

    companion object {
        const val EXTRA_GOAL = "goal"
        const val EXTRA_TRACE = "trace"
        const val EXTRA_PKG = "pkg"
    }
}

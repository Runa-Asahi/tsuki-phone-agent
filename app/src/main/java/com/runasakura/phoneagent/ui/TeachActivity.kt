package com.runasakura.phoneagent.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
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
import com.runasakura.phoneagent.prefs.AgentPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * User teaches Tsuki a multi-step procedure.
 * - Dictate: type the goal + steps in natural language; cloud LLM compresses into a [Recipe].
 * - Demonstrate: while the target app is open, tap「记录此步」on each key screen; we snapshot package + OCR labels
 *   + optional note; then the same compression runs. (No click hooking — tree=0 games can't expose taps.)
 */
class TeachActivity : AppCompatActivity() {
    private val prefs get() = PhoneAgentApp.instance.prefs
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = TsukiTheme.color(this, id)

    private lateinit var goalIn: EditText
    private lateinit var stepsIn: EditText
    private lateinit var pkgIn: EditText
    private lateinit var log: TextView
    private val demo = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)); fitsSystemWindows = true }
        val bar = com.google.android.material.appbar.MaterialToolbar(this).apply {
            title = getString(R.string.teach_title); setTitleTextColor(col(R.color.text_primary))
            setNavigationIcon(R.drawable.ic_tool_back); navigationIcon?.setTint(col(R.color.text_primary)); setNavigationOnClickListener { finish() }
        }
        root.addView(bar)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        body.addView(hint("描述目标，再用「口述步骤」或「示范记录」教会 Tsuki。整理后写入学习库，下次同类任务会优先按这个流程走。"))
        goalIn = field("目标（例如：打一把宝石争霸）"); body.addView(goalIn)
        pkgIn = field("应用包名（可选，默认当前前台）"); body.addView(pkgIn)
        stepsIn = field("口述步骤（每行一步，或一段话）", minLines = 5); body.addView(stepsIn)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("口述 → 保存") { saveDictate() })
        row.addView(btn("记录此步") { markStep() })
        row.addView(btn("示范完成") { saveDemo() })
        body.addView(row)
        log = hint("还没有记录。").also { body.addView(it) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        TsukiTheme.applyBars(this)
        intent.getStringExtra(EXTRA_GOAL)?.let { goalIn.setText(it) }
    }

    private fun markStep() {
        val a11y = com.runasakura.phoneagent.a11y.AgentAccessibilityService.require()
        val pkg = a11y?.foregroundPackage()?.takeIf { it.isNotBlank() && !com.runasakura.phoneagent.agent.AppResolver.isAgentOrBrain(it) }
            ?: pkgIn.text.toString().trim().ifBlank { null }
        if (pkg == null) { Toast.makeText(this, "请先打开要教的应用，或填写包名", Toast.LENGTH_LONG).show(); return }
        if (pkgIn.text.isBlank()) pkgIn.setText(pkg)
        lifecycleScope.launch {
            val labels = withContext(Dispatchers.Default) {
                runCatching {
                    a11y?.let { svc ->
                        val phone = com.runasakura.phoneagent.a11y.ServicePhone(svc)
                        phone.screenItems().map { it.label }.filter { it.isNotBlank() }.take(16)
                    }.orEmpty()
                }.getOrDefault(emptyList())
            }
            val note = stepsIn.text.toString().trim().lines().lastOrNull()?.takeIf { it.isNotBlank() }
            val line = buildString {
                append("${demo.size + 1}. 包=$pkg")
                if (labels.isNotEmpty()) append(" 可见：" + labels.take(10).joinToString("、") { it.take(12) })
                if (!note.isNullOrBlank()) append(" 说明：$note")
            }
            demo += line
            log.text = "已记录 ${demo.size} 步：\n" + demo.joinToString("\n")
            Toast.makeText(this@TeachActivity, "已记录第 ${demo.size} 步", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveDictate() {
        val goal = goalIn.text.toString().trim()
        val steps = stepsIn.text.toString().trim()
        if (goal.length < 2 || steps.length < 2) { Toast.makeText(this, "请填写目标和步骤", Toast.LENGTH_SHORT).show(); return }
        compressAndSave(goal, pkgIn.text.toString().trim().ifBlank { null }, "口述：\n$steps")
    }

    private fun saveDemo() {
        val goal = goalIn.text.toString().trim()
        if (goal.length < 2 || demo.isEmpty()) { Toast.makeText(this, "请填写目标并至少记录一步", Toast.LENGTH_SHORT).show(); return }
        compressAndSave(goal, pkgIn.text.toString().trim().ifBlank { null }, "示范轨迹：\n" + demo.joinToString("\n"))
    }

    private fun compressAndSave(goal: String, pkg: String?, trace: String) {
        val cloud = com.runasakura.phoneagent.llm.CloudProviders.clientFor(prefs)
        if (cloud == null) {
            // Offline fallback: each non-empty line becomes a step (no LLM).
            val steps = trace.lines().map { it.trim() }.filter { it.length in 2..60 && !it.startsWith("口述") && !it.startsWith("示范") }
            if (steps.isEmpty()) { Toast.makeText(this, "需要云端模型来整理步骤（设置里填写 API Key），或每行写清一步", Toast.LENGTH_LONG).show(); return }
            persist(pkg, Recipe(goal, steps.take(12)))
            return
        }
        Toast.makeText(this, "正在用 AI 整理流程…", Toast.LENGTH_SHORT).show()
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
        MaterialAlertDialogBuilder(this)
            .setTitle("已学会")
            .setMessage("目标：${recipe.goal}\n步骤：\n" + recipe.steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n") +
                (if (recipe.tips.isNotEmpty()) "\n注意：" + recipe.tips.joinToString("；") else "") +
                "\n\n保存在「${if (key == AppMemory.GLOBAL) "通用" else key}」。可在学习记录里查看或删除。")
            .setPositiveButton("完成") { _, _ -> finish() }
            .setNegativeButton("继续教", null)
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
    }
}

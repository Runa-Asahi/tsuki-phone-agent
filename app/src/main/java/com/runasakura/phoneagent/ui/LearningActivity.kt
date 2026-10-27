package com.runasakura.phoneagent.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.runasakura.phoneagent.PhoneAgentApp
import com.runasakura.phoneagent.R
import com.runasakura.phoneagent.agent.AppMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 学习记录: everything Tsuki learned, per app — lessons (经验), routes (路线) and skills (技能) with how often each was
 * verified, when it was last confirmed, and 待验证 for one-off observations. Tap an entry to delete it; per app
 * "AI 整理" merges duplicates with the cloud model (evidence counts are kept, nothing is invented).
 */
class LearningActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private val prefs get() = PhoneAgentApp.instance.prefs
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(id: Int) = TsukiTheme.color(this, id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)); fitsSystemWindows = true }
        val bar = com.google.android.material.appbar.MaterialToolbar(this).apply {
            title = getString(R.string.learning_title); setTitleTextColor(col(R.color.text_primary))
            setNavigationIcon(R.drawable.ic_tool_back); navigationIcon?.setTint(col(R.color.text_primary)); setNavigationOnClickListener { finish() }
        }
        root.addView(bar)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(24)) }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        TsukiTheme.applyBars(this)
        reload()
    }

    data class Row(val kind: Char, val text: String, val conf: Int, val ts: Long)
    data class Bucket(val pkg: String, val label: String, val rows: List<Row>)

    private fun reload() {
        list.removeAllViews(); list.addView(note("读取中…"))
        lifecycleScope.launch {
            val buckets = withContext(Dispatchers.IO) {
                runCatching { com.runasakura.phoneagent.llm.TextEmbedder.attach(this@LearningActivity) }
                prefs.allAppMemory().map { (k, v) ->
                    val pkg = k.removePrefix("appmem_"); val m = AppMemory.decode(v)
                    val label = if (pkg == AppMemory.GLOBAL) "通用界面经验（跨应用）" else runCatching {
                        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    Bucket(pkg, label, m.factEntries.map { Row('F', it.text, it.conf, it.ts) } + m.pathEntries.map { Row('P', it.text, it.conf, it.ts) } +
                        m.skillEntries.map { Row('S', it.text, it.conf, it.ts) } + m.recipeEntries.map { Row('R', it.text, it.conf, it.ts) })
                }.filter { it.rows.isNotEmpty() }.sortedWith(compareBy({ it.pkg != AppMemory.GLOBAL }, { -it.rows.size }))
            }
            render(buckets)
        }
    }

    private fun render(buckets: List<Bucket>) {
        list.removeAllViews()
        val total = buckets.sumOf { it.rows.size }; val verified = buckets.sumOf { b -> b.rows.count { it.conf >= 2 } }
        list.addView(button("教 Tsuki 做一件事") { startActivity(android.content.Intent(this, TeachActivity::class.java)) })
        list.addView(note("检索方式：" + retrievalMode()))
        list.addView(note(if (total == 0) "还没有学到经验。每次任务结束后，Tsuki 会根据这次的证据记下可复用的经验；成功时验证被用到的经验，失败时降低它们的可信度，过期或从未被验证的会自动淘汰。"
            else "共 $total 条，其中 $verified 条已被后续任务验证。「待验证」只出现过一次，10 天内没被再次证实会自动淘汰；点一条可删除。"))
        for (b in buckets) {
            list.addView(TextView(this).apply { text = b.label; textSize = 16f; setTextColor(col(R.color.text_primary)); paint.isFakeBoldText = true
                setPadding(0, dp(18), 0, dp(4)); isFocusable = true; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES; androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (b.rows.count { it.kind == 'F' } >= 2) actions.addView(button("AI 整理") { consolidate(b) })
            actions.addView(button("清除此项") { confirm("清除「${b.label}」的全部 ${b.rows.size} 条经验？") { prefs.clearAppMemory(b.pkg); reload() } })
            list.addView(actions)
            for (r in b.rows.sortedWith(compareBy({ it.kind }, { -it.conf }))) list.addView(row(b, r))
        }
        if (buckets.isNotEmpty()) list.addView(button("全部清除") { confirm("清除所有应用的全部经验？此操作无法撤销。") { prefs.clearAppMemory(); reload() } }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(20) })
    }

    private fun row(b: Bucket, r: Row): View {
        val kind = when (r.kind) { 'F' -> "经验"; 'P' -> "路线"; 'R' -> "教授流程"; else -> "技能" }
        val body = when (r.kind) {
            'F' -> r.text
            'R' -> com.runasakura.phoneagent.agent.Recipe.decode(r.text)?.let { rec ->
                rec.goal + "：" + rec.steps.joinToString(" → ") } ?: r.text
            else -> r.text.substringBefore("=>") + "：" + r.text.substringAfter("=>")
        }
        val badge = if (r.conf >= 2) "已验证 ${r.conf} 次" else "待验证"
        val age = if (r.ts <= 0) "" else " · " + ago(System.currentTimeMillis() - r.ts)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(col(R.color.card_bg)); setStroke(dp(1), col(R.color.outline)) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            isClickable = true; isFocusable = true; minimumHeight = dp(48)
            contentDescription = "$kind，$badge：$body。点按管理"
            setOnClickListener {
                if (r.kind == 'R') manageRecipe(b, r)
                else confirm("删除这条$kind？\n\n$body") {
                    prefs.setAppMemory(b.pkg, AppMemory.decode(prefs.appMemory(b.pkg)).remove(r.kind, r.text).encode()); reload()
                }
            }
        }
        card.addView(TextView(this).apply { text = body; textSize = 14f; setTextColor(col(R.color.text_primary)) })
        card.addView(TextView(this).apply { text = "$kind · $badge$age"; textSize = 12f
            setTextColor(if (r.conf >= 2) col(R.color.primary) else col(R.color.text_tertiary)); setPadding(0, dp(4), 0, 0) })
        return card
    }

    /** Recipe card: view / try / verify / edit / delete — counterpart of Grok Bot skill management after a teach. */
    private fun manageRecipe(b: Bucket, r: Row) {
        val rec = com.runasakura.phoneagent.agent.Recipe.decode(r.text) ?: return
        val detail = buildString {
            append(rec.goal); append('\n')
            if (rec.pre.isNotEmpty()) append("前置：").append(rec.pre.joinToString("；")).append('\n')
            rec.steps.forEachIndexed { i, s -> append("${i + 1}. $s\n") }
            if (rec.tips.isNotEmpty()) append("失败提示：").append(rec.tips.joinToString("；")).append('\n')
            if (rec.slots.isNotEmpty()) append("可变输入：").append(rec.slots.joinToString("、"))
        }
        val actions = arrayOf("立刻试一次", if (r.conf >= 2) "已验证 · 再验证一次" else "标记已验证", "编辑步骤", "删除")
        MaterialAlertDialogBuilder(this).setTitle("教授流程").setMessage(detail)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        ChatSession.startTask(rec.goal)
                        startActivity(android.content.Intent(this, MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(MainActivity.EXTRA_START_GOAL, rec.goal))
                    }
                    1 -> {
                        val bumped = AppMemory.decode(prefs.appMemory(b.pkg)).learnRecipe(rec.copy(conf = maxOf(2, r.conf + 1)))
                        prefs.setAppMemory(b.pkg, bumped.encode()); reload()
                        android.widget.Toast.makeText(this, "已标记验证", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    2 -> editRecipe(b, r, rec)
                    3 -> { prefs.setAppMemory(b.pkg, AppMemory.decode(prefs.appMemory(b.pkg)).remove('R', r.text).encode()); reload() }
                }
            }.setNegativeButton("关闭", null).show()
    }

    private fun editRecipe(b: Bucket, r: Row, rec: com.runasakura.phoneagent.agent.Recipe) {
        val input = android.widget.EditText(this).apply {
            setText(rec.steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"))
            setMinLines(4); setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        MaterialAlertDialogBuilder(this).setTitle("编辑步骤（每行一步）").setView(input)
            .setPositiveButton("保存") { _, _ ->
                val steps = input.text.toString().lines()
                    .map { it.trim().replace(Regex("""^\d+[．.、)]\s*"""), "") }
                    .filter { it.length in 2..60 }
                if (steps.isEmpty()) { android.widget.Toast.makeText(this, "至少保留一步", android.widget.Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val updated = rec.copy(steps = steps.take(12))
                var m = AppMemory.decode(prefs.appMemory(b.pkg)).remove('R', r.text)
                m = m.learnRecipe(updated)
                prefs.setAppMemory(b.pkg, m.encode()); reload()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun consolidate(b: Bucket) {
        // Consolidation is a cloud-only, user-initiated call; the local slot is never used for it.
        val cloud = com.runasakura.phoneagent.llm.CloudProviders.clientFor(prefs)
        val router = cloud?.let { com.runasakura.phoneagent.llm.LlmRouter({ com.runasakura.phoneagent.prefs.AgentPreferences.ROUTE_CLOUD_ONLY }, it, it) }
        if (router == null) { android.widget.Toast.makeText(this, "AI 整理需要云端模型（设置里填写 API Key）", android.widget.Toast.LENGTH_LONG).show(); return }
        android.widget.Toast.makeText(this, "AI 整理中…", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val m = AppMemory.decode(prefs.appMemory(b.pkg))
            val plan = withContext(Dispatchers.IO) { router.consolidateLessons(b.label, m.facts) }
            val after = plan?.let { m.consolidate(it) }
            val msg = when {
                plan == null -> "整理失败（网络或模型不可用），经验未改动"
                after == null || after.factEntries.size >= m.factEntries.size -> "没有发现可以合并的重复经验"
                else -> { prefs.setAppMemory(b.pkg, after.encode()); "已合并：${m.factEntries.size} → ${after.factEntries.size} 条（保留验证次数）" }
            }
            android.widget.Toast.makeText(this@LearningActivity, msg, android.widget.Toast.LENGTH_LONG).show(); reload()
        }
    }

    private fun retrievalMode() = com.runasakura.phoneagent.agent.Relevance.mode() +
        if (com.runasakura.phoneagent.llm.TextEmbedder.llmRerank(this)) " + 云端 AI 重排" else ""
    private fun ago(ms: Long): String { val d = ms / 86_400_000; val h = ms / 3_600_000
        return when { d >= 1 -> "$d 天前"; h >= 1 -> "$h 小时前"; else -> "刚刚" } }
    private fun note(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(col(R.color.text_secondary)); setPadding(0, dp(8), 0, dp(4)) }
    private fun button(t: String, on: () -> Unit) = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
        text = t; setTextColor(col(R.color.primary)); minHeight = dp(48); gravity = Gravity.CENTER; setOnClickListener { on() } }
    private fun confirm(msg: String, yes: () -> Unit) = MaterialAlertDialogBuilder(this).setMessage(msg)
        .setPositiveButton("确定") { _, _ -> yes() }.setNegativeButton("取消", null).show()
}

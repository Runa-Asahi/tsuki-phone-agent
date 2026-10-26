package com.runasakura.phoneagent.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.runasakura.phoneagent.R
import com.runasakura.phoneagent.a11y.AgentAccessibilityService
import com.runasakura.phoneagent.databinding.ActivityMainBinding
import com.runasakura.phoneagent.service.AgentForegroundService

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val a11yReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AgentAccessibilityService.ACTION_A11Y_STATE) return
            val on = intent.getBooleanExtra(AgentAccessibilityService.EXTRA_CONNECTED, false)
            runOnUiThread {
                refreshA11yStatus()
                if (!on) {
                    UiLog.add("⚠ 无障碍服务已断开（常见于刚更新 APK）。请到系统设置重新打开本服务。")
                    Toast.makeText(this@MainActivity, "无障碍服务已断开，请重新开启", Toast.LENGTH_LONG).show()
                    AgentForegroundService.stop(this@MainActivity)
                }
            }
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AgentForegroundService.ACTION_STATUS) return
            val type = intent.getStringExtra(AgentForegroundService.EXTRA_TYPE)
            val msg = intent.getStringExtra(AgentForegroundService.EXTRA_MESSAGE).orEmpty()
            runOnUiThread {
                if (type == "log") UiLog.add(msg)
                when (type) {
                    "running" -> setRunning(true)
                    "done", "stopped" -> setRunning(false)
                    "paused" -> binding.btnPause.text = if (msg == "1") "继续" else "暂停"
                }
                scheduleRender()
                if (type != "log") refreshA11yStatus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.inflateMenu(R.menu.main_menu)
        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.action_new -> newSession()
                R.id.action_history -> showHistory()
                R.id.action_selftest -> runSelfTest()
                R.id.action_export -> exportDiagnostics()
                R.id.action_a11y -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                R.id.action_overlay -> openOverlayPermission()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) { com.runasakura.phoneagent.ocr.OcrEngine.warmUp() }
        ContextCompat.registerReceiver(
            this, statusReceiver, IntentFilter(AgentForegroundService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Quick chips: recent successful goals first (one-tap replay), then examples.
        val recentOk = TaskHistory.load(this).filter { it.ok }.map { it.goal }.distinct().take(4)
        for (g in recentOk) binding.exampleChips.addView(com.google.android.material.chip.Chip(this).apply {
            text = "↻ " + g.take(16); setOnClickListener { binding.goalInput.setText(g); binding.goalInput.setSelection(g.length) } })
        for (ex in SUGGESTIONS.filter { it !in recentOk }) {
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = ex
                setOnClickListener { binding.goalInput.setText(ex); binding.goalInput.setSelection(ex.length) }
            }
            binding.exampleChips.addView(chip)
        }
        binding.cardA11y.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        binding.cardMnn.setOnClickListener { refreshMnnStatus() }
        binding.btnStart.setOnClickListener { startAgent() }
        binding.btnStop.setOnClickListener {
            AgentForegroundService.stop(this)
            UiLog.add("请求急停")
        }
        binding.btnPause.setOnClickListener { AgentForegroundService.pause(this) }

        // Ask for notifications ONCE (it used to re-prompt on every launch).
        val asked = getSharedPreferences("ui", 0)
        if (Build.VERSION.SDK_INT >= 33 && !asked.getBoolean("notif_asked", false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            asked.edit().putBoolean("notif_asked", true).apply()
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }
        renderChat()
        setRunning(AgentForegroundService.isRunning())
    }

    override fun onDestroy() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun setRunning(running: Boolean) {
        binding.btnStart.visibility = if (running) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnStop.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnPause.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        if (!running) binding.btnPause.text = "暂停"
        binding.goalInput.isEnabled = !running
        binding.goalLayout.hint = if (!running && ChatSession.lastGoal != null) "继续追问或下一个任务…" else getString(R.string.goal_hint)
        renderPhase()
    }

    private fun renderPhase() {
        val ph = ChatSession.phase
        binding.statusText.text = (if (ph == ChatSession.Phase.IDLE) "● " else "◌ ") + ph.label
        binding.statusText.setTextColor(ContextCompat.getColor(this, when (ph) {
            ChatSession.Phase.IDLE -> R.color.text_secondary
            ChatSession.Phase.CONFIRM, ChatSession.Phase.PAUSED -> R.color.warn_amber
            else -> R.color.step_accent
        }))
        binding.sessionHint.text = ChatSession.lastGoal?.let { if (ph == ChatSession.Phase.IDLE) "同一会话 · 可追问" else it } ?: ""
    }

    /** Home card shows the ACTIVE brain; never probes MNN in cloud_only. */
    private fun refreshMnnStatus() {
        val p = com.runasakura.phoneagent.PhoneAgentApp.instance.prefs
        val mode = p.routeMode
        val cloudOk = com.runasakura.phoneagent.llm.CloudProviders.ready(p.cloudProvider, p.cloudBaseUrl, p.cloudApiKey)
        if (!com.runasakura.phoneagent.llm.RoutePolicy.usesLocal(mode)) {
            binding.mnnStatus.text = if (cloudOk) "云端 ${p.cloudModel}" else "云端未配置"
            binding.mnnStatus.setTextColor(ContextCompat.getColor(this, if (cloudOk) R.color.ok_green else R.color.err_red))
            return
        }
        binding.mnnStatus.text = "检测中…"
        lifecycleScope.launch {
            val m = try {
                kotlinx.coroutines.withTimeoutOrNull(4000) {
                    com.runasakura.phoneagent.llm.LocalMnnClient(p.mnnBaseUrl, p.mnnApiKey, p.mnnModel).probe()
                }
            } catch (_: Exception) { null }
            val local = when { m == null -> "本机未启动"; m.isBlank() -> "本机已连接"; else -> m }
            binding.mnnStatus.text = if (cloudOk && mode != com.runasakura.phoneagent.prefs.AgentPreferences.ROUTE_LOCAL_ONLY) "$local+云端" else local
            binding.mnnStatus.setTextColor(ContextCompat.getColor(this@MainActivity, if (m == null && !cloudOk) R.color.warn_amber else R.color.ok_green))
        }
    }

    override fun onResume() {
        super.onResume()
        renderChat(); setRunning(AgentForegroundService.isRunning())
        refreshMnnStatus()
        ContextCompat.registerReceiver(
            this, a11yReceiver,
            IntentFilter(AgentAccessibilityService.ACTION_A11Y_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshA11yStatus()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(a11yReceiver)
        } catch (_: Exception) {
        }
    }

    private fun startAgent(retry: String? = null) {
        val goal = retry ?: binding.goalInput.text?.toString()?.trim().orEmpty()
        if (goal.isEmpty()) {
            Toast.makeText(this, R.string.goal_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (AgentAccessibilityService.require() == null) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.a11y_required_title)
                .setMessage(R.string.a11y_required_msg)
                .setPositiveButton(R.string.open_a11y) { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val ctx = ChatSession.followUpContext()
        UiLog.clear(); UiLog.add("启动任务: $goal")
        binding.goalInput.setText("")
        AgentForegroundService.start(this, goal, ctx)
        setRunning(true); renderChat()
    }

    private fun showConfirm(reason: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_title)
            .setMessage(reason)
            .setCancelable(false)
            .setPositiveButton(R.string.confirm_yes) { _, _ ->
                AgentForegroundService.deliverConfirm(true)
            }
            .setNegativeButton(R.string.confirm_no) { _, _ ->
                AgentForegroundService.deliverConfirm(false)
            }
            .show()
    }

    private fun runSelfTest() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("自检")
            .setMessage("将检查无障碍、浮窗、截图、OCR、MNN API、微信/QQ 可启动性。\n\n可选：短暂打开微信和 QQ 检查能否读到界面（不会点击或发送任何内容）。")
            .setPositiveButton("含打开微信/QQ") { _, _ -> doSelfTest(true) }
            .setNeutralButton("仅基础检查") { _, _ -> doSelfTest(false) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doSelfTest(probe: Boolean) {
        UiLog.clear(); UiLog.add("—— 自检开始 ——")
        Toast.makeText(this, "自检中…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                SelfTest(this@MainActivity) { line -> UiLog.add(line) }.run(probe)
            }
            val fails = res.count { !it.ok }
            res.firstOrNull { it.name.contains("OCR") }?.let {
                binding.ocrStatus.text = if (it.ok) "可用" else "失败"
                binding.ocrStatus.setTextColor(ContextCompat.getColor(this@MainActivity, if (it.ok) R.color.ok_green else R.color.err_red))
            }
            UiLog.add("—— 自检完成：${res.size - fails} 通过，$fails 失败（可点「导出诊断日志」分享）——")
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(if (fails == 0) "自检全部通过" else "自检：$fails 项失败")
                .setMessage(res.joinToString("\n") { "${if (it.ok) "✅" else "❌"} ${it.name}\n    ${it.detail}" })
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton("MNN 后台设置") { _, _ -> openMnnBackgroundSettings() }
                .show()
        }
    }

    /**
     * MNN Chat stalls when backgrounded (MagicOS throttles it; seen 170 s per request).
     * Opens its app-info page → 电池 → 「允许后台活动」/ 应用启动管理 → 手动管理.
     */
    private fun openMnnBackgroundSettings() {
        val pkg = listOf("com.alibaba.mnnllm.android.release", "com.alibaba.mnnllm.android").firstOrNull {
            try { packageManager.getPackageInfo(it, 0); true } catch (_: Exception) { false }
        }
        Toast.makeText(this, "请在「电池/应用启动管理」里允许 MNN Chat 后台运行，并在最近任务里锁定它", Toast.LENGTH_LONG).show()
        val i = if (pkg != null) Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg"))
        else Intent(android.provider.Settings.ACTION_SETTINGS)
        try { startActivity(i) } catch (_: Exception) { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
    }

    private fun exportDiagnostics() {
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        val a11y = if (AgentAccessibilityService.require() != null) "已连接" else "未连接"
        val text = com.runasakura.phoneagent.util.Diagnostics.export("$packageName $ver", a11y) +
            "\n--- 界面日志 ---\n" + UiLog.snapshot().joinToString("\n")
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("phone-agent-diagnostics", text))
        Toast.makeText(this, "诊断日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Phone Agent 诊断日志")
                    .putExtra(Intent.EXTRA_TEXT, text),
                "分享诊断日志"
            )
        )
    }

    private fun refreshA11yStatus() {
        val connected = AgentAccessibilityService.require() != null
        val enabled = isA11yEnabled()
        binding.a11yStatus.text = when {
            connected -> "已连接"
            enabled -> "已开启·未连接"
            else -> "未开启"
        }
        binding.a11yStatus.setTextColor(ContextCompat.getColor(this, when {
            connected -> R.color.ok_green
            enabled -> R.color.warn_amber
            else -> R.color.err_red
        }))
    }

    private fun isA11yEnabled(): Boolean {
        if (AgentAccessibilityService.require() != null) return true
        val am = getSystemService(AccessibilityManager::class.java) ?: return false
        val expected = "$packageName/${AgentAccessibilityService::class.java.name}"
        // also match debug suffix package
        return am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any {
            val id = it.id
            id.contains("com.runasakura.phoneagent") && id.contains("AgentAccessibilityService")
        } || expected.let { false }
    }

    // ---------------- Chat rendering (Grok Bot / Codex style) ----------------
    private val renderPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun scheduleRender() {
        if (renderPending.compareAndSet(false, true)) binding.chatList.postDelayed({ renderPending.set(false); renderChat() }, 120)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun bubble(bg: Int, radius: Int = 14) = android.graphics.drawable.GradientDrawable().apply { setColor(bg); cornerRadius = dp(radius).toFloat() }
    private fun tv(text: CharSequence, size: Float = 14f, c: Int = R.color.text_primary, bold: Boolean = false) =
        android.widget.TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color(c)); setTextIsSelectable(false)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    private fun lp(top: Int = 6, gravity: Int = android.view.Gravity.START, match: Boolean = false) =
        android.widget.LinearLayout.LayoutParams(if (match) -1 else -2, -2).apply { topMargin = dp(top); this.gravity = gravity }

    private fun renderChat() {
        val list = binding.chatList
        val atBottom = !binding.scrollChat.canScrollVertically(1)
        list.removeAllViews()
        val items = ChatSession.snapshot()
        if (items.isEmpty()) {
            list.addView(tv("👋 你好，我是你的手机 Agent", 18f, bold = true), lp(24))
            list.addView(tv("用一句话告诉我要做什么，我会一步步操作手机，并在这里实时展示计划、每一步的动作和耗时。\n发送消息、打电话、支付等敏感操作一定会先问你。\n任务进行中可随时「暂停」或「停止」；完成后可以直接追问。", 14f, R.color.text_secondary), lp(8))
            val recent = TaskHistory.load(this).take(3)
            if (recent.isNotEmpty()) {
                list.addView(tv("最近任务", 13f, R.color.text_secondary, bold = true), lp(18))
                recent.forEach { e -> list.addView(tv((if (e.ok) "✓ " else "✗ ") + e.goal, 14f, R.color.step_accent).apply {
                    setPadding(0, dp(6), 0, dp(6)); setOnClickListener { binding.goalInput.setText(e.goal) } }, lp(2)) }
            }
            renderPhase(); return
        }
        for (it in items) list.addView(viewFor(it))
        if (ChatSession.phase != ChatSession.Phase.IDLE) {
            list.addView(tv("◌ " + ChatSession.phase.label + "…", 13f, R.color.step_accent), lp(8))
        }
        renderPhase()
        if (atBottom || ChatSession.phase != ChatSession.Phase.IDLE) binding.scrollChat.post { binding.scrollChat.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun viewFor(item: ChatSession.Item): android.view.View = when (item) {
        is ChatSession.Item.User -> tv(item.text, 15f, R.color.white).apply {
            background = bubble(color(R.color.step_accent)); setPadding(dp(14), dp(10), dp(14), dp(10)); maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
        }.also { it.layoutParams = lp(14, android.view.Gravity.END) }
        is ChatSession.Item.Plan -> android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(tv("🧭 计划", 13f, R.color.text_secondary, bold = true))
            item.lines.takeLast(6).forEach { l -> addView(tv("· " + l, 13f, R.color.text_primary), lp(2)) }
            layoutParams = lp(10, match = true)
        }
        is ChatSession.Item.Step -> stepCard(item)
        is ChatSession.Item.Confirm -> android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = bubble(color(R.color.confirm_bg)); setPadding(dp(14), dp(12), dp(14), dp(10))
            addView(tv("⚠ 需要你确认", 14f, R.color.warn_amber, bold = true))
            addView(tv(item.reason, 14f), lp(6))
            if (item.answer == null) {
                val row = android.widget.LinearLayout(this@MainActivity).apply { gravity = android.view.Gravity.END }
                row.addView(com.google.android.material.button.MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = getString(R.string.confirm_no); setOnClickListener { AgentForegroundService.deliverConfirm(false) } })
                row.addView(com.google.android.material.button.MaterialButton(this@MainActivity).apply {
                    text = getString(R.string.confirm_yes); setOnClickListener { AgentForegroundService.deliverConfirm(true) } }, lp(0).apply { marginStart = dp(8) })
                addView(row, lp(6, match = true))
            } else addView(tv(if (item.answer == true) "✓ 已确认" else "✗ 已拒绝", 13f, if (item.answer == true) R.color.ok_green else R.color.err_red), lp(6))
            layoutParams = lp(10, match = true)
        }
        is ChatSession.Item.Result -> android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = bubble(color(if (item.ok) R.color.ok_bg else R.color.err_bg)); setPadding(dp(14), dp(12), dp(14), dp(10))
            addView(tv((if (item.ok) "✅ 完成  " else "⛔ 未完成  ") + item.summary, 15f, if (item.ok) R.color.ok_green else R.color.err_red, bold = true))
            item.hint?.let { addView(tv("💡 $it", 13f, R.color.text_secondary), lp(6)) }
            if (!item.ok && item.goal.isNotBlank()) addView(com.google.android.material.button.MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "重试"; setOnClickListener { if (!AgentForegroundService.isRunning()) startAgent(item.goal) } }, lp(6))
            layoutParams = lp(12, match = true)
        }
        is ChatSession.Item.Note -> tv(item.text, 11f, R.color.text_secondary).also { it.layoutParams = lp(4) }
    }

    private fun stepCard(s: ChatSession.Item.Step): android.view.View = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        background = bubble(color(R.color.card_bg), 10); setPadding(dp(12), dp(8), dp(12), dp(8))
        val icon = when (s.state) { ChatSession.State.RUNNING -> "◌"; ChatSession.State.OK -> "✓"; ChatSession.State.FAIL -> "✗" }
        val dur = if (s.endMs != null) "  %.1fs".format(s.durationMs / 1000.0) else ""
        val head = android.widget.LinearLayout(this@MainActivity).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(tv("$icon  ${s.title}", 14f, when (s.state) { ChatSession.State.OK -> R.color.text_primary; ChatSession.State.FAIL -> R.color.err_red; else -> R.color.step_accent }, bold = s.state == ChatSession.State.RUNNING),
            android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(tv("$dur  ${if (s.expanded) "▾" else "▸"} ${s.details.size}", 12f, R.color.text_secondary))
        addView(head, lp(0, match = true))
        // Live "what the model is aiming at" line (model's own subgoal + screen read), always visible.
        s.insight?.let { addView(tv("🎯 $it", 12f, R.color.insight), lp(2)) }
        val shown = if (s.expanded) s.details else s.details.filter { it.startsWith("想法：") }.takeLast(1)
        shown.forEach { d -> addView(tv(d, 12f, if (d.startsWith("✗") || d.contains("错误")) R.color.err_red else R.color.text_secondary).apply {
            setTextIsSelectable(s.expanded); typeface = if (d.startsWith("想法：")) typeface else android.graphics.Typeface.MONOSPACE }, lp(3)) }
        setOnClickListener { ChatSession.toggle(s.id); renderChat() }
        layoutParams = lp(6, match = true)
    }

    private fun newSession() {
        if (AgentForegroundService.isRunning()) { Toast.makeText(this, "任务进行中，先停止再开新会话", Toast.LENGTH_SHORT).show(); return }
        ChatSession.clear(); UiLog.clear(); setRunning(false); renderChat()
    }

    private fun showHistory() {
        val h = TaskHistory.load(this)
        if (h.isEmpty()) { Toast.makeText(this, "还没有历史任务", Toast.LENGTH_SHORT).show(); return }
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("任务历史（点选重新填入）")
            .setItems(h.map { "${if (it.ok) "✓" else "✗"} ${fmt.format(java.util.Date(it.time))}  ${it.goal}\n    ${it.summary.take(60)}" }.toTypedArray()) { _, i ->
                binding.goalInput.setText(h[i].goal); binding.goalInput.setSelection(h[i].goal.length)
            }
            .setNegativeButton("清空历史") { _, _ -> TaskHistory.clear(this); renderChat() }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else Toast.makeText(this, R.string.overlay_ok, Toast.LENGTH_SHORT).show()
    }

    companion object {
        val SUGGESTIONS = listOf("给文件传输助手发消息说你好", "打开蓝牙", "明天早上7点的闹钟", "打开图库看第一张照片", "打开B站，搜索猫咪", "微信里最后一条消息是什么")
    }
}

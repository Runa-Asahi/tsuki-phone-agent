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
                    // Don't kill the running task: the agent waits for the rebind and resumes (or stops with the reason).
                    val cause = com.runasakura.phoneagent.a11y.A11yEvents.cause(com.runasakura.phoneagent.a11y.A11yEvents.lastDisconnect(),
                        AgentAccessibilityService.isEnabledInSettings(this@MainActivity))
                    UiLog.add("⚠ 无障碍服务断开：$cause。任务会等待重连后继续。")
                    Toast.makeText(this@MainActivity, "无障碍服务断开，正在等待重连", Toast.LENGTH_SHORT).show()
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
                    "paused" -> setPauseButton(msg == "1")
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
        applyTsuki()

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
        if (!running) setPauseButton(false)
        binding.goalInput.isEnabled = !running
        val asking = (ChatSession.snapshot().lastOrNull() as? ChatSession.Item.Result)?.ask == true
        binding.goalInput.hint = getString(when {
            !running && asking -> R.string.reply_hint
            !running && ChatSession.lastGoal != null -> R.string.followup_hint
            else -> R.string.goal_hint })
        renderPhase()
    }

    private fun renderPhase() {
        val ph = ChatSession.phase
        val c = TsukiTheme.color(this, when (ph) {
            ChatSession.Phase.IDLE -> R.color.text_tertiary
            ChatSession.Phase.CONFIRM, ChatSession.Phase.PAUSED -> R.color.warn_amber
            else -> R.color.primary
        })
        binding.statusText.text = ph.label
        binding.statusText.setTextColor(if (ph == ChatSession.Phase.IDLE) TsukiTheme.color(this, R.color.text_secondary) else c)
        val dot = ContextCompat.getDrawable(this, R.drawable.dot)!!.mutate().apply { setTint(c) }
        binding.statusText.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
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

    /** Palette → sky, bars and the primary round button (palette may have changed in Settings). */
    private var appliedLook: Any? = null
    private fun applyTsuki() {
        val k = TsukiTheme.current(this)
        if (appliedLook != null && appliedLook != k) renderer.reset(binding.chatList)
        appliedLook = k
        TsukiTheme.retint(binding.root)
        TsukiTheme.applyBars(this)
        binding.sky.colors = k
        binding.sky.animateMotes = !TsukiTheme.reduceMotion(this)
        binding.btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(k.accent)
        binding.btnStart.imageTintList = android.content.res.ColorStateList.valueOf(k.onAccent)
        binding.btnStop.backgroundTintList = android.content.res.ColorStateList.valueOf(k.crimson)
        binding.btnStop.imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
    }

    /** First-run checklist shown in the empty state until everything is ready. */
    private fun setupItems(): List<ChatRenderer.SetupItem> {
        val p = com.runasakura.phoneagent.PhoneAgentApp.instance.prefs
        val cloudOk = com.runasakura.phoneagent.llm.CloudProviders.ready(p.cloudProvider, p.cloudBaseUrl, p.cloudApiKey)
        val modelOk = cloudOk || com.runasakura.phoneagent.llm.RoutePolicy.usesLocal(p.routeMode)
        return listOf(
            ChatRenderer.SetupItem("无障碍服务（读屏与点按）", AgentAccessibilityService.require() != null) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            ChatRenderer.SetupItem("模型（在设置里填写 API Key）", modelOk) { startActivity(Intent(this, SettingsActivity::class.java)) },
            ChatRenderer.SetupItem("悬浮窗（运行时显示进度与急停）", Settings.canDrawOverlays(this)) { openOverlayPermission() },
            ChatRenderer.SetupItem("通知（后台任务状态）", Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001) },
        )
    }

    override fun onResume() {
        super.onResume()
        applyTsuki()
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
        val typed = binding.goalInput.text?.toString()?.trim().orEmpty()
        val goal = retry ?: ChatSession.composeGoal(typed)
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
                    .putExtra(Intent.EXTRA_SUBJECT, "Tsuki 诊断日志")
                    .putExtra(Intent.EXTRA_TEXT, text),
                "分享诊断日志"
            )
        )
    }

    private var healTried = 0L

    private fun refreshA11yStatus() {
        val connected = AgentAccessibilityService.require() != null
        val enabled = isA11yEnabled()
        // Switched off (MagicOS after a kill/crash): try to self-heal at most every 10 s, then re-check.
        if (!connected && !enabled && System.currentTimeMillis() - healTried > 10_000) {
            healTried = System.currentTimeMillis()
            Thread {
                val r = com.runasakura.phoneagent.a11y.A11ySelfHeal.heal(this, "检测到被关闭")
                if (r != null) runOnUiThread { android.widget.Toast.makeText(this, r, android.widget.Toast.LENGTH_SHORT).show(); binding.root.postDelayed({ refreshA11yStatus() }, 1500) }
            }.start()
        }
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

    // ---------------- Chat rendering (Codex / Grok style, see ChatRenderer) ----------------
    private val renderPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun scheduleRender() {
        if (renderPending.compareAndSet(false, true)) binding.chatList.postDelayed({ renderPending.set(false); renderChat() }, 120)
    }
    private val renderer by lazy {
        ChatRenderer(this,
            onConfirm = { AgentForegroundService.deliverConfirm(it) },
            onRetry = { g -> if (!AgentForegroundService.isRunning()) startAgent(g) },
            onPick = { g -> binding.goalInput.setText(g); binding.goalInput.setSelection(g.length); binding.goalInput.requestFocus() },
            onToggle = { id -> ChatSession.toggle(id); renderChat() })
    }

    private fun renderChat() {
        val atBottom = !binding.scrollChat.canScrollVertically(1)
        val recent = TaskHistory.load(this).take(3).map { it.goal to it.ok }
        renderer.render(binding.chatList, ChatSession.snapshot(), ChatSession.phase, recent, SUGGESTIONS,
            if (ChatSession.snapshot().isEmpty()) setupItems() else emptyList(), java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
        renderPhase()
        if (atBottom || ChatSession.phase != ChatSession.Phase.IDLE) binding.scrollChat.post { binding.scrollChat.smoothScrollTo(0, binding.chatList.height) }
    }

    private fun setPauseButton(paused: Boolean) {
        binding.btnPause.text = getString(if (paused) R.string.resume else R.string.pause)
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

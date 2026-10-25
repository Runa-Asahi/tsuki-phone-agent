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
                    appendLog("⚠ 无障碍服务已断开（常见于刚更新 APK）。请到系统设置重新打开本服务。")
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
                appendLog(msg)
                when (type) {
                    "confirm" -> Unit // shown by ConfirmActivity on top of any app
                    "running" -> setRunning(true)
                    "done" -> setRunning(false, "完成")
                    "stopped" -> setRunning(false, "已停止")
                }
                refreshA11yStatus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_settings) {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            } else false
        }
        binding.toolbar.inflateMenu(R.menu.main_menu)
        ContextCompat.registerReceiver(
            this, statusReceiver, IntentFilter(AgentForegroundService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        for (ex in listOf("打开设置并进入电池", "给文件传输助手发消息说你好", "打开B站，搜索猫咪", "回到桌面")) {
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = ex
                setOnClickListener { binding.goalInput.setText(ex); binding.goalInput.setSelection(ex.length) }
            }
            binding.exampleChips.addView(chip)
        }
        binding.btnClearLog.setOnClickListener { UiLog.clear(); binding.logView.text = "" }
        binding.cardA11y.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        binding.cardMnn.setOnClickListener { refreshMnnStatus() }

        binding.btnStart.setOnClickListener { startAgent() }
        binding.btnStop.setOnClickListener {
            AgentForegroundService.stop(this)
            appendLog("请求急停")
        }
        binding.btnExport.setOnClickListener { exportDiagnostics() }
        binding.btnSelfTest.setOnClickListener { runSelfTest() }
        binding.btnOpenA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                Toast.makeText(this, R.string.overlay_ok, Toast.LENGTH_SHORT).show()
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }
        if (UiLog.snapshot().isEmpty()) appendLog(getString(R.string.welcome_log)) else renderLog()
        setRunning(AgentForegroundService.isRunning())
    }

    override fun onDestroy() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun setRunning(running: Boolean, idleLabel: String = getString(R.string.status_idle)) {
        binding.statusText.text = if (running) getString(R.string.status_running) else idleLabel
        binding.btnStart.isEnabled = !running
        binding.btnStop.isEnabled = running
        binding.goalInput.isEnabled = !running
    }

    private fun refreshMnnStatus() {
        binding.mnnStatus.text = "检测中…"
        lifecycleScope.launch {
            val p = com.runasakura.phoneagent.PhoneAgentApp.instance.prefs
            val m = try {
                kotlinx.coroutines.withTimeoutOrNull(4000) {
                    com.runasakura.phoneagent.llm.LocalMnnClient(p.mnnBaseUrl, p.mnnApiKey, p.mnnModel).resolveModel()
                }
            } catch (_: Exception) { null }
            binding.mnnStatus.text = when {
                m == null -> "未连接"
                m.isBlank() -> "已连接"
                else -> m
            }
            binding.mnnStatus.setTextColor(ContextCompat.getColor(this@MainActivity, if (m == null) R.color.err_red else R.color.ok_green))
        }
    }

    override fun onResume() {
        super.onResume()
        renderLog()
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

    private fun startAgent() {
        val goal = binding.goalInput.text?.toString()?.trim().orEmpty()
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
        UiLog.clear(); binding.logView.text = ""
        appendLog("启动任务: $goal")
        setRunning(true)
        AgentForegroundService.start(this, goal)
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
        UiLog.clear(); binding.logView.text = ""
        appendLog("—— 自检开始 ——")
        lifecycleScope.launch {
            val res = SelfTest(this@MainActivity) { line -> runOnUiThread { appendLog(line) } }.run(probe)
            val fails = res.count { !it.ok }
            res.firstOrNull { it.name.contains("OCR") }?.let {
                binding.ocrStatus.text = if (it.ok) "可用" else "失败"
                binding.ocrStatus.setTextColor(ContextCompat.getColor(this@MainActivity, if (it.ok) R.color.ok_green else R.color.err_red))
            }
            appendLog("—— 自检完成：${res.size - fails} 通过，$fails 失败（可点「导出诊断日志」分享）——")
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(if (fails == 0) "自检全部通过" else "自检：$fails 项失败")
                .setMessage(res.joinToString("\n") { "${if (it.ok) "✅" else "❌"} ${it.name}\n    ${it.detail}" })
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun exportDiagnostics() {
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        val a11y = if (AgentAccessibilityService.require() != null) "已连接" else "未连接"
        val text = com.runasakura.phoneagent.util.Diagnostics.export("$packageName $ver", a11y) +
            "\n--- 界面日志 ---\n" + binding.logView.text
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

    private fun styled(line: String): CharSequence {
        val color = when (UiLog.kind(line)) {
            UiLog.Kind.STEP -> R.color.step_accent
            UiLog.Kind.OK -> R.color.ok_green
            UiLog.Kind.ERR -> R.color.err_red
            UiLog.Kind.WARN -> R.color.warn_amber
            UiLog.Kind.NORMAL -> R.color.log_text
        }
        val sp = android.text.SpannableString(line + "\n")
        sp.setSpan(android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, color)), 0, sp.length, 0)
        if (UiLog.kind(line) == UiLog.Kind.STEP) sp.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sp.length, 0)
        return sp
    }

    private fun renderLog() {
        val sb = android.text.SpannableStringBuilder()
        for (l in UiLog.snapshot()) sb.append(styled(l))
        binding.logView.text = sb
        binding.scrollLog.post { binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun appendLog(line: String) {
        UiLog.add(line)
        // Re-render when the cap trimmed old lines, otherwise append incrementally.
        if (binding.logView.lineCount >= UiLog.CAP) renderLog() else {
            binding.logView.append(styled(line))
            binding.scrollLog.post { binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }
}

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
import com.runasakura.phoneagent.R
import com.runasakura.phoneagent.a11y.AgentAccessibilityService
import com.runasakura.phoneagent.databinding.ActivityMainBinding
import com.runasakura.phoneagent.service.AgentForegroundService

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AgentForegroundService.ACTION_STATUS) return
            val type = intent.getStringExtra(AgentForegroundService.EXTRA_TYPE)
            val msg = intent.getStringExtra(AgentForegroundService.EXTRA_MESSAGE).orEmpty()
            runOnUiThread {
                appendLog(msg)
                when (type) {
                    "confirm" -> Unit // shown by ConfirmActivity on top of any app
                    "running" -> binding.statusText.text = getString(R.string.status_running)
                    "done" -> binding.statusText.text = getString(R.string.status_idle)
                    "stopped" -> binding.statusText.text = getString(R.string.status_idle)
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

        binding.btnStart.setOnClickListener { startAgent() }
        binding.btnStop.setOnClickListener {
            AgentForegroundService.stop(this)
            appendLog("请求急停")
        }
        binding.btnExport.setOnClickListener { exportDiagnostics() }
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
        appendLog(getString(R.string.welcome_log))
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(AgentForegroundService.ACTION_STATUS)
        ContextCompat.registerReceiver(
            this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshA11yStatus()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
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
            AlertDialog.Builder(this)
                .setTitle(R.string.a11y_required_title)
                .setMessage(R.string.a11y_required_msg)
                .setPositiveButton(R.string.open_a11y) { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        binding.logView.text = ""
        appendLog("启动任务: $goal")
        binding.statusText.text = getString(R.string.status_running)
        AgentForegroundService.start(this, goal)
    }

    private fun showConfirm(reason: String) {
        AlertDialog.Builder(this)
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
        val enabled = isA11yEnabled()
        binding.a11yStatus.text = if (enabled) {
            getString(R.string.a11y_on)
        } else {
            getString(R.string.a11y_off)
        }
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

    private fun appendLog(line: String) {
        binding.logView.append(line)
        binding.logView.append("\n")
        binding.scrollLog.post { binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}

package com.runasakura.phoneagent.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.runasakura.phoneagent.PhoneAgentApp
import com.runasakura.phoneagent.R
import com.runasakura.phoneagent.databinding.ActivitySettingsBinding
import com.runasakura.phoneagent.llm.CloudProviders
import com.runasakura.phoneagent.prefs.AgentPreferences

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TsukiTheme.applyBars(this); setupAppearance(); TsukiTheme.retint(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val prefs = PhoneAgentApp.instance.prefs
        binding.mnnUrl.setText(prefs.mnnBaseUrl)
        binding.mnnKey.setText(prefs.mnnApiKey)
        binding.mnnModel.setText(prefs.mnnModel)
        binding.cloudUrl.setText(prefs.cloudBaseUrl)
        binding.cloudKey.setText(prefs.cloudApiKey)
        binding.cloudKey.hint = getString(R.string.cloud_key_hint)
        binding.cloudModel.setText(prefs.cloudModel)
        binding.maxSteps.setText(prefs.maxSteps.toString())
        binding.cloudShot.isChecked = prefs.allowCloudScreenshot
        binding.visionTip.text = com.runasakura.phoneagent.llm.CloudProviders.VISION_TIP
        binding.visionUrl.setText(prefs.visionBaseUrl)
        binding.visionModel.setText(prefs.visionModel)
        binding.visionLocal.isChecked = prefs.visionLocal
        binding.visionLocalModel.setText(prefs.visionLocalModel)
        binding.confirmAlarm.isChecked = prefs.confirmAlarm
        binding.cloudThinking.isChecked = prefs.cloudThinking
        binding.modelCheckpoint.isChecked = prefs.modelCheckpoint
        setupShizuku()
        setupKeepAlive()
        setupLocalModel()
        if (prefs.visionApiKey.isNotBlank()) binding.visionKey.hint = "已保存 " + com.runasakura.phoneagent.llm.CloudProviders.mask(prefs.visionApiKey) + "（留空不修改）"

        val modes = listOf(
            AgentPreferences.ROUTE_CLOUD_ASSIST,
            AgentPreferences.ROUTE_HYBRID,
            AgentPreferences.ROUTE_LOCAL_ONLY,
            AgentPreferences.ROUTE_CLOUD_ONLY
        )
        binding.routeMode.adapter = ArrayAdapter(this, R.layout.item_spinner,
            modes.map { com.runasakura.phoneagent.llm.RoutePolicy.label(it) + if (it == AgentPreferences.ROUTE_CLOUD_ASSIST) "（推荐）" else "" })
        val cur = if (prefs.routeMode == AgentPreferences.ROUTE_LOCAL_FIRST) AgentPreferences.ROUTE_HYBRID else prefs.routeMode
        binding.routeMode.setSelection(modes.indexOf(cur).coerceAtLeast(0))

        binding.btnAppMemory.setOnClickListener { showAppMemory() }
        binding.btnTestCloud.setOnClickListener {
            val url = binding.cloudUrl.text.toString().trim(); val key = binding.cloudKey.text.toString().trim()
            val model = binding.cloudModel.text.toString().trim().ifBlank { CloudProviders.byId(CloudProviders.effectiveProvider(prefs.cloudProvider, url, key)).model }
            binding.cloudTestResult.text = "测试中…"
            lifecycleScope.launch {
                val (ok, msg) = try { CloudProviders.test(url, key, model) } catch (e: Exception) { false to (e.message ?: "$e") }
                binding.cloudTestResult.text = (if (ok) "✅ " else "❌ ") + msg
            }
        }

        val providers = CloudProviders.ALL
        val modelAdapter = ArrayAdapter<String>(this, R.layout.item_spinner, mutableListOf())
        binding.cloudModel.setAdapter(modelAdapter)
        fun setModelChoices(list: List<String>) {
            modelAdapter.clear(); modelAdapter.addAll(list); modelAdapter.notifyDataSetChanged()
        }
        binding.cloudModel.setOnClickListener { binding.cloudModel.showDropDown() }
        binding.cloudModel.setOnFocusChangeListener { _, f -> if (f && modelAdapter.count > 0) binding.cloudModel.post { binding.cloudModel.showDropDown() } }
        val initialPos = providers.indexOfFirst { it.id == CloudProviders.effectiveProvider(prefs.cloudProvider, prefs.cloudBaseUrl, prefs.cloudApiKey) }.coerceAtLeast(0)
        binding.cloudProvider.adapter = ArrayAdapter(this, R.layout.item_spinner, providers.map { it.label })
        binding.cloudProvider.setSelection(initialPos)
        setModelChoices(providers[initialPos].models)
        if (binding.cloudModel.text.isNullOrBlank()) binding.cloudModel.setText(providers[initialPos].model)
        var lastPos = initialPos
        binding.cloudProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val p = providers[position]
                setModelChoices(p.models)
                if (position == lastPos) return   // initial callback: never overwrite the saved model
                val old = providers[lastPos]; lastPos = position
                val (u, m) = CloudProviders.onProviderChange(old, p, binding.cloudUrl.text.toString(), binding.cloudModel.text.toString())
                binding.cloudUrl.setText(u); binding.cloudModel.setText(m)
            }
        }
        binding.btnFetchModels.setOnClickListener {
            binding.cloudTestResult.text = "获取模型列表中…"
            lifecycleScope.launch {
                val (list, err) = CloudProviders.listModels(binding.cloudUrl.text.toString(), binding.cloudKey.text.toString())
                if (err != null) binding.cloudTestResult.text = "❌ 获取失败：$err（仍可手动输入模型名）"
                else {
                    setModelChoices(list)
                    binding.cloudTestResult.text = "✅ 共 ${list.size} 个模型，点模型框选择"
                    binding.cloudModel.requestFocus(); binding.cloudModel.showDropDown()
                }
            }
        }

        binding.btnSave.setOnClickListener {
            prefs.mnnBaseUrl = binding.mnnUrl.text.toString()
            prefs.mnnApiKey = binding.mnnKey.text.toString()
            prefs.mnnModel = binding.mnnModel.text.toString()
            prefs.cloudProvider = providers[binding.cloudProvider.selectedItemPosition].id
            prefs.cloudBaseUrl = binding.cloudUrl.text.toString().trim()
            prefs.cloudApiKey = binding.cloudKey.text.toString().trim()
            prefs.cloudModel = binding.cloudModel.text.toString().trim()
            CloudProviders.modelWarning(prefs.cloudModel, emptyList(), CloudProviders.byId(prefs.cloudProvider).takeIf { it.models.isNotEmpty() })
                ?.let { Toast.makeText(this, "⚠ $it（可点「测试连接」核对）", Toast.LENGTH_LONG).show() }
            if (CloudProviders.isReasoner(prefs.cloudModel)) Toast.makeText(this, "提示：推理模型较慢，建议日常用 chat 类模型", Toast.LENGTH_LONG).show()
            prefs.cloudProvider = CloudProviders.effectiveProvider(prefs.cloudProvider, prefs.cloudBaseUrl, prefs.cloudApiKey)
            prefs.maxSteps = binding.maxSteps.text.toString().toIntOrNull() ?: 25
            prefs.allowCloudScreenshot = binding.cloudShot.isChecked
            prefs.visionBaseUrl = binding.visionUrl.text.toString()
            prefs.visionModel = binding.visionModel.text.toString()
            prefs.visionLocal = binding.visionLocal.isChecked
            prefs.visionLocalModel = binding.visionLocalModel.text.toString()
            prefs.confirmAlarm = binding.confirmAlarm.isChecked
            prefs.cloudThinking = binding.cloudThinking.isChecked
            prefs.modelCheckpoint = binding.modelCheckpoint.isChecked
            com.runasakura.phoneagent.shell.ShizukuBackend.setEnabled(this, binding.useShizuku.isChecked)
            binding.visionKey.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.visionApiKey = it }
            prefs.routeMode = modes[binding.routeMode.selectedItemPosition]
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private val shizukuListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refreshShizuku() } }

    private fun setupShizuku() {
        binding.useShizuku.isChecked = com.runasakura.phoneagent.shell.ShizukuBackend.isEnabled(this)
        binding.btnShizukuRefresh.setOnClickListener { refreshShizuku() }
        binding.btnShizukuGrant.setOnClickListener {
            val st = com.runasakura.phoneagent.shell.ShizukuBackend.status(this)
            when (st) {
                com.runasakura.phoneagent.shell.ShizukuStatus.READY -> Toast.makeText(this, "已授权", Toast.LENGTH_SHORT).show()
                com.runasakura.phoneagent.shell.ShizukuStatus.NO_PERMISSION -> if (!com.runasakura.phoneagent.shell.ShizukuBackend.requestPermission())
                    Toast.makeText(this, "之前选择了拒绝：请在 Shizuku 应用的「已授权应用」里手动允许", Toast.LENGTH_LONG).show()
                com.runasakura.phoneagent.shell.ShizukuStatus.NOT_RUNNING -> openShizukuApp()
                com.runasakura.phoneagent.shell.ShizukuStatus.NOT_INSTALLED -> Toast.makeText(this, "请先安装 Shizuku（GitHub RikkaApps/Shizuku 或应用商店）", Toast.LENGTH_LONG).show()
            }
        }
        try { rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuListener) } catch (_: Throwable) {}
        refreshShizuku()
    }

    private fun openShizukuApp() {
        packageManager.getLaunchIntentForPackage(com.runasakura.phoneagent.shell.ShizukuBackend.MANAGER_PKG)?.let { startActivity(it) }
            ?: Toast.makeText(this, "未找到 Shizuku 应用", Toast.LENGTH_SHORT).show()
    }

    private fun refreshShizuku() {
        val st = com.runasakura.phoneagent.shell.ShizukuBackend.status(this)
        val dot = if (st == com.runasakura.phoneagent.shell.ShizukuStatus.READY) "●  " else "○  "
        binding.shizukuStatus.text = dot + st.label
        binding.btnShizukuGrant.text = when (st) {
            com.runasakura.phoneagent.shell.ShizukuStatus.NOT_RUNNING -> "打开 Shizuku"
            com.runasakura.phoneagent.shell.ShizukuStatus.READY -> "已授权"
            else -> getString(R.string.shizuku_grant)
        }
        binding.btnShizukuGrant.isEnabled = st != com.runasakura.phoneagent.shell.ShizukuStatus.READY
    }

    override fun onResume() { super.onResume(); if (::binding.isInitialized) { refreshShizuku(); refreshAlive() } }

    private fun setupKeepAlive() {
        val heal = com.runasakura.phoneagent.a11y.A11ySelfHeal
        binding.selfHeal.isChecked = heal.isOn(this)
        binding.selfHeal.setOnCheckedChangeListener { _, on -> heal.setOn(this, on) }
        binding.keepAlive.isChecked = com.runasakura.phoneagent.service.KeepAliveService.isOn(this)
        binding.keepAlive.setOnCheckedChangeListener { _, on -> com.runasakura.phoneagent.service.KeepAliveService.setOn(this, on) }
        binding.btnBattery.setOnClickListener {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm?.isIgnoringBatteryOptimizations(packageName) == true) Toast.makeText(this, "已忽略电池优化", Toast.LENGTH_SHORT).show()
            else tryStart(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName")),
                android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        binding.btnLaunchMgr.setOnClickListener {
            // The vendor "app launch" manager first (Honor/Huawei system manager), then this app's details page.
            val vendor = com.runasakura.phoneagent.a11y.KeepAliveIntents.launchManagerCandidates().map { (p, c) -> android.content.Intent().setClassName(p, c) }
            tryStart(*(vendor + android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))).toTypedArray())
        }
        binding.btnA11ySettings.setOnClickListener { tryStart(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        binding.btnGrantSecure.setOnClickListener {
            Thread {
                val ok = heal.grantSecurePermViaShizuku(this)
                runOnUiThread {
                    Toast.makeText(this, if (ok) "已授予：之后无障碍被关闭会自动重新开启" else "需要 Shizuku 已就绪并已授权本应用", Toast.LENGTH_LONG).show()
                    refreshAlive()
                }
            }.start()
        }
        refreshAlive()
    }

    private fun tryStart(vararg intents: android.content.Intent) {
        for (i in intents) try { startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        Toast.makeText(this, "无法打开该设置页，请在系统设置里手动查找", Toast.LENGTH_LONG).show()
    }

    private fun refreshAlive() {
        val heal = com.runasakura.phoneagent.a11y.A11ySelfHeal
        val connected = com.runasakura.phoneagent.a11y.AgentAccessibilityService.require() != null
        val st = com.runasakura.phoneagent.a11y.A11yHealth.state(connected, runCatching { heal.listed(this) }.getOrDefault(false), true)
        val battery = getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
        binding.aliveStatus.text = listOfNotNull(
            "无障碍：${st.label}",
            "电池优化：" + if (battery) "已忽略 ✓" else "受限制（建议忽略）",
            "自愈权限：" + if (heal.hasSecurePerm(this)) "已授予 ✓" else "未授予（可用 Shizuku 授予）",
            heal.lastHeal(this)?.let { "最近自愈：$it" },
            com.runasakura.phoneagent.CrashGuard.last(this)?.let { "最近异常：" + it.substringAfter(' ').take(80) },
        ).joinToString("\n")
        binding.btnGrantSecure.isEnabled = !heal.hasSecurePerm(this)
    }

    override fun onDestroy() {
        try { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuListener) } catch (_: Throwable) {}
        super.onDestroy()
    }

    /** Per-app experience written by the model: view, delete one app, or clear all. */
    private fun showAppMemory() {
        val prefs = PhoneAgentApp.instance.prefs
        val all = prefs.allAppMemory()
        if (all.isEmpty()) { android.widget.Toast.makeText(this, "还没有学到任何应用经验", android.widget.Toast.LENGTH_SHORT).show(); return }
        val pkgs = all.keys.sorted()
        fun name(p: String) = if (p == com.runasakura.phoneagent.agent.AppMemory.GLOBAL) "通用界面经验（跨应用）" else p
        val labels = pkgs.map { p -> val m = com.runasakura.phoneagent.agent.AppMemory.decode(all[p]); "${name(p)}\n经验 ${m.facts.size} 条 · 路线 ${m.paths.size} 条" }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("应用经验（点一项查看）")
            .setItems(labels) { _, i ->
                val p = pkgs[i]; val m = com.runasakura.phoneagent.agent.AppMemory.decode(all[p])
                fun badge(c: Int) = if (c >= 2) "（已验证 $c 次）" else "（未验证）"
                val body = (m.factEntries.map { "• ${it.text}${badge(it.conf)}" } + m.pathEntries.map { "→ ${it.text.substringBefore("=>")}：${it.text.substringAfter("=>")}${badge(it.conf)}" }).joinToString("\n").ifBlank { "（空）" }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(name(p)).setMessage(body)
                    .setPositiveButton("关闭", null)
                    .setNegativeButton("删除此应用经验") { _, _ -> prefs.clearAppMemory(p) }.show()
            }
            .setNegativeButton("全部清除") { _, _ -> prefs.clearAppMemory() }
            .setPositiveButton("关闭", null).show()
    }

    private val pickModelDir = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) com.runasakura.phoneagent.service.ModelDownloadService.import(this, uri)
    }

    /** 本机模型 card: engine choice (embedded / MNN Chat API), status, download / import / warm-up / delete. */
    private fun setupLocalModel() {
        val prefs = com.runasakura.phoneagent.PhoneAgentApp.instance.prefs
        val engines = listOf(AgentPreferences.LOCAL_EMBEDDED to "Tsuki 内置（推荐）", AgentPreferences.LOCAL_MNN_CHAT to "MNN Chat API")
        for ((id, label) in engines) binding.localEngine.addView(com.google.android.material.chip.Chip(this).apply {
            text = label; isCheckable = true; isChecked = prefs.localEngine == id; minHeight = (48 * resources.displayMetrics.density).toInt()
            setOnClickListener { prefs.localEngine = id; refreshLocal() } })
        binding.btnLocalDownload.setOnClickListener {
            if (com.runasakura.phoneagent.service.ModelDownloadService.state.value.running) com.runasakura.phoneagent.service.ModelDownloadService.cancel(this)
            else com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("下载 Qwen3-VL-2B-Instruct（MNN）")
                .setMessage("约 1.5 GB，来自 ModelScope（MNN/Qwen3-VL-2B-Instruct-MNN），逐个文件 sha256 校验，可断点续传。建议连接 Wi-Fi。\n\nMNN Chat 已下载的模型在它的私有目录里，其他应用读不到；如果你用文件管理器把模型文件夹复制出来，也可以用「导入模型文件夹」。")
                .setPositiveButton("下载") { _, _ -> com.runasakura.phoneagent.service.ModelDownloadService.download(this) }.setNegativeButton("取消", null).show()
        }
        binding.btnLocalImport.setOnClickListener { pickModelDir.launch(null) }
        binding.btnLocalWarm.setOnClickListener {
            binding.localStatus.text = "预热中…"
            lifecycleScope.launch {
                val dir = com.runasakura.phoneagent.llm.ModelStore.selected(this@SettingsActivity)
                val msg = if (dir == null) "还没有本机模型" else try {
                    val t0 = System.currentTimeMillis()
                    val ok = com.runasakura.phoneagent.llm.EmbeddedMnn.ensureLoaded(applicationContext, dir)
                    if (!ok) "加载失败：${com.runasakura.phoneagent.llm.EmbeddedMnn.lastError}" else {
                        val t1 = System.currentTimeMillis()
                        val out = com.runasakura.phoneagent.llm.EmbeddedMnn.chat(applicationContext, listOf(com.runasakura.phoneagent.llm.ChatMessage("user", "用一句话介绍你自己")), 48)
                        "体检通过：加载 ${t1 - t0}ms，生成 ${System.currentTimeMillis() - t1}ms：${com.runasakura.phoneagent.llm.MnnText.clean(out).take(40)}"
                    }
                } catch (e: Throwable) { "体检失败：${e.message}" }
                binding.localStatus.text = msg
            }
        }
        binding.btnLocalDelete.setOnClickListener {
            val dir = com.runasakura.phoneagent.llm.ModelStore.selected(this) ?: return@setOnClickListener
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("删除 ${dir.name}？")
                .setMessage("释放约 ${com.runasakura.phoneagent.llm.ModelStore.sizeOf(dir) / 1_000_000} MB，之后可重新下载。")
                .setPositiveButton("删除") { _, _ -> com.runasakura.phoneagent.llm.ModelStore.delete(this, dir); refreshLocal() }.setNegativeButton("取消", null).show()
        }
        lifecycleScope.launch {
            com.runasakura.phoneagent.service.ModelDownloadService.state.collect { st ->
                binding.localProgress.visibility = if (st.running) android.view.View.VISIBLE else android.view.View.GONE
                if (st.total > 0) binding.localProgress.setProgressCompat((st.done * 1000 / st.total).toInt(), true) else binding.localProgress.isIndeterminate = st.running
                binding.btnLocalDownload.text = if (st.running) "取消下载" else getString(R.string.local_download)
                if (st.running) binding.localStatus.text = "${st.msg} · ${st.done / 1_000_000}/${st.total / 1_000_000} MB"
                else if (st.msg.isNotBlank()) { binding.localStatus.text = st.msg; refreshLocal(keepMsg = true) }
            }
        }
        refreshLocal()
    }

    private fun refreshLocal(keepMsg: Boolean = false) {
        val prefs = com.runasakura.phoneagent.PhoneAgentApp.instance.prefs
        val embedded = prefs.localEngine == AgentPreferences.LOCAL_EMBEDDED
        val dir = com.runasakura.phoneagent.llm.ModelStore.selected(this)
        for (b in listOf(binding.btnLocalDownload, binding.btnLocalImport, binding.btnLocalWarm, binding.btnLocalDelete)) b.isEnabled = embedded
        binding.btnLocalWarm.isEnabled = embedded && dir != null; binding.btnLocalDelete.isEnabled = embedded && dir != null
        if (keepMsg) return
        binding.localStatus.text = when {
            !embedded -> "使用 MNN Chat 的 API（下面的地址）。MNN Chat 在后台会被系统限速。"
            !com.runasakura.phoneagent.llm.EmbeddedMnn.available -> "本设备不支持内置推理（需要 arm64）"
            else -> com.runasakura.phoneagent.llm.LocalWarmup.status(this) + (dir?.let { " · ${com.runasakura.phoneagent.llm.ModelStore.sizeOf(it) / 1_000_000} MB" + if (com.runasakura.phoneagent.llm.ModelStore.isVision(it)) " · 可看图" else "" } ?: "")
        }
    }

    /** Palette + light/dark chips. Both apply immediately: palette retints live, a night-mode change recreates. */
    private fun setupAppearance() {
        val pg = binding.paletteGroup; val mg = binding.modeGroup
        for (p in TsukiTheme.Palette.entries) pg.addView(com.google.android.material.chip.Chip(this).apply {
            text = p.label; isCheckable = true; isChecked = TsukiTheme.palette(this@SettingsActivity) == p; minHeight = (48 * resources.displayMetrics.density).toInt()
            setOnClickListener { TsukiTheme.setPalette(this@SettingsActivity, p); TsukiTheme.applyBars(this@SettingsActivity); TsukiTheme.retint(binding.root); syncModeChips() } })
        for (m in TsukiTheme.Mode.entries) mg.addView(com.google.android.material.chip.Chip(this).apply {
            text = m.label; isCheckable = true; isChecked = TsukiTheme.mode(this@SettingsActivity) == m; minHeight = (48 * resources.displayMetrics.density).toInt()
            setOnClickListener { if (TsukiTheme.mode(this@SettingsActivity) != m) TsukiTheme.setMode(this@SettingsActivity, m) } })
        syncModeChips()
        // Floating Tsuki orb: on/off + idle opacity, in the same card (it follows the palette too).
        val host = pg.parent as android.view.ViewGroup
        val orbSwitch = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = getString(R.string.orb_toggle); isChecked = com.runasakura.phoneagent.overlay.TsukiOrb.enabled(this@SettingsActivity)
            minHeight = (48 * resources.displayMetrics.density).toInt(); setTextColor(TsukiTheme.color(this@SettingsActivity, R.color.text_primary))
        }
        val opLabel = TextView(this).apply { setTextColor(TsukiTheme.color(this@SettingsActivity, R.color.text_secondary)); textSize = 13f }
        val slider = com.google.android.material.slider.Slider(this).apply {
            valueFrom = 40f; valueTo = 100f; stepSize = 10f; value = com.runasakura.phoneagent.overlay.TsukiOrb.opacity(this@SettingsActivity).toFloat()
            contentDescription = getString(R.string.orb_opacity)
        }
        fun sync() { val on = orbSwitch.isChecked; slider.isEnabled = on; opLabel.alpha = if (on) 1f else 0.5f
            opLabel.text = getString(R.string.orb_opacity) + " · ${slider.value.toInt()}%" }
        orbSwitch.setOnCheckedChangeListener { _, on -> com.runasakura.phoneagent.overlay.TsukiOrb.setEnabled(this, on); sync() }
        slider.addOnChangeListener { _, v, _ -> com.runasakura.phoneagent.overlay.TsukiOrb.setOpacity(this, v.toInt()); sync() }
        host.addView(orbSwitch); host.addView(opLabel); host.addView(slider); sync()
    }

    /** 纯月 is light-only: the light/dark chips are disabled with a hint instead of silently doing nothing. */
    private fun syncModeChips() {
        val pure = TsukiTheme.palette(this) == TsukiTheme.Palette.PURE_MOON
        for (i in 0 until binding.modeGroup.childCount) binding.modeGroup.getChildAt(i).apply { isEnabled = !pure; alpha = if (pure) 0.45f else 1f }
        binding.modeGroup.contentDescription = if (pure) "纯月为明亮主题，固定浅色" else null
    }
}

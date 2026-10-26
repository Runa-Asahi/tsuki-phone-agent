package com.runasakura.phoneagent.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
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
        if (prefs.visionApiKey.isNotBlank()) binding.visionKey.hint = "已保存 " + com.runasakura.phoneagent.llm.CloudProviders.mask(prefs.visionApiKey) + "（留空不修改）"

        val modes = listOf(
            AgentPreferences.ROUTE_HYBRID,
            AgentPreferences.ROUTE_LOCAL_ONLY,
            AgentPreferences.ROUTE_CLOUD_ONLY
        )
        binding.routeMode.adapter = ArrayAdapter(this, R.layout.item_spinner,
            modes.map { com.runasakura.phoneagent.llm.RoutePolicy.label(it) + if (it == AgentPreferences.ROUTE_HYBRID) "（推荐）" else "" })
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
            binding.visionKey.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.visionApiKey = it }
            prefs.routeMode = modes[binding.routeMode.selectedItemPosition]
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** Per-app experience written by the model: view, delete one app, or clear all. */
    private fun showAppMemory() {
        val prefs = PhoneAgentApp.instance.prefs
        val all = prefs.allAppMemory()
        if (all.isEmpty()) { android.widget.Toast.makeText(this, "还没有学到任何应用经验", android.widget.Toast.LENGTH_SHORT).show(); return }
        val pkgs = all.keys.sorted()
        val labels = pkgs.map { p -> val m = com.runasakura.phoneagent.agent.AppMemory.decode(all[p]); "$p\n经验 ${m.facts.size} 条 · 路线 ${m.paths.size} 条" }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("应用经验（点一项查看）")
            .setItems(labels) { _, i ->
                val p = pkgs[i]; val m = com.runasakura.phoneagent.agent.AppMemory.decode(all[p])
                val body = (m.facts.map { "• $it" } + m.paths.map { "→ ${it.substringBefore("=>")}：${it.substringAfter("=>")}" }).joinToString("\n").ifBlank { "（空）" }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(p).setMessage(body)
                    .setPositiveButton("关闭", null)
                    .setNegativeButton("删除此应用经验") { _, _ -> prefs.clearAppMemory(p) }.show()
            }
            .setNegativeButton("全部清除") { _, _ -> prefs.clearAppMemory() }
            .setPositiveButton("关闭", null).show()
    }
}

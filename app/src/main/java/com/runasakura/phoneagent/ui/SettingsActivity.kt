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
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
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

        val modes = listOf(
            AgentPreferences.ROUTE_HYBRID,
            AgentPreferences.ROUTE_LOCAL_ONLY,
            AgentPreferences.ROUTE_CLOUD_ONLY
        )
        binding.routeMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            modes.map { com.runasakura.phoneagent.llm.RoutePolicy.label(it) + if (it == AgentPreferences.ROUTE_HYBRID) "（推荐）" else "" })
        val cur = if (prefs.routeMode == AgentPreferences.ROUTE_LOCAL_FIRST) AgentPreferences.ROUTE_HYBRID else prefs.routeMode
        binding.routeMode.setSelection(modes.indexOf(cur).coerceAtLeast(0))

        binding.btnTestCloud.setOnClickListener {
            val url = binding.cloudUrl.text.toString().trim(); val key = binding.cloudKey.text.toString().trim()
            val model = binding.cloudModel.text.toString().trim().ifBlank { AgentPreferences.DEEPSEEK_MODEL }
            binding.cloudTestResult.text = "测试中…"
            lifecycleScope.launch {
                val (ok, msg) = try { CloudProviders.test(url, key, model) } catch (e: Exception) { false to (e.message ?: "$e") }
                binding.cloudTestResult.text = (if (ok) "✅ " else "❌ ") + msg
            }
        }

        val providers = CloudProviders.ALL
        binding.cloudProvider.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers.map { it.label })
        binding.cloudProvider.setSelection(providers.indexOfFirst { it.id == prefs.cloudProvider }.coerceAtLeast(0))
        binding.cloudProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val p = providers[position]
                if (p.id == AgentPreferences.PROVIDER_DEEPSEEK) {
                    if (binding.cloudUrl.text.isNullOrBlank() || binding.cloudUrl.text.toString() == AgentPreferences.DEEPSEEK_BASE ||
                        prefs.cloudProvider != AgentPreferences.PROVIDER_DEEPSEEK) {
                        binding.cloudUrl.setText(AgentPreferences.DEEPSEEK_BASE)
                    }
                    if (binding.cloudModel.text.isNullOrBlank() || prefs.cloudProvider != AgentPreferences.PROVIDER_DEEPSEEK) {
                        binding.cloudModel.setText(AgentPreferences.DEEPSEEK_MODEL)
                    }
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
            prefs.cloudProvider = CloudProviders.effectiveProvider(prefs.cloudProvider, prefs.cloudBaseUrl, prefs.cloudApiKey)
            prefs.maxSteps = binding.maxSteps.text.toString().toIntOrNull() ?: 25
            prefs.allowCloudScreenshot = binding.cloudShot.isChecked
            prefs.routeMode = modes[binding.routeMode.selectedItemPosition]
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

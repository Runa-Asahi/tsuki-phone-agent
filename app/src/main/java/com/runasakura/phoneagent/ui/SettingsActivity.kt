package com.runasakura.phoneagent.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.runasakura.phoneagent.PhoneAgentApp
import com.runasakura.phoneagent.R
import com.runasakura.phoneagent.databinding.ActivitySettingsBinding
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
        binding.cloudModel.setText(prefs.cloudModel)
        binding.maxSteps.setText(prefs.maxSteps.toString())
        binding.cloudShot.isChecked = prefs.allowCloudScreenshot

        val modes = listOf(
            AgentPreferences.ROUTE_HYBRID,
            AgentPreferences.ROUTE_LOCAL_FIRST,
            AgentPreferences.ROUTE_LOCAL_ONLY
        )
        binding.routeMode.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, modes
        )
        binding.routeMode.setSelection(modes.indexOf(prefs.routeMode).coerceAtLeast(0))

        binding.btnSave.setOnClickListener {
            prefs.mnnBaseUrl = binding.mnnUrl.text.toString()
            prefs.mnnApiKey = binding.mnnKey.text.toString()
            prefs.mnnModel = binding.mnnModel.text.toString()
            prefs.cloudBaseUrl = binding.cloudUrl.text.toString()
            prefs.cloudApiKey = binding.cloudKey.text.toString()
            prefs.cloudModel = binding.cloudModel.text.toString()
            prefs.maxSteps = binding.maxSteps.text.toString().toIntOrNull() ?: 25
            prefs.allowCloudScreenshot = binding.cloudShot.isChecked
            prefs.routeMode = binding.routeMode.selectedItem as String
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

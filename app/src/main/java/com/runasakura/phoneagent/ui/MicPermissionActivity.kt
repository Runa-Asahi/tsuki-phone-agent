package com.runasakura.phoneagent.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.runasakura.phoneagent.service.AgentForegroundService
import com.runasakura.phoneagent.voice.VoiceInput

/**
 * Transparent one-shot RECORD_AUDIO request, usable from the floating orb in the middle of a task (the service has no
 * UI to ask from). On grant it tells the running agent service to re-type its foreground service with MICROPHONE
 * (legal now because this activity is visible) and start listening — no restart of the task needed.
 */
class MicPermissionActivity : AppCompatActivity() {
    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> done(ok) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VoiceInput.hasMicPermission(this)) done(true) else if (savedInstanceState == null) perm.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    private fun done(ok: Boolean) {
        if (ok) {
            // Delivered while we are still in front, so the while-in-use FGS microphone type is allowed.
            if (AgentForegroundService.isRunning()) runCatching {
                startService(Intent(this, AgentForegroundService::class.java).setAction(AgentForegroundService.ACTION_MIC_READY))
            }
        } else Toast.makeText(this, "没有麦克风权限，无法语音（可在系统设置→应用→Tsuki→权限中开启）", Toast.LENGTH_LONG).show()
        window.decorView.postDelayed({ finish(); overridePendingTransition(0, 0) }, if (ok) 350 else 0)
    }
}

package com.runasakura.phoneagent.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.runasakura.phoneagent.R
import com.runasakura.phoneagent.service.AgentForegroundService

/**
 * Translucent, separate-task confirmation screen. Launched by the service so the send-confirmation
 * appears on top of WeChat/QQ (the main activity is paused then and would miss the broadcast).
 * Finishing returns straight to the chat app (own task affinity, excluded from recents).
 */
class ConfirmActivity : AppCompatActivity() {
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val msg = intent.getStringExtra(EXTRA_MSG).orEmpty()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_title)
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton(R.string.confirm_yes) { _, _ -> answer(true) }
            .setNegativeButton(R.string.confirm_no) { _, _ -> answer(false) }
            .show()
    }

    private fun answer(ok: Boolean) {
        answered = true
        AgentForegroundService.deliverConfirm(ok)
        finish()
    }

    override fun onDestroy() {
        // Leaving without answering = NOT confirmed (fail safe)
        if (!answered && isFinishing) AgentForegroundService.deliverConfirm(false)
        super.onDestroy()
    }

    companion object { const val EXTRA_MSG = "msg" }
}

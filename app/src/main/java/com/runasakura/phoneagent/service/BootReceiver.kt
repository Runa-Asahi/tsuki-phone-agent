package com.runasakura.phoneagent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot / app update: re-enable the accessibility service if MagicOS dropped it, and restore keep-alive. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        Thread {
            try {
                com.runasakura.phoneagent.a11y.A11ySelfHeal.heal(context, "开机/更新后")
                if (KeepAliveService.isOn(context)) KeepAliveService.start(context)
            } catch (_: Throwable) {} finally { pending.finish() }
        }.start()
    }
}

package com.runasakura.phoneagent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.runasakura.phoneagent.prefs.AgentPreferences

class PhoneAgentApp : Application() {
    lateinit var prefs: AgentPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = AgentPreferences(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AGENT,
                getString(R.string.channel_agent),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_agent_desc)
                setShowBadge(false)
            }
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                getString(R.string.channel_alert),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    companion object {
        const val CHANNEL_AGENT = "agent_running"
        const val CHANNEL_ALERT = "agent_alert"
        lateinit var instance: PhoneAgentApp
            private set
    }
}

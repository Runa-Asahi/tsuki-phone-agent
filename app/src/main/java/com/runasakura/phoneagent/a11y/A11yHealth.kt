package com.runasakura.phoneagent.a11y

import android.content.Context

/**
 * Pure logic for keeping our accessibility service enabled (MagicOS turns it off after a crash or a background kill).
 * The secure setting is a ':'-separated list of components; we only ever APPEND ours and never drop anyone else's.
 */
object A11yHealth {
    fun component(pkg: String) = "$pkg/${AgentAccessibilityService::class.java.name}"

    private fun same(entry: String, pkg: String): Boolean {
        val e = entry.trim()
        val full = component(pkg)
        val short = "$pkg/" + full.substringAfter("/").removePrefix(pkg)   // "pkg/.a11y.AgentAccessibilityService"
        return e.equals(full, true) || e.equals(short, true)
    }

    fun isListed(current: String?, pkg: String): Boolean = current.orEmpty().split(':').any { same(it, pkg) }

    /** [current] with our component appended (unchanged when already present; empty entries dropped). */
    fun merged(current: String?, pkg: String): String {
        val parts = current.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.any { same(it, pkg) }) parts.joinToString(":") else (parts + component(pkg)).joinToString(":")
    }

    /** argv commands (privileged shell) that re-enable the service. */
    fun healCommands(current: String?, pkg: String): List<List<String>> = listOf(
        listOf("settings", "put", "secure", "enabled_accessibility_services", merged(current, pkg)),
        listOf("settings", "put", "secure", "accessibility_enabled", "1"),
    )

    enum class State(val label: String) { CONNECTED("已连接"), ENABLED_NOT_BOUND("已开启·未连接"), DISABLED("未开启") }

    fun state(connected: Boolean, listed: Boolean, masterOn: Boolean): State = when {
        connected -> State.CONNECTED
        listed && masterOn -> State.ENABLED_NOT_BOUND
        else -> State.DISABLED
    }
}

/**
 * Self-heal: when the service has been switched off, turn it back on — directly when we hold WRITE_SECURE_SETTINGS
 * (granted once through Shizuku, survives reboots), else through Shizuku's shell. Never touches other services.
 */
object A11ySelfHeal {
    private const val PREFS = "agent_backend"
    private const val KEY = "a11y_self_heal"
    private const val PERM = "android.permission.WRITE_SECURE_SETTINGS"

    fun isOn(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    fun setOn(ctx: Context, on: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()

    fun listed(ctx: Context): Boolean {
        val cur = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services")
        val master = android.provider.Settings.Secure.getInt(ctx.contentResolver, "accessibility_enabled", 0) == 1
        return master && A11yHealth.isListed(cur, ctx.packageName)
    }

    fun hasSecurePerm(ctx: Context) = ctx.checkSelfPermission(PERM) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Ask Shizuku to grant WRITE_SECURE_SETTINGS to ourselves so later heals (e.g. at boot, Shizuku stopped) work. */
    fun grantSecurePermViaShizuku(ctx: Context): Boolean {
        if (hasSecurePerm(ctx)) return true
        val sh = com.runasakura.phoneagent.shell.ShizukuBackend.shellOrNull(ctx) ?: return false
        return sh.exec(listOf("pm", "grant", ctx.packageName, PERM), 5000).ok && hasSecurePerm(ctx)
    }

    /** Returns a short description of what happened (null = nothing needed / not possible). */
    fun heal(ctx: Context, reason: String): String? = try {
        when {
            !isOn(ctx) -> null
            AgentAccessibilityService.require() != null || listed(ctx) -> null
            else -> {
                val cur = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services")
                if (hasSecurePerm(ctx) || grantSecurePermViaShizuku(ctx)) {
                    android.provider.Settings.Secure.putString(ctx.contentResolver, "enabled_accessibility_services", A11yHealth.merged(cur, ctx.packageName))
                    android.provider.Settings.Secure.putInt(ctx.contentResolver, "accessibility_enabled", 1)
                    log(ctx, "已自动重新开启无障碍（$reason，系统权限）")
                } else {
                    val sh = com.runasakura.phoneagent.shell.ShizukuBackend.shellOrNull(ctx)
                    if (sh != null && A11yHealth.healCommands(cur, ctx.packageName).all { sh.exec(it, 5000).ok }) log(ctx, "已自动重新开启无障碍（$reason，Shizuku）")
                    else null
                }
            }
        }
    } catch (e: Throwable) { android.util.Log.w("A11ySelfHeal", "heal failed", e); null }

    private fun log(ctx: Context, msg: String): String {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("a11y_last_heal", "${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date())} $msg").apply()
        return msg
    }

    fun lastHeal(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("a11y_last_heal", null)
}

/** Settings pages for keep-alive guidance (system pages only; tried in order, the first that opens wins). */
object KeepAliveIntents {
    fun launchManagerCandidates(): List<Pair<String, String>> = listOf(
        "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
    )
}

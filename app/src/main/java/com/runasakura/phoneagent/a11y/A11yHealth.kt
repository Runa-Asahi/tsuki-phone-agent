package com.runasakura.phoneagent.a11y

import android.content.Context

/**
 * Pure logic for keeping our accessibility service enabled (MagicOS turns it off after a crash or a background kill).
 * The secure setting is a ':'-separated list of components; we only ever APPEND ours and never drop anyone else's.
 */
object A11yHealth {
    fun component(pkg: String) = "$pkg/${AgentAccessibilityService::class.java.name}"

    /**
     * Exact ComponentName match: "pkg/fully.qualified.Class" or the short form "pkg/.rel.Class" (relative to the
     * package OR to the code namespace — debug builds have an applicationId suffix the class name lacks).
     */
    fun same(entry: String, pkg: String, cls: String = AgentAccessibilityService::class.java.name): Boolean {
        val e = entry.trim(); val slash = e.indexOf('/'); if (slash <= 0) return false
        val p = e.substring(0, slash); val c = e.substring(slash + 1)
        if (!p.equals(pkg, true) || c.isEmpty()) return false
        if (!c.startsWith(".")) return c == cls
        val ns = cls.substringBeforeLast('.').let { full -> NAMESPACE.takeIf { full.startsWith(it) } ?: full }
        return pkg + c == cls || ns + c == cls
    }
    private const val NAMESPACE = "com.runasakura.phoneagent"

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

    enum class HealAction { NONE, MASTER_ONLY, APPEND }

    /**
     * What self-heal may write. NEVER touches the services list while our component is present (rewriting it — even
     * with the same value — makes the system re-evaluate bindings and drop/rebind the service): present + master off
     * → only flip the master switch; absent → append ours (others untouched); connected → nothing at all.
     */
    fun decide(connected: Boolean, current: String?, masterOn: Boolean, pkg: String): HealAction = when {
        connected -> HealAction.NONE
        isListed(current, pkg) -> if (masterOn) HealAction.NONE else HealAction.MASTER_ONLY
        else -> HealAction.APPEND
    }

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
            else -> {
                val cur = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services")
                val master = android.provider.Settings.Secure.getInt(ctx.contentResolver, "accessibility_enabled", 0) == 1
                when (val act = A11yHealth.decide(AgentAccessibilityService.require() != null, cur, master, ctx.packageName)) {
                    A11yHealth.HealAction.NONE -> null
                    else -> {
                        A11yEvents.record("自愈写入（$reason）：${if (act == A11yHealth.HealAction.MASTER_ONLY) "仅打开总开关" else "追加本服务"}")
                        if (hasSecurePerm(ctx) || grantSecurePermViaShizuku(ctx)) {
                            if (act == A11yHealth.HealAction.APPEND)
                                android.provider.Settings.Secure.putString(ctx.contentResolver, "enabled_accessibility_services", A11yHealth.merged(cur, ctx.packageName))
                            android.provider.Settings.Secure.putInt(ctx.contentResolver, "accessibility_enabled", 1)
                            log(ctx, "已自动重新开启无障碍（$reason，系统权限）")
                        } else {
                            val sh = com.runasakura.phoneagent.shell.ShizukuBackend.shellOrNull(ctx)
                            val cmds = A11yHealth.healCommands(cur, ctx.packageName).let { if (act == A11yHealth.HealAction.MASTER_ONLY) it.drop(1) else it }
                            if (sh != null && cmds.all { sh.exec(it, 5000).ok }) log(ctx, "已自动重新开启无障碍（$reason，Shizuku）")
                            else null
                        }
                    }
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


/**
 * Connect/disconnect timeline of our accessibility service, each disconnect annotated with the Shizuku commands run
 * in the preceding seconds — the evidence needed to tell a self-inflicted unbind (UiAutomation) from a system reclaim.
 */
object A11yEvents {
    data class Event(val atMs: Long, val what: String, val recentShell: List<String>)
    private val ring = ArrayDeque<Event>()

    @Synchronized fun record(what: String, now: Long = System.currentTimeMillis()) {
        val recent = if (what.startsWith("已连接")) emptyList()
            else com.runasakura.phoneagent.shell.ShellLog.entries().filter { now - it.atMs in 0..5000 }.map { "${it.cmd}（${it.caller}）" }
        ring.addLast(Event(now, what, recent)); while (ring.size > 40) ring.removeFirst()
    }
    @Synchronized fun events(): List<Event> = ring.toList()
    @Synchronized fun lastDisconnect(): Event? = ring.lastOrNull { !it.what.startsWith("已连接") }

    /** The real likely cause of a disconnect, for the user-facing message (no "just updated the APK" guess). */
    fun cause(e: Event?, enabledInSettings: Boolean): String = when {
        e != null && e.recentShell.any { it.contains("uiautomator") || it.contains("instrument") } ->
            "断开前 5 秒内运行了 UiAutomation 类命令（${e.recentShell.first()}），系统因此解绑了无障碍服务"
        !enabledInSettings -> "系统设置里本应用的无障碍开关已被关闭（系统省电/清理或手动关闭）"
        else -> "系统暂时解绑了服务（内存回收/进程被杀或刚更新安装），开关仍开着，通常会自动重连"
    }

    fun export(): String = events().joinToString("\n") { ev ->
        com.runasakura.phoneagent.shell.ShellLog.fmt(ev.atMs) + " " + ev.what + (if (ev.recentShell.isNotEmpty()) "；之前 5 秒 Shizuku：" + ev.recentShell.joinToString("、") else "")
    }.ifEmpty { "（无）" }
}

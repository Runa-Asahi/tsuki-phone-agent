package com.runasakura.phoneagent.a11y

import com.runasakura.phoneagent.agent.AppResolver
import com.runasakura.phoneagent.flow.Device
import com.runasakura.phoneagent.model.UiSnapshot
import com.runasakura.phoneagent.screen.ScreenItem

/**
 * Everything the agent loop does to the phone besides the [Device] observation/gesture layer. The real
 * implementation is [ServicePhone] (AccessibilityService); the offline simulator implements it with a scripted
 * state machine so the real AgentLoop can be regression-tested end to end on the JVM.
 */
interface Phone {
    /** False once this handle no longer backs the live accessibility service (restart / disconnect). */
    fun isCurrent(): Boolean
    fun device(log: (String) -> Unit): Device
    fun resetTaskState()
    var lastItems: List<ScreenItem>
    fun screenItems(): List<ScreenItem>
    fun screenSize(): Pair<Int, Int>
    fun foregroundPackage(): String
    fun dumpUiTree(): UiSnapshot
    fun screenshotJpegBase64(maxSide: Int = 896, marks: List<ScreenItem>? = null, screenW: Int = 0, screenH: Int = 0): String?
    fun resolveApp(name: String): AppResolver.AppMatch?
    fun launchApp(pkg: String): Boolean
    fun launchByLabel(label: String): Boolean
    fun launchWeChat(): Boolean
    fun launchSystemSettings(): Boolean
    fun launchSettingsAction(action: String): Boolean
    fun isSettingsForeground(pkg: String): Boolean
    fun setAlarm(hour: Int, minute: Int, label: String): Boolean
    fun tap(x: Int, y: Int): Boolean
    fun longPress(x: Int, y: Int): Boolean
    fun doubleTap(x: Int, y: Int): Boolean
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean
    /** Tap the first node whose text matches (node-level click, not a gesture). */
    fun tapText(text: String): Boolean
    fun typeText(text: String, nodeId: String?): Boolean
    fun clearText(nodeId: String?): Boolean
    fun back(): Boolean
    fun home(): Boolean
    fun recents(): Boolean
    fun global(id: Int): Boolean
    fun openUri(uri: String, pkg: String?): Boolean
    fun fireIntent(action: String, data: String?): Boolean
    fun setClipboard(text: String): Boolean
    fun readClipboard(): String?
    fun imeEnter(): Boolean
    fun volume(name: String): Boolean
    /** Is [pkg] the home screen / launcher (a crash or exit lands there; it is never part of a task)? */
    fun isHome(pkg: String): Boolean = false
    /** Optional privileged shell (Shizuku) — null means accessibility + OCR only. Re-checked on every use. */
    fun shell(): com.runasakura.phoneagent.shell.PrivilegedShell? = null
    /** Launchable apps (label to package) whose label or package contains [query] (all when blank). */
    fun listApps(query: String): List<Pair<String, String>> = emptyList()
}

/** The phone through the live AccessibilityService. */
class ServicePhone(private val s: AgentAccessibilityService) : Phone {
    override fun isCurrent() = AgentAccessibilityService.require() === s
    override fun device(log: (String) -> Unit): Device = A11yDevice(s, log)
    override fun resetTaskState() = s.resetTaskState()
    override var lastItems: List<ScreenItem>
        get() = s.lastItems
        set(v) { s.lastItems = v }
    override fun screenItems() = s.screenItems()
    override fun screenSize() = s.screenSize()
    override fun foregroundPackage() = s.foregroundPackage()
    override fun dumpUiTree() = s.dumpUiTree()
    override fun screenshotJpegBase64(maxSide: Int, marks: List<ScreenItem>?, screenW: Int, screenH: Int) =
        s.screenshotJpegBase64(maxSide, marks, screenW, screenH)
    override fun resolveApp(name: String) = AppResolver.resolve(s, name)
    override fun launchApp(pkg: String) = s.launchApp(pkg)
    override fun launchByLabel(label: String) = s.launchByLabel(label)
    override fun launchWeChat() = s.launchWeChat()
    override fun launchSystemSettings() = s.launchSystemSettings()
    override fun launchSettingsAction(action: String) = s.launchSettingsAction(action)
    override fun isSettingsForeground(pkg: String) = s.isSettingsForeground(pkg)
    override fun setAlarm(hour: Int, minute: Int, label: String) = s.setAlarm(hour, minute, label)
    override fun shell() = com.runasakura.phoneagent.shell.ShizukuBackend.shellOrNull(s)
    override fun listApps(query: String): List<Pair<String, String>> = try {
        val pm = s.packageManager
        pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinctBy { it.second }
            .let { all -> AppListFilter.filter(all, query) }
    } catch (_: Exception) { emptyList() }
    // Gestures: the accessibility gesture first (it steps the stop pill aside); when the system rejects it
    // (MagicOS sometimes cancels dispatchGesture), inject the same gesture through Shizuku `input`.
    override fun tap(x: Int, y: Int) = s.tap(x, y) || (shell()?.let { com.runasakura.phoneagent.shell.ShellOps.tap(it, x, y) } ?: false)
    override fun longPress(x: Int, y: Int) = s.longPress(x, y) || (shell()?.let { com.runasakura.phoneagent.shell.ShellOps.longPress(it, x, y) } ?: false)
    override fun doubleTap(x: Int, y: Int) = s.doubleTap(x, y)
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = s.swipe(x1, y1, x2, y2, durationMs) ||
        (shell()?.let { com.runasakura.phoneagent.shell.ShellOps.swipe(it, x1, y1, x2, y2, durationMs) } ?: false)
    override fun tapText(text: String) = s.findByText(text)?.let { s.tapNode(it) } ?: false
    override fun typeText(text: String, nodeId: String?) = s.typeText(text, nodeId?.let { s.findNodeById(it) })
    override fun clearText(nodeId: String?) = s.clearText(nodeId?.let { s.findNodeById(it) })
    override fun isHome(pkg: String): Boolean = pkg.isNotBlank() && (runCatching {
        s.packageManager.resolveActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName == pkg
    }.getOrDefault(false) || pkg.contains("launcher", ignoreCase = true))
    override fun back() = s.back() || (shell()?.let { com.runasakura.phoneagent.shell.ShellOps.key(it, "KEYCODE_BACK") } ?: false)
    override fun home() = s.home() || (shell()?.let { com.runasakura.phoneagent.shell.ShellOps.key(it, "KEYCODE_HOME") } ?: false)
    override fun recents() = s.recents()
    override fun global(id: Int) = s.global(id)
    override fun openUri(uri: String, pkg: String?) = s.openUri(uri, pkg)
    override fun fireIntent(action: String, data: String?) = s.fireIntent(action, data)
    override fun setClipboard(text: String) = s.setClipboard(text)
    /** Android 10+ blocks background reads for the a11y service; the shell identity is exempt. */
    override fun readClipboard() = s.readClipboard() ?: shell()?.readClipboard()
    override fun imeEnter() = s.imeEnter()
    override fun volume(name: String) = s.volume(name)

    companion object {
        fun current(): Phone? = AgentAccessibilityService.require()?.let { ServicePhone(it) }
    }
}

/** Pure ranking for list_apps: exact label, label prefix, label contains, package contains. */
object AppListFilter {
    fun filter(all: List<Pair<String, String>>, query: String, max: Int = 15): List<Pair<String, String>> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all.sortedBy { it.first }.take(max)
        return all.mapNotNull { (l, p) ->
            val ll = l.lowercase()
            val rank = when { ll == q -> 0; ll.startsWith(q) -> 1; ll.contains(q) -> 2; p.lowercase().contains(q) -> 3; else -> null }
            rank?.let { Triple(it, l, p) }
        }.sortedWith(compareBy({ it.first }, { it.second.length })).take(max).map { it.second to it.third }
    }
}

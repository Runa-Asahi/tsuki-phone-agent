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
    override fun tap(x: Int, y: Int) = s.tap(x, y)
    override fun longPress(x: Int, y: Int) = s.longPress(x, y)
    override fun doubleTap(x: Int, y: Int) = s.doubleTap(x, y)
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = s.swipe(x1, y1, x2, y2, durationMs)
    override fun tapText(text: String) = s.findByText(text)?.let { s.tapNode(it) } ?: false
    override fun typeText(text: String, nodeId: String?) = s.typeText(text, nodeId?.let { s.findNodeById(it) })
    override fun clearText(nodeId: String?) = s.clearText(nodeId?.let { s.findNodeById(it) })
    override fun back() = s.back()
    override fun home() = s.home()
    override fun recents() = s.recents()
    override fun global(id: Int) = s.global(id)
    override fun openUri(uri: String, pkg: String?) = s.openUri(uri, pkg)
    override fun fireIntent(action: String, data: String?) = s.fireIntent(action, data)
    override fun setClipboard(text: String) = s.setClipboard(text)
    override fun readClipboard() = s.readClipboard()
    override fun imeEnter() = s.imeEnter()
    override fun volume(name: String) = s.volume(name)

    companion object {
        fun current(): Phone? = AgentAccessibilityService.require()?.let { ServicePhone(it) }
    }
}

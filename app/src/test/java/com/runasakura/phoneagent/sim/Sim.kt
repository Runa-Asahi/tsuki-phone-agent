package com.runasakura.phoneagent.sim

import com.runasakura.phoneagent.a11y.Phone
import com.runasakura.phoneagent.agent.AppResolver
import com.runasakura.phoneagent.flow.Device
import com.runasakura.phoneagent.flow.Screen
import com.runasakura.phoneagent.model.UiSnapshot
import com.runasakura.phoneagent.screen.ScreenItem

/**
 * Offline phone simulator: scripted screens as a state machine. Implements both [Phone] and [Device] so the REAL
 * AgentLoop (parser, validator, safety gate, transcript, recovery, CloudClient) runs unchanged on the JVM.
 * Generic scenarios only — the scripted "apps" are fictional and nothing here is used by production code.
 */
class SimEl(
    val label: String, val x: Int, val y: Int, val w: Int = 300, val h: Int = 100,
    val edit: Boolean = false, val onTap: (SimWorld.() -> Unit)? = null,
)

class SimScreen(val name: String, val els: SimWorld.() -> List<SimEl>)

class SimApp(val pkg: String, val label: String, val home: String, val screens: Map<String, SimScreen>) {
    /** Called when the app is (re)started from the launcher; may push a splash/popup. */
    var onLaunch: (SimWorld.() -> Unit)? = null
}

class SimWorld(val apps: List<SimApp>, val w: Int = 1080, val h: Int = 2400) {
    val launcher = "com.sim.launcher"
    var fg: String = launcher
    private val stacks = HashMap<String, ArrayDeque<String>>()
    val fields = HashMap<String, String>()          // field label → text
    var focused: String? = null
    var clipboard: String? = null
    val log = ArrayList<String>()                    // world events (assertions read this)
    val flags = HashMap<String, Any>()
    var reads = 0                                    // observations so far (timers tick on reads)
    var systemPanel: SimScreen? = null               // notification shade / quick settings over the app
    var source = "tree"                              // "ocr" = webview/game with no accessibility tree

    fun app(pkg: String) = apps.first { it.pkg == pkg }
    fun screenName(): String = systemPanel?.name ?: stacks[fg]?.lastOrNull() ?: "launcher"
    fun current(): SimScreen = systemPanel ?: if (fg == launcher) LAUNCHER else app(fg).screens.getValue(screenName())

    fun go(screen: String) { stacks.getOrPut(fg) { ArrayDeque() }.addLast(screen); focused = null; log += "go:$screen" }
    fun replace(screen: String) { stacks[fg]?.removeLastOrNull(); go(screen) }
    fun launch(pkg: String, fresh: Boolean = false) {
        val a = apps.firstOrNull { it.pkg == pkg } ?: return
        systemPanel = null
        val st = stacks.getOrPut(pkg) { ArrayDeque() }
        val cold = st.isEmpty() || fresh
        fg = pkg; log += "launch:$pkg"
        if (cold) { st.clear(); st.addLast(a.home); a.onLaunch?.invoke(this) }
    }
    fun crash() { stacks[fg]?.clear(); log += "crash:$fg"; fg = launcher }
    fun back() {
        if (focused != null && flags["imeOpen"] != false) { focused = null; log += "back:ime"; return }   // like Android: back hides the keyboard first
        if (systemPanel != null) { systemPanel = null; return }
        val st = stacks[fg] ?: return
        if (st.size > 1) { st.removeLast(); log += "back:${st.last()}" } else { fg = launcher; log += "back:launcher" }
        focused = null
    }

    fun items(): List<ScreenItem> = current().els(this).mapIndexed { i, e ->
        ScreenItem("e${i + 1}", if (e.edit) fields[e.label]?.takeIf { it.isNotBlank() } ?: e.label else e.label,
            e.x, e.y, tap = source == "tree" && (e.onTap != null || e.edit), edit = source == "tree" && e.edit, scroll = false, ref = e, bw = e.w, bh = e.h)
    }

    fun tapAt(x: Int, y: Int): Boolean {
        val e = current().els(this).firstOrNull { kotlin.math.abs(it.x - x) <= it.w / 2 && kotlin.math.abs(it.y - y) <= it.h / 2 } ?: return true
        log += "tap:${e.label}"
        if (e.edit) { focused = e.label; return true }
        e.onTap?.invoke(this); return true
    }

    companion object {
        val LAUNCHER = SimScreen("launcher") { listOf(SimEl("时钟", 150, 2200), SimEl("相机", 410, 2200), SimEl("图库", 670, 2200)) + apps.mapIndexed { i, a -> SimEl(a.label, 150 + (i % 4) * 260, 400 + (i / 4) * 300) { launch(a.pkg) } } }
    }
}

class SimPhone(val world: SimWorld) : Phone {
    var shellImpl: com.runasakura.phoneagent.shell.PrivilegedShell? = null
    override fun shell() = shellImpl?.takeIf { world.flags["shizukuDead"] != true }
    var installedOverride: List<AppResolver.AppMatch>? = null
    val settingsActionWorks = false
    override fun isCurrent() = true
    override fun device(log: (String) -> Unit): Device = dev
    override fun resetTaskState() {}
    override var lastItems: List<ScreenItem> = emptyList()
    override fun screenItems() = world.items()
    override fun screenSize() = world.w to world.h
    override fun foregroundPackage() = if (world.systemPanel != null) "com.android.systemui" else world.fg
    override fun dumpUiTree(): UiSnapshot = UiSnapshot(foregroundPackage(), screenWidth = world.w, screenHeight = world.h, tree = null,
        compressedText = world.items().joinToString("\n") { it.label }, nodeCount = world.items().size)
    override fun screenshotJpegBase64(maxSide: Int, marks: List<ScreenItem>?, screenW: Int, screenH: Int): String? = null
    override fun resolveApp(name: String) = world.apps.firstOrNull { it.label == name || it.label.contains(name) || name.contains(it.label) }
        ?.let { AppResolver.AppMatch(it.label, it.pkg, 90) }
    override fun launchApp(pkg: String): Boolean { world.launch(pkg); return world.fg == pkg }
    override fun launchByLabel(label: String): Boolean = resolveApp(label)?.let { launchApp(it.packageName) } ?: false
    override fun launchWeChat() = false
    override fun launchSystemSettings() = false
    override fun launchSettingsAction(action: String) = settingsActionWorks
    override fun isSettingsForeground(pkg: String) = false
    override fun setAlarm(hour: Int, minute: Int, label: String) = false
    override fun tap(x: Int, y: Int) = world.tapAt(x, y)
    override fun longPress(x: Int, y: Int) = world.tapAt(x, y)
    override fun doubleTap(x: Int, y: Int) = world.tapAt(x, y)
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        world.log += "swipe:${if (y2 < y1) "down" else "up"}"; world.flags["scroll"] = ((world.flags["scroll"] as? Int) ?: 0) + if (y2 < y1) 1 else -1; return true
    }
    override fun tapText(text: String): Boolean = world.items().firstOrNull { it.label == text }?.let { world.tapAt(it.cx, it.cy) } ?: false
    override fun typeText(text: String, nodeId: String?): Boolean {
        val target = nodeId?.let { id -> world.items().firstOrNull { it.id == id && it.edit }?.let { (it.ref as SimEl).label } } ?: world.focused
            ?: world.current().els(world).firstOrNull { it.edit }?.label ?: return false
        world.fields[target] = text; world.focused = target; world.log += "type:$target=$text"; return true
    }
    override fun clearText(nodeId: String?): Boolean { world.focused?.let { world.fields[it] = "" }; return true }
    override fun back(): Boolean { world.back(); return true }
    override fun home(): Boolean { world.systemPanel = null; world.fg = world.launcher; return true }
    override fun recents() = true
    override fun global(id: Int): Boolean = when (id) {
        4 -> { world.systemPanel = world.flags["shade"] as? SimScreen; world.systemPanel != null }
        5 -> { world.systemPanel = world.flags["qs"] as? SimScreen; world.systemPanel != null }
        1 -> back(); 2 -> home()
        else -> false
    }
    override fun openUri(uri: String, pkg: String?) = false
    override fun fireIntent(action: String, data: String?) = false
    override fun setClipboard(text: String): Boolean { world.clipboard = text; return true }
    override fun readClipboard(): String? = null   // like Android 10+ for a background a11y service
    override fun imeEnter(): Boolean { val f = world.focused ?: return false; world.log += "enter:$f"; (world.flags["enter:$f"] as? (SimWorld.() -> Unit))?.invoke(world); return true }
    override fun volume(name: String) = true
    override fun isHome(pkg: String) = pkg == world.launcher

    val dev = object : Device {
        override suspend fun screen(): Screen {
        world.reads++; (world.flags["onRead"] as? (SimWorld.() -> Unit))?.invoke(world)
        val items = if (world.flags["hiddenTree"] == true && world.fg != world.launcher) emptyList() else world.items(); this@SimPhone.lastItems = items
        return Screen(this@SimPhone.foregroundPackage(), world.w, world.h, items, if (items.isEmpty()) "none" else world.source, if (world.focused != null) world.h - 900 else null, world.focused != null)
        }
        override suspend fun tap(item: ScreenItem) = world.tapAt(item.cx, item.cy)
        override suspend fun tapXY(x: Int, y: Int) = world.tapAt(x, y)
        override suspend fun longPress(item: ScreenItem) = world.tapAt(item.cx, item.cy)
        override suspend fun type(text: String, hintX: Int?, hintY: Int?, attempt: Int): Boolean {
        if (hintX != null && hintY != null) world.tapAt(hintX, hintY)
        return this@SimPhone.typeText(text, null)
    }
        override suspend fun fieldText(): String? = world.focused?.let { world.fields[it] }
        override suspend fun clearField(): Boolean = this@SimPhone.clearText(null)
        override suspend fun back() { this@SimPhone.back() }
        override suspend fun home() { this@SimPhone.home() }
        override suspend fun afterAction(maxMs: Long) {}
        override suspend fun scroll(forward: Boolean) { this@SimPhone.swipe(540, if (forward) 1700 else 700, 540, if (forward) 700 else 1700, 300) }
        override suspend fun bringToFront(pkg: String, label: String, force: Boolean): Boolean { world.launch(pkg, fresh = force); return world.fg == pkg }
        override suspend fun sleep(ms: Long) {}
        override suspend fun invalidateObservation() {}

        override fun foregroundPackage() = this@SimPhone.foregroundPackage()
    }
}

/** Fake Shizuku shell over the simulated world: uiautomator dump, settings/svc, am/pm, keyevents. */
class SimShell(val world: SimWorld) : com.runasakura.phoneagent.shell.PrivilegedShell {
    val commands = ArrayList<String>()
    private fun ok(s: String = "") = com.runasakura.phoneagent.shell.ShellResult(0, s.toByteArray())
    private fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
    fun xml(): String = buildString {
        append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">")
        append("<node index=\"0\" text=\"\" class=\"android.widget.FrameLayout\" package=\"${world.fg}\" clickable=\"false\" bounds=\"[0,0][${world.w},${world.h}]\">")
        world.current().els(world).forEach { e ->
            val label = if (e.edit) world.fields[e.label]?.takeIf { it.isNotBlank() } ?: e.label else e.label
            append("<node index=\"1\" text=\"${esc(label)}\" class=\"${if (e.edit) "android.widget.EditText" else "android.widget.TextView"}\" package=\"${world.fg}\" " +
                "clickable=\"${e.onTap != null}\" bounds=\"[${e.x - e.w / 2},${e.y - e.h / 2}][${e.x + e.w / 2},${e.y + e.h / 2}]\" />")
        }
        append("</node></hierarchy>")
    }
    override fun exec(argv: List<String>, timeoutMs: Long): com.runasakura.phoneagent.shell.ShellResult {
        val c = argv.joinToString(" "); commands += c; world.log += "sh:$c"
        return when {
            argv[0] == "uiautomator" -> ok("UI hierchary dumped to: ${argv.last()}")
            argv[0] == "cat" -> ok(xml())
            c.startsWith("svc wifi ") -> { world.flags["wifi"] = argv[2] == "enable"; ok() }
            c.startsWith("cmd wifi set-wifi-enabled") -> { world.flags["wifi"] = argv[3] == "enabled"; ok() }
            c == "settings get global wifi_on" -> ok(if (world.flags["wifi"] == false) "0" else "1")
            argv[0] == "am" && argv[1] == "force-stop" -> { if (world.fg == argv[2]) world.crash(); world.flags["stopped:${argv[2]}"] = true; ok() }
            argv[0] == "pm" && argv[1] == "uninstall" -> { world.flags["uninstalled"] = argv.last(); ok("Success") }
            argv[0] == "pm" && argv[1] == "list" -> ok(world.apps.joinToString("\n") { "package:${it.pkg}" })
            argv[0] == "dumpsys" -> ok((world.flags["dumpsys"] as? String).orEmpty())
            argv[0] == "input" -> { world.log += "key:${argv.last()}"; ok() }
            else -> com.runasakura.phoneagent.shell.ShellResult(1, ByteArray(0), "unknown")
        }
    }
    override fun readClipboard(): String? = world.clipboard
}

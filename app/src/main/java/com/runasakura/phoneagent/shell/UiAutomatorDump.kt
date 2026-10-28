package com.runasakura.phoneagent.shell

import com.runasakura.phoneagent.flow.Device
import com.runasakura.phoneagent.flow.Screen
import com.runasakura.phoneagent.screen.RawNode
import com.runasakura.phoneagent.screen.ScreenItems

/**
 * `uiautomator dump` through the privileged shell → the same [RawNode] model the accessibility tree uses, so the
 * existing generic filtering ([ScreenItems.leaves]) applies unchanged. Pure parsing (regex over the flat XML), JVM-testable.
 */
object UiAutomatorDump {
    const val PATH = "/data/local/tmp/honor_agent_uia.xml"
    private val TAG = Regex("<node\\b([^>]*?)(/?)>|</node>")
    private val ATTR = Regex("([a-z-]+)=\"([^\"]*)\"")
    private val BOUNDS = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")

    private class B(val a: Map<String, String>) { val kids = ArrayList<RawNode>() }

    private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&#10;", " ").replace("&#xA;", " ").replace("&amp;", "&")

    private fun build(b: B): RawNode {
        val m = BOUNDS.find(b.a["bounds"].orEmpty())
        val (l, t, r, bt) = m?.destructured?.toList()?.map { it.toInt() } ?: listOf(0, 0, 0, 0)
        return RawNode(
            text = unesc(b.a["text"].orEmpty()), desc = unesc(b.a["content-desc"].orEmpty()),
            viewId = b.a["resource-id"].orEmpty().substringAfterLast('/'),
            l = l, t = t, r = r, b = bt,
            clickable = b.a["clickable"] == "true" || b.a["long-clickable"] == "true",
            editable = b.a["class"].orEmpty().contains("EditText"),
            scrollable = b.a["scrollable"] == "true",
            children = b.kids, ref = null,
        )
    }

    /** Parse the dump; returns the roots plus the package of the first node ("" when unknown). */
    fun parse(xml: String): Pair<List<RawNode>, String> {
        val roots = ArrayList<RawNode>(); val stack = ArrayDeque<B>(); var pkg = ""
        for (m in TAG.findAll(xml)) {
            if (m.value.startsWith("</")) {
                val done = stack.removeLastOrNull() ?: continue
                val node = build(done)
                stack.lastOrNull()?.kids?.add(node) ?: roots.add(node)
                continue
            }
            val attrs = ATTR.findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
            if (pkg.isEmpty()) pkg = attrs["package"].orEmpty()
            val b = B(attrs)
            if (m.groupValues[2] == "/") { val node = build(b); stack.lastOrNull()?.kids?.add(node) ?: roots.add(node) }
            else stack.addLast(b)
        }
        while (stack.isNotEmpty()) { val node = build(stack.removeLast()); stack.lastOrNull()?.kids?.add(node) ?: roots.add(node) }
        return roots to pkg
    }

    /** Run the dump through [shell]; null when it failed (Shizuku gone, "could not get idle state", …). */
    fun dump(shell: PrivilegedShell): Pair<List<RawNode>, String>? {
        val r = shell.exec(listOf("uiautomator", "dump", "--compressed", PATH), 8000)
        if (!r.ok && !r.text.contains("dumped")) return null
        val xml = shell.exec(listOf("cat", PATH), 4000).takeIf { it.ok }?.text ?: return null
        if (!xml.contains("<node")) return null
        return parse(xml)
    }
}

/**
 * Observation merge: when the accessibility tree is empty or nearly empty (WeChat ≥8.0.52 hides its window root,
 * Flutter/web content) and the privileged shell is active, read the UI through `uiautomator dump` before falling
 * back to OCR. A rich a11y tree is used unchanged (it is faster and its nodes support node-level actions).
 */
class ShellAugmentedDevice(
    private val d: Device, private val shell: () -> PrivilegedShell?, private val log: (String) -> Unit,
    /** True while our accessibility service is connected/enabled: then NEVER dump (it unbinds the service). */
    private val a11yActive: () -> Boolean = { ShellLog.a11yActive() },
) : Device by d {
    private var skipLogged = false
    private var failures = 0

    override suspend fun screen(): Screen = augment(d.screen())
    override suspend fun refineScreen(): Screen = augment(d.refineScreen())

    private fun augment(s: Screen): Screen {
        if (s.source == "tree" && s.textCount >= MIN_TREE_TEXT) return s
        if (failures >= 3) return s
        if (a11yActive()) {
            if (!skipLogged) { skipLogged = true; log("界面树稀疏：无障碍服务在线，不使用 uiautomator（会断开无障碍服务），改用 OCR + 色块候选") }
            return s
        }
        val sh = shell() ?: return s
        val parsed = UiAutomatorDump.dump(sh)
        if (parsed == null) { failures++; return s }
        val (roots, pkg) = parsed
        val items = ScreenItems.leaves(roots, s.w, s.h)
        if (ScreenItems.textCount(items) <= s.textCount) return s
        failures = 0
        log("界面树为空/稀疏，改用 Shizuku uiautomator 读取（${items.size} 个元素）")
        return s.copy(pkg = s.pkg.ifBlank { pkg }, items = items, source = "uia")
    }

    companion object { const val MIN_TREE_TEXT = 3 }
}

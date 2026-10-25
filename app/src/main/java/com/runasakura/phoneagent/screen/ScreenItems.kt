package com.runasakura.phoneagent.screen

/** Platform-free node used for filtering (unit-testable on JVM). */
data class RawNode(
    val text: String = "",
    val desc: String = "",
    val viewId: String = "",
    val l: Int = 0, val t: Int = 0, val r: Int = 0, val b: Int = 0,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val children: List<RawNode> = emptyList(),
    /** Opaque handle back to the platform node (AccessibilityNodeInfo). */
    val ref: Any? = null
)

data class ScreenItem(
    val id: String,
    val label: String,
    val cx: Int,
    val cy: Int,
    val tap: Boolean,
    val edit: Boolean,
    val scroll: Boolean,
    val ref: Any?
) {
    fun format(): String = buildString {
        append('[').append(id).append("] ").append(label.ifBlank { "(输入框)" })
        append(" (").append(cx).append(',').append(cy).append(')')
        val f = listOfNotNull(if (tap) "tap" else null, if (edit) "edit" else null, if (scroll) "scroll" else null)
        if (f.isNotEmpty()) append(' ').append(f.joinToString("|"))
    }
}

object ScreenItems {
    private val OBFUSCATED = Regex("^[a-z0-9_]{1,4}$", RegexOption.IGNORE_CASE)

    fun cleanLabel(s: String): String {
        val t = s.replace(Regex("\\s+"), " ").replace(Regex("(, )+"), ", ").trim(' ', ',')
        if (t.isBlank() || OBFUSCATED.matches(t)) return ""
        return t
    }

    /** Visible on screen: non-empty and mostly inside [0,w]x[0,h] (drops swipe-menu items at x>=w). */
    fun visible(n: RawNode, w: Int, h: Int): Boolean {
        if (n.r <= n.l || n.b <= n.t) return false
        val il = maxOf(n.l, 0); val it = maxOf(n.t, 0)
        val ir = minOf(n.r, w); val ib = minOf(n.b, h)
        if (ir <= il || ib <= it) return false
        val area = (n.r - n.l).toLong() * (n.b - n.t)
        val inter = (ir - il).toLong() * (ib - it)
        return inter * 2 >= area // at least half on screen
    }

    private fun ownLabel(n: RawNode) = cleanLabel(n.text).ifBlank { cleanLabel(n.desc) }

    private fun descendantText(n: RawNode, w: Int, h: Int, out: MutableList<String>, depth: Int = 0) {
        if (depth > 8 || out.size >= 4) return
        for (c in n.children) {
            if (!visible(c, w, h)) continue
            val l = ownLabel(c)
            if (l.isNotBlank() && l !in out) out += l
            descendantText(c, w, h, out, depth + 1)
        }
    }

    fun build(roots: List<RawNode>, w: Int, h: Int, maxItems: Int = 40, maxChars: Int = 1200): List<ScreenItem> {
        val raw = mutableListOf<Triple<String, RawNode, Boolean>>() // label, node, isContainerMerge
        fun walk(n: RawNode, insideActionable: Boolean, depth: Int) {
            if (depth > 40) return
            val vis = visible(n, w, h)
            val own = ownLabel(n)
            val actionable = n.clickable || n.editable
            var consumed = insideActionable
            if (vis) {
                if (actionable) {
                    val label = if (own.isNotBlank()) own else {
                        val parts = mutableListOf<String>(); descendantText(n, w, h, parts)
                        parts.joinToString(" ")
                    }
                    if (label.isNotBlank() || n.editable) {
                        raw += Triple(label.take(60), n, true); consumed = true
                    }
                } else if (own.isNotBlank() && !insideActionable) {
                    raw += Triple(own.take(60), n, false)
                }
            }
            n.children.forEach { walk(it, consumed, depth + 1) }
        }
        roots.forEach { walk(it, false, 0) }

        val seen = HashSet<String>()
        val sorted = raw.sortedWith(compareBy({ (it.second.t + it.second.b) / 2 }, { it.second.l }))
        val items = mutableListOf<ScreenItem>()
        var chars = 0
        for ((label, n, _) in sorted) {
            val cx = (maxOf(n.l, 0) + minOf(n.r, w)) / 2
            val cy = (maxOf(n.t, 0) + minOf(n.b, h)) / 2
            val key = "$label@${cx / 20},${cy / 20}"
            if (!seen.add(key)) continue
            val item = ScreenItem("e${items.size + 1}", label, cx, cy, n.clickable, n.editable, n.scrollable, n.ref)
            val len = item.format().length + 1
            if (items.size >= maxItems || chars + len > maxChars) break
            items += item; chars += len
        }
        return items
    }

    fun textCount(items: List<ScreenItem>) = items.count { it.label.isNotBlank() }
}

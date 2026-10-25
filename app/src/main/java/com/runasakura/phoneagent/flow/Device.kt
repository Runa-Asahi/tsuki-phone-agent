package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.screen.ScreenItem

/** One observation of the screen. [source] = "tree" | "ocr" | "none". */
data class Screen(
    val pkg: String,
    val w: Int,
    val h: Int,
    val items: List<ScreenItem>,
    val source: String,
    /** Top y of the soft keyboard window, or null when no IME is shown. */
    val imeTop: Int? = null
) {
    val textCount: Int get() = items.count { it.label.isNotBlank() }
    fun isEmpty() = textCount == 0
    fun summary(max: Int = 12) = items.take(max).joinToString(" | ") { it.label.take(12) }
}

/** Abstraction over the phone so flows are unit-testable on the JVM. */
interface Device {
    suspend fun screen(): Screen
    suspend fun tap(item: ScreenItem): Boolean
    suspend fun tapXY(x: Int, y: Int): Boolean
    /** Type into the focused/given field (tap hint first if given). Replaces existing text. */
    suspend fun type(text: String, hintX: Int? = null, hintY: Int? = null): Boolean
    suspend fun back()
    /** Scroll content: forward = reveal content further down. */
    suspend fun scroll(forward: Boolean = true)
    suspend fun bringToFront(pkg: String, label: String): Boolean
    suspend fun sleep(ms: Long)
}

object Text {
    private val SPACES = Regex("[\\s\\u200B-\\u200D\\uFEFF]+")
    fun norm(s: String): String = SPACES.replace(s, "")
        .replace('（', '(').replace('）', ')')
        .replace('：', ':').trim()

    fun eq(a: String, b: String) = norm(a) == norm(b)
}

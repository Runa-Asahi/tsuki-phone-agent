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
    suspend fun longPress(item: ScreenItem): Boolean
    /** False once the accessibility service backing this device has disconnected/restarted. */
    fun alive(): Boolean = true
    /**
     * Type into the focused/given field (tap hint first if needed). Replaces existing text.
     * [attempt] selects the strategy order so callers can retry with a different channel:
     * 0 = SET_TEXT → IME → paste, 1 = clipboard paste, 2 = IME commitText.
     */
    suspend fun type(text: String, hintX: Int? = null, hintY: Int? = null, attempt: Int = 0): Boolean
    suspend fun back()
    suspend fun home()
    /** Wait for the UI to react to the last action: first change event, then ~300ms quiet (≤ maxMs). */
    suspend fun afterAction(maxMs: Long = 1500)
    /** Scroll content: forward = reveal content further down. */
    suspend fun scroll(forward: Boolean = true)
    suspend fun bringToFront(pkg: String, label: String, force: Boolean = false): Boolean
    suspend fun sleep(ms: Long)
    /** Drop any cached observation (call after overlays that didn't emit a11y events). */
    suspend fun invalidateObservation()
}

object Text {
    private val SPACES = Regex("[\\s\\u200B-\\u200D\\uFEFF\\u3000]+")

    /** Full-width ASCII (！-～) → half-width; ideographic space handled by SPACES. */
    fun halfWidth(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in '\uFF01'..'\uFF5E') (c.code - 0xFEE0).toChar() else c)
        return sb.toString()
    }

    /**
     * Canonical form used for ALL label comparisons: strip whitespace/zero-width, full→half width.
     * Exact matching stays exact after this normalization (no fuzzy/edit-distance).
     */
    fun norm(s: String): String = halfWidth(SPACES.replace(s, "")).trim()

    fun eq(a: String, b: String) = norm(a) == norm(b)
}

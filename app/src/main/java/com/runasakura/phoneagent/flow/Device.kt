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
    /** Same screen with key regions re-OCR'd at higher resolution (no-op for tree / fakes). */
    suspend fun refineScreen(): Screen = screen()
    suspend fun tap(item: ScreenItem): Boolean
    suspend fun tapXY(x: Int, y: Int): Boolean
    suspend fun longPress(item: ScreenItem): Boolean
    /** False once the accessibility service backing this device has disconnected/restarted. */
    fun alive(): Boolean = true
    /** Actual content of the focused input (IME surrounding text / node text); null = unknown. */
    suspend fun fieldText(): String? = null
    /** Clear the focused input (IME select-all+delete, else SET_TEXT ""). */
    suspend fun clearField(): Boolean = false
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
    fun norm(s: String): String = fold(TradSimp.toSimp(halfWidth(SPACES.replace(s, "")).trim()))

    /**
     * OCR confusables + Latin case-fold, applied identically to both sides of every comparison:
     * I/l/| → 1, O/o → 0, く/〈/‹ → <, then lowercase. (「文件传輸助手」 == 「文件传输助手」 via TradSimp.)
     */
    fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(when (c) {
            'I', 'l', '|', '丨' -> '1'
            'O', 'o' -> '0'
            'く', '〈', '‹', '＜' -> '<'
            else -> c.lowercaseChar()
        })
        return sb.toString()
    }

    /** Name key: [norm] plus dropping punctuation/symbols (keeps letters, digits, CJK, parentheses). */
    fun nameKey(s: String): String = norm(s).filter { it.isLetterOrDigit() || it == '(' || it == ')' }

    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    /** Allowed edits for a fuzzy name match: none below 4 chars, 1 for 4–7, 2 for ≥8. */
    fun fuzzyBudget(len: Int) = when { len < 4 -> 0; len < 8 -> 1; else -> 2 }

    /**
     * Index of the single label within the fuzzy budget of [target] (by [nameKey]); null when
     * zero or several labels qualify — ambiguity is never resolved by guessing.
     */
    fun uniqueFuzzy(labels: List<String>, target: String): Int? {
        val t = nameKey(target)
        val budget = fuzzyBudget(t.length)
        if (budget == 0) return null
        // Substitutions only (same length): OCR misreads a glyph, it rarely adds one — and
        // insertions would let 「dramadan2」 pass for 「dramadan」.
        val hits = labels.indices.filter { i ->
            val k = nameKey(labels[i])
            k.length == t.length && k.indices.count { k[it] != t[it] } <= budget
        }
        return hits.singleOrNull()
    }

    fun eq(a: String, b: String) = norm(a) == norm(b)
}

package com.runasakura.phoneagent.ocr

import com.runasakura.phoneagent.screen.ScreenItem

/** Raw OCR line in screen coordinates. */
data class OcrLine(val text: String, val l: Int, val t: Int, val r: Int, val b: Int) {
    val h get() = (b - t).coerceAtLeast(1)
    val cy get() = (t + b) / 2
}

/** Pure OCR post-processing (unit-tested): band filter, fragment merge, cleanup. */
object OcrPost {
    /**
     * @param top drop lines whose center is above (status bar), @param bottom drop below (nav/gesture bar).
     * Fragments on the same row with a gap < ~0.8 line height are merged ("王小" + "明2" → "王小明2"),
     * which keeps exact matching safe (a split name can never look like a shorter contact).
     */
    /** Text drawn by the agent's own floating overlay (progress label, 暂停/急停 buttons), incl. OCR misreads. */
    val OWN_OVERLAY = Regex("^\\|?第\\s*\\d+\\s*步.*|^.{0,2}[暂背][停偉假信].{0,1}[急急][停信]$|^(暂停|继续|急停|已暂停|理解任务…|等待你确认)$")

    fun process(lines: List<OcrLine>, top: Int, bottom: Int, ignore: Set<String> = emptySet(),
                exclude: IntArray? = null): List<ScreenItem> {
        val kept = lines.filter { ln -> ln.text.isNotBlank() && ln.cy in top until bottom && ln.text.trim() !in ignore &&
            !OWN_OVERLAY.matches(ln.text.trim()) &&
            (exclude == null || (ln.l + ln.r) / 2 !in exclude[0]..exclude[2] || ln.cy !in exclude[1]..exclude[3]) }
            .sortedWith(compareBy({ it.cy / 30 }, { it.l }))
        val merged = mutableListOf<OcrLine>()
        for (ln in kept) {
            val i = merged.indexOfLast { sameRow(it, ln) && ln.l - it.r in -it.h..(maxOf(minOf(it.h, ln.h) * 9 / 10, 24)) }
            if (i >= 0) {
                val m = merged[i]
                merged[i] = OcrLine(m.text + ln.text, minOf(m.l, ln.l), minOf(m.t, ln.t), maxOf(m.r, ln.r), maxOf(m.b, ln.b))
            } else merged += ln
        }
        return merged.sortedWith(compareBy({ it.cy / 30 }, { it.l })).mapIndexed { idx, m ->
            ScreenItem("e${idx + 1}", m.text.trim(), (m.l + m.r) / 2, m.cy, true, false, false, null)
        }
    }

    private fun sameRow(a: OcrLine, b: OcrLine): Boolean {
        val overlap = minOf(a.b, b.b) - maxOf(a.t, b.t)
        return overlap >= minOf(a.h, b.h) * 6 / 10
    }
}

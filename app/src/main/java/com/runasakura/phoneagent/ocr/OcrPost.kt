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
    fun process(lines: List<OcrLine>, top: Int, bottom: Int, ignore: Set<String> = emptySet()): List<ScreenItem> {
        val kept = lines.filter { it.text.isNotBlank() && it.cy in top until bottom && it.text.trim() !in ignore }
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

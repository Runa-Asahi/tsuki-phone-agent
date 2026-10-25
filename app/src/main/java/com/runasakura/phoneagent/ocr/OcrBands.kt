package com.runasakura.phoneagent.ocr

/**
 * Detail OCR bands: the downscaled full-screen pass (≈351x720 before) loses small Chinese glyphs
 * (「音视频通话」→「初麵通话」). Title bar and bottom area (input bar, 「+」 panel, sheets, keyboard keys)
 * are re-OCR'd at near-native resolution and replace the global lines in those bands.
 */
object OcrBands {
    /** Bands in bitmap pixel coords given the usable [top, bottom) area. */
    fun detailBands(top: Int, bottom: Int): List<Pair<Int, Int>> {
        val h = bottom - top
        if (h < 400) return emptyList()
        return listOf(top to (top + (h * 0.10).toInt()), (top + (h * 0.60).toInt()) to bottom)
    }

    /** Replace global lines whose centre lies in a band that produced detail lines. */
    fun merge(global: List<OcrLine>, detail: Map<Pair<Int, Int>, List<OcrLine>>): List<OcrLine> {
        val covered = detail.filterValues { it.isNotEmpty() }.keys
        val keep = global.filter { g -> covered.none { (a, b) -> g.cy in a until b } }
        return (keep + detail.values.flatten()).sortedWith(compareBy({ it.t }, { it.l }))
    }
}

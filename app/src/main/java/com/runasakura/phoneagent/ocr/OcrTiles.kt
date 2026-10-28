package com.runasakura.phoneagent.ocr

/** Native-resolution OCR tiling for wide frames (landscape games): overlapping columns, then de-duplicated merge. Pure. */
object OcrTiles {
    /** Column ranges of at most [maxW] px with [overlap] px overlap covering [0,width). */
    fun columns(width: Int, maxW: Int, overlap: Int): List<Pair<Int, Int>> {
        if (width <= maxW) return listOf(0 to width)
        val n = Math.ceil((width - overlap).toDouble() / (maxW - overlap)).toInt()
        val step = (width - overlap) / n
        return (0 until n).map { i -> val a = i * step; a to if (i == n - 1) width else minOf(width, a + step + overlap) }
    }

    private fun iou(a: OcrLine, b: OcrLine): Float {
        val l = maxOf(a.l, b.l); val r = minOf(a.r, b.r); val t = maxOf(a.t, b.t); val bo = minOf(a.b, b.b)
        if (r <= l || bo <= t) return 0f
        val inter = (r - l).toFloat() * (bo - t)
        val small = minOf((a.r - a.l).toFloat() * (a.b - a.t), (b.r - b.l).toFloat() * (b.b - b.t)).coerceAtLeast(1f)
        return inter / small
    }

    /** Tile lines win over overlapping global lines (higher resolution); global lines no tile saw are kept. */
    fun merge(global: List<OcrLine>, tiles: List<OcrLine>): List<OcrLine> {
        val t = ArrayList<OcrLine>()
        for (x in tiles.sortedByDescending { it.text.length }) if (t.none { iou(it, x) > 0.5f }) t += x
        return t + global.filter { g -> t.none { iou(it, g) > 0.3f } }
    }
}

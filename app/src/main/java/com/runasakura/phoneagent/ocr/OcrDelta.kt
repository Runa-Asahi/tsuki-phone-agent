package com.runasakura.phoneagent.ocr

/**
 * Region-incremental OCR. Most agent steps change only part of the screen (a toggle, a new chat bubble, a dialog
 * row, a keyboard), yet ML Kit cost grows with the pixels it reads. We grid the frame into ~40 px rows × [GW]
 * columns of average luminance, compare with the previous frame, and re-OCR only the horizontal bands whose cells
 * changed; text lines from untouched bands are reused. Pure (unit-tested); the device glue is in A11yDevice.
 *
 * Correctness guard: a band is "changed" when ANY cell moves more than [TOL] luma levels (a checkmark or a toggle knob
 * moves a 50×40 px cell by tens of levels), bands are padded and grown to swallow every old line they touch (no text
 * is cut at a band edge), and a full pass is forced when too much changed or after [MAX_CHAIN] incremental frames.
 */
object OcrDelta {
    const val GW = 24
    const val CELL_H = 40
    const val TOL = 4
    const val PAD = 24
    const val MAX_FRACTION = 0.45
    const val MAX_BANDS = 3
    const val MAX_CHAIN = 4

    /** Average luminance per cell of a w×h ARGB frame, sampled every [step] px. Row count = ceil(h / CELL_H). */
    fun grid(px: IntArray, w: Int, h: Int, cellH: Int = CELL_H, gw: Int = GW, step: Int = 3): IntArray =
        grid(w, h, cellH, gw, step) { y, row -> System.arraycopy(px, y * w, row, 0, w) }

    /** Same, reading one sampled row at a time (a full-frame IntArray would be 13 MB per step on a 1264×2800 screen). */
    fun grid(w: Int, h: Int, cellH: Int = CELL_H, gw: Int = GW, step: Int = 3, row: (Int, IntArray) -> Unit): IntArray {
        val rows = (h + cellH - 1) / cellH
        val sum = LongArray(rows * gw); val n = IntArray(rows * gw); val buf = IntArray(w)
        var y = 0
        while (y < h) {
            row(y, buf)
            val r = y / cellH; var x = 0
            while (x < w) {
                val c = buf[x]; val i = r * gw + x * gw / w
                sum[i] += (((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255)).toLong(); n[i]++
                x += step
            }
            y += step
        }
        return IntArray(rows * gw) { if (n[it] == 0) 0 else (sum[it] / (n[it] * 10L)).toInt() }
    }

    fun changedRows(prev: IntArray, cur: IntArray, gw: Int = GW, tol: Int = TOL): BooleanArray? {
        if (prev.size != cur.size || cur.size % gw != 0) return null
        val rows = cur.size / gw
        return BooleanArray(rows) { r -> (0 until gw).any { c -> kotlin.math.abs(prev[r * gw + c] - cur[r * gw + c]) > tol } }
    }

    /** Changed rows → merged pixel bands [a, b) padded by [pad], clamped to [h]. */
    fun bands(changed: BooleanArray, h: Int, cellH: Int = CELL_H, pad: Int = PAD): List<IntArray> {
        val out = ArrayList<IntArray>()
        var r = 0
        while (r < changed.size) {
            if (!changed[r]) { r++; continue }
            var e = r; while (e + 1 < changed.size && changed[e + 1]) e++
            val a = (r * cellH - pad).coerceAtLeast(0); val b = ((e + 1) * cellH + pad).coerceAtMost(h)
            if (out.isNotEmpty() && a <= out.last()[1]) out.last()[1] = maxOf(out.last()[1], b) else out += intArrayOf(a, b)
            r = e + 1
        }
        return out
    }

    /** Grow bands so no previous line straddles a band edge (it would be half re-read and half reused). */
    fun swallow(bands: List<IntArray>, prev: List<OcrLine>, h: Int): List<IntArray> {
        var cur = bands.map { it.copyOf() }
        while (true) {
            var grew = false
            for (bd in cur) for (l in prev) if (l.b > bd[0] && l.t < bd[1] && (l.t < bd[0] || l.b > bd[1])) {
                bd[0] = minOf(bd[0], (l.t - 4).coerceAtLeast(0)); bd[1] = maxOf(bd[1], (l.b + 4).coerceAtMost(h)); grew = true
            }
            val sorted = cur.sortedBy { it[0] }; val merged = ArrayList<IntArray>()
            for (bd in sorted) if (merged.isNotEmpty() && bd[0] <= merged.last()[1]) merged.last()[1] = maxOf(merged.last()[1], bd[1]) else merged += bd
            if (merged.size != cur.size) grew = true
            cur = merged
            if (!grew) return cur
        }
    }

    sealed class Plan {
        object Same : Plan()
        object Full : Plan()
        class Partial(val bands: List<IntArray>, val fraction: Double) : Plan()
    }

    /** What to OCR for this frame given the previous grid/lines (null prev → full). */
    fun plan(prevGrid: IntArray?, curGrid: IntArray, prevLines: List<OcrLine>?, h: Int, chain: Int): Plan {
        if (prevGrid == null || prevLines == null || chain >= MAX_CHAIN) return Plan.Full
        val ch = changedRows(prevGrid, curGrid) ?: return Plan.Full
        if (ch.none { it }) return Plan.Same
        val b = swallow(bands(ch, h), prevLines, h)
        val px = b.sumOf { it[1] - it[0] }
        val f = px.toDouble() / h.coerceAtLeast(1)
        return if (b.size > MAX_BANDS || f > MAX_FRACTION) Plan.Full else Plan.Partial(b, f)
    }

    /** Old lines outside every band + freshly read lines (already in the same coordinates). */
    fun merge(prev: List<OcrLine>, fresh: List<OcrLine>, bands: List<IntArray>): List<OcrLine> =
        prev.filter { l -> bands.none { l.b > it[0] && l.t < it[1] } } + fresh
}

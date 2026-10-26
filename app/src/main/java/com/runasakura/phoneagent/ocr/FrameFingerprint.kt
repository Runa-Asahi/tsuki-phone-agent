package com.runasakura.phoneagent.ocr

/**
 * Coarse luminance fingerprint of a frame (e.g. 32x18 grid). Games and video fire accessibility events constantly,
 * so "no new event" never holds there; comparing pixels instead lets an unchanged frame reuse the previous OCR
 * (and skip the native-resolution tiles), which is the dominant per-step cost in landscape.
 */
object FrameFingerprint {
    fun of(px: IntArray, w: Int, h: Int, gw: Int = 32, gh: Int = 18): IntArray {
        val out = IntArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val x0 = gx * w / gw; val x1 = maxOf(x0 + 1, (gx + 1) * w / gw)
            val y0 = gy * h / gh; val y1 = maxOf(y0 + 1, (gy + 1) * h / gh)
            var sum = 0L; var n = 0
            var y = y0; while (y < y1 && y < h) { var x = x0; while (x < x1 && x < w) {
                val c = px[y * w + x]; sum += ((c shr 16 and 255) * 3 + (c shr 8 and 255) * 6 + (c and 255)) / 10; n++; x += 2 }; y += 2 }
            out[gy * gw + gx] = if (n == 0) 0 else (sum / n).toInt()
        }
        return out
    }

    /** Same frame: mean cell difference small AND no single cell changed a lot (a new popup/button changes a few). */
    fun same(a: IntArray?, b: IntArray?, meanTol: Double = 2.5, cellTol: Int = 28): Boolean {
        if (a == null || b == null || a.size != b.size) return false
        var total = 0L
        for (i in a.indices) { val d = kotlin.math.abs(a[i] - b[i]); if (d > cellTol) return false; total += d }
        return total.toDouble() / a.size <= meanTol
    }
}

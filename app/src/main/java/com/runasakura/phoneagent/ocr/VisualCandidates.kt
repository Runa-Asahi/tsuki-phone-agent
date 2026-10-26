package com.runasakura.phoneagent.ocr

import com.runasakura.phoneagent.screen.ScreenItem

/**
 * Text-free visual substitute for a screenshot when the user's model can't see images: finds big saturated
 * "button-like" colour blocks and small high-contrast marks in the corners (close ✕ / back arrows) on a thumbnail
 * of the frame, and returns them as unlabeled tappable candidates with position, size and dominant colour.
 * Pure Kotlin over an ARGB IntArray (JVM-testable); generic — no app/game knowledge.
 */
object VisualCandidates {
    data class Blob(val l: Int, val t: Int, val r: Int, val b: Int, val color: String, val kind: String) {
        val cx get() = (l + r) / 2; val cy get() = (t + b) / 2
        val w get() = r - l; val h get() = b - t
    }

    private fun hsvPure(c: Int): FloatArray {
        val r = (c shr 16 and 255) / 255f; val g = (c shr 8 and 255) / 255f; val b = (c and 255) / 255f
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
        val h = when { d == 0f -> 0f; mx == r -> 60 * (((g - b) / d) % 6); mx == g -> 60 * ((b - r) / d + 2); else -> 60 * ((r - g) / d + 4) }
        return floatArrayOf(if (h < 0) h + 360 else h, if (mx == 0f) 0f else d / mx, mx)
    }

    fun colorName(hue: Float, sat: Float, v: Float): String = when {
        v < 0.2f -> "黑"
        sat < 0.18f -> if (v > 0.85f) "白" else "灰"
        hue < 15 || hue >= 345 -> "红"
        hue < 40 -> "橙"
        hue < 70 -> "黄"
        hue < 160 -> "绿"
        hue < 200 -> "青"
        hue < 255 -> "蓝"
        hue < 290 -> "紫"
        else -> "粉"
    }

    /**
     * [px] thumbnail pixels (w×h). Returns blobs in THUMBNAIL coords. Cells of [cell] px are classified as
     * "saturated & bright"; 4-connected cell groups with a reasonable size/fill become button candidates.
     */
    fun detect(px: IntArray, w: Int, h: Int, cell: Int = 6, max: Int = 8): List<Blob> {
        val gw = w / cell; val gh = h / cell
        if (gw < 4 || gh < 4) return emptyList()
        val hue = FloatArray(gw * gh); val on = BooleanArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var rs = 0; var gs = 0; var bs = 0; var n = 0
            var y = gy * cell; while (y < (gy + 1) * cell) { var x = gx * cell; while (x < (gx + 1) * cell) {
                val c = px[y * w + x]; rs += c shr 16 and 255; gs += c shr 8 and 255; bs += c and 255; n++; x += 2 }; y += 2 }
            val avg = (0xFF shl 24) or ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n)
            val f = hsvPure(avg); hue[gy * gw + gx] = f[0]
            on[gy * gw + gx] = f[1] >= 0.45f && f[2] >= 0.55f
        }
        val seen = BooleanArray(gw * gh); val out = ArrayList<Blob>()
        val total = gw * gh
        for (start in 0 until total) {
            if (!on[start] || seen[start]) continue
            val q = ArrayDeque<Int>(); q.add(start); seen[start] = true
            var minX = gw; var minY = gh; var maxX = 0; var maxY = 0; var count = 0; var hx = 0.0; var hy = 0.0
            val h0 = hue[start]
            while (q.isNotEmpty()) {
                val i = q.removeFirst(); val x = i % gw; val y = i / gw; count++
                minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
                val a = Math.toRadians(hue[i].toDouble()); hx += Math.cos(a); hy += Math.sin(a)
                for ((dx, dy) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = x + dx; val ny = y + dy; if (nx !in 0 until gw || ny !in 0 until gh) continue
                    val j = ny * gw + nx
                    if (!seen[j] && on[j] && hueDist(hue[j], h0) < 40) { seen[j] = true; q.add(j) }
                }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            val area = bw * bh; val fill = count.toFloat() / area
            if (count < 6 || count > total * 0.18 || fill < 0.55f) continue
            if (bw > gw * 0.7 || bh > gh * 0.6) continue            // backgrounds / banners, not buttons
            val aspect = bw.toFloat() / bh; if (aspect > 8 || aspect < 0.2) continue
            var mh = Math.toDegrees(Math.atan2(hy, hx)).toFloat(); if (mh < 0) mh += 360
            out += Blob(minX * cell, minY * cell, (maxX + 1) * cell, (maxY + 1) * cell, colorName(mh, 0.6f, 0.8f), "色块")
        }
        return out.sortedByDescending { it.w * it.h }.take(max)
    }

    private fun hueDist(a: Float, b: Float): Float { val d = kotlin.math.abs(a - b) % 360; return if (d > 180) 360 - d else d }

    /** Small high-contrast marks in the four corners (typical close ✕ / back ‹ / menu icons). */
    fun corners(px: IntArray, w: Int, h: Int): List<Blob> {
        val bw = (w * 0.09).toInt().coerceAtLeast(8); val bh = (h * 0.14).toInt().coerceAtLeast(8)
        val out = ArrayList<Blob>()
        for ((ox, oy) in listOf(0 to 0, w - bw to 0, 0 to h - bh, w - bw to h - bh)) {
            var sum = 0L; var n = 0
            for (y in oy until oy + bh step 2) for (x in ox until ox + bw step 2) { sum += lum(px[y * w + x]); n++ }
            val mean = sum / n.coerceAtLeast(1)
            var minX = w; var minY = h; var maxX = -1; var maxY = -1; var hits = 0
            for (y in oy until oy + bh) for (x in ox until ox + bw) if (kotlin.math.abs(lum(px[y * w + x]) - mean) > 90) {
                hits++; minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y) }
            if (hits < 12 || maxX < 0) continue
            val ww = maxX - minX + 1; val hh = maxY - minY + 1
            if (ww > bw * 0.95 && hh > bh * 0.95) continue          // busy area, not an icon
            out += Blob(minX, minY, maxX + 1, maxY + 1, "", "角落图标")
        }
        return out
    }

    private fun lum(c: Int) = ((c shr 16 and 255) * 30 + (c shr 8 and 255) * 59 + (c and 255) * 11) / 100

    /**
     * Turns blobs (thumbnail coords, scale [k] = screen/thumbnail) into tappable items, skipping those already
     * covered by a text item (the text label is better). Ids continue after [existing].
     */
    fun toItems(blobs: List<Blob>, k: Float, existing: List<ScreenItem>): List<ScreenItem> {
        var n = existing.size
        return blobs.mapNotNull { b ->
            val l = (b.l * k).toInt(); val t = (b.t * k).toInt(); val r = (b.r * k).toInt(); val bo = (b.b * k).toInt()
            if (existing.any { it.cx in l..r && it.cy in t..bo }) return@mapNotNull null
            val size = if ((r - l) * (bo - t) > 40000) "大" else "小"
            val label = if (b.kind == "色块") "〔${b.color}色${size}按钮·无文字〕" else "〔${b.kind}·无文字〕"
            ScreenItem("e${++n}", label, (l + r) / 2, (t + bo) / 2, true, false, false, null, r - l, bo - t)
        }
    }
}

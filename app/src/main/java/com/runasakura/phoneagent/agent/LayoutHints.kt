package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.flow.Screen
import com.runasakura.phoneagent.flow.Text

/**
 * Generic visual-layout hints for screens whose important targets have no text
 * (thumbnails, video covers, icon buttons). Pure: unit-tested; the model decides what to do.
 */
object LayoutHints {
    private val DATE = Regex("^(今天|昨天|前天|星期.|周.|\\d{1,2}月\\d{1,2}日.*|\\d{4}年.*|\\d{4}[-/.]\\d{1,2}.*)$")
    private val GALLERY_PKG = Regex("photos|gallery|album|camera", RegexOption.IGNORE_CASE)
    private val GALLERY_WORDS = setOf("照片", "相册", "图库", "时刻", "发现", "全部照片", "所有照片", "最近项目")

    fun isGallery(s: Screen): Boolean = GALLERY_PKG.containsMatchIn(s.pkg) ||
        s.items.count { Text.norm(it.label) in GALLERY_WORDS } >= 2

    /** Where the first thumbnail of a photo grid most likely is (below the first date header / top bar). */
    fun firstThumbnail(s: Screen): Pair<Int, Int> {
        val header = s.items.filter { it.cy in (s.h * 0.08).toInt()..(s.h * 0.6).toInt() && DATE.matches(Text.norm(it.label)) }.minByOrNull { it.cy }
        val y = (header?.cy ?: (s.h * 0.16).toInt()) + (s.h * 0.075).toInt()
        return (s.w / 8) to y
    }

    /** 3x3 region map so a text-only model can reason about where things are (games/landscape have few labels). */
    fun regions(s: Screen): String {
        if (s.items.isEmpty()) return ""
        val rn = listOf("上", "中", "下"); val cn = listOf("左", "中", "右")
        val g = s.items.groupBy { (it.cy * 3 / maxOf(1, s.h)).coerceIn(0, 2) to (it.cx * 3 / maxOf(1, s.w)).coerceIn(0, 2) }
        return "区域：" + g.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).joinToString("；") { (k, v) ->
            rn[k.first] + cn[k.second] + "=" + v.take(4).joinToString("、") { it.label.take(8) } }
    }

    /**
     * Per-step layout notes. Kept short: the element lines already carry coordinates + region, the header carries
     * the screen size, and bottom-tab items are in the element list, so those are no longer repeated here.
     * The region map is only added for landscape screens (few, stylised labels).
     */
    fun describe(s: Screen): String = buildString {
        val land = s.w > s.h
        append("布局：无文字的图标/图片用 tap x,y；4列网格单元≈${s.w / 4}px 宽。")
        if (land) {
            append("\n横屏：x 范围 0..${s.w}，y 范围 0..${s.h}；主按钮常是无文字或艺术字，OCR 可能读错，看不清就 look。")
            append("\n").append(regions(s))
        }
        if (isGallery(s)) {
            val (x, y) = firstThumbnail(s)
            append("\n提示：这是相册/图库网格，缩略图没有文字；第一张照片约在 ($x,$y)，打开它用 {\"type\":\"tap\",\"x\":$x,\"y\":$y}。")
        }
    }

    /** Label of the item a coordinate tap lands on (≤ r px), so the safety gate sees 「发送」 even for x,y taps. */
    fun labelNear(s: Screen, x: Int, y: Int, r: Int = 90): String? =
        s.items.filter { kotlin.math.abs(it.cx - x) <= r * 2 && kotlin.math.abs(it.cy - y) <= r }
            .minByOrNull { kotlin.math.abs(it.cx - x) + kotlin.math.abs(it.cy - y) * 2 }?.label
}

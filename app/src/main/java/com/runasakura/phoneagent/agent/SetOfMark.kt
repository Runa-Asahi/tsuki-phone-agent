package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.screen.ScreenItem

/**
 * Set-of-Mark prompting: number the observed elements directly on the screenshot (same e-ids as the text list),
 * so a vision model can say "e7" instead of guessing pixels. Pure geometry here; drawing happens in the a11y service.
 */
object SetOfMark {
    data class Box(val id: String, val l: Int, val t: Int, val r: Int, val b: Int)

    fun boxes(items: List<ScreenItem>, screenW: Int, screenH: Int, bmpW: Int, bmpH: Int, max: Int = 40): List<Box> {
        if (screenW <= 0 || screenH <= 0) return emptyList()
        val sx = bmpW.toFloat() / screenW; val sy = bmpH.toFloat() / screenH
        return items.filter { it.id.isNotBlank() }.take(max).map {
            val hw = (if (it.bw > 0) it.bw else 80) / 2; val hh = (if (it.bh > 0) it.bh else 40) / 2
            Box(it.id, ((it.cx - hw) * sx).toInt().coerceIn(0, bmpW - 1), ((it.cy - hh) * sy).toInt().coerceIn(0, bmpH - 1),
                ((it.cx + hw) * sx).toInt().coerceIn(1, bmpW), ((it.cy + hh) * sy).toInt().coerceIn(1, bmpH))
        }
    }

    const val NOTE = "截图上的彩色编号框与元素列表的 e 编号一一对应：能用编号就用 node_id；框外的无文字图标再用坐标。"
}

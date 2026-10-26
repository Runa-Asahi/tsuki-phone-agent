package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.ocr.OcrLine
import com.runasakura.phoneagent.ocr.OcrPost
import com.runasakura.phoneagent.screen.ScreenItem
import org.junit.Assert.*
import org.junit.Test

class SelfRound1Test {
    @Test fun ownOverlayTextIsDropped() {
        val lines = listOf(OcrLine("对战", 1200, 1100, 1400, 1160), OcrLine("第3步点击e13", 10, 500, 200, 540),
            OcrLine("背停急停", 10, 560, 200, 600), OcrLine("暂偉急停", 10, 560, 200, 600), OcrLine("商店", 100, 100, 200, 140))
        val items = OcrPost.process(lines, 0, 2000)
        assertEquals(listOf("商店", "对战"), items.map { it.label })
    }

    @Test fun overlayRegionExcluded() {
        val lines = listOf(OcrLine("XYZ", 20, 500, 120, 540), OcrLine("前往", 1500, 800, 1600, 850))
        val items = OcrPost.process(lines, 0, 2000, emptySet(), intArrayOf(0, 480, 200, 620))
        assertEquals(listOf("前往"), items.map { it.label })
    }

    @Test fun noisyOcrIsNotAScreenChange() {
        val h = StepHistory()
        h.observe(listOf("鲁洛V列塔尼业", "增益挂饰", "商店", "3小小吋33分"))
        h.recordAction("点击「对战」")
        val n = h.observe(listOf("鲁迢V列塔尼业", "增益挂市", "商店", "3时33分"))!!
        assertTrue(n, n.contains("无变化"))
    }

    @Test fun failureIsReportedAndLoopHintNamesTarget() {
        val h = StepHistory()
        h.recordAction("点击「前往」 ✗执行失败：e9 不在当前列表中", ok = false)
        assertTrue(h.lastFailure()!!.contains("不在当前列表"))
        h.recordAction("点击「前往」"); h.recordAction("点击「前往」")
        assertTrue(h.loopHint()!!.contains("点击「前往」"))
        assertNull(h.lastFailure())
    }

    @Test fun actionDescIsIdFree() {
        assertEquals("点击「对战」", ActionDesc.of(AgentAction.Tap(nodeId = "e13"), "对战"))
        assertEquals("点击(1400,1150)", ActionDesc.of(AgentAction.Tap(x = 1400, y = 1150)))
    }

    @Test fun fuzzyTextLookup() {
        val items = listOf(ScreenItem("e1", "今日不再弹出", 1, 1, true, false, false, null), ScreenItem("e2", "前往", 2, 2, true, false, false, null))
        assertEquals("e1", KeyMatchLite.best(items, "今日不再迎出")?.id)
    }
}

package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.flow.Screen
import com.runasakura.phoneagent.llm.CloudProviders
import com.runasakura.phoneagent.screen.ScreenItem
import org.junit.Assert.assertNotNull
import org.junit.Assert.*
import org.junit.Test

class Round2Test {
    private fun it(l: String, x: Int, y: Int) = ScreenItem("", l, x, y, true, false, false, l)

    @Test fun galleryHintPointsAtFirstThumbnail() {
        val s = Screen("com.hihonor.photos", 1264, 2800, listOf(it("照片", 200, 200), it("今天", 120, 420),
            it("照片", 158, 2690), it("相册", 474, 2690), it("时刻", 790, 2690), it("发现", 1106, 2690)), "tree")
        assertTrue(LayoutHints.isGallery(s))
        val (x, y) = LayoutHints.firstThumbnail(s)
        assertEquals(158, x); assertTrue(y in 500..700)
        val d = LayoutHints.describe(s)
        assertTrue(d, d.contains("第一张照片") && d.contains("\"x\":158"))
        assertFalse(LayoutHints.isGallery(Screen("com.tencent.mm", 1264, 2800, listOf(it("微信", 1, 1)), "ocr")))
    }

    @Test fun coordinateTapResolvesLabelForSafety() {
        val s = Screen("com.tencent.mm", 1264, 2800, listOf(it("发送", 1160, 1700), it("八嘎", 560, 1700)), "ocr")
        assertEquals("发送", LayoutHints.labelNear(s, 1150, 1710))
        assertNull(LayoutHints.labelNear(s, 600, 400))
    }

    @Test fun progressMemoryAndLoopDetection() {
        val h = StepHistory()
        repeat(3) { h.recordAction("Tap(x=1)") }
        assertTrue(h.looping); assertTrue(h.progress().startsWith("已执行：1."))
        h.recordAction("Back"); assertTrue("loop window is 6 actions", h.looping)
        assertNotNull(h.loopHint())
        repeat(4) { h.recordAction("Swipe$it") }; assertFalse(h.looping)
    }

    @Test fun visionModels() {
        assertTrue(CloudProviders.isVision("qwen-vl-max")); assertTrue(CloudProviders.isVision("gpt-4o-mini"))
        assertFalse(CloudProviders.isVision("deepseek-flash")); assertFalse(CloudProviders.isVision("qwen-plus"))
    }

    @Test fun coordinateTapIsValid() {
        val a = com.runasakura.phoneagent.model.AgentAction.Tap(x = 158, y = 560)
        assertNull(ActionValidator.validate(a, emptyList(), 1264, 2800))
    }
}

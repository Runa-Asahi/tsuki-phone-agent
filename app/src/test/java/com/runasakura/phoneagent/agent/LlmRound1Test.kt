package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.screen.ScreenItem
import org.junit.Assert.*
import org.junit.Test

class LlmRound1Test {
    private fun it(id: String, l: String, x: Int, y: Int, w: Int = 200, h: Int = 50) = ScreenItem(id, l, x, y, true, false, false, null, w, h)

    @Test fun ocrChurnIsNotAChange() {
        val a = listOf(it("e1", "鲁路修V布列塔尼", 300, 80), it("e2", "「继续寻找格。", 1400, 1000), it("e3", "3小小吋33分", 2400, 60), it("e4", "今日不再弹出", 1500, 1150))
        val b = listOf(it("e1", "鲁迢V列塔尼业", 302, 82), it("e2", "|继续寻技。", 1390, 1003), it("e3", "6リ时43分", 2400, 60), it("e4", "令日不再弹出1", 1500, 1150))
        assertFalse(SemanticDiff.compare(a, b).meaningful)
        val c = b.take(1) + it("e9", "宝石争霸", 1400, 600)
        assertTrue(SemanticDiff.compare(b, c).meaningful)
    }

    @Test fun lineHasSizeAndRegionButNoKeywordRoles() {
        val line = ObsFormat.line(it("e2", "好的!", 1400, 1000, 300, 90), 2800, 1264)
        assertTrue(line, line.contains("300x90") && line.contains("(1400,1000)") && !line.contains("下中") && !line.contains("【"))
    }

    @Test fun guardRefusesRepeatedNoOpAcrossOcrVariants() {
        val g = NoEffectGuard()
        g.record(AgentAction.Tap(nodeId = "e17"), "令日不再弹出1", "点击「令日不再弹出1」")
        assertNotNull(g.check(AgentAction.Tap(nodeId = "e16"), "今日不再弹出"))
        assertNull(g.check(AgentAction.Tap(nodeId = "e2"), "好的!"))
        g.record(AgentAction.Tap(x = 2500, y = 1100), null, "点击(2500,1100)")
        assertNotNull(g.check(AgentAction.Tap(x = 2520, y = 1150), null))
        assertTrue(g.summary()!!.contains("不要再做"))
    }

    @Test fun blindTapDetection() {
        val items = listOf(it("e1", "商店", 200, 1100, 120, 50))
        assertTrue(ObsFormat.nearAny(items, 250, 1120))
        assertFalse(ObsFormat.nearAny(items, 2500, 1100))
    }

    @Test fun lookAction() {
        val p = ActionParser.parse("{\"thought\":\"看不清\",\"action\":{\"type\":\"look\"}}")
        assertTrue(p.needScreenshot); assertTrue(p.action is AgentAction.Wait)
    }
}

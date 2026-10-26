package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.screen.ScreenItem
import org.junit.Assert.*
import org.junit.Test

/** Offline replay of the v0.5.0 荒野乱斗 log: popup screen with OCR churn, model repeatedly taps 今日不再弹出 / blind (2500,1100). */
class BrawlReplayTest {
    private fun s(l: String, x: Int, y: Int) = ScreenItem("e", l, x, y, true, false, false, null, 200, 50)
    private val frames = listOf(
        listOf(s("「继续寻找格。", 1400, 1000), s("今日不再弹出", 1500, 1150), s("好的!", 1400, 1060), s("3小吋33分", 2400, 60)),
        listOf(s("|继续寻技。", 1390, 1003), s("令日不再弹出1", 1500, 1150), s("好的", 1402, 1061), s("3小时32分", 2400, 60)),
        listOf(s("建续导技", 1405, 998), s("今日不再弹出", 1498, 1152), s("好的!", 1400, 1060), s("6リ时43分", 2400, 60)),
        listOf(s("继续寻找", 1400, 1000), s("今日不再弹出", 1500, 1150), s("好的!", 1400, 1060), s("3小时31分", 2400, 60)))
    private val modelWants = listOf<Pair<AgentAction, String?>>(
        AgentAction.Tap(nodeId = "e2") to "今日不再弹出", AgentAction.Tap(nodeId = "e2") to "令日不再弹出1",
        AgentAction.Tap(x = 2500, y = 1100) to null, AgentAction.Tap(x = 2500, y = 1100) to null)

    @Test fun loopNoLongerCycles() {
        val g = NoEffectGuard(); var refused = 0; var executed = 0
        for (i in 0 until frames.size - 1) {
            val (a, label) = modelWants[i]
            val blind = a is AgentAction.Tap && a.x != null && !ObsFormat.nearAny(frames[i], a.x!!, a.y!!)
            if (g.check(a, label) != null || blind) { refused++; continue }
            executed++
            val d = SemanticDiff.compare(frames[i], frames[i + 1])
            assertFalse("frame $i churn must read as no change", d.meaningful)
            g.record(a, label, label ?: "tap")
        }
        assertEquals("只执行第一次勾选，其余重复/盲点被拒", 1, executed); assertEquals(2, refused)
        assertNull(g.check(AgentAction.Tap(nodeId = "e3"), "好的!"))
    }

    @Test fun appMemoryRoundTripAndReuse() {
        val m = AppMemory(emptyList(), emptyList()).merge(listOf("今日不再弹出是复选框", "「返回」会弹退出确认"), "打开荒野乱斗开一局宝石争霸", "点好的 → 点模式 → 选宝石争霸 → 点对战")
        val d = AppMemory.decode(m.encode())
        assertEquals(2, d.facts.size)
        assertNotNull(d.knownPath("打开荒野乱斗开一局宝石争霸"))
        assertTrue(d.hint("打开荒野乱斗开一局宝石争霸")!!.contains("复选框"))
        val d2 = d.merge(listOf("今日不再弹出是复选框。"), null, null)
        assertEquals(2, d2.facts.size)
    }
}

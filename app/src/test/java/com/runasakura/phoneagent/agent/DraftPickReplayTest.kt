package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.screen.ScreenItem
import org.junit.Assert.*
import org.junit.Test

/**
 * Replay of the 0.5.0 log (14:29, 打开荒野乱斗开一把宝石争霸): info page → blind (2500,1100) → pick screen,
 * then the model taps the same confirm label (e8/e19/e29 「对战」) again and again while OCR churn makes the
 * diff look "changed". Log text is fixture data only; the production logic under test is app-agnostic.
 */
class DraftPickReplayTest {
    private fun s(l: String, x: Int, y: Int) = ScreenItem("e", l, x, y, true, false, false, null, 200, 50)
    private val infoPage = listOf(s("活动信息", 1400, 150), s("宝石争霸", 1400, 260), s("3对3", 1400, 360), s("收集从地图中心的宝石", 1400, 600))
    private val pick1 = listOf(s("选择英雄16秒", 1400, 80), s("杰西", 600, 700), s("已选", 900, 700), s("对战", 2450, 1150))
    private val pick2 = listOf(s("选择英雄16秒", 1400, 80), s("(①y", 600, 700), s("E选", 900, 700), s("对战", 2450, 1150))

    @Test fun blindTapOnInfoPageRefused() {
        assertFalse(ObsFormat.nearAny(infoPage, 2500, 1100))
    }

    @Test fun ocrChurnOnPickScreenIsNoChange() {
        assertFalse(SemanticDiff.compare(pick1, pick2).meaningful)
    }

    @Test fun repeatedConfirmWithoutSelectingIsStopped() {
        // Mirrors AgentLoop: same target executed twice in a row → recorded as ineffective → third refused.
        val g = NoEffectGuard(); var prev: String? = null; var executed = 0; var refused = 0
        for (id in listOf("e8", "e19", "e29", "e29")) {
            val a = AgentAction.Tap(nodeId = id)
            if (g.check(a, "对战") != null) { refused++; continue }
            executed++
            val k = g.key(a, "对战"); if (k == prev) g.record(a, "对战", "点击「对战」（连续两次同一目标未推进）"); prev = k
        }
        assertEquals(2, executed); assertEquals(2, refused)
        assertNull("picking a candidate is still allowed", g.check(AgentAction.Tap(x = 600, y = 700), "杰西"))
    }

    @Test fun modelJudgedMismatchBlocksDone() {
        val p = ActionParser.parse("""{"screen":"候选选择","goal_match":"否：这是活动版本","action":{"type":"done","summary":"x"}}""")
        assertTrue(p.action is AgentAction.Done); assertTrue(GoalMatch.saysNo(p.goalMatch))
    }
}

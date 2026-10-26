package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.flow.Screen
import com.runasakura.phoneagent.llm.LlmResponse
import com.runasakura.phoneagent.llm.LlmRouter
import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.prefs.MemorySettings
import com.runasakura.phoneagent.screen.ScreenItem
import org.junit.Assert.*
import org.junit.Test

class Pt54Test {
    private fun it(id: String, l: String, y: Int, bw: Int = 0) = ScreenItem(id, l, 540, y, true, false, false, null, bw, if (bw > 0) 80 else 0)
    private fun listScreen(rows: Int): Screen {
        val items = ArrayList<ScreenItem>(); var n = 0
        repeat(rows) { r -> items += it("e${n++}", "歌曲$r - 歌手$r", 300 + r * 100); items += it("e${n++}", "播放", 300 + r * 100) }
        return Screen("p", 1080, 2400, items, "tree")
    }

    @Test fun dumpGroupsRepeatsKeepsCoordinatesAndCountsHidden() {
        val d = ObsFormat.dump(listScreen(5))
        assertTrue(d, d.contains("「播放」×5："))
        assertTrue(d.contains("[e1](540,300)") && d.contains("[e9](540,700)"))
        assertEquals(1, Regex("「播放」").findAll(d).count())
        val big = ObsFormat.dump(listScreen(80))
        assertTrue(big, big.contains("…另有") && big.contains("未列出"))
    }

    @Test fun tokenMeasurement() {
        val s = listScreen(20)
        val before = s.items.joinToString("\n") { x -> ObsFormat.line(x, s.w, s.h) }.take(1600)
        val after = ObsFormat.dump(s)
        val b = LlmRouter.approxTokens(before); val a = LlmRouter.approxTokens(after)
        println("TOKENS list20 before=$b after=$a")
        assertTrue("after=$a before=$b", a < b)
    }

    @Test fun exactDiffSeesTreeScroll() {
        val a = listScreen(5).items; val b = a.map { x -> if (x.label.startsWith("歌曲0")) x.copy(label = "歌曲0X - 歌手0X") else x }
        assertTrue(SemanticDiff.compare(a, b, exact = true).meaningful)
        assertTrue(SemanticDiff.compare(a, a, exact = true).added.isEmpty())
    }

    @Test fun copyPasteParserAndValidator() {
        ActionParser.parse("""{"action":{"type":"copy","node_id":"e1"}}""").action.let { a -> assertTrue(a.toString(), a is AgentAction.Copy) }
        assertFalse(ActionParser.parse("""{"action":{"type":"copy","text":"x"}}""").action is AgentAction.Copy)
        ActionParser.parse("""{"action":{"type":"paste"}}""").action.let { a -> assertTrue(a.toString(), a is AgentAction.Paste) }
        val items = listOf(it("e1", "取件码 8848", 500))
        assertNull(ActionValidator.validate(AgentAction.Copy("e1"), items, 1080, 2400))
        assertNotNull(ActionValidator.validate(AgentAction.Copy("e9"), items, 1080, 2400))
    }

    @Test fun approvedConfirmAllowsNextSensitiveTap() {
        val items = listOf(it("e3", "发送", 2200))
        assertNotNull(ActionValidator.validate(AgentAction.Tap(nodeId = "e3"), items, 1080, 2400))
        assertNull(ActionValidator.validate(AgentAction.Tap(nodeId = "e3"), items, 1080, 2400, approved = true))
    }

    @Test fun transcriptLinesAndCheckpoint() {
        val l = Transcript.stepLine("""{"subgoal":"搜索","state":"首页","action":{"type":"tap","node_id":"e3"},"expect":"搜索页"}""")!!
        assertFalse(l, l.contains("e3")); assertTrue(l.contains("[搜索]"))
        assertEquals(listOf("a（重复 3 次）", "b"), Transcript.collapse(listOf("a", "a", "a", "b")))
        val t = Transcript(); var folded: Any? = null
        repeat(7) { i -> t.append("obs$i", """{"action":{"type":"wait"}}""")?.let { f -> folded = f } }
        assertNotNull(folded)
        t.replaceCheckpoint("## 已完成\n- x")
        assertTrue(t.window().first().content.contains("## 已完成"))
    }

    @Test fun parseCheckpointJson() {
        val c = LlmRouter.parseCheckpoint("""{"done":["打开音乐"],"facts":["歌名=晴天"],"next":"点播放"}""")!!
        assertTrue(c.contains("打开音乐") && c.contains("歌名=晴天") && c.contains("点播放"))
        assertNull(LlmRouter.parseCheckpoint("""{"done":[]}"""))
        assertNull(LlmRouter.parseCheckpoint("garbage"))
    }

    @Test fun splitterSplitsBeforePlay() {
        val s = GoalSplitter.split("搜索晴天并播放")
        assertTrue(s.toString(), s.size >= 2 && !s.toString().contains("晴天并播放"))
    }

    @Test fun searchEntryIgnoresNarrowSkipButton() {
        val s = Screen("p", 1080, 2400, listOf(it("e0", "跳过 3", 120, bw = 160)), "ocr")
        assertNull(SearchEntry.find(s))
    }

    @Test fun localTimeoutStops() {
        val r = { b: String -> LlmResponse("", b, 1, error = "timeout", errorCode = "TIMEOUT") }
        assertEquals(LlmRecovery.Action.STOP, LlmRecovery.decide(r("mnn:qwen"), 0))
        assertEquals(LlmRecovery.Action.REOBSERVE, LlmRecovery.decide(r("deepseek:flash"), 0))
    }

    @Test fun memorySettingsDefaults() {
        val m = MemorySettings(); assertFalse(m.modelCheckpoint); assertEquals(25, m.maxSteps)
    }

    @Test fun pagingWithSameSwipeIsNotALoop() {
        val h = StepHistory()
        h.observe(listOf("歌曲零"))
        for (i in 1..5) { h.recordAction("滑动 up"); h.observe(listOf("歌曲第${i}首"), exact = true) }
        assertFalse(h.looping)
        val c = StepHistory(); c.observe(listOf("首页"))
        for (i in 1..4) { c.recordAction("点击「切换」"); c.observe(listOf(if (i % 2 == 0) "首页" else "设置"), exact = true) }
        assertTrue(c.looping)
    }
}

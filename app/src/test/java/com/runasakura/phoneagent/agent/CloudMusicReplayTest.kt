package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.a11y.A11yDevice
import com.runasakura.phoneagent.llm.ChatMessage
import com.runasakura.phoneagent.llm.LlmRouter
import com.runasakura.phoneagent.overlay.OverlayAvoid
import com.runasakura.phoneagent.screen.ScreenItem
import org.junit.Assert.*
import org.junit.Test

/** Replay of the v0.6.0 log「打开网易云音乐并播放钢之炼金术师主题曲」(15:28). Log text = fixture only. */
class CloudMusicReplayTest {
    private fun s(id: String, l: String, x: Int, y: Int, edit: Boolean = false) = ScreenItem(id, l, x, y, true, edit, false, null, 300, 80)
    private val searchPage = listOf(s("e1", "", 600, 180, edit = true), s("e2", "搜索", 1150, 180), s("e3", "歌手", 200, 400), s("e4", "排行榜", 600, 400))

    @Test fun tappingInputWithKeyboardIsNotNoEffect() {
        // Step 4: identical text before/after tapping the input, keyboard appeared.
        assertFalse(SemanticDiff.compare(searchPage, searchPage).meaningful)
        assertTrue(SemanticDiff.stateChange(null, 1700, false, true)!!.contains("键盘已弹出"))
        assertTrue("focus verified by a11y even without IME", SemanticDiff.stateChange(null, null, false, true)!!.contains("系统确认"))
        assertNull("tapping an editable alone is no longer assumed to work", SemanticDiff.stateChange(null, null, false, false))
        assertNull(SemanticDiff.stateChange(1700, 1700, true, true))
    }

    @Test fun transcriptIsAppendOnlyThenCompacts() {
        val t = Transcript()
        val sys = listOf(ChatMessage("system", SystemPrompt.SHORT), ChatMessage("user", "目标"))
        val r1 = sys + t.window() + ChatMessage("user", "obs1")
        t.append("obs1", """{"state":"首页","action":{"type":"tap","node_id":"e15"},"expect":"搜索页"}""")
        val r2 = sys + t.window() + ChatMessage("user", "obs2")
        assertEquals("request 2 starts with request 1 exactly", r1, r2.take(r1.size))
        val big = "屏".repeat(3000)
        repeat(8) { t.append(big + it, """{"state":"s$it","action":{"type":"back"}}""") { "进度X" } }
        assertTrue(t.size() <= Transcript.MAX)
        val cp = t.checkpoint!!
        assertTrue(cp.contains("<checkpoint>") && cp.contains("在「首页」") && cp.contains("进度X"))
        val w = t.window()
        assertTrue("old screens pruned and marked stale", w.filter { it.role == "user" && it.content.startsWith("（较早") }.all { it.content.length < 1200 })
        // after compaction the series is append-only again
        val before = sys + t.window()
        t.append("obsN", "{}")
        assertEquals(before, (sys + t.window()).take(before.size))
    }

    @Test fun overlayHitDetectionForPassThrough() {
        val r = intArrayOf(0, 1100, 350, 1300)
        assertTrue(OverlayAvoid.hits(200, 1200, r)); assertFalse(OverlayAvoid.hits(800, 1200, r)); assertFalse(OverlayAvoid.hits(200, 1200, null))
        assertTrue("swipe crossing the button", OverlayAvoid.pathHits(600, 1600, 100, 900, r))
        assertFalse(OverlayAvoid.pathHits(700, 2000, 700, 500, r))
    }

    @Test fun realUsageAndCacheHitParsed() {
        val raw = """{"usage":{"prompt_tokens":2137,"completion_tokens":71,"prompt_cache_hit_tokens":640}}"""
        val r = LlmRouter({ "cloud_only" }, com.runasakura.phoneagent.llm.LocalMnnClient("http://127.0.0.1:1", "", "m"), null)
        assertEquals(Triple(2137L, 71L, 640L), r.usage(raw)); assertNull(r.usage("x"))
    }

    @Test fun settleBudgetShrinksForAnimatedApps() {
        val p = "pkg.anim.test"
        assertEquals(1200L, A11yDevice.SettleBudget.maxMs(p))
        A11yDevice.SettleBudget.record(p, false); A11yDevice.SettleBudget.record(p, false)
        assertEquals(450L, A11yDevice.SettleBudget.maxMs(p))
        A11yDevice.SettleBudget.record(p, true); assertEquals(1200L, A11yDevice.SettleBudget.maxMs(p))
    }

    @Test fun setOfMarkSkipsStackedDuplicates() {
        val items = listOf(s("e1", "again", 500, 900), s("e2", "again", 505, 905), s("e3", "其他", 500, 1300))
        assertEquals(listOf("e1", "e3"), SetOfMark.boxes(items, 1264, 2800, 640, 1418).map { it.id })
    }
}

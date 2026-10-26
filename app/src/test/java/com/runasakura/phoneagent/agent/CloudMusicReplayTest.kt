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
        assertNotNull(SemanticDiff.stateChange(null, 1700, tappedEditable = true))
        assertTrue(SemanticDiff.stateChange(null, 1700, false)!!.contains("键盘已弹出"))
        assertNotNull("tapping an editable counts even if IME is not reported", SemanticDiff.stateChange(null, null, true))
        assertNull(SemanticDiff.stateChange(1700, 1700, false))
    }

    @Test fun transcriptIsAppendOnlyForPrefixCache() {
        val turns = ArrayList<ChatMessage>()
        val sys = listOf(ChatMessage("system", SystemPrompt.SHORT), ChatMessage("user", "目标"))
        val r1 = sys + Transcript.window(turns) + ChatMessage("user", "obs1")
        Transcript.append(turns, "obs1", "a1")
        val r2 = sys + Transcript.window(turns) + ChatMessage("user", "obs2")
        assertEquals("request 2 starts with request 1 exactly", r1, r2.take(r1.size))
        repeat(10) { Transcript.append(turns, "o$it", "a$it") }
        assertTrue(turns.size <= Transcript.MAX)
    }

    @Test fun tapNeverLandsOnOwnStopButton() {
        val r = intArrayOf(0, 1100, 350, 1300)
        assertEquals(366 to 1200, OverlayAvoid.point(200, 1200, r))
        assertEquals(800 to 1200, OverlayAvoid.point(800, 1200, r))
        assertEquals(200 to 1200, OverlayAvoid.point(200, 1200, null))
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

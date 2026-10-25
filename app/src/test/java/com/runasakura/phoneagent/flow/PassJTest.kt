package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.agent.ScreenSig
import com.runasakura.phoneagent.agent.Subgoal
import com.runasakura.phoneagent.agent.SubgoalExecutor
import com.runasakura.phoneagent.screen.ScreenItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassJTest {
    private fun scr(top: List<String>, mid: List<String>, bottom: List<String>, pkg: String = "com.ss.android.ugc.aweme") =
        Screen(pkg, 1264, 2800,
            top.mapIndexed { i, l -> ScreenItem("t$i", l, 200 + i * 200, 150, true, false, false, null) } +
            mid.mapIndexed { i, l -> ScreenItem("m$i", l, 600, 600 + i * 300, true, false, false, null) } +
            bottom.mapIndexed { i, l -> ScreenItem("b$i", l, 150 + i * 250, 2700, true, false, false, null) }, "tree")

    private val tabs = listOf("首页", "朋友", "消息", "我")

    @Test fun feedRefreshStillSimilar() {
        val a = scr(listOf("推荐", "关注"), listOf("视频A标题很长很长", "@作者甲", "1.2万"), tabs)
        val b = scr(listOf("推荐", "关注"), listOf("完全不同的视频B", "@作者乙", "3456", "广告"), tabs)
        assertTrue(ScreenSig.similar(ScreenSig.of(a), ScreenSig.of(b)))
    }

    @Test fun differentTabSameBottomBarNotSimilar() {
        val a = scr(listOf("推荐", "关注"), listOf("x"), tabs)
        val b = scr(listOf("消息", "通讯录"), listOf("x"), tabs)
        assertFalse(ScreenSig.similar(ScreenSig.of(a), ScreenSig.of(b)))
    }

    @Test fun clockInTopBarIgnored() {
        val a = scr(listOf("12:01", "推荐"), listOf("a"), tabs)
        val b = scr(listOf("12:02", "推荐"), listOf("b"), tabs)
        assertTrue(ScreenSig.similar(ScreenSig.of(a), ScreenSig.of(b)))
    }

    @Test fun undoStopsWhenBackLeavesApp() = runBlocking {
        val d = FakeWeChat()
        val ex = SubgoalExecutor(d, {}, { true })
        assertEquals(SubgoalExecutor.Status.OK, ex.run(Subgoal.LongPress("王五")))
        d.state = "me" // user wandered elsewhere; back from 我 goes to the launcher
        ex.run(Subgoal.Undo)
        assertTrue("at most 2 backs, got ${d.backs}", d.backs <= 2)
    }
}

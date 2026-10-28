package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.agent.GoalSplitter
import com.runasakura.phoneagent.agent.Subgoal
import com.runasakura.phoneagent.agent.SubgoalExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassATest {
    @Test fun splitterNewVerbs() {
        assertEquals(listOf(Subgoal.LongPress("王五")), GoalSplitter.split("长按王五"))
        assertEquals(listOf(Subgoal.TypeInto("搜索框", "原神，启动")), GoalSplitter.split("在搜索框输入原神，启动"))
        assertEquals(listOf(Subgoal.Open("搜索"), Subgoal.TypeInto("搜索框", "耳机")), GoalSplitter.split("点击搜索，在搜索框里输入耳机"))
        assertEquals(listOf(Subgoal.ScrollTo("设置"), Subgoal.Open("设置")), GoalSplitter.split("滑到设置，然后点击设置"))
    }

    @Test fun longPressVerifiesMenu() = runBlocking {
        val d = FakeWeChat()
        assertEquals(SubgoalExecutor.Status.OK, SubgoalExecutor(d, {}, { true }).run(Subgoal.LongPress("王五")))
        assertEquals(listOf("王五"), d.longPresses)
    }

    @Test fun longPressMissingTargetFails() = runBlocking {
        val d = FakeWeChat()
        assertEquals(SubgoalExecutor.Status.FAILED, SubgoalExecutor(d, {}, { true }).run(Subgoal.LongPress("不存在的人")))
        assertTrue(d.longPresses.isEmpty())
    }

    @Test fun scrollToFindsOffscreenRowWithoutTapping() = runBlocking {
        val d = FakeWeChat(moreListRows = listOf("赵六", "孙七"))
        val ex = SubgoalExecutor(d, {}, { true })
        val hit = ex.scrollTo("孙七")
        assertEquals("孙七", hit?.label)
        assertTrue(d.taps.none { it == "孙七" })
    }

    @Test fun scrollToStopsAtEnd() = runBlocking {
        val d = FakeWeChat()
        assertNull(SubgoalExecutor(d, {}, { true }).scrollTo("不存在"))
        assertTrue(d.scrolls <= 3) // stops after 2 unchanged screens
    }

    @Test fun typeIntoSearchField() = runBlocking {
        val d = FakeWeChat(state = "search")
        val r = SubgoalExecutor(d, {}, { true }).run(Subgoal.TypeInto("搜索", "王小明"))
        assertEquals(SubgoalExecutor.Status.OK, r)
        assertEquals("王小明", d.searchText)
    }
}

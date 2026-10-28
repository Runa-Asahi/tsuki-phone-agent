package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.agent.GoalSplitter
import com.runasakura.phoneagent.agent.ScreenSig
import com.runasakura.phoneagent.agent.Subgoal
import com.runasakura.phoneagent.agent.SubgoalExecutor
import com.runasakura.phoneagent.screen.ScreenItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassDTest {
    @Test fun splitUndo() {
        assertEquals(listOf(Subgoal.Undo), GoalSplitter.split("撤销上一步"))
        assertEquals(listOf(Subgoal.LongPress("王五"), Subgoal.Undo), GoalSplitter.split("长按王五，然后撤销"))
    }

    @Test fun undoReturnsToPreviousScreen() = runBlocking {
        val d = FakeWeChat()
        val ex = SubgoalExecutor(d, {}, { true })
        assertEquals(SubgoalExecutor.Status.OK, ex.run(Subgoal.LongPress("王五")))
        assertTrue(d.state.startsWith("menu:"))
        assertEquals(SubgoalExecutor.Status.OK, ex.run(Subgoal.Undo))
        assertEquals("main", d.state)
        assertEquals(1, d.backs)
    }

    @Test fun undoWithNothingFails() = runBlocking {
        val d = FakeWeChat()
        assertEquals(SubgoalExecutor.Status.FAILED, SubgoalExecutor(d, {}, { true }).run(Subgoal.Undo))
        assertEquals(0, d.backs)
    }

    private fun s(pkg: String, vararg l: String) = Screen(pkg, 1, 1, l.map { ScreenItem(it, it, 0, 0, true, false, false, null) }, "tree")

    @Test fun sigToleratesSmallChanges() {
        assertTrue(ScreenSig.similar(ScreenSig.of(s("p", "微信", "通讯录", "发现", "我", "12:01")), ScreenSig.of(s("p", "微信", "通讯录", "发现", "我", "12:02"))))
        assertFalse(ScreenSig.similar(ScreenSig.of(s("p", "a")), ScreenSig.of(s("q", "a"))))
        assertFalse(ScreenSig.similar(ScreenSig.of(s("p", "a", "b", "c")), ScreenSig.of(s("p", "x", "y", "c"))))
    }
}

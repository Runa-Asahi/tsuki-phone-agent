package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.agent.GoalSplitter
import com.runasakura.phoneagent.agent.StepHistory
import com.runasakura.phoneagent.agent.Subgoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassCTest {
    @Test fun firstObservationHasNoNote() {
        assertNull(StepHistory().observe(listOf("a", "b")))
    }
    @Test fun diffReportsAddedAndRemoved() {
        val h = StepHistory()
        h.observe(listOf("首页", "搜索"))
        h.recordAction("Tap(搜索)")
        val n = h.observe(listOf("搜索", "取消", "历史记录"))!!
        assertTrue(n, n.contains("新增:取消、历史记录") || n.contains("新增:历史记录、取消"))
        assertTrue(n, n.contains("消失:首页"))
    }
    @Test fun repeatedNoOpsBecomeStuck() {
        val h = StepHistory()
        h.observe(listOf("x"))
        repeat(3) { h.recordAction("Tap(e1)"); h.observe(listOf("x")) }
        assertTrue(h.stuckCount >= 3)
        h.recordAction("Back"); h.observe(listOf("y"))
        assertEquals(0, h.stuckCount)
    }
    @Test fun compoundGoalAcrossNewVerbs() {
        assertEquals(
            listOf(Subgoal.Open("抖音"), Subgoal.Search("猫咪"), Subgoal.LongPress("第一个视频")),
            GoalSplitter.split("打开抖音，搜索猫咪，然后长按第一个视频")
        )
    }
    @Test fun typeIntoOwnsRestIncludingCommas() {
        assertEquals(listOf(Subgoal.TypeInto("评论框", "好看，然后呢")), GoalSplitter.split("在评论框输入好看，然后呢"))
    }
}

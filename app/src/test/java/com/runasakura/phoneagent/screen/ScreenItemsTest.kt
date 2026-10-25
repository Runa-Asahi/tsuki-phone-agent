package com.runasakura.phoneagent.screen

import com.runasakura.phoneagent.agent.GoalSplitter
import com.runasakura.phoneagent.agent.Subgoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenItemsTest {
    private val W = 1264
    private val H = 2800

    @Test
    fun dropsObfuscatedIdsOffscreenAndMergesRowText() {
        val row = RawNode(
            viewId = "oax", l = 0, t = 133, r = 1264, b = 360, clickable = true,
            children = listOf(
                RawNode(text = "我的电脑", l = 239, t = 140, r = 900, b = 220),
                RawNode(text = "删除", l = 1944, t = 133, r = 2208, b = 360, clickable = true), // swipe menu offscreen
            )
        )
        val root = RawNode(viewId = "r4b", desc = "r4b", l = 0, t = 0, r = W, b = H,
            children = listOf(RawNode(desc = "37k", l = 0, t = 0, r = W, b = H, children = listOf(row))))
        val items = ScreenItems.build(listOf(root), W, H)
        assertEquals(1, items.size)
        assertEquals("我的电脑", items[0].label)
        assertTrue(items[0].tap)
        assertTrue(items.none { it.label == "删除" || it.label == "37k" || it.label == "r4b" })
    }

    @Test
    fun sortsTopToBottomAndCaps() {
        val kids = (0 until 100).map { RawNode(text = "联系人$it", l = 0, t = 2000 - it * 20, r = 500, b = 2015 - it * 20, clickable = true) }
        val items = ScreenItems.build(listOf(RawNode(l = 0, t = 0, r = W, b = H, children = kids)), W, H)
        assertTrue(items.size <= 40)
        assertTrue(items.zipWithNext().all { (a, b) -> a.cy <= b.cy })
        assertTrue(items.joinToString("\n") { it.format() }.length <= 1200)
    }

    @Test
    fun splitsGoal() {
        val s = GoalSplitter.split("进入我的电脑，发送111")
        assertEquals(listOf(Subgoal.Open("我的电脑"), Subgoal.Send("111", true)), s)
        assertEquals(Subgoal.Send("你好", false), GoalSplitter.classify("输入你好"))
    }
}

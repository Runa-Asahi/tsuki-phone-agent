package com.runasakura.phoneagent.agent

import org.junit.Assert.*
import org.junit.Test

class GoalRewriteTest {
    @Test fun rewrites() {
        assertEquals("打开淘宝，搜索机械键盘", GoalRewrite.normalize("在淘宝搜机械键盘"))
        assertEquals("打开B站，搜索凉宫春日", GoalRewrite.normalize("B站上搜索凉宫春日"))
        assertEquals("打开拼多多，搜索凉宫春日", GoalRewrite.normalize("打开拼多多搜索凉宫春日"))
        assertEquals("打开抖音，搜索猫", GoalRewrite.normalize("用抖音搜一下猫。"))
        assertEquals("打开小红书，搜索长春美食", GoalRewrite.normalize("帮我在小红书里搜长春美食"))
        assertEquals("打开拼多多，搜索X", GoalRewrite.normalize("打开拼多多并搜索X"))
    }
    @Test fun leavesOthers() {
        listOf("打开微信给王小明发你好", "打开淘宝", "给王小明打视频电话", "打开设置，进入蓝牙").forEach { assertEquals(it, GoalRewrite.normalize(it)) }
        assertEquals("淘宝", AppResolver.extractAppName(GoalRewrite.normalize("在淘宝搜机械键盘")))
        val subs = GoalSplitter.split(AppResolver.remainingGoal("打开淘宝，搜索机械键盘", "淘宝")!!)
        assertEquals(listOf(Subgoal.Search("机械键盘")), subs)
    }
}

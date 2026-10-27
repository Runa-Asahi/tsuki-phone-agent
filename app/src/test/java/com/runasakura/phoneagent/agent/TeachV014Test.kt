package com.runasakura.phoneagent.agent

import org.junit.Assert.*
import org.junit.Test

/** v0.14 teach / on-the-job training: recipe slots, offline steps, deviate detection, session helpers. */
class TeachV014Test {
    @Test fun slotsRoundTrip() {
        val r = Recipe("给{联系人}发{内容}", listOf("打开聊天「{联系人}」", "点「发送」"), pre = listOf("已登录"), tips = listOf("没找到人就搜索"), slots = listOf("联系人", "内容"))
        val d = Recipe.decode(r.encode())!!
        assertEquals(r.goal, d.goal); assertEquals(r.steps, d.steps); assertEquals(r.pre, d.pre); assertEquals(r.tips, d.tips); assertEquals(r.slots, d.slots)
    }

    @Test fun parsePlanWithSlots() {
        val r = Recipe.parsePlan("""{"goal":"搜索{关键词}","pre":["打开目标应用"],"steps":["点「搜索」","输入{关键词}","点结果"],"tips":["没结果就换词"],"slots":["关键词"]}""")!!
        assertEquals("搜索{关键词}", r.goal); assertEquals(listOf("关键词"), r.slots); assertEquals(3, r.steps.size)
    }

    @Test fun offlineStepsSkipsHeaders() {
        val t = Recipe.offlineSteps("示范轨迹：\n1. 包=com.x 可见：搜索\n2. 点「发送」\n口述：忽略")
        assertEquals(listOf("包=com.x 可见：搜索", "点「发送」"), t)
    }

    @Test fun nextMissingForcesDeepSignal() {
        val r = Recipe("发消息", listOf("点「发送」", "确认"))
        assertEquals("发送", Recipe.nextMissing(r, emptyList(), listOf("取消", "返回")))
        assertNull(Recipe.nextMissing(r, emptyList(), listOf("发送", "输入框")))
        assertNull(Recipe.nextMissing(r, listOf("点「发送」"), listOf("取消")))
    }

    @Test fun dominantPkg() {
        val steps = listOf(
            TeachSession.Step(1, "com.a", emptyList(), null, "none"),
            TeachSession.Step(2, "com.b", emptyList(), null, "none"),
            TeachSession.Step(3, "com.b", emptyList(), null, "none"),
        )
        assertEquals("com.b", TeachSession.dominantPkg(steps))
        assertNull(TeachSession.dominantPkg(emptyList()))
    }

    @Test fun stepLineFormat() {
        val s = TeachSession.Step(1, "com.game", listOf("开始", "设置"), "点中间大按钮", "ocr")
        val line = s.line()
        assertTrue(line.contains("包=com.game"))
        assertTrue(line.contains("可见[ocr]"))
        assertTrue(line.contains("说明：点中间大按钮"))
    }

    @Test fun forceDeepSticky() {
        val st = ThinkMode.State()
        ThinkMode.forceDeep(st, "教授流程偏离")
        val d = ThinkMode.decide(st, 3, 40, enabled = true)
        assertEquals(ThinkMode.Mode.DEEP, d.mode)
        assertEquals("教授流程偏离", d.reason)
    }
}

package com.runasakura.phoneagent.agent

import org.junit.Assert.*
import org.junit.Test

/** Teach state machine: dictate, demo, mix, cancel, persist restore, click debounce. */
class TeachV014Test {
    @Test fun beginRequiresGoal() {
        assertNull(TeachMachine.begin(" ", null, 1))
        assertTrue(TeachMachine.begin("打开蓝牙", null, 1)!!.active)
    }

    @Test fun dictateFlow() {
        var s = TeachMachine.begin("教发消息", null, 10)!!
        s = TeachMachine.appendDictate(s, "先打开微信")
        s = TeachMachine.appendDictate(s, "点通讯录")
        assertEquals(2, s.dictations.size)
        assertTrue(s.empty().not())
        val done = TeachMachine.finish(s)!!
        assertTrue(done.ready)
        val trace = TeachMachine.mergeTrace(done)
        assertTrue(trace.contains("口述："))
        assertTrue(trace.contains("先打开微信"))
        assertFalse(trace.contains("示范轨迹"))
    }

    @Test fun demoFlow() {
        var s = TeachMachine.begin("打一把", "com.game", 10)!!
        s = TeachMachine.addStep(s, "com.game", listOf("开始"), null, "ocr", 20)
        s = TeachMachine.addStep(s, "com.game", listOf("准备"), "点中间", "a11y", 30)
        assertEquals(2, s.steps.size)
        val done = TeachMachine.finish(s)!!
        assertTrue(TeachMachine.mergeTrace(done).contains("示范轨迹"))
        assertEquals("com.game", TeachMachine.dominantPkg(done.steps))
    }

    @Test fun mixedDictateAndDemo() {
        var s = TeachMachine.begin("混合", null, 1)!!
        s = TeachMachine.appendDictate(s, "进入设置")
        s = TeachMachine.addStep(s, "com.settings", listOf("蓝牙"), "点击「蓝牙」", "click", 2)
        val t = TeachMachine.mergeTrace(TeachMachine.finish(s)!!)
        assertTrue(t.contains("口述：") && t.contains("示范轨迹") && t.contains("进入设置") && t.contains("蓝牙"))
    }

    @Test fun finishEmptyRejected() {
        val s = TeachMachine.begin("空的目标", null, 1)!!
        assertNull(TeachMachine.finish(s))
    }

    @Test fun cancelClears() {
        var s = TeachMachine.begin("x任务", null, 1)!!
        s = TeachMachine.appendDictate(s, "一步")
        s = TeachMachine.cancel(s)
        assertEquals(TeachMachine.Phase.Idle, s.phase)
        assertTrue(s.empty())
    }

    @Test fun persistRoundTrip() {
        var s = TeachMachine.begin("持久化目标", "com.a", 100)!!
        s = TeachMachine.appendDictate(s, "第一句")
        s = TeachMachine.addStep(s, "com.a", listOf("确定"), null, "click", 200)
        val raw = TeachMachine.encode(s)
        val d = TeachMachine.decode(raw)
        assertEquals(s.phase, d.phase)
        assertEquals(s.goal, d.goal)
        assertEquals(s.dictations, d.dictations)
        assertEquals(s.steps.size, d.steps.size)
        assertEquals(s.steps[0].labels, d.steps[0].labels)
        // ReadyToCompress survives
        val ready = TeachMachine.finish(s)!!
        assertEquals(TeachMachine.Phase.ReadyToCompress, TeachMachine.decode(TeachMachine.encode(ready)).phase)
    }

    @Test fun clickDebounce() {
        assertTrue(TeachMachine.shouldRecordClick(null, null, 0, "com.x", "发送", 1000))
        assertFalse(TeachMachine.shouldRecordClick("com.x", "发送", 1000, "com.x", "发送", 1500))
        assertTrue(TeachMachine.shouldRecordClick("com.x", "发送", 1000, "com.x", "发送", 2000))
        assertTrue(TeachMachine.shouldRecordClick("com.x", "发送", 1000, "com.x", "取消", 1100))
    }

    @Test fun appendIgnoredWhenIdle() {
        val s = TeachMachine.State()
        assertEquals(0, TeachMachine.appendDictate(s, "hi").dictations.size)
        assertEquals(0, TeachMachine.addStep(s, "com.x", listOf("a"), null, "a11y", 1).steps.size)
    }

    @Test fun slotsRoundTrip() {
        val r = Recipe("给{联系人}发{内容}", listOf("打开「{联系人}」", "点「发送」"), slots = listOf("联系人", "内容"))
        val d = Recipe.decode(r.encode())!!
        assertEquals(r.slots, d.slots)
    }

    @Test fun offlineStepsFromMergedTrace() {
        val t = Recipe.offlineSteps("口述：\n1. 打开设置\n2. 点蓝牙\n\n示范轨迹（1 步）：\n1. 包=com.x 可见[a11y]：确定")
        assertTrue(t.any { it.contains("打开设置") || it == "打开设置" })
        assertTrue(t.size >= 2)
    }

    @Test fun forceDeepSticky() {
        val st = ThinkMode.State()
        ThinkMode.forceDeep(st, "教授流程偏离")
        assertEquals(ThinkMode.Mode.DEEP, ThinkMode.decide(st, 3, 40, true).mode)
    }

    @Test fun statusLineActive() {
        val s = TeachMachine.begin("目标", null, 1)!!
        assertTrue(TeachMachine.statusLine(s).contains("上岗中"))
    }
}

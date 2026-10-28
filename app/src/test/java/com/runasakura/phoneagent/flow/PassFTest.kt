package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.agent.StepHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassFTest {
    @Test fun loadingScreensDoNotCountAsStuck() {
        val h = StepHistory()
        h.observe(listOf("x", "加载中"))
        repeat(6) { h.recordAction("Tap(e1)"); h.observe(listOf("x", "加载中")) }
        assertEquals(0, h.stuckCount); assertFalse(h.stuck); assertTrue(h.isLoadingNow)
    }
    @Test fun stopNeedsFourRepeats() {
        val h = StepHistory()
        h.observe(listOf("x"))
        repeat(3) { h.recordAction("Tap(e1)"); h.observe(listOf("x")) }
        assertFalse(h.stuck)
        h.recordAction("Tap(e1)"); h.observe(listOf("x"))
        assertTrue(h.stuck)
    }
    @Test fun ellipsisPreviewIsNotLoading() {
        assertFalse(StepHistory.isLoading(listOf("张三：明天见…", "文件传输助手")))
        assertTrue(StepHistory.isLoading(listOf("正在加载")))
        assertFalse(StepHistory.isLoading(listOf("这是一条很长的消息内容提到了加载中这个词哦")))
    }
}

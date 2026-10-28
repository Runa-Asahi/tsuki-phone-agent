package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.ui.UiLog
import org.junit.Assert.assertEquals
import org.junit.Test

class PassLTest {
    @Test fun uiLogIsBoundedAndKeepsNewest() {
        UiLog.clear()
        repeat(UiLog.CAP + 50) { UiLog.add("line $it") }
        val s = UiLog.snapshot()
        assertEquals(UiLog.CAP, s.size)
        assertEquals("line ${UiLog.CAP + 49}", s.last())
        assertEquals("line 50", s.first())
        UiLog.clear()
    }
    @Test fun logKinds() {
        assertEquals(UiLog.Kind.STEP, UiLog.kind("—— 步骤 1/20 ——"))
        assertEquals(UiLog.Kind.OK, UiLog.kind("✓ 聊天标题已核对：张三"))
        assertEquals(UiLog.Kind.ERR, UiLog.kind("✗ 未找到「设置」"))
        assertEquals(UiLog.Kind.ERR, UiLog.kind("无障碍服务已断开，任务已停止"))
        assertEquals(UiLog.Kind.WARN, UiLog.kind("提示：若任务中微信被杀"))
        assertEquals(UiLog.Kind.NORMAL, UiLog.kind("前台=com.tencent.mm 来源=ocr"))
    }
}

package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.agent.SettingsPages
import com.runasakura.phoneagent.llm.LlmRouter
import org.junit.Assert.*
import org.junit.Test

class PassUTest {
    @Test fun settingsPagesOpenOnly() {
        assertEquals("蓝牙", SettingsPages.match("打开蓝牙设置")?.label)
        assertEquals("WLAN", SettingsPages.match("打开WLAN设置")?.label)
        assertEquals("电池", SettingsPages.match("查看电池")?.label)
        assertNull(SettingsPages.match("关闭蓝牙"))
        assertNull(SettingsPages.match("把亮度调到50%"))
        assertNull(SettingsPages.match("打开微信给王小明发你好"))
    }
    @Test fun tokenEstimate() {
        assertEquals(2, LlmRouter.approxTokens("你好"))
        assertEquals(2, LlmRouter.approxTokens("abcdefgh"))
    }
}

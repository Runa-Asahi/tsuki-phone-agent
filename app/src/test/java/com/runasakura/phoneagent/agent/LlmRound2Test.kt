package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.llm.CloudProviders
import org.junit.Assert.*
import org.junit.Test

class LlmRound2Test {
    @Test fun scratchpadFieldsParsed() {
        val p = ActionParser.parse("""{"state":"活动弹窗","subgoal":"关闭弹窗","thought":"点好的","action":{"type":"tap","id":"e2"},"expect":"回到大厅","memory":"今日不再弹出是复选框"}""")
        assertEquals("回到大厅", p.expect); assertEquals("今日不再弹出是复选框", p.memory); assertEquals("关闭弹窗", p.subgoal)
    }
    @Test fun taskMemoryDedupes() {
        val m = TaskMemory(3)
        assertTrue(m.add("今日不再弹出是复选框")); assertFalse(m.add("今日不再弹出是复选框。"))
        m.add("返回键会弹出退出确认"); m.add("开始按钮在右下"); m.add("宝石争霸在模式列表")
        assertEquals(3, m.all().size); assertFalse(m.all().contains("今日不再弹出是复选框"))
    }
    @Test fun escalationModel() {
        assertEquals("deepseek-v4-pro", CloudProviders.strongModelFor("https://api.deepseek.com", "deepseek-v4-flash"))
        assertNull(CloudProviders.strongModelFor("https://api.deepseek.com", "deepseek-v4-pro"))
        assertNull(CloudProviders.strongModelFor("https://example.com/v1", "foo"))
    }
}

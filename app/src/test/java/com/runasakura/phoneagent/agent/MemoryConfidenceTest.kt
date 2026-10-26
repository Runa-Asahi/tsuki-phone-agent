package com.runasakura.phoneagent.agent

import org.junit.Assert.*
import org.junit.Test

class MemoryConfidenceTest {
    private val day = 24L * 3600 * 1000
    private val g = "打开网易云音乐并播放钢之炼金术师主题曲"

    @Test fun routeNeedsTwoConfirmationsAndFailureDropsIt() {
        var m = AppMemory.EMPTY.merge(emptyList(), g, "跳过 → 搜索 → 输入 → 点单曲", now = 1000)
        assertNull(m.knownPath(g, 1000))
        m = m.merge(emptyList(), g, "跳过 → 搜索 → 输入 → 点单曲", now = 2000)
        assertNotNull(m.knownPath(g, 2000))
        assertTrue(m.hint(g, 2000)!!.contains("已验证2次"))
        m = m.penalize(g, 3000)
        assertNull("one failure after reuse removes a 2-confirmed route", m.knownPath(g, 3000))
        assertTrue(m.pathEntries.isEmpty())
    }

    @Test fun entriesExpire() {
        val m = AppMemory.EMPTY.merge(listOf("搜索入口在顶部"), g, "a → b", now = 0L + 1)
        assertNull(m.hint(g, 1 + 31 * day))
        assertNotNull(m.hint(g, 1 + 5 * day))
    }

    @Test fun legacyLinesDecodeAndRoundTrip() {
        val m = AppMemory.decode("F:旧事实\nP:旧目标=>x → y")
        assertEquals(listOf("旧事实"), m.facts)
        val r = AppMemory.decode(m.merge(listOf("旧事实"), null, null, now = 5).encode())
        assertEquals(2, r.factEntries.single().conf)
    }
}

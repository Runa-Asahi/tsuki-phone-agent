package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.llm.LlmRouter
import com.runasakura.phoneagent.llm.ModelSources
import com.runasakura.phoneagent.llm.VisionChoice
import com.runasakura.phoneagent.prefs.AgentPreferences
import org.junit.Assert.*
import org.junit.Test

/** pt79: embedded-first vision, model import parsing, AI-assisted learning. */
class Pt79Test {
    @Test fun visionPrefersEmbedded() {
        assertEquals(VisionChoice.Source.EMBEDDED, VisionChoice.pick(AgentPreferences.ROUTE_CLOUD_ASSIST, true, true, true))
        assertEquals(VisionChoice.Source.MNN_CHAT, VisionChoice.pick(AgentPreferences.ROUTE_CLOUD_ASSIST, false, true, true))
        assertEquals(VisionChoice.Source.MNN_CHAT, VisionChoice.pick(AgentPreferences.ROUTE_HYBRID, true, false, true))
        assertEquals(VisionChoice.Source.NONE, VisionChoice.pick(AgentPreferences.ROUTE_HYBRID, false, true, false))
        assertEquals(VisionChoice.Source.NONE, VisionChoice.pick(AgentPreferences.ROUTE_CLOUD_ONLY, true, true, true))
    }

    @Test fun visionDescribeHonest() {
        val d = VisionChoice.describe(false, "deepseek-chat", true, VisionChoice.Source.EMBEDDED, "Qwen2.5-VL-3B")
        assertTrue(d, d.contains("Tsuki 内置") && d.contains("Qwen2.5-VL-3B"))
        assertTrue(VisionChoice.describe(false, "x", true, VisionChoice.Source.NONE, null).contains("OCR"))
        assertTrue(VisionChoice.describe(true, "gpt", false, VisionChoice.Source.NONE, null).contains("未允许"))
    }

    @Test fun shellScanParsing() {
        val find = "/data/local/tmp/mnn_models/Qwen/llm.mnn\n/sdcard/Download/a b/llm.mnn\nfind: permission denied\n/data/local/tmp/mnn_models/Qwen/llm.mnn\n"
        assertEquals(listOf("/data/local/tmp/mnn_models/Qwen", "/sdcard/Download/a b"), ModelSources.dirsFromFind(find))
        val stat = "120 /sdcard/Download/a b/config.json\n9000 /sdcard/Download/a b/llm.mnn\n5 /sdcard/Download/a b/sub/x\nstat: cannot\n0 /sdcard/Download/a b/*"
        val files = ModelSources.filesFromStat(stat, "/sdcard/Download/a b")
        assertEquals(listOf("config.json" to 120L, "llm.mnn" to 9000L), files)
        assertTrue(ModelSources.looksComplete(files.map { it.first }))
        assertFalse(ModelSources.looksComplete(listOf("config.json", "llm.mnn", "llm.mnn.weight.part")))
        assertFalse(ModelSources.looksComplete(listOf("llm.mnn")))
    }

    @Test fun parseConsolidationPlan() {
        val p = LlmRouter.parseConsolidation("好的 {\"merged\":[{\"text\":\"搜索框在顶部\",\"from\":[0,2]},{\"text\":\"\",\"from\":[1]}]}")
        assertNotNull(p); assertEquals("搜索框在顶部", p!![0].first); assertEquals(listOf(0, 2), p[0].second)
        assertNull(LlmRouter.parseConsolidation("没有 json"))
    }

    private val now = 1_800_000_000_000L
    private fun mem(vararg f: Pair<String, Int>) = AppMemory(f.map { AppMemory.Entry(it.first, it.second, now - 1000) }, emptyList())

    @Test fun consolidateKeepsEvidence() {
        val m = mem("点右上角放大镜搜索" to 3, "发送按钮在输入框右侧" to 1, "搜索入口在右上角" to 1)
        val c = m.consolidate(listOf("搜索入口在右上角放大镜" to listOf(0, 2, 9)), now)
        assertEquals(2, c.factEntries.size)
        val merged = c.factEntries.first { it.text.startsWith("搜索入口") }
        assertEquals(3, merged.conf)
        assertTrue(c.facts.contains("发送按钮在输入框右侧"))
        // Empty / bogus plans change nothing.
        assertEquals(m.facts.toSet(), m.consolidate(listOf("x" to listOf(0), "好的文本" to listOf(42)), now).facts.toSet())
    }

    @Test fun confirmShownOnlyRelevantAndCapped() {
        val m = mem("在微信里点右上角加号发起群聊" to 5, "设置页底部有退出登录" to 1)
        val c = m.confirmShown("微信发起群聊", now)
        val g = c.factEntries.first { it.text.contains("群聊") }
        assertTrue(g.conf in 6..AppMemory.MAX_CONF)
        assertEquals(1, c.factEntries.first { it.text.contains("退出") }.conf)
        assertTrue(c.confirmShown("微信发起群聊", now).factEntries.first { it.text.contains("群聊") }.conf <= AppMemory.MAX_CONF)
    }

    @Test fun removeAndPatterns() {
        val m = mem("列表页下拉可刷新" to 2, "弹窗先点关闭再操作" to 1)
        assertEquals(listOf("弹窗先点关闭再操作"), m.remove('F', "列表页下拉可刷新").facts)
        val p = m.patternsFor("关闭弹窗后继续", now, max = 1)!!
        assertTrue(p, p.startsWith("弹窗先点关闭再操作") && p.contains("未验证"))
        assertNull(AppMemory(emptyList(), emptyList()).patternsFor("x", now))
    }
}

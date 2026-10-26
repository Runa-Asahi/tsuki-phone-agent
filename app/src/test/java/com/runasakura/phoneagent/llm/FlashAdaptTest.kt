package com.runasakura.phoneagent.llm

import com.runasakura.phoneagent.agent.ActionParser
import com.runasakura.phoneagent.agent.SystemPrompt
import com.runasakura.phoneagent.model.AgentAction
import org.junit.Assert.*
import org.junit.Test

class FlashAdaptTest {
    private val msgs = listOf(ChatMessage("system", SystemPrompt.SHORT), ChatMessage("user", "x"))

    @Test fun nonThinkingBody() {
        val b = CloudClient("https://api.deepseek.com", "k", "deepseek-flash").buildBody(msgs, 0.0)
        assertEquals("disabled", b.getJSONObject("thinking").getString("type"))
        assertEquals("json_object", b.getJSONObject("response_format").getString("type"))
        assertTrue(b.has("temperature")); assertFalse(b.has("reasoning_effort"))
    }

    @Test fun thinkingBodyIsUserToggle() {
        val b = CloudClient("https://api.deepseek.com", "k", "deepseek-flash", thinking = true).buildBody(msgs, 0.0)
        assertEquals("enabled", b.getJSONObject("thinking").getString("type"))
        assertEquals("low", b.getString("reasoning_effort"))
        assertFalse(b.has("temperature")); assertEquals(2000, b.getInt("max_tokens"))
    }

    @Test fun emptyContentFallsBackToReasoningJson() {
        val raw = """{"choices":[{"message":{"content":"","reasoning_content":"想一想 {\"a\":1} 最终 {\"thought\":\"t\",\"action\":{\"type\":\"back\"}}"}}]}"""
        val c = CloudClient.contentOrReasoningJson(raw)
        assertTrue(ActionParser.parse(c).action is AgentAction.Back)
    }

    @Test fun promptStableAndGenericAndMentionsJson() {
        val p = SystemPrompt.SHORT
        assertSame(p, SystemPrompt.SHORT); assertTrue(p.contains("json"))
        assertFalse(Regex("\\d{1,2}:\\d{2}|步骤:").containsMatchIn(p))
        listOf("微信", "荒野", "宝石", "英雄", "对战").forEach { assertFalse("no app-specific word: $it", p.contains(it)) }
        assertTrue(p.length < 1800)
    }

    @Test fun malformedJsonStillParsed() {
        val p = ActionParser.parse("""好的 {"screen":"列表","goal_match":"否","action":{"type":"tap","node_id":"e3"},"expect":"进入"} 多余""")
        assertTrue(p.action is AgentAction.Tap); assertEquals("进入", p.expect)
    }
}

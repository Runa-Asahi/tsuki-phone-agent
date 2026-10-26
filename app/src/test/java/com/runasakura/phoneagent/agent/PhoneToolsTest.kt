package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.llm.CloudClient
import com.runasakura.phoneagent.model.AgentAction
import org.junit.Assert.*
import org.junit.Test

class PhoneToolsTest {
    private fun act(json: String) = ActionParser.parse("""{"goal_match":"否","action":$json}""").action

    @Test fun parsesSystemTools() {
        assertEquals(AgentAction.Global("notifications"), act("""{"type":"notifications"}"""))
        assertEquals(AgentAction.Global("quick_settings"), act("""{"type":"quick_settings"}"""))
        assertEquals(AgentAction.Key("enter"), act("""{"type":"key","key":"enter"}"""))
        assertEquals(AgentAction.Intent("android.settings.WIFI_SETTINGS", null), act("""{"type":"intent","action":"android.settings.WIFI_SETTINGS"}"""))
        assertTrue(act("""{"type":"open_url","url":"https://example.com"}""") is AgentAction.OpenUri)
        assertEquals(AgentAction.SetClipboard("abc"), act("""{"type":"set_clipboard","text":"abc"}"""))
        assertEquals(AgentAction.ReadClipboard, act("""{"type":"read_clipboard"}"""))
        assertEquals("设置", (act("""{"type":"scroll_find","text":"设置"}""") as AgentAction.ScrollFind).text)
    }

    @Test fun confirmPolicyForLinksAndIntents() {
        listOf("tel:10086", "sms:123", "mailto:a@b.c", "alipays://x", "weixin://x", "upi://pay").forEach { assertNotNull(it, PhoneTools.uriNeedsConfirm(it)) }
        assertNull(PhoneTools.uriNeedsConfirm("https://example.com"))
        assertNotNull(PhoneTools.intentNeedsConfirm("android.intent.action.CALL"))
        assertNotNull(PhoneTools.intentNeedsConfirm("android.intent.action.SENDTO"))
        assertNotNull(PhoneTools.intentNeedsConfirm("android.intent.action.UNINSTALL_PACKAGE"))
        assertTrue(PhoneTools.intentAllowedWithoutConfirm("android.settings.WIFI_SETTINGS"))
        assertFalse(PhoneTools.intentAllowedWithoutConfirm("com.example.CUSTOM"))
    }

    @Test fun repeatReminderIsAdvisoryAt3_5_8() {
        val r = RepeatReminder(); val a = AgentAction.Back
        val out = (1..9).map { r.observe(a) }
        assertEquals(listOf(3, 5, 8), out.mapIndexedNotNull { i, s -> if (s != null) i + 1 else null })
        assertNull(r.observe(AgentAction.Home))
    }

    @Test fun truncationDetectedAndGoalYes() {
        assertEquals("length", CloudClient.finishReason("""{"choices":[{"finish_reason":"length","message":{"content":"{\"a\""}}]}"""))
        assertEquals("stop", CloudClient.finishReason("""{"choices":[{"finish_reason":"stop"}]}"""))
        assertTrue(GoalMatch.saysYes("是：已在播放")); assertFalse(GoalMatch.saysYes("部分"))
    }
}

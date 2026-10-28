package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.a11y.StableFrames
import com.runasakura.phoneagent.model.AgentAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AuditFixesTest {
    @Test fun viewIntentWithDialDataNeedsConfirm() {
        assertNotNull(PhoneTools.intentConfirm("android.intent.action.VIEW", "tel:10086"))
        assertNotNull(PhoneTools.intentConfirm("android.intent.action.VIEW", "smsto:123"))
        assertNull(PhoneTools.intentConfirm("android.intent.action.VIEW", "https://example.com"))
        assertNull(PhoneTools.intentConfirm("android.settings.APPLICATION_DETAILS_SETTINGS", "package:com.example"))
        assertNotNull(PhoneTools.intentConfirm("com.example.CUSTOM", null))
        assertNotNull(PhoneTools.uriNeedsConfirm("intent://x#Intent;action=android.intent.action.CALL;end"))
        assertNotNull(PhoneTools.uriNeedsConfirm("javascript:alert(1)"))
        assertNotNull(PhoneTools.uriNeedsConfirm("  TEL:10086"))
    }

    @Test fun validatorRejectsMalformedToolArgs() {
        fun v(a: AgentAction) = ActionValidator.validate(a, emptyList(), 1080, 2400)
        assertNotNull(v(AgentAction.Global("open_everything")))
        assertNull(v(AgentAction.Global("notifications")))
        assertNotNull(v(AgentAction.OpenUri("")))
        assertNotNull(v(AgentAction.OpenUri("www.example.com")))
        assertNull(v(AgentAction.OpenUri("https://example.com")))
        assertNotNull(v(AgentAction.Intent("WIFI")))
        assertNotNull(v(AgentAction.Key("power")))
        assertNotNull(v(AgentAction.ScrollFind("")))
        assertNotNull(v(AgentAction.ScrollFind("设置", "sideways")))
        assertNotNull(v(AgentAction.SetClipboard(" ")))
    }

    @Test fun parserNormalisesCase() {
        val a = ActionParser.parse("""{"action":{"type":"scroll_find","text":"蓝牙","direction":"UP"}}""").action as AgentAction.ScrollFind
        assertEquals("up", a.direction)
        assertEquals(AgentAction.Global("quick_settings"), ActionParser.parse("""{"action":{"type":"global","name":"Quick_Settings"}}""").action)
    }

    @Test fun uiDescribesNewTools() {
        assertEquals("下拉通知栏", ActionDesc.of(AgentAction.Global("notifications")))
        assertEquals("键盘回车/搜索", ActionDesc.of(AgentAction.Key("enter")))
        assertTrue(com.runasakura.phoneagent.ui.ActionText.describe(AgentAction.ScrollFind("蓝牙").toString()).startsWith("滚动查找"))
        assertEquals("打开快捷设置", com.runasakura.phoneagent.ui.ActionText.describe(AgentAction.Global("quick_settings").toString()))
    }

    @Test fun repeatReminderUsesIdFreeKeys() {
        val r = RepeatReminder()
        // Same target with different per-step ids → same ActionDesc → counted as repeats.
        val keys = (1..3).map { ActionDesc.of(AgentAction.Tap(nodeId = "e$it"), "下一步") }
        assertNull(r.observe(keys[0])); assertNull(r.observe(keys[1])); assertNotNull(r.observe(keys[2]))
    }

    @Test fun twoFrameStabilityStopsAtFirstAgreement() = runBlocking {
        var reads = 0
        val frames = listOf("a", "b", "b", "c")
        val (f, stable) = StableFrames.confirm("a", 2, pause = {}, read = { frames[++reads] }) { x, y -> x == y }
        assertTrue(stable); assertEquals("b", f); assertEquals(2, reads)
        reads = 0
        val (_, s2) = StableFrames.confirm("x", 2, pause = {}, read = { "y${++reads}" }) { x, y -> x == y }
        assertFalse(s2); assertEquals(2, reads)
    }

    @Test fun recoveryLadder() {
        assertNull(Recovery.forLoop(2, "pkg"))
        assertEquals(Recovery.Step.BACK_OUT, Recovery.forLoop(3, null))
        assertEquals(Recovery.Step.RELAUNCH, Recovery.forLoop(4, "pkg"))
        assertNull(Recovery.forLoop(4, null))
    }
}

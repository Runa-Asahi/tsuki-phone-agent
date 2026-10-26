package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.flow.Device
import com.runasakura.phoneagent.flow.Screen
import com.runasakura.phoneagent.flow.StopGuardDevice
import com.runasakura.phoneagent.flow.StopRequested
import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.model.UiSnapshot
import com.runasakura.phoneagent.prefs.MemorySettings
import com.runasakura.phoneagent.screen.ScreenItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Pt55Test {
    @Test fun formTextStopsBeforeThenSubmit() {
        val s = GoalSplitter.split("打开反馈，填写内容：界面很好用，然后提交")
        val t = s.filterIsInstance<Subgoal.Send>().single()
        assertEquals("界面很好用", t.text); assertFalse(t.send)
        assertTrue(s.toString(), s.last() !is Subgoal.Send)
    }
    @Test fun urlIsNotMistakenForAFieldLabel() {
        assertEquals("https://a.com/x", GoalSplitter.split("打开浏览器，输入https://a.com/x").filterIsInstance<Subgoal.Send>().single().text)
    }
    @Test fun chatMessageKeepsCommasVerbatim() {
        val t = GoalSplitter.split("打开张三，发送：好的，然后去吃饭").filterIsInstance<Subgoal.Send>().single()
        assertEquals("好的，然后去吃饭", t.text)
    }
    @Test fun askUserParsedAndValidated() {
        val a = ActionParser.parse("""{"action":{"type":"ask_user","question":"发给谁？"}}""").action
        assertEquals(AgentAction.AskUser("发给谁？"), a)
        assertNotNull(ActionValidator.validate(AgentAction.AskUser(""), emptyList(), 1080, 2400))
    }
    @Test fun alarmSaveNeedsConfirmOnlyWhenEnabled() {
        val snap = UiSnapshot("p", screenWidth = 1080, screenHeight = 2400, tree = null, compressedText = "[e3] 保存 (980,150)", nodeCount = 1)
        val on = SafetyGate(MemorySettings(confirmAlarm = true)).check(AgentAction.Tap(nodeId = "e3"), snap, "设一个7点的闹钟")
        assertEquals(SafetyDecision.REQUIRE_CONFIRM, on.decision)
        val off = SafetyGate(MemorySettings(confirmAlarm = false)).check(AgentAction.Tap(nodeId = "e3"), snap, "设一个7点的闹钟")
        assertEquals(SafetyDecision.ALLOW, off.decision)
        assertEquals(SafetyDecision.ALLOW, SafetyGate(MemorySettings()).check(AgentAction.Tap(nodeId = "e3"), snap, "写一条笔记并保存").decision)
    }
    @Test fun openingAnAppIsNotSensitive() {
        assertFalse(SafetyGate(MemorySettings()).goalNeedsConfirm("打开通讯录，找到王五"))
        assertTrue(SafetyGate(MemorySettings()).goalNeedsConfirm("开通会员"))
    }
    @Test fun stopGuardThrowsOnGesture() = runBlocking {
        var stop = false; var taps = 0
        val inner = object : Device {
            override suspend fun screen() = Screen("p", 1, 1, emptyList(), "tree")
            override suspend fun tap(item: ScreenItem): Boolean { taps++; return true }
            override suspend fun tapXY(x: Int, y: Int) = true
            override suspend fun longPress(item: ScreenItem) = true
            override suspend fun type(text: String, hintX: Int?, hintY: Int?, attempt: Int) = true
            override suspend fun back() {}
            override suspend fun home() {}
            override suspend fun afterAction(maxMs: Long) {}
            override suspend fun scroll(forward: Boolean) {}
            override suspend fun bringToFront(pkg: String, label: String, force: Boolean) = true
            override suspend fun sleep(ms: Long) {}
            override suspend fun invalidateObservation() {}
        }
        val d = StopGuardDevice(inner) { stop }
        val item = ScreenItem("e1", "x", 1, 1, true, false, false, null)
        d.tap(item); stop = true
        try { d.tap(item); fail() } catch (_: StopRequested) {}
        assertEquals(1, taps)
    }

    @Test fun toolIconsByActionTitle() {
        val k = com.runasakura.phoneagent.ui.ToolIcon
        assertEquals(com.runasakura.phoneagent.ui.ToolIcon.Kind.TAP, k.of("第 1 步 · 点击「搜索」"))
        assertEquals(com.runasakura.phoneagent.ui.ToolIcon.Kind.TYPE, k.of("第 2 步 · 输入「晴天」"))
        assertEquals(com.runasakura.phoneagent.ui.ToolIcon.Kind.SEARCH, k.of("第 3 步 · 滚动查找「王五」"))
        assertEquals(com.runasakura.phoneagent.ui.ToolIcon.Kind.ASK, k.of("第 1 步 · 问你：发给谁？"))
        assertEquals(com.runasakura.phoneagent.ui.ToolIcon.Kind.THINK, k.of("第 4 步"))
    }
    @Test fun askResultIsNotAFailedStepAndReplyMergesIntoGoal() {
        val cs = com.runasakura.phoneagent.ui.ChatSession
        cs.clear(); cs.startTask("给他发个消息", 0)
        cs.onLog("—— 步骤 1/25 ——", 1); cs.onLog("action: AskUser(question=发给谁？)", 2)
        cs.onStatus("stopped", "结束(1步): ${cs.ASK_MARK}发给谁？", 3)
        val items = cs.snapshot()
        assertEquals(com.runasakura.phoneagent.ui.ChatSession.State.OK, (items.first { it is com.runasakura.phoneagent.ui.ChatSession.Item.Step } as com.runasakura.phoneagent.ui.ChatSession.Item.Step).state)
        val r = items.last() as com.runasakura.phoneagent.ui.ChatSession.Item.Result
        assertTrue(r.ask); assertEquals("发给谁？", r.summary)
        assertEquals("给他发个消息（补充：张三）", cs.composeGoal("张三"))
        cs.startTask("给他发个消息（补充：张三）", 4)
        assertEquals("打开蓝牙", cs.composeGoal("打开蓝牙"))
        cs.clear()
    }
}

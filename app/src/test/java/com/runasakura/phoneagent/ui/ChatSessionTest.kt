package com.runasakura.phoneagent.ui

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ChatSessionTest {
    @Before fun reset() = ChatSession.clear()

    @Test fun buildsStepCardsFromLog() {
        val c = ChatSession
        c.startTask("打开图库第一张照片", 0)
        c.onLog("意图：app 应用=图库（模型）", 10)
        c.onLog("匹配应用：图库 (com.hihonor.photos) score=50", 20)
        c.onLog("[观察] 包=x", 25)
        c.onLog("—— 步骤 1/25 ——", 100)
        c.onLog("thought: 点第一张", 150)
        c.onLog("action: Tap(x=158, y=560, nodeId=null, text=null)", 160)
        c.onStatus("done", "结束(2步): 已打开第一张照片", 900)
        val s = c.snapshot()
        assertTrue(s[0] is ChatSession.Item.User)
        assertTrue((s[1] as ChatSession.Item.Plan).lines.first().startsWith("意图"))
        val steps = s.filterIsInstance<ChatSession.Item.Step>()
        assertEquals(2, steps.size)
        assertTrue(steps[1].title, steps[1].title.contains("点击(158,560)"))
        assertTrue(steps.all { it.state == ChatSession.State.OK })
        assertEquals(800, steps[1].durationMs)
        assertTrue(steps[1].details.none { it.startsWith("[观察]") })
        val r = s.last() as ChatSession.Item.Result
        assertTrue(r.ok); assertEquals("已打开第一张照片", r.summary)
        assertEquals(ChatSession.Phase.IDLE, c.phase)
    }

    @Test fun confirmCardAndPhase() {
        ChatSession.startTask("给王泓凯发你好", 0)
        ChatSession.onStatus("confirm-overlay", "发送确认\n收件人：王泓凯", 5)
        assertEquals(ChatSession.Phase.CONFIRM, ChatSession.phase)
        ChatSession.onLog("用户确认", 6)
        assertEquals(true, (ChatSession.snapshot().last { it is ChatSession.Item.Confirm } as ChatSession.Item.Confirm).answer)
    }

    @Test fun failureGetsFriendlyHintAndFollowUpContext() {
        ChatSession.startTask("打开微信给王小明发你好", 0)
        ChatSession.onStatus("stopped", "结束(1步): 搜索结果中没有与「王小明」完全一致的联系人", 10)
        val r = ChatSession.snapshot().last() as ChatSession.Item.Result
        assertFalse(r.ok); assertTrue(r.hint!!.contains("完整备注名"))
        assertTrue(ChatSession.followUpContext()!!.contains("王小明"))
        ChatSession.startTask("用备注名12345678王小明再试", 20)
        assertTrue((ChatSession.snapshot().last { it is ChatSession.Item.User } as ChatSession.Item.User).followUp)
    }

    @Test fun actionText() {
        assertEquals("输入「你好」", ActionText.describe("Type(text=你好, nodeId=null)"))
        assertEquals("返回", ActionText.describe("Back"))
    }
}

class FriendlyAndHistoryTest {
    @Test fun friendly() {
        assertTrue(FriendlyErrors.explain("任务超时（8 分钟），已停止").contains("重试"))
        assertTrue(FriendlyErrors.explain("无障碍服务未开启").contains("辅助功能"))
        assertTrue(FriendlyErrors.explain("奇怪的错误").isNotBlank())
    }
    @Test fun duplicateEndIgnored() {
        ChatSession.clear(); ChatSession.startTask("x", 0)
        assertTrue(ChatSession.onStatus("stopped", "急停", 1))
        assertFalse(ChatSession.onStatus("stopped", "结束(0步): 已取消", 2))
        assertEquals(1, ChatSession.snapshot().count { it is ChatSession.Item.Result })
    }
}

class TaskHistoryTest {
    @Test fun roundTripAndCap() {
        var l = emptyList<TaskHistory.Entry>()
        repeat(60) { l = TaskHistory.prepend(l, TaskHistory.Entry("任务$it", it % 2 == 0, "s\"$it", it.toLong())) }
        val back = TaskHistory.decode(TaskHistory.encode(l))
        assertEquals(TaskHistory.MAX, back.size)
        assertEquals("任务59", back.first().goal); assertEquals("s\"59", back.first().summary)
        assertTrue(TaskHistory.decode("garbage").isEmpty())
    }
}

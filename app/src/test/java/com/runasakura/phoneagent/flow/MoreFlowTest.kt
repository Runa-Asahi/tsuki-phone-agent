package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.agent.GoalSplitter
import com.runasakura.phoneagent.agent.Subgoal
import com.runasakura.phoneagent.agent.SubgoalExecutor
import com.runasakura.phoneagent.screen.RawNode
import com.runasakura.phoneagent.screen.ScreenItems
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoreFlowTest {
    @Test fun splitterKeepsMessageIntact() {
        assertEquals(
            listOf(Subgoal.Open("我的电脑"), Subgoal.Send("你好，在吗", true)),
            GoalSplitter.split("进入我的电脑，发送你好，在吗")
        )
        assertEquals(listOf(Subgoal.Send("再见", true)), GoalSplitter.split("发送再见"))
        assertEquals(
            listOf(Subgoal.Open("设置"), Subgoal.Open("电池")),
            GoalSplitter.split("进入设置然后点击电池")
        )
        assertEquals(listOf(Subgoal.Open("合并转发")), GoalSplitter.split("点击合并转发"))
        assertEquals(
            listOf(Subgoal.Open("我的电脑"), Subgoal.Send("你好", false)),
            GoalSplitter.split("进入我的电脑再输入你好")
        )
    }

    @Test fun leavesKeepRowTextsSeparate() {
        val row = RawNode(l = 0, t = 400, r = 1264, b = 600, clickable = true, children = listOf(
            RawNode(text = "王小明", l = 200, t = 420, r = 500, b = 480),
            RawNode(text = "昨天", l = 1100, t = 420, r = 1250, b = 480),
            RawNode(text = "删除", l = 1300, t = 400, r = 1500, b = 600) // offscreen swipe menu
        ))
        val items = ScreenItems.leaves(listOf(RawNode(l = 0, t = 0, r = 1264, b = 2800, children = listOf(row))), 1264, 2800)
        assertEquals(listOf("王小明", "昨天"), items.map { it.label })
        assertTrue(items.all { it.tap }) // clickable ancestor
    }

    @Test fun executorOpensExactItem() = runBlocking {
        val d = FakeWeChat(listRows = listOf("文件传输助手2", "文件传输助手"))
        val ex = SubgoalExecutor(d, {}, { true })
        assertTrue(ex.open("文件传输助手"))
        assertEquals("chat:文件传输助手", d.state)
    }

    @Test fun executorSendNeedsConfirm() = runBlocking {
        val d = FakeWeChat(state = "chat:我的电脑")
        val ex = SubgoalExecutor(d, {}, { false })
        assertEquals(SubgoalExecutor.Status.CANCELLED, ex.send("你好", true))
        assertTrue(d.sent.isEmpty())
    }

    @Test fun qqMyComputerViaSearchBar() = runBlocking {
        val d = FakeWeChat(
            tabs = listOf("消息", "联系人", "动态"), pkg = "com.tencent.mobileqq",
            listRows = listOf("某群"), searchLabel = "搜索",
            resultRows = listOf("功能", "我的电脑", "聊天记录", "我的电脑")
        )
        val logs = mutableListOf<String>()
        val r = MessagingFlow(d, AppProfile.QQ, { logs += it }, { true }).run("我的电脑", "你好")
        assertTrue(logs.joinToString("\n"), r.ok)
        assertEquals(listOf("chat:我的电脑" to "你好"), d.sent)
    }

    @Test fun emptyScreenFailsWithoutGuessing() = runBlocking {
        val d = object : Device by FakeWeChat() {
            override suspend fun screen() = Screen("com.tencent.mm", 1264, 2800, emptyList(), "none")
        }
        val r = MessagingFlow(d, AppProfile.WECHAT, {}, { true }).run("王小明", "你好")
        assertFalse(r.ok)
    }
}

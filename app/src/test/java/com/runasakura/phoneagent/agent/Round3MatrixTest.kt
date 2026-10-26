package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.flow.AppProfile
import com.runasakura.phoneagent.flow.FakeWeChat
import com.runasakura.phoneagent.flow.MessagingFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Task matrix: which sub-skill each varied goal routes to (the model/verifier own the rest). */
class Round3MatrixTest {
    @Test fun galleryFirstPhotoGoesToModelWithRemainder() {
        val g = "打开图库第一张照片"
        assertNull(MessagingGoals.parse(g)); assertNull(CallGoals.parse(g)); assertNull(QuickIntents.toggle(g))
        assertEquals("第一张照片", AppResolver.remainderAfterApp(g, AppResolver.extractAppName(g)!!, "图库", "com.hihonor.photos"))
    }

    @Test fun settingsToggleIsNotJustOpeningPage() {
        assertEquals("蓝牙" to true, QuickIntents.toggle("打开蓝牙"))
        assertEquals("WLAN" to false, QuickIntents.toggle("关闭WLAN"))
        assertNull(QuickIntents.toggle("打开蓝牙设置"))
        assertNotNull(SettingsPages.match("打开蓝牙设置"))
    }

    @Test fun alarmIntent() {
        assertEquals(QuickIntents.Alarm(7, 0, "Phone Agent"), QuickIntents.alarm("设个明天早上7点的闹钟"))
        assertEquals(QuickIntents.Alarm(19, 30, "Phone Agent"), QuickIntents.alarm("帮我定一个晚上七点半的闹钟"))
        assertEquals(QuickIntents.Alarm(6, 15, "Phone Agent"), QuickIntents.alarm("设置6:15闹钟"))
        assertNull(QuickIntents.alarm("打开时钟"))
    }

    @Test fun readLastMessage() {
        assertEquals("王泓凯", QuickIntents.readLast("看看王泓凯最后一条消息"))
        assertEquals("王小明", QuickIntents.readLast("王小明发了什么"))
        assertNull(QuickIntents.readLast("给王小明发你好"))
    }

    @Test fun readLastFlow() = runBlocking {
        val d = FakeWeChat().apply { sent += "chat:王小明" to "明天见" }
        val out = MessagingFlow(d, AppProfile.WECHAT, {}, { true }).readLast("王小明")
        assertTrue(out.summary, out.ok); assertTrue(out.summary, out.summary.contains("明天见"))
        assertEquals(1, d.sent.size); assertTrue(d.calls.isEmpty())
    }

    @Test fun appSearchAndPlay() {
        val g = GoalRewrite.normalize("在B站搜索凉宫春日并播放")
        assertTrue(g, g.startsWith("打开B站，搜索"))
        val subs = GoalSplitter.split(AppResolver.remainingGoal(g, "B站")!!)
        assertTrue(subs.first() is Subgoal.Search)
    }

    @Test fun stickerAndTextAreDistinguished() {
        assertEquals("一个表情包", MessagingGoals.parse("打开微信给王泓凯发送一个表情包")!!.message)
        assertTrue(MessageContent.isSticker("一个表情包"))
        assertFalse(MessageContent.isSticker(MessagingGoals.parse("打开微信给💩发送奶龙NB")!!.message!!))
    }

    @Test fun safetyStillConfirmsSensitive() {
        assertTrue(ConfirmPolicy.isSensitive(com.runasakura.phoneagent.model.AgentAction.AskConfirm("即将视频通话")))
        assertFalse(ConfirmPolicy.isSensitive(com.runasakura.phoneagent.model.AgentAction.AskConfirm("确认点击搜索框")))
    }
}

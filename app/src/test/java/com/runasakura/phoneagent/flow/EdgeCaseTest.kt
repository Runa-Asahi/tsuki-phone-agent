package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.agent.ActionValidator
import com.runasakura.phoneagent.agent.MessagingGoals
import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.ocr.CropBands
import com.runasakura.phoneagent.ocr.OcrLine
import com.runasakura.phoneagent.ocr.OcrPost
import com.runasakura.phoneagent.screen.ScreenItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeCaseTest {
    private fun item(label: String, cx: Int, cy: Int, edit: Boolean = false) =
        ScreenItem("", label, cx, cy, true, edit, false, null)

    @Test fun emptyContactRefused() = runBlocking {
        assertEquals("联系人为空，拒绝发送", MessagingGoals.validate("", "你好"))
        assertEquals("消息内容为空，拒绝发送", MessagingGoals.validate("王小明", "  "))
        val d = FakeWeChat()
        val r = MessagingFlow(d, AppProfile.WECHAT, {}, { true }).run("  ", "你好")
        assertFalse(r.ok); assertTrue(r.summary.contains("空"))
        assertTrue(d.sent.isEmpty())
    }

    @Test fun multilineMessageParsedAndVerified() = runBlocking {
        assertEquals("你好\n在吗", MessagingGoals.parse("打开微信给王小明发送你好\n在吗")!!.message)
        val d = FakeWeChat(source = "ocr")
        val r = MessagingFlow(d, AppProfile.WECHAT, {}, { true }).run("王小明", "你好\n在吗")
        assertTrue(r.summary, r.ok)
        assertEquals("你好\n在吗", d.sent.single().second)
        assertTrue(r.verified)
    }

    @Test fun emojiMessageVerifiedByJoinedInput() {
        val send = item("发送", 1180, 2660)
        val s = Screen("com.tencent.mm", 1264, 2800, listOf(item("👋", 500, 2660), item("你好", 600, 2660), send), "ocr")
        assertTrue(InputVerify.inputContains(s, "👋你好", send))
    }

    @Test fun voiceModeSwitchedBeforeTyping() = runBlocking {
        val d = FakeWeChat(voiceMode = true)
        val r = MessagingFlow(d, AppProfile.WECHAT, {}, { true }).run("王小明", "你好")
        assertTrue(r.summary, r.ok)
        assertFalse(d.voiceMode)
        assertEquals(1, d.sent.size)
    }

    @Test fun fileTransferHelperExactMatch() = runBlocking {
        val d = FakeWeChat(
            listRows = listOf("张三"),
            resultRows = listOf("功能", "文件传输助手", "聊天记录", "文件传输助手")
        )
        val r = MessagingFlow(d, AppProfile.WECHAT, {}, { true }).run("文件传输助手", "ping")
        assertTrue(r.summary, r.ok)
        assertEquals("chat:文件传输助手", d.sent.single().first)
    }

    @Test fun preferContactOverGroupWithSameName() {
        val c1 = item("王小明", 400, 500)
        val c2 = item("王小明", 400, 900)
        val secs = mapOf(c1 to "联系人", c2 to "群聊")
        assertEquals(listOf(c1), InputVerify.preferContactSection(listOf(c1, c2), secs))
        // two contacts still ambiguous
        assertEquals(2, InputVerify.preferContactSection(listOf(c1, c2), mapOf(c1 to "联系人", c2 to "联系人")).size)
    }

    @Test fun groupTitleWithMemberCountMatches() {
        val flow = MessagingFlow(FakeWeChat(), AppProfile.WECHAT, {}, { true })
        val s = Screen("com.tencent.mm", 1264, 2800, listOf(item("周末聚餐(8)", 632, 180)), "ocr")
        assertTrue(flow.titleMatches(s, "周末聚餐"))
        assertFalse(flow.titleMatches(s, "周末"))
    }

    @Test fun landscapeRefused() = runBlocking {
        val d = object : Device by FakeWeChat() {
            override suspend fun screen() = Screen("com.tencent.mm", 2800, 1264, listOf(item("微信", 100, 100)), "ocr")
        }
        val r = MessagingFlow(d, AppProfile.WECHAT, {}, { true }).run("王小明", "你好")
        assertFalse(r.ok); assertTrue(r.summary.contains("横屏"))
    }

    @Test fun ppgAn00GestureNavCrop() {
        // 2800-tall screenshot, gesture nav → navBarPx=0 → ~2.8% bottom crop
        val (top, bottom) = CropBands.compute(2800, statusBarPx = 0, navBarPx = 0)
        assertEquals(89, top) // 2800*0.032
        assertEquals(2800 - 78, bottom) // 2800*0.028=78
        val soft = CropBands.compute(2800, 96, 168)
        assertEquals(96, soft.first)
        assertEquals(2800 - 168, soft.second)
    }

    @Test fun ocrMergesSmallTextFragments() {
        // Small/dark-mode OCR often splits a name into tight fragments
        val items = OcrPost.process(listOf(
            OcrLine("文", 200, 400, 230, 440),
            OcrLine("件", 232, 401, 262, 441),
            OcrLine("传", 264, 400, 294, 440),
            OcrLine("输", 296, 400, 326, 440),
            OcrLine("助", 328, 400, 358, 440),
            OcrLine("手", 360, 400, 390, 440)
        ), 0, 2800)
        assertEquals(listOf("文件传输助手"), items.map { it.label })
    }

    @Test fun validatorBlocksDirectSendTap() {
        val items = listOf(ScreenItem("e3", "发送", 1180, 2660, true, false, false, null))
        assertNotNull(ActionValidator.validate(AgentAction.Tap(nodeId = "e3"), items, 1264, 2800))
        assertNotNull(ActionValidator.validate(AgentAction.Tap(text = "确认支付"), items + ScreenItem("e4", "确认支付", 600, 2000, true, false, false, null), 1264, 2800))
        assertNull(ActionValidator.validate(AgentAction.AskConfirm("发"), items, 1264, 2800))
    }

    @Test fun qqMyComputerStillWorks() = runBlocking {
        val d = FakeWeChat(
            tabs = listOf("消息", "联系人", "动态"), pkg = "com.tencent.mobileqq",
            listRows = listOf("某群"), searchLabel = "搜索",
            resultRows = listOf("功能", "我的电脑")
        )
        val r = MessagingFlow(d, AppProfile.QQ, {}, { true }).run("我的电脑", "hello 👋")
        assertTrue(r.summary, r.ok)
    }
}

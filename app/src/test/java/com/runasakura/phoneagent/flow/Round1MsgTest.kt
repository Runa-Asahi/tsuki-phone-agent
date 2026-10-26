package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.agent.MessageContent
import com.runasakura.phoneagent.screen.ScreenItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Round1MsgTest {
    private fun it(l: String, x: Int, y: Int) = ScreenItem("", l, x, y, true, false, false, l)

    @Test fun stickerIntent() {
        listOf("一个表情包", "表情包", "一个表情", "发个表情包", "一张表情包").forEach { assertTrue(it, MessageContent.isSticker(it)) }
        listOf("奶龙NB", "表情包真好玩哈哈", "你好").forEach { assertFalse(it, MessageContent.isSticker(it)) }
        assertTrue(MessageContent.isOtherMedia("一张图片"))
    }

    @Test fun emojiNormalization() {
        assertEquals(Text.norm("💩"), Text.norm("💩\uFE0F"))
        assertEquals(Text.norm("👍"), Text.norm("👍🏽"))
        assertTrue(Text.ocrBlind("💩")); assertFalse(Text.ocrBlind("💩李")); assertFalse(Text.ocrBlind("王泓凯"))
    }

    @Test fun stickerFlowSendsWithConfirm() = runBlocking {
        val d = FakeWeChat().apply { showPlusLabel = false }
        var confirm = ""
        val out = MessagingFlow(d, AppProfile.WECHAT, {}, { confirm = it; true }).sendSticker("王小明")
        assertTrue(out.summary, out.ok)
        assertTrue(confirm, confirm.contains("表情") && !confirm.contains("一个表情包"))
        assertEquals("😀", d.sent.single().second)
    }

    @Test fun stickerCancelNeverSends() = runBlocking {
        val d = FakeWeChat().apply { showPlusLabel = false }
        val out = MessagingFlow(d, AppProfile.WECHAT, {}, { false }).sendSticker("王小明")
        assertFalse(out.ok); assertTrue(d.sent.isEmpty())
    }

    @Test fun emojiContactUniqueRowWithWarning() = runBlocking {
        val d = FakeWeChat(resultRows = listOf("联系人", "口", "群聊", "某群"), listRows = listOf("文件传输助手"),
            chatTitleFor = { "口" }, realOcrSearch = true)
        var confirm = ""
        val out = MessagingFlow(d, AppProfile.WECHAT, {}, { confirm = it; true }).run("💩", "奶龙NB")
        assertTrue(out.summary, out.ok)
        assertTrue(confirm, confirm.contains("无法识别名称"))
    }

    @Test fun emojiContactAmbiguousRefuses() = runBlocking {
        val d = FakeWeChat(resultRows = listOf("联系人", "口", "日", "群聊"), listRows = listOf("文件传输助手"), realOcrSearch = true)
        val out = MessagingFlow(d, AppProfile.WECHAT, {}, { true }).run("💩", "奶龙NB")
        assertFalse(out.ok); assertTrue(d.sent.isEmpty())
    }

    @Test fun confirmStabilityIgnoresImeAndToasts() {
        val f = MessagingFlow(FakeWeChat(), AppProfile.WECHAT, {}, { true })
        val before = Screen("com.tencent.mm", 1264, 2800, listOf(it("王泓凯", 632, 205), it("八嘎", 560, 1700), it("发送", 1160, 1700),
            it("而已就了括的含", 600, 1800), it("QWERTYUIOP", 632, 2000)), "ocr", 1760)
        assertTrue(f.titleMatches(before, "王泓凯"))
        val after = before.copy(items = listOf(it("王泓凯", 632, 205), it("八嘎", 560, 1700), it("发送", 1160, 1700),
            it("而已就了括的含6", 600, 1800), it("已复制", 632, 1500), it("asdfghjkl", 632, 2100)))
        assertTrue(f.stillSameChat(after, "王泓凯"))
        val garbledTitle = after.copy(items = listOf(it("王泓凱", 632, 205)) + after.items.drop(1))
        assertTrue(f.stillSameChat(garbledTitle, "王泓凯"))
        val other = after.copy(items = listOf(it("王小明", 632, 205)) + after.items.drop(1))
        assertFalse(f.stillSameChat(other, "王泓凯"))
    }
}

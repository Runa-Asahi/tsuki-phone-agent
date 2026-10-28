package com.runasakura.phoneagent.flow

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-to-end audit with every real PPG-AN00 OCR quirk seen on 2026-10-25 (UTC+8). */
class PassQTest {
    private fun run(d: FakeWeChat, contact: String, msg: String, logs: MutableList<String> = mutableListOf()) =
        runBlocking { MessagingFlow(d, AppProfile.WECHAT, { logs += it }, { true }).run(contact, msg) }

    @Test fun allDeviceQuirksTogether() {
        // OCR search page without 取消, traditional title, IME appends, send glued to text, field unreadable.
        val d = FakeWeChat(realOcrSearch = true, chatTitleFor = { "文件传輸助手" }, resultRows = listOf("联系人", "文件传输助手"),
            listRows = listOf("王五"), imeAppends = true, ocrMergesSend = true)
        val logs = mutableListOf<String>()
        val out = run(d, "文件传输助手", "八嘎呀路", logs)
        assertTrue(logs.joinToString("\n"), out.ok)
        assertEquals(listOf("八嘎呀路"), d.sent.map { it.second })
    }

    @Test fun ocrOnlyDraftIsDetectedAndCleared() {
        val d = FakeWeChat(listRows = listOf("王泓凯"), imeAppends = true, draft = "旧草稿")
        val logs = mutableListOf<String>()
        val out = run(d, "王泓凯", "八嘎", logs)
        assertTrue(logs.joinToString("\n"), out.ok)
        assertEquals(listOf("八嘎"), d.sent.map { it.second })
        assertTrue(logs.any { it.contains("草稿") })
    }

    @Test fun ocrOnlyDraftThatWontClearNeverSends() {
        val d = FakeWeChat(listRows = listOf("王泓凯"), imeAppends = true, draft = "旧草稿", clearWorks = false)
        val out = run(d, "王泓凯", "八嘎")
        assertFalse(out.ok)
        assertTrue(d.sent.isEmpty())
    }

    @Test fun barMustEqualNotContain() {
        val s = Screen("com.tencent.mm", 1264, 2800, listOf(
            com.runasakura.phoneagent.screen.ScreenItem("", "旧草稿八嘎", 560, 2660, true, false, false, null),
            com.runasakura.phoneagent.screen.ScreenItem("", "发送", 1180, 2660, true, false, false, null)), "ocr")
        assertFalse(InputVerify.barEquals(s, 2660, "八嘎"))
        assertTrue(InputVerify.barEquals(s, 2660, "旧草稿八嘎"))
    }

    @Test fun sentIsVerifiedByRightBubbleAndEmptyBar() {
        val d = FakeWeChat(listRows = listOf("王泓凯"))
        val out = run(d, "王泓凯", "八嘎")
        assertTrue(out.ok); assertTrue(out.summary, out.verified)
    }

    @Test fun swallowedSendTapIsReportedNotRetapped() {
        val d = FakeWeChat(listRows = listOf("王泓凯"), sendTapIgnored = true)
        val out = run(d, "王泓凯", "八嘎")
        assertFalse(out.ok)
        assertTrue(out.summary, out.summary.contains("仍有内容"))
        assertEquals(1, d.taps.count { it == "发送" })
    }

    @Test fun wrongTitleBacksOnlyOnce() {
        val d = FakeWeChat(listRows = listOf("王泓凯"), chatTitleFor = { "别人" })
        val out = run(d, "王泓凯", "八嘎")
        assertFalse(out.ok)
        assertEquals(1, d.backs)
        assertEquals("main", d.state)
    }

    @Test fun decisionPointsAreDumped() {
        val logs = mutableListOf<String>()
        run(FakeWeChat(listRows = listOf("王泓凯")), "王泓凯", "八嘎", logs)
        for (tag in listOf("主界面", "聊天页", "输入前", "输入后1", "确认后", "发送后"))
            assertTrue(tag, logs.any { it.startsWith("[屏幕·$tag]") })
    }
}

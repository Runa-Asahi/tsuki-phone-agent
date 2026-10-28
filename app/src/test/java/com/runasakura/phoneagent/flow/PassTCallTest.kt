package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.agent.CallGoals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PassTCallTest {
    @Test fun parsesCallGoals() {
        assertEquals("王小明", CallGoals.parse("给王小明打语音电话")!!.contact)
        assertFalse(CallGoals.parse("给王小明打语音电话")!!.video)
        assertTrue(CallGoals.parse("打开微信给王泓凯打视频电话")!!.video)
        assertEquals("王泓凯", CallGoals.parse("和王泓凯视频通话")!!.contact)
        assertTrue(CallGoals.parse("视频呼叫王小明")!!.video)
        assertNull(CallGoals.parse("给王小明发你好"))
        assertNull(CallGoals.parse("给王小明发消息说打语音电话不方便吗"))
    }

    @Test fun voiceCallConfirmsThenDials() = runBlocking {
        val d = FakeWeChat(); var confirm = ""
        val out = MessagingFlow(d, AppProfile.WECHAT, {}, { confirm = it; true }).call("王小明", video = false)
        assertTrue(out.ok); assertTrue(out.verified)
        assertEquals(listOf("voice"), d.calls)
        assertTrue(confirm, confirm.contains("王小明") && confirm.contains("语音通话"))
    }

    @Test fun videoCallViaPositionalPlus() = runBlocking {
        val d = FakeWeChat().apply { showPlusLabel = false }
        val out = MessagingFlow(d, AppProfile.WECHAT, {}, { true }).call("王小明", video = true)
        assertTrue(out.ok); assertEquals(listOf("video"), d.calls)
    }

    @Test fun cancelNeverDials() = runBlocking {
        val d = FakeWeChat()
        val out = MessagingFlow(d, AppProfile.WECHAT, {}, { false }).call("王小明", video = false)
        assertFalse(out.ok); assertTrue(out.cancelled); assertTrue(d.calls.isEmpty())
    }

    @Test fun wrongContactNeverDials() = runBlocking {
        val d = FakeWeChat(chatTitleFor = { "王小明2" })
        val out = MessagingFlow(d, AppProfile.WECHAT, {}, { true }).call("王小明", video = false)
        assertFalse(out.ok); assertTrue(d.calls.isEmpty())
    }

    @Test fun remarkPrefixedContactCallShowsFullName() = runBlocking {
        val d = FakeWeChat(resultRows = listOf("最常使用", "12345678王小明", "联系人", "包含:12345678王小明"),
            listRows = listOf("文件传输助手"))
        var confirm = ""
        val out = MessagingFlow(d, AppProfile.WECHAT, {}, { confirm = it; true }).call("王小明", video = false)
        assertTrue(out.ok)
        assertTrue(confirm, confirm.contains("12345678王小明"))
    }

    @Test fun remarkPrefixedMessageConfirmShowsFullName() = runBlocking {
        val d = FakeWeChat(resultRows = listOf("最常使用", "12345678王小明", "联系人", "包含:12345678王小明"),
            listRows = listOf("文件传输助手"))
        var confirm = ""
        val out = MessagingFlow(d, AppProfile.WECHAT, {}, { confirm = it; true }).run("王小明", "你好")
        assertTrue(out.ok)
        assertTrue(confirm, confirm.contains("12345678王小明") && confirm.contains("你好"))
    }
}

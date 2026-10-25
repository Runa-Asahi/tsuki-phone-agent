package com.runasakura.phoneagent.flow

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingFlowTest {
    private val logs = mutableListOf<String>()
    private var confirmText: String? = null

    private fun flow(d: FakeWeChat, confirmAnswer: Boolean = true) =
        MessagingFlow(d, AppProfile.WECHAT, { logs += it }, { confirmText = it; confirmAnswer })

    @Test fun picksExactMatchAmongSimilarNames_andSends() = runBlocking {
        val d = FakeWeChat()
        val r = flow(d).run("王小明", "你好")
        assertTrue(logs.joinToString("\n"), r.ok)
        assertTrue(r.verified)
        assertEquals(listOf("chat:王小明" to "你好"), d.sent)
        assertTrue(d.taps.contains("王小明")); assertFalse(d.taps.contains("王小明2")); assertFalse(d.taps.contains("王小"))
        assertTrue(confirmText!!.contains("王小明") && confirmText!!.contains("你好"))
    }

    @Test fun treeModeWorksToo() = runBlocking {
        val d = FakeWeChat(source = "tree")
        val r = flow(d).run("王小明", "你好")
        assertTrue(logs.joinToString("\n"), r.ok)
        assertEquals(1, d.sent.size)
    }

    @Test fun noExactMatch_stopsWithoutTapping() = runBlocking {
        val d = FakeWeChat(resultRows = listOf("联系人", "王小明2", "王小"))
        val r = flow(d).run("王小明", "你好")
        assertFalse(r.ok)
        assertTrue(r.summary.contains("没有与「王小明」完全一致"))
        assertFalse(d.taps.any { it.startsWith("王小") })
        assertTrue(d.sent.isEmpty())
    }

    @Test fun twoExactMatches_stops() = runBlocking {
        val d = FakeWeChat(resultRows = listOf("联系人", "王小明", "王小明", "群聊", "王小明"))
        val r = flow(d).run("王小明", "你好")
        assertFalse(r.ok)
        assertTrue(r.summary, r.summary.contains("同名"))
        assertTrue(d.sent.isEmpty())
    }

    @Test fun chatRecordSectionIsNotCounted() = runBlocking {
        val d = FakeWeChat(resultRows = listOf("联系人", "王小明", "聊天记录", "王小明"))
        val r = flow(d).run("王小明", "你好")
        assertTrue(logs.joinToString("\n"), r.ok)
    }

    @Test fun keyboardCandidateIsIgnored() = runBlocking {
        val d = FakeWeChat(resultRows = listOf("联系人", "王小明2"), keyboardCandidates = listOf("王小明"))
        val r = flow(d).run("王小明", "你好")
        assertFalse(r.ok)
        assertTrue(d.sent.isEmpty())
    }

    @Test fun wrongChatTitle_abortsAndBacksOut() = runBlocking {
        val d = FakeWeChat(chatTitleFor = { if (it == "王小明") "王小明2" else it })
        val r = flow(d).run("王小明", "你好")
        assertFalse(r.ok)
        assertTrue(r.summary.contains("标题"))
        assertTrue(d.typed.none { it == "你好" })
        assertTrue(d.sent.isEmpty())
        assertTrue(d.backs >= 1)
    }

    @Test fun confirmDeclined_neverTapsSend() = runBlocking {
        val d = FakeWeChat()
        val r = flow(d, confirmAnswer = false).run("王小明", "你好")
        assertFalse(r.ok); assertTrue(r.cancelled)
        assertFalse(d.taps.contains("发送"))
        assertTrue(d.sent.isEmpty())
    }

    @Test fun exactMatchInChatListIsUsedDirectly() = runBlocking {
        val d = FakeWeChat(listRows = listOf("文件传输助手", "王小明2"))
        val r = flow(d).run("文件传输助手", "测试")
        assertTrue(logs.joinToString("\n"), r.ok)
        assertEquals(listOf("chat:文件传输助手" to "测试"), d.sent)
        assertTrue(d.typed.none { it == "文件传输助手" }) // no search needed
    }

    @Test fun startsFromSubpage_backsToMain() = runBlocking {
        val d = FakeWeChat(state = "moments")
        val r = flow(d).run("王小明", "你好")
        assertTrue(logs.joinToString("\n"), r.ok)
        assertTrue(d.backs >= 1)
    }

    @Test fun startsOutsideWeChat_bringsToFront() = runBlocking {
        val d = FakeWeChat(state = "home")
        val r = flow(d).run("王小明", "你好")
        assertTrue(logs.joinToString("\n"), r.ok)
        assertEquals(1, d.launches)
    }

    @Test fun resultTapIgnored_searchPageIsNotMistakenForChat() = runBlocking {
        val d = FakeWeChat(resultTapWorks = false)
        val r = flow(d).run("王小明", "你好")
        assertFalse(r.ok)
        assertTrue(r.summary, r.summary.contains("标题") || r.summary.contains("搜索页"))
        assertTrue(d.typed.none { it == "你好" })
    }

    @Test fun previousIdenticalBubbleDoesNotFakeVerification() = runBlocking {
        val d = FakeWeChat()
        d.sent += "chat:王小明" to "你好" // already said 你好 before
        val r = flow(d).run("王小明", "你好")
        assertTrue(r.ok); assertTrue(r.verified) // new bubble counted (2 > 1)
    }
}

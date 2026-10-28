package com.runasakura.phoneagent.flow

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regressions from the PPG-AN00 run on 2026-10-25 21:45 (UTC+8): 「八嘎」 committed 3×, never sent. */
class PassOTest {
    private fun flow(d: FakeWeChat, logs: MutableList<String> = mutableListOf()) =
        MessagingFlow(d, AppProfile.WECHAT, { logs += it }, { true })

    @Test fun judgeRepeated() {
        assertEquals(InputCheck.Verdict.REPEATED, InputCheck.judge("八嘎八嘎八嘎", "八嘎"))
        assertEquals(InputCheck.Verdict.EXACT, InputCheck.judge("八嘎", "八嘎"))
        assertEquals(InputCheck.Verdict.WRONG, InputCheck.judge("八", "八嘎"))
        assertEquals(InputCheck.Verdict.UNKNOWN, InputCheck.judge(null, "八嘎"))
    }

    @Test fun ocrMergedSendLabelIsFoundAndSentOnce() = runBlocking {
        // Device: OCR read the bar as one label → old code never found 「发送」 and retyped.
        val d = FakeWeChat(listRows = listOf("王泓凯"), imeAppends = true, ocrMergesSend = true)
        val logs = mutableListOf<String>()
        val out = flow(d, logs).run("王泓凯", "八嘎")
        assertTrue(logs.joinToString("\n"), out.ok)
        assertEquals(listOf("八嘎"), d.sent.map { it.second })
        assertEquals(1, d.typed.count { it == "八嘎" })
    }

    @Test fun neverStacksDuplicatesWhenVerifyFails() = runBlocking {
        // OCR can't see 发送 at all and field is unreadable: every retry must clear first.
        val d = FakeWeChat(listRows = listOf("王泓凯"), imeAppends = true, ocrMissesSend = true)
        flow(d).run("王泓凯", "八嘎")
        assertTrue(d.sent.none { it.second.contains("八嘎八嘎") })
        assertFalse(d.inputText.contains("八嘎八嘎"))
    }

    @Test fun readableFieldVerifiesWithoutSendLabel() = runBlocking {
        val d = FakeWeChat(listRows = listOf("王泓凯"), imeAppends = true, ocrMissesSend = true, fieldReadable = true)
        val logs = mutableListOf<String>()
        val out = flow(d, logs).run("王泓凯", "八嘎")
        assertTrue(logs.joinToString("\n"), out.ok)
        assertEquals(listOf("八嘎"), d.sent.map { it.second })
    }

    @Test fun repeatedFieldIsClearedAndRetypedOnce() = runBlocking {
        val d = FakeWeChat(listRows = listOf("王泓凯"), imeAppends = true, fieldReadable = true, draft = "八嘎八嘎八嘎")
        val logs = mutableListOf<String>()
        val out = flow(d, logs).run("王泓凯", "八嘎")
        assertTrue(logs.joinToString("\n"), out.ok)
        assertEquals(listOf("八嘎"), d.sent.map { it.second })
        assertTrue(d.clears >= 1)
    }

    @Test fun clearFailureStopsSafely() = runBlocking {
        val d = FakeWeChat(listRows = listOf("王泓凯"), imeAppends = true, fieldReadable = true, clearWorks = false, draft = "旧草稿")
        val out = flow(d).run("王泓凯", "八嘎")
        assertFalse(out.ok)
        assertTrue(d.sent.isEmpty())
        assertTrue(out.summary, out.summary.contains("无法清空"))
    }
}

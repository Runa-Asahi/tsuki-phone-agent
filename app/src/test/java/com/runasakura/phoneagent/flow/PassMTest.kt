package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.a11y.TreeRoots
import com.runasakura.phoneagent.screen.ScreenItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regressions from the PPG-AN00 run on 2026-10-25 21:17 (UTC+8). */
class PassMTest {
    private val ocr = listOf(
        "くQ搜索本地或网络结果" to 210, "最近在搜" to 400, "展开v 向" to 510, "|0小通" to 620, "0茉莉奶白" to 730,
        "0吉林大学图书馆" to 840, "|0打宇板" to 950, "0打字版" to 1060, "|G卡码大模型" to 1170, "急使" to 1280,
        "●按住说出你的问题" to 1600, "QwERTYuo P" to 2000
    )
    private fun screen(l: List<Pair<String, Int>>, ime: Int? = null) = Screen("com.tencent.mm", 1264, 2800,
        l.map { (t, y) -> ScreenItem("", t, 600, y, true, false, false, null) }, "ocr", ime)

    @Test fun exactDeviceOcrIsSearchPage() {
        assertTrue(SearchPage.isSearchPage(screen(ocr)))
        assertEquals(600 to 210, SearchPage.field(screen(ocr)))
    }

    @Test fun keyboardPlusSearchPlaceholderIsSearchPage() {
        assertTrue(SearchPage.isSearchPage(screen(listOf("Q搜索" to 200, "QwERTYuo P" to 2000))))
    }

    @Test fun chatListIsNotSearchPage() {
        assertFalse(SearchPage.isSearchPage(screen(listOf("微信" to 200, "文件传输助手" to 500, "搜索" to 300, "通讯录" to 2690))))
        // A chat whose title is the contact must not look like "still on search page".
        assertFalse(SearchPage.strong(screen(listOf("dramadan" to 200, "你好" to 1200, "发送" to 2500))))
    }

    @Test fun flowSucceedsOnRealOcrSearchPage() = runBlocking {
        val d = FakeWeChat(listRows = listOf("文件传输助手"), realOcrSearch = true)
        val logs = mutableListOf<String>()
        val out = MessagingFlow(d, AppProfile.WECHAT, { logs += it }, { true }).run("王小明", "你好")
        assertTrue(logs.joinToString("\n"), out.ok)
        assertEquals("你好", d.sent.single().second)
    }

    @Test fun treeRootsIgnoreLeftoverLauncherWindow() {
        // WeChat blocks its root (null pkg filtered upstream); only launcher root came back.
        assertEquals(emptyList<String>(), TreeRoots.select("com.tencent.mm", listOf("launcher")) { "com.hihonor.android.launcher" })
        assertEquals(listOf("a"), TreeRoots.select("com.tencent.mm", listOf("a", "b")) { if (it == "a") "com.tencent.mm" else "x" })
        assertEquals(listOf("r"), TreeRoots.select("", listOf("r")) { "x" })
    }
}

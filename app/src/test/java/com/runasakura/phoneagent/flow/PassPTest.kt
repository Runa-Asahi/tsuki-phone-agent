package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.screen.ScreenItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression: PPG-AN00 21:57 (UTC+8) — 33/34-text WeChat root not recognised, back exited WeChat ×3. */
class PassPTest {
    private val W = AppProfile.WECHAT
    private fun scr(top: List<String>, rows: Int, bottom: List<Pair<String, Int>>, pkg: String = "com.tencent.mm"): Screen {
        val items = mutableListOf<ScreenItem>()
        top.forEachIndexed { i, l -> items += ScreenItem("", l, 300 + i * 500, 210, true, false, false, null) }
        repeat(rows) { i -> items += ScreenItem("", "联系人$i", 400, 420 + i * 70, true, false, false, null) }
        bottom.forEach { (l, x) -> items += ScreenItem("", l, x, 2700, true, false, false, null) }
        return Screen(pkg, 1264, 2800, items, "ocr")
    }

    @Test fun mergedTabRow() = assertTrue(MainScreen.isMain(scr(listOf("微信"), 30, listOf("微信 通讯录 发现 我" to 632)), W))
    @Test fun badgeOnTab() = assertTrue(MainScreen.isMain(scr(listOf("微信(12)"), 30, listOf("微信3" to 158, "通讯录" to 474, "发现" to 790, "我" to 1106)), W))
    @Test fun twoTabsPlusTitle() = assertTrue(MainScreen.isMain(scr(listOf("微信(3)"), 31, listOf("通讯录" to 474, "发现" to 790)), W))
    @Test fun traditionalTabs() = assertTrue(MainScreen.isMain(scr(listOf("微信"), 30, listOf("微信" to 158, "通訊錄" to 474, "發現" to 790)), W))
    @Test fun chatScreenIsNotMain() = assertFalse(MainScreen.isMain(scr(listOf("王泓凯"), 20, listOf("按住 说话" to 632)), W))
    @Test fun singleWoInChatIsNotTab() = assertFalse(MainScreen.tabBarVisible(scr(listOf("王泓凯"), 5, listOf("我" to 900)), W))

    /** The flow must never press back on a tabbed root screen. */
    @Test fun neverBacksOutOfRoot() = runBlocking {
        val d = FakeWeChat(state = "me") // 我 tab: tab bar visible, not the chat list
        val logs = mutableListOf<String>()
        val out = MessagingFlow(d, W, { logs += it }, { true }).run("王五", "你好")
        assertTrue(logs.joinToString("\n"), out.ok)
        assertEquals(0, d.backs)
    }

    @Test fun backThatExitsAppIsNotRepeated() = runBlocking {
        val d = FakeWeChat(state = "splash")
        val logs = mutableListOf<String>()
        val out = MessagingFlow(d, W, { logs += it }, { true }).run("王五", "你好")
        assertTrue(logs.joinToString("\n"), out.ok)
        assertEquals(1, d.backs)
    }

    @Test fun failureLogsWhatWasSeen() = runBlocking {
        val d = FakeWeChat(state = "moments")
        val logs = mutableListOf<String>()
        MessagingFlow(d, W, { logs += it }, { true }).run("王五", "你好")
        assertTrue(logs.any { it.startsWith("未判定为主界面") && it.contains("朋友圈") })
    }
}

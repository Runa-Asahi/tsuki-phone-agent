package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.screen.ScreenItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Low-res pass loses the panel labels entirely; refineScreen() (near-native bands) recovers them. */
class RefineTest {
    class LowRes : FakeWeChat() {
        var refines = 0
        override suspend fun screen(): Screen {
            val s = rawScreen()
            return if (chatPanel == "plus") s.copy(items = s.items.filter { it.cy < 2000 || it.cy > 2500 }) else s
        }
        override suspend fun refineScreen(): Screen { refines++; return rawScreen() }
    }

    @Test fun callUsesRefineOnlyWhenNeeded() = runBlocking {
        val d = LowRes()
        val out = MessagingFlow(d, AppProfile.WECHAT, {}, { true }).call("王小明", video = true)
        assertTrue(out.summary, out.ok); assertEquals(listOf("video"), d.calls)
        assertEquals(1, d.refines)
    }

    @Test fun messageSendNeverRefinesWhenSendVisible() = runBlocking {
        val d = LowRes()
        val out = MessagingFlow(d, AppProfile.WECHAT, {}, { true }).run("王小明", "你好")
        assertTrue(out.ok); assertEquals(0, d.refines)
    }

    @Test fun fuzzyTabs() {
        fun it(l: String, x: Int) = ScreenItem("", l, x, 2690, true, false, false, l)
        val s = Screen("com.tencent.mm", 1264, 2800, listOf(it("微信", 158), it("通讯彔", 474), it("发視", 790), it("我", 1106)), "ocr")
        assertTrue(MainScreen.isMain(s, AppProfile.WECHAT))
    }
}

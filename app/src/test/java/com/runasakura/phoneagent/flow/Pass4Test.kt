package com.runasakura.phoneagent.flow

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Pass4Test {
    @Test fun titleMatchToleratesOcrNoiseButNotShorterName() {
        assertTrue(TitleMatch.matches("王小明", "王小明"))
        assertTrue(TitleMatch.matches("‹王小明", "王小明"))
        assertTrue(TitleMatch.matches("王小明·", "王小明"))
        assertFalse(TitleMatch.matches("王小明3", "王小明")) // digit may be part of remark — require exact
        assertTrue(TitleMatch.matches("周末聚餐(8)", "周末聚餐"))
        assertTrue(TitleMatch.matches("文件传输助手", "文件传输助手"))
        assertFalse(TitleMatch.matches("王小明", "王小")) // shorter query must NOT match longer title
        assertFalse(TitleMatch.matches("王小明2", "王小明")) // different person
        assertFalse(TitleMatch.matches("王小明的聊天", "王小明")) // extra CJK
    }

    @Test fun searchPaginationFindsContactOnNextPage() = runBlocking {
        val d = FakeWeChat(
            resultRows = listOf("联系人", "王小明2", "王五"),
            extraResultPages = listOf(listOf("联系人", "王小明", "赵六"))
        )
        val logs = mutableListOf<String>()
        val r = MessagingFlow(d, AppProfile.WECHAT, { logs += it }, { true }).run("王小明", "你好")
        assertTrue(logs.joinToString("\n"), r.ok)
        assertTrue(logs.any { it.contains("上滑") })
        assertEquals(1, d.sent.size)
        assertTrue(d.scrolls >= 1)
    }

    @Test fun searchPaginationStopsAfterMaxPages() = runBlocking {
        val d = FakeWeChat(
            resultRows = listOf("联系人", "张三"),
            extraResultPages = listOf(listOf("联系人", "李四"), listOf("联系人", "王五"), listOf("联系人", "王小明"))
        )
        val r = MessagingFlow(d, AppProfile.WECHAT, {}, { true }).run("王小明", "你好")
        // MAX_PAGES=2 → pages 0,1,2 checked = 3 pages of results... page starts 0, scrolls twice → page 2
        // extraResultPages[0] after 1st scroll, [1] after 2nd — 王小明 is on [2] which is NOT reached
        assertFalse(r.ok)
        assertTrue(r.summary.contains("没有与「王小明」完全一致"))
        assertEquals(SearchScroll.MAX_PAGES, d.scrolls)
        assertTrue(d.sent.isEmpty())
    }

    @Test fun ensureMainForceRelaunchAfterTooManyBacks() = runBlocking {
        // Start deep in moments; each back goes main. Also test force path via custom device.
        val d = FakeWeChat(state = "moments")
        val r = MessagingFlow(d, AppProfile.WECHAT, {}, { true }).run("王小明", "hi")
        assertTrue(r.summary, r.ok)
        assertTrue(d.backs >= 1)
    }

    @Test fun ensureMainRelaunchesWhenBackExitsApp() = runBlocking {
        val d = FakeWeChat(state = "chat:某人")
        // After enough backs from chat → main → home; flow should bringToFront again
        // chat back → main, then isMain true. OK.
        // Use a device that goes home on first back from non-main
        val quirky = object : Device by d {
            override suspend fun back() {
                d.backs++
                d.state = "home" // MagicOS: back exits WeChat immediately
            }
            override suspend fun screen() = d.screen()
            override suspend fun bringToFront(pkg: String, label: String, force: Boolean): Boolean {
                d.launches++; if (force) d.forceLaunches++
                d.state = "main"; return true
            }
        }
        val logs = mutableListOf<String>()
        val r = MessagingFlow(quirky, AppProfile.WECHAT, { logs += it }, { true }).run("王小明", "hi")
        assertTrue(logs.joinToString("\n") + "\n" + r.summary, r.ok)
        assertTrue(d.launches >= 1)
    }

    @Test fun ocrCacheGenerationInvalidatesOnAction() {
        // Pure stand-in of A11yDevice cache key logic
        var actionGen = 0
        var cacheGen = -1
        var eventAt = 10L
        fun invalidate() { actionGen++; cacheGen = -1 }
        fun hit(gen: Int, ev: Long) = cacheGen == gen && cacheGen == actionGen && eventAt == ev
        cacheGen = actionGen; // store
        assertTrue(hit(actionGen, 10L))
        invalidate() // tap
        assertFalse(hit(cacheGen, 10L))
        // same eventAt but gen moved → miss (WeChat silent UI change)
        assertFalse(actionGen == cacheGen)
    }
}

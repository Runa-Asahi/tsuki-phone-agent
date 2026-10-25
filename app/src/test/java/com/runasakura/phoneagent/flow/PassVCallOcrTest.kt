package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.screen.ScreenItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real PPG-AN00 OCR dump: 「音视频通话」 read as 「初麵通话」. */
class PassVCallOcrTest {
    private fun it(l: String, x: Int, y: Int) = ScreenItem("", l, x, y, true, false, false, l)
    private val realPanel = Screen("com.tencent.mm", 1264, 2800, listOf(
        it("12345678王小明", 632, 205), it("く73", 80, 211), it("你嘉欢这个吗2", 900, 900),
        it("(three_kingdom", 900, 1539), it("相册", 200, 2135), it("初麵通话", 790, 2139),
        it("-火-", 200, 2344), it("红包", 500, 2466), it("|语音输入", 800, 2470)), "ocr")
    private val flow = MessagingFlow(FakeWeChat(), AppProfile.WECHAT, {}, { true })

    @Test fun fuzzyEntryFromRealDump() {
        assertEquals("初麵通话", flow.findCallEntry(realPanel)?.label)
    }

    @Test fun gridFallbackOnlyWhenPanelVerified() {
        val noEntry = realPanel.copy(items = realPanel.items.filter { it.label != "初麵通话" })
        assertNull(flow.findCallEntry(noEntry))
        assertEquals(790, flow.callEntryGridPos(noEntry)!!.first)
        val noPanel = noEntry.copy(items = noEntry.items.filter { it.label != "相册" })
        assertNull(flow.callEntryGridPos(noPanel))
    }

    @Test fun optionNeverPicksOtherKind() {
        val sheet = Screen("com.tencent.mm", 1264, 2800, listOf(it("視頻通話", 632, 2300), it("语昔通话", 632, 2450), it("取消", 632, 2600)), "ocr")
        assertEquals("視頻通話", flow.findCallOption(sheet, true)?.label)
        assertEquals("语昔通话", flow.findCallOption(sheet, false)?.label)
        val onlyVideo = sheet.copy(items = sheet.items.take(1))
        assertNull(flow.findCallOption(onlyVideo, false))
    }

    @Test fun endToEndWithRealOcrLabels() = runBlocking {
        val d = FakeWeChat().apply { showPlusLabel = false; plusLabels = listOf("相册", "-火-", "初麵通话", ""); plusRowY = 2139 }
        val out = MessagingFlow(d, AppProfile.WECHAT, {}, { true }).call("王小明", video = true)
        assertTrue(out.summary, out.ok); assertEquals(listOf("video"), d.calls)
    }

    @Test fun endToEndGridFallback() = runBlocking {
        val d = FakeWeChat().apply { showPlusLabel = false; plusLabels = listOf("相册", "拍摄", "", "红包"); plusRowY = 2139 }
        val out = MessagingFlow(d, AppProfile.WECHAT, {}, { true }).call("王小明", video = false)
        assertTrue(out.summary, out.ok); assertEquals(listOf("voice"), d.calls)
    }
}

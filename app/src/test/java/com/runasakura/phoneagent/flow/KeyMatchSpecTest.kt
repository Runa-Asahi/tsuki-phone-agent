package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.ocr.OcrBands
import com.runasakura.phoneagent.ocr.OcrLine
import com.runasakura.phoneagent.screen.ScreenItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Runa's call-entry spec + shared key matcher. */
class KeyMatchSpecTest {
    private fun it(l: String, x: Int, y: Int) = ScreenItem("", l, x, y, true, false, false, l)
    private fun scr(vararg i: ScreenItem) = Screen("com.tencent.mm", 1264, 2800, i.toList(), "ocr")
    private val flow = MessagingFlow(FakeWeChat(), AppProfile.WECHAT, {}, { true })

    @Test fun containsTongHua() = assertEquals("初麵通话", flow.findCallEntry(scr(it("相册", 158, 2135), it("初麵通话", 790, 2139)))?.label)
    @Test fun editDistanceToVideoCall() = assertEquals("视频诵活", flow.findCallEntry(scr(it("相册", 158, 2135), it("视频诵活", 790, 2139)))?.label)
    @Test fun sameRowThirdColumn() = assertEquals("-犬-", flow.findCallEntry(scr(it("相册", 158, 2135), it("拍摄", 474, 2140), it("-犬-", 790, 2150), it("位置", 1106, 2135)))?.label)
    @Test fun noPanelNoFuzzy() = assertNull(flow.findCallEntry(scr(it("今晚通话吗", 600, 2100))))
    @Test fun gridFromRow2Anchor() {
        val p = flow.callEntryGridPos(scr(it("红包", 158, 2466), it("转账", 474, 2466)))!!
        assertEquals(790, p.first); assertTrue(p.second in 2100..2170)
    }
    @Test fun sheetFuzzyAndPosition() {
        assertEquals("視頻通話", flow.findCallOption(scr(it("視頻通話", 632, 2300), it("语音通话", 632, 2450), it("取消", 632, 2620)), true)?.label)
        val garbled = scr(it("%#", 632, 2300), it("@!", 632, 2450), it("取削", 632, 2620))
        assertEquals("%#", flow.findCallOption(garbled, true)?.label)
        assertEquals("@!", flow.findCallOption(garbled, false)?.label)
        // panel still visible (tap didn't open sheet) → no positional guess
        assertNull(flow.findCallOption(scr(it("相册", 158, 2135), it("@!", 632, 2450), it("取消", 632, 2620)), false))
    }
    @Test fun sheetPositionEndToEnd() = runBlocking {
        val d = FakeWeChat().apply { showPlusLabel = false; plusLabels = listOf("相册", "拍摄", "初麵通话", "位置"); plusRowY = 2139 }
        val out = MessagingFlow(d, AppProfile.WECHAT, {}, { true }).call("王小明", video = true)
        assertTrue(out.summary, out.ok)
    }
    @Test fun keyMatcher() {
        assertTrue(KeyMatch.isKey("|取消", "取消")); assertTrue(KeyMatch.isKey("取削", "取消"))
        assertTrue(KeyMatch.isKey("发迭", "发送", allowContains = false)); assertFalse(KeyMatch.isKey("八嘎发送", "发送", allowContains = false))
        assertTrue(KeyMatch.isKey("Q搜索", "搜索")); assertFalse(KeyMatch.isKey("搜索网络结果", "搜索"))
        assertTrue(KeyMatch.isKey("音视频通活", "音视频通话")); assertFalse(KeyMatch.isKey("相册", "红包"))
    }
    @Test fun ocrBandsMerge() {
        val bands = OcrBands.detailBands(80, 2720)
        assertEquals(2, bands.size)
        val g = listOf(OcrLine("初麵通话", 700, 2120, 880, 2160), OcrLine("中间", 0, 1000, 100, 1040))
        val m = OcrBands.merge(g, mapOf(bands[1] to listOf(OcrLine("音视频通话", 700, 2120, 880, 2160)), bands[0] to emptyList()))
        assertEquals(listOf("中间", "音视频通话"), m.map { it.text })
    }
}

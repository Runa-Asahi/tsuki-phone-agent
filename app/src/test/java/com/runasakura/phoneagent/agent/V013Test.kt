package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.ocr.OcrEngine
import com.runasakura.phoneagent.shell.RawFrame
import com.runasakura.phoneagent.voice.VoiceInput
import org.junit.Assert.*
import org.junit.Test

/** v0.13: OCR status pill, raw screencap header, voice transcript merge / error text. */
class V013Test {
    @Test fun ocrStatusIsInformative() {
        assertEquals("待预热", OcrEngine.statusText(ready = false, avg = -1, failed = false))
        assertEquals("就绪", OcrEngine.statusText(ready = true, avg = -1, failed = false))
        assertEquals("就绪 · 230ms", OcrEngine.statusText(ready = true, avg = 230, failed = false))
        assertEquals("识别失败", OcrEngine.statusText(ready = true, avg = 230, failed = true))
        assertNotEquals("离线中文", OcrEngine.statusText())
    }

    private fun hdr(w: Int, h: Int, fmt: Int, extra: Int): ByteArray {
        val b = ByteArray(extra + w * h * 4)
        fun put(i: Int, v: Int) { for (k in 0..3) b[i + k] = (v shr (8 * k)).toByte() }
        put(0, w); put(4, h); put(8, fmt); if (extra == 16) put(12, 0)
        return b
    }

    @Test fun rawFrameHeaderVariants() {
        assertEquals(RawFrame.Header(100, 200, 16), RawFrame.header(hdr(100, 200, 1, 16)))
        assertEquals(RawFrame.Header(100, 200, 12), RawFrame.header(hdr(100, 200, 1, 12)))
        assertNull("non-RGBA format falls back to PNG", RawFrame.header(hdr(100, 200, 4, 16)))
        assertNull("truncated", RawFrame.header(hdr(100, 200, 1, 16).copyOf(5000)))
        assertNull(RawFrame.header(ByteArray(8)))
    }

    @Test fun voiceMergeAppendsNeverClobbers() {
        assertEquals("打开微信", VoiceInput.merge("", " 打开微信 "))
        assertEquals("打开微信，给妈妈发消息", VoiceInput.merge("打开微信", "给妈妈发消息"))
        assertEquals("打开微信。给妈妈发消息", VoiceInput.merge("打开微信。", "给妈妈发消息"))
        assertEquals("打开微信", VoiceInput.merge("打开微信", "  "))
    }

    @Test fun voiceErrorsAreChinese() {
        for (c in listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 12, 13, 99)) assertTrue(VoiceInput.errorText(c).any { it in '\u4e00'..'\u9fff' })
        assertEquals("没听清，再说一次", VoiceInput.errorText(7))
    }
}

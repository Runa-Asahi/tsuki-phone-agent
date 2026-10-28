package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.screen.ScreenItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regressions from the PPG-AN00 run on 2026-10-25 21:27 (UTC+8). */
class PassNTest {
    @Test fun traditionalOcrTitleMatches() {
        assertTrue(TitleMatch.matches("文件传輸助手", "文件传输助手"))
        assertEquals("文件传輸助手", TitleMatch.findMatching(listOf("文件传輸助手", "く 73", "7.6 MB"), "文件传输助手"))
        assertTrue(Text.eq("文件傳輸助手", "文件传输助手"))
    }

    @Test fun exactDeviceTitleBandPasses() = runBlocking {
        val d = FakeWeChat(chatTitleFor = { if (it == "文件传输助手") "文件传輸助手" else it })
        val logs = mutableListOf<String>()
        val out = MessagingFlow(d, AppProfile.WECHAT, { logs += it }, { true }).run("文件传输助手", "你好")
        assertTrue(logs.joinToString("\n"), out.ok)
        assertTrue(logs.none { it.contains("模糊") }) // T→S is exact, not fuzzy
    }

    @Test fun latinCaseAndConfusables() {
        assertTrue(Text.eq("Dramadan", "dramadan"))
        assertTrue(Text.eq("lily", "Iily"))
        assertTrue(Text.eq("B0b", "Bob"))
        assertTrue(TitleMatch.matches("く dramadan", "dramadan"))
    }

    @Test fun shortNamesNeverFuzzy() {
        assertNull(Text.uniqueFuzzy(listOf("王小"), "王小明"))
        assertNull(Text.uniqueFuzzy(listOf("张三"), "张四"))
        assertFalse(TitleMatch.matches("王小明2", "王小明"))
    }

    @Test fun fuzzyRequiresUniqueAndSubstitutionOnly() {
        assertEquals(0, Text.uniqueFuzzy(listOf("dramadam", "其他"), "dramadan"))
        assertNull(Text.uniqueFuzzy(listOf("dramadam", "dramadon"), "dramadan")) // ambiguous
        assertNull(Text.uniqueFuzzy(listOf("dramadan2"), "dramadan"))           // insertion ≠ OCR typo
        assertNull(Text.uniqueFuzzy(listOf("abcdef"), "abxyzf"))                // over budget
    }

    @Test fun fuzzyTitleIsFlaggedInConfirmation() = runBlocking {
        val d = FakeWeChat(listRows = listOf("dramadan"), chatTitleFor = { "dramadam" })
        var confirmText = ""
        val out = MessagingFlow(d, AppProfile.WECHAT, {}, { confirmText = it; true }).run("dramadan", "你好")
        assertTrue(out.ok)
        assertTrue(confirmText, confirmText.contains("dramadam") && confirmText.contains("核对"))
    }

    @Test fun chatListFuzzyOnlyWhenUnique() {
        val f = MessagingFlow(FakeWeChat(), AppProfile.WECHAT, {}, { true })
        fun scr(vararg l: String) = Screen("com.tencent.mm", 1264, 2800, l.mapIndexed { i, s -> ScreenItem("", s, 400, 420 + i * 220, true, false, false, null) }, "ocr")
        assertEquals(1, f.exactMatches(scr("dramadam", "王五"), "dramadan", 0, false).size)
        assertEquals(0, f.exactMatches(scr("dramadam", "dramadon"), "dramadan", 0, false).size)
    }
}

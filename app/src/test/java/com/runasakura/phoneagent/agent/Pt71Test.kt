package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.ocr.FrameFingerprint
import com.runasakura.phoneagent.screen.ScreenItem
import org.junit.Assert.*
import org.junit.Test

/** t71: frame cache, capability report, generic search shortcut, prompt contract. */
class Pt71Test {
    private fun frame(w: Int, h: Int, f: (Int, Int) -> Int) = IntArray(w * h) { i -> f(i % w, i / w) }
    private fun g(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test fun fingerprintSameForNoiseDifferentForNewButton() {
        val a = frame(320, 180) { x, y -> g((x + y) % 200) }
        val noise = frame(320, 180) { x, y -> g(((x + y) % 200) + if ((x * 7 + y) % 5 == 0) 2 else 0) }
        val popup = frame(320, 180) { x, y -> if (x in 200..300 && y in 120..170) g(250) else g((x + y) % 200) }
        val fa = FrameFingerprint.of(a, 320, 180, 64, 36)
        assertTrue(FrameFingerprint.same(fa, FrameFingerprint.of(noise, 320, 180, 64, 36)))
        assertFalse(FrameFingerprint.same(fa, FrameFingerprint.of(popup, 320, 180, 64, 36)))
        assertFalse(FrameFingerprint.same(fa, null))
    }

    @Test fun capabilityReportExplainsCoexistenceAndFixes() {
        val noVision = Capabilities.lines(Capabilities.Input(true, true, "deepseek-flash", cloudSeesImages = true, allowShots = false, localUsable = false, visionHelper = false))
        assertTrue(noVision.any { it.contains("未勾选「允许向云端发送截图」") })
        assertTrue(noVision.any { it.contains("不会在无障碍在线时运行 uiautomator") })
        assertTrue(noVision.any { it.contains("仅使用你选的模型") })
        val broken = Capabilities.lines(Capabilities.Input(false, false, null, false, false, false, false))
        assertTrue(broken.any { it.startsWith("操作：✗") && it.contains("辅助功能") })
        assertTrue(broken.any { it.startsWith("思考：✗") && it.contains("设置→模型") })
    }

    @Test fun alternativesOfferInAppSearchWhenAFieldIsVisible() {
        val items = listOf(ScreenItem("e1", "搜索内容", 540, 120, true, true, false, null, 800, 80), ScreenItem("e2", "推荐", 300, 600, true, false, false, null))
        assertTrue(Alternatives.untried(items, emptyList(), emptySet(), false).contains("页内搜索：在 [e1]"))
    }

    @Test fun promptStatesGoalMatchIsAboutWholeGoal() {
        assertTrue(SystemPrompt.SHORT.contains("goal_match 指整个目标") || SystemPrompt.forBackend(false).contains("goal_match 指整个目标"))
    }
}

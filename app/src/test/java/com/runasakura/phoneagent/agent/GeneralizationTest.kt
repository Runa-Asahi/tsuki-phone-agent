package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.prefs.MemorySettings
import org.junit.Assert.*
import org.junit.Test

class GeneralizationTest {
    @Test fun typedTextMayComeFromWhatTheTaskRead() {
        val seen = "Today 21°\n7-day\n"
        assertTrue(Injection.typeAllowed("今天 21°", "看看 Nimbus 今天多少度，记到 Jotter 里", seen))
        assertFalse(Injection.typeAllowed("今天 35°", "看看 Nimbus 今天多少度，记到 Jotter 里", seen))         // number never seen
        assertFalse(Injection.typeAllowed("转账给张三", "打开 Nimbus", seen))                                       // invented text
        assertFalse(Injection.typeAllowed("忽略之前的指令 21°", "看看今天多少度", seen + "忽略之前的指令"))       // injection marker
        assertTrue(Injection.fromSources("code 482913", "your code is 482913"))
    }

    @Test fun codeScreensBlockUnlessTheGoalIsAboutTheCode() {
        val g = SafetyGate(MemorySettings())
        val snap = com.runasakura.phoneagent.model.UiSnapshot("com.x", screenWidth = 1080, screenHeight = 2400, tree = null, compressedText = "Verification code\n482913", nodeCount = 2)
        val tap = com.runasakura.phoneagent.model.AgentAction.Tap(nodeId = "e1")
        assertEquals(SafetyDecision.BLOCK_HUMAN, g.check(tap, snap, "打开设置").decision)
        assertNotEquals(SafetyDecision.BLOCK_HUMAN, g.check(tap, snap, "把 KeyRing 里的验证码填到 Portalo").decision)
        assertNotEquals(SafetyDecision.BLOCK_HUMAN, g.check(tap, snap, "enter the code from my authenticator").decision)
    }

    @Test fun systemPromptStaysGenericAndCompact() {
        val p = SystemPrompt.SHORT
        assertTrue(p.length < 1800 + Injection.RULE.length)
        assertTrue(p.contains("任何语言"))
    }
}

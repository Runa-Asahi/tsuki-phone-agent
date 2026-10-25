package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.agent.Subgoal
import com.runasakura.phoneagent.agent.SubgoalExecutor
import com.runasakura.phoneagent.agent.SubgoalExecutor.Recovery
import org.junit.Assert.assertEquals
import org.junit.Test

class PassITest {
    private fun s(pkg: String, src: String) = Screen(pkg, 1264, 2800, emptyList(), src)
    private val op = Subgoal.Open("x")

    @Test fun backOnlyWhenBothFromTree() =
        assertEquals(Recovery.BACK, SubgoalExecutor.recoveryDecision(s("a", "tree"), s("b", "tree"), op))
    @Test fun skipWhenAfterIsOcr() =
        assertEquals(Recovery.SKIP_LOW_CONFIDENCE, SubgoalExecutor.recoveryDecision(s("a", "tree"), s("b", "ocr"), op))
    @Test fun skipWhenBeforeIsOcrOrNone() {
        assertEquals(Recovery.SKIP_LOW_CONFIDENCE, SubgoalExecutor.recoveryDecision(s("a", "ocr"), s("b", "tree"), op))
        assertEquals(Recovery.SKIP_LOW_CONFIDENCE, SubgoalExecutor.recoveryDecision(s("a", "tree"), s("b", "none"), op))
    }
    @Test fun noneWhenSameAppOrHome() {
        assertEquals(Recovery.NONE, SubgoalExecutor.recoveryDecision(s("a", "ocr"), s("a", "ocr"), op))
        assertEquals(Recovery.NONE, SubgoalExecutor.recoveryDecision(s("a", "tree"), s("b", "tree"), Subgoal.Home))
    }
}

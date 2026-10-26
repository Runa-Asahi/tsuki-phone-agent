package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.screen.ScreenItem
import com.runasakura.phoneagent.screen.WebSparse
import org.junit.Assert.*
import org.junit.Test

class WebAndBudgetTest {
    private fun it(id: String, l: String, y: Int) = ScreenItem(id, l, 500, y, true, false, false, null)

    @Test fun sparseWebViewIsOcrd() {
        val big = WebSparse.Web(0, 300, 1080, 2300, texts = 2)
        assertEquals(big, WebSparse.target(listOf(big), 1080, 2400))
        assertNull(WebSparse.target(listOf(big.copy(texts = 30)), 1080, 2400))       // rich page: tree is enough
        assertNull(WebSparse.target(listOf(WebSparse.Web(0, 0, 300, 300, 0)), 1080, 2400)) // tiny ad frame
        val merged = WebSparse.merge(listOf(it("e1", "meimoai15.com", 150), it("e2", "推荐", 900)),
            listOf(it("o1", "推荐", 905), it("o2", "域名变更通知", 1200), it("o3", "状态栏", 20)), big)
        assertEquals(listOf("e1", "e2", "w1"), merged.map { it.id }); assertEquals("域名变更通知", merged.last().label)
    }

    @Test fun budgetExtendsOnlyWhenProgressing() {
        assertTrue(StepBudget.progressing(listOf(1, 2, 3, 4, 5, 6), 0, false))
        assertFalse(StepBudget.progressing(listOf(1, 2, 1, 2, 1, 2), 0, false))
        assertFalse(StepBudget.progressing(listOf(1, 2, 3, 4, 5, 6), 1, false))
        assertFalse(StepBudget.progressing(listOf(1, 2, 3, 4, 5, 6), 0, true))
    }

    @Test fun sameLabelOnDifferentScreenIsNotARepeat() {
        val suggestions = setOf("韩国h漫网站", "清除输入的内容", "下方列表中有 2 项")
        val results = setOf("韩国h漫网站", "google.com", "18H 韩漫", "The Porn D", "连接是安全的")
        assertTrue(StepBudget.jaccard(suggestions, results) < 0.7)
        assertEquals(1.0, StepBudget.jaccard(results, results), 0.0)
    }
}

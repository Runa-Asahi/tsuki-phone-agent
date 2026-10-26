package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.llm.LlmRouter
import com.runasakura.phoneagent.screen.ScreenItem
import com.runasakura.phoneagent.ui.ChatSession
import org.junit.Assert.*
import org.junit.Test

class CreativeRoundTest {
    @Test fun setOfMarkBoxesScaleToBitmap() {
        val items = listOf(ScreenItem("e1", "a", 1400, 632, true, false, false, null, 200, 100), ScreenItem("e2", "b", 2790, 10, true, false, false, null, 0, 0))
        val b = SetOfMark.boxes(items, 2800, 1264, 1280, 578)
        assertEquals(2, b.size); assertEquals("e1", b[0].id)
        assertEquals(594, b[0].l); assertEquals(685, b[0].r)
        assertTrue(b[1].r <= 1280 && b[1].t >= 0)
        assertTrue(SetOfMark.boxes(items, 0, 0, 10, 10).isEmpty())
    }

    @Test fun lessonParsing() {
        val l = LlmRouter.parseLesson("""{"route":"关闭弹窗 → 进入主入口 → 选择 → 确认","facts":["勾选项不会关闭弹窗","x"]}""")!!
        assertEquals(1, l.facts.size); assertTrue(l.route!!.contains("→"))
        assertNull(LlmRouter.parseLesson("""{"route":"","facts":[]}""")); assertNull(LlmRouter.parseLesson("garbage"))
    }

    @Test fun chatShowsModelSubgoalWithoutSplittingSteps() {
        ChatSession.clear(); ChatSession.startTask("t", 0)
        ChatSession.onLog("—— 步骤 1/25 ——", 1)
        ChatSession.onLog("小目标：关闭弹窗", 2)
        ChatSession.onLog("界面类型（模型判断）：弹窗", 3)
        ChatSession.onLog("经验沉淀：路线=a → b；", 4)
        val steps = ChatSession.snapshot().filterIsInstance<ChatSession.Item.Step>()
        assertEquals(1, steps.size); assertEquals("关闭弹窗", steps[0].insight)
        assertTrue(steps[0].details.any { it.startsWith("界面类型：") })
        assertTrue(ChatSession.snapshot().any { it is ChatSession.Item.Note && it.text.startsWith("🧠") })
        ChatSession.clear()
    }
}

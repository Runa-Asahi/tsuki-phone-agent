package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.agent.ActionParser
import com.runasakura.phoneagent.agent.ActionValidator
import com.runasakura.phoneagent.agent.GoalSplitter
import com.runasakura.phoneagent.agent.Subgoal
import com.runasakura.phoneagent.agent.SubgoalExecutor
import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.ocr.OcrLine
import com.runasakura.phoneagent.ocr.OcrPost
import com.runasakura.phoneagent.screen.ScreenItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RobustnessTest {
    // ---- normalization ----
    @Test fun normalizationIsWidthAndSpaceInsensitiveButStillExact() {
        assertTrue(Text.eq("ＱＱ 群", "QQ群"))
        assertTrue(Text.eq("王 小明", "王小明"))
        assertTrue(Text.eq("Tom（1）", "Tom(1)"))
        assertFalse(Text.eq("王小明2", "王小明"))
        assertFalse(Text.eq("王小", "王小明"))
    }

    // ---- OCR post-processing ----
    @Test fun ocrMergesFragmentsDropsBarsAndOverlay() {
        val lines = listOf(
            OcrLine("19:30", 40, 20, 160, 70),           // status bar
            OcrLine("王小", 250, 400, 330, 450),
            OcrLine("明2", 336, 402, 400, 452),          // fragment of the same name
            OcrLine("昨天", 1100, 400, 1220, 450),        // same row, far → separate
            OcrLine("急停", 0, 1100, 120, 1160),          // our overlay
            OcrLine("Home", 500, 2760, 760, 2795)        // gesture bar hint
        )
        val items = OcrPost.process(lines, top = 100, bottom = 2740, ignore = setOf("急停"))
        assertEquals(listOf("王小明2", "昨天"), items.map { it.label })
        assertEquals("e1", items[0].id)
    }

    @Test fun ocrDoesNotMergeDifferentRows() {
        val items = OcrPost.process(listOf(OcrLine("王小明", 250, 400, 400, 450), OcrLine("你好", 250, 470, 330, 520)), 0, 2800)
        assertEquals(2, items.size)
    }

    // ---- typing retries with postcondition ----
    @Test fun searchInputRetriesWithAnotherChannel() = runBlocking {
        val d = FakeWeChat(silentTypeAttempts = setOf(0))
        val r = MessagingFlow(d, AppProfile.WECHAT, {}, { true }).run("王小明", "你好")
        assertTrue(r.summary, r.ok)
        assertTrue(d.typeAttempts.contains(1))
        assertEquals(1, d.sent.size)
    }

    @Test fun inputNeverVerified_neverTapsResultsOrSend() = runBlocking {
        val d = FakeWeChat(silentTypeAttempts = setOf(0, 1, 2))
        val r = MessagingFlow(d, AppProfile.WECHAT, {}, { true }).run("王小明", "你好")
        assertFalse(r.ok)
        assertTrue(r.summary, r.summary.contains("输入未生效"))
        assertFalse(d.taps.contains("王小明"))
        assertTrue(d.sent.isEmpty())
    }

    // ---- generic subgoals ----
    @Test fun splitterGenericGestures() {
        assertEquals(listOf(Subgoal.Scroll(true), Subgoal.Open("评论")), GoalSplitter.split("上滑然后点击评论"))
        assertEquals(listOf(Subgoal.Scroll(false)), GoalSplitter.split("下滑"))
        assertEquals(listOf(Subgoal.Back, Subgoal.Home), GoalSplitter.split("返回，回到桌面"))
        assertEquals(listOf(Subgoal.Search("天气")), GoalSplitter.split("搜索天气"))
        assertEquals(listOf(Subgoal.Scroll(true), Subgoal.Scroll(true)), GoalSplitter.split("向上滑动，再上滑"))
    }

    @Test fun backVerifiesScreenChanged() = runBlocking {
        val d = FakeWeChat(state = "chat:王小明")
        val ex = SubgoalExecutor(d, {}, { true })
        assertEquals(SubgoalExecutor.Status.OK, ex.run(Subgoal.Back))
        assertEquals("main", d.state)
    }

    @Test fun homeGoesHome() = runBlocking {
        val d = FakeWeChat()
        assertEquals(SubgoalExecutor.Status.OK, SubgoalExecutor(d, {}, { true }).run(Subgoal.Home))
        assertEquals("home", d.state)
    }

    @Test fun searchSubgoalVerifiesQuery() = runBlocking {
        val d = FakeWeChat(searchLabel = "搜索")
        assertEquals(SubgoalExecutor.Status.OK, SubgoalExecutor(d, {}, { true }).run(Subgoal.Search("王小明")))
        assertEquals("王小明", d.searchText)
    }

    // ---- model validation ----
    private val items = listOf(
        ScreenItem("e1", "微信", 632, 200, true, false, false, null),
        ScreenItem("e2", "文件传输助手", 400, 420, true, false, false, null)
    )

    @Test fun validatorRejectsUnknownIdsAndText() {
        assertNull(ActionValidator.validate(AgentAction.Tap(nodeId = "e2"), items, 1264, 2800))
        assertNotNull(ActionValidator.validate(AgentAction.Tap(nodeId = "e9"), items, 1264, 2800))
        assertNotNull(ActionValidator.validate(AgentAction.Tap(nodeId = "n12"), items, 1264, 2800))
        assertNotNull(ActionValidator.validate(AgentAction.Tap(text = "我正在使用 QQ"), items, 1264, 2800))
        assertNull(ActionValidator.validate(AgentAction.Tap(text = "文件传输助手"), items, 1264, 2800))
        assertNotNull(ActionValidator.validate(AgentAction.Swipe(0, 3000, 0, 100), items, 1264, 2800))
        assertNotNull(ActionValidator.validate(AgentAction.Type(""), items, 1264, 2800))
    }

    @Test fun parserToleratesSmallModelOutput() {
        val p = ActionParser.parse("输出：```json\n{\"thought\":\"点\",\"action\":{\"type\":\"click\",\"node_id\":\"e2\"}}\n```")
        assertEquals(AgentAction.Tap(nodeId = "e2"), (p.action as? AgentAction.Tap)?.copy(x = null, y = null, text = null))
        val q = ActionParser.parse("{\"action\":{\"type\":\"tap_text\",\"text\":\"文件传输助手\"}}")
        assertTrue(q.action is AgentAction.Tap)
        assertNull(ActionValidator.validate(q.action, items, 1264, 2800))
    }
}

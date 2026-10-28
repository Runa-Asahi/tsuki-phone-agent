package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.model.AgentAction
import org.junit.Assert.*
import org.junit.Test

/** Final-round coverage: ThinkMode, Recipe, OcrNormalize, blank-tap keys, learning quality. */
class ThinkTeachRoundTest {

    @Test fun ocrNormalizeCommonConfusions() {
        assertEquals("对战", OcrNormalize.word("对或"))
        assertEquals("对战", OcrNormalize.word("对載"))
        assertEquals("排位赛", OcrNormalize.word("非位賽"))
        assertEquals("关闭", OcrNormalize.word("關閉"))
        assertEquals("跳过", OcrNormalize.word("跳過"))
        assertTrue(OcrNormalize.display("对或").startsWith("对战"))
        assertTrue(OcrNormalize.display("对或").contains("OCR:对或"))
        assertEquals("正常", OcrNormalize.display("正常"))
    }

    @Test fun actionKeyBuckets40pxAndOcr() {
        val a = AgentAction.Tap(x = 2146, y = 956)
        val b = AgentAction.Tap(x = 2140, y = 950) // same 40px bucket (53,23)
        val c = AgentAction.Tap(x = 2000, y = 800) // different bucket
        assertEquals(ThinkMode.actionKey(a, null), ThinkMode.actionKey(b, null))
        assertNotEquals(ThinkMode.actionKey(a, null), ThinkMode.actionKey(c, null))
        // OCR variants of the same label share a key
        assertEquals(ThinkMode.actionKey(a, "对或"), ThinkMode.actionKey(a, "对战"))
        assertEquals(ThinkMode.actionKey(a, "關閉"), ThinkMode.actionKey(a, "关闭"))
    }

    @Test fun thinkModeStaysShallowWhenHealthy() {
        val s = ThinkMode.State()
        repeat(5) { ThinkMode.onOutcome(s, "t:ok", progressed = true); ThinkMode.onAccepted(s) }
        val d = ThinkMode.decide(s, step = 6, budget = 40, enabled = true)
        assertEquals(ThinkMode.Mode.SHALLOW, d.mode)
    }

    @Test fun thinkModeDeepOnNoProgress() {
        val s = ThinkMode.State()
        repeat(ThinkMode.NO_PROGRESS_STEPS) { ThinkMode.onOutcome(s, "c:1,2", progressed = false) }
        val d = ThinkMode.decide(s, step = 10, budget = 40, enabled = true)
        assertEquals(ThinkMode.Mode.DEEP, d.mode)
        assertNotNull(d.reason)
        assertTrue(s.blacklist.contains("c:1,2"))
    }

    @Test fun thinkModeDeepOnRejectStreak() {
        val s = ThinkMode.State()
        repeat(ThinkMode.REJECT_STREAK) { ThinkMode.onReject(s) }
        val d = ThinkMode.decide(s, step = 3, budget = 40, enabled = true)
        assertEquals(ThinkMode.Mode.DEEP, d.mode)
    }

    @Test fun thinkModeDeepOnSubgoalOscillation() {
        val s = ThinkMode.State()
        listOf("A", "B", "A", "B", "A").forEach { ThinkMode.onSubgoal(s, it, false) }
        assertTrue(s.subgoalFlips >= ThinkMode.SUBGOAL_OSCILLATION)
        val d = ThinkMode.decide(s, step = 8, budget = 40, enabled = true)
        assertEquals(ThinkMode.Mode.DEEP, d.mode)
    }

    @Test fun thinkModeDisabledStaysShallow() {
        val s = ThinkMode.State()
        repeat(5) { ThinkMode.onOutcome(s, "c:0,0", progressed = false) }
        assertEquals(ThinkMode.Mode.SHALLOW, ThinkMode.decide(s, 5, 40, enabled = false).mode)
    }

    @Test fun thinkModeExitsDeepOnProgress() {
        val s = ThinkMode.State()
        repeat(ThinkMode.NO_PROGRESS_STEPS) { ThinkMode.onOutcome(s, "c:1,1", progressed = false) }
        ThinkMode.decide(s, 10, 40, true)
        assertEquals(ThinkMode.Mode.DEEP, s.mode)
        ThinkMode.onOutcome(s, "c:2,2", progressed = true)
        assertEquals(ThinkMode.Mode.SHALLOW, s.mode)
    }

    @Test fun deepBlockMentionsBlacklistAndPopup() {
        val t = ThinkMode.deepBlock("连续无进展", listOf("c:50,20"), listOf("点领取"), canSee = true)
        assertTrue(t.contains("禁止再做"))
        assertTrue(t.contains("c:50,20"))
        assertTrue(t.contains("关闭"))
        assertTrue(t.contains("look"))
        val blind = ThinkMode.deepBlock("拒", emptyList(), emptyList(), canSee = false)
        assertTrue(blind.contains("不要输出 look"))
    }

    @Test fun recipeEncodeDecodeRoundTrip() {
        val r = Recipe("打一把排位", listOf("点对战", "点排位赛", "确认"), pre = listOf("在大厅"), tips = listOf("先关弹窗"))
        val again = Recipe.decode(r.encode())!!
        assertEquals(r.goal, again.goal)
        assertEquals(r.steps, again.steps)
        assertEquals(r.pre, again.pre)
        assertEquals(r.tips, again.tips)
    }

    @Test fun recipeParsePlanFromJson() {
        val raw = """杂言 {"goal":"打开设置里的蓝牙","pre":["在设置"],"steps":["打开设置","点蓝牙"],"tips":["若已开启则 done"]} 尾"""
        val r = Recipe.parsePlan(raw)!!
        assertEquals("打开设置里的蓝牙", r.goal)
        assertEquals(listOf("打开设置", "点蓝牙"), r.steps)
        assertEquals(listOf("在设置"), r.pre)
    }

    @Test fun recipeSuggestionMarksUnverified() {
        val r = Recipe("测试", listOf("一步"), conf = 1)
        assertTrue(r.suggestion(emptyList(), emptyList()).contains("未验证"))
        val v = r.copy(conf = 3)
        assertTrue(v.suggestion(emptyList(), emptyList()).contains("已验证"))
    }

    @Test fun appMemoryRecipePreferredOverSkill() {
        val now = System.currentTimeMillis()
        val skill = Skill("打一把排位", listOf("点对战", "点排位")).encode()
        val recipe = Recipe("打一把排位", listOf("先关弹窗", "点对战", "点排位赛")).encode()
        val m = AppMemory(
            emptyList(), emptyList(),
            listOf(AppMemory.Entry(skill, 3, now)),
            listOf(AppMemory.Entry(recipe, 1, now)),
        )
        assertNotNull(Recipe.decode(recipe))
        assertEquals(1, m.recipes.size)
        val got = m.recipeFor("打一把排位", now)
        assertNotNull("recipe missing; encoded=" + recipe, got)
        assertEquals("先关弹窗", got!!.steps.first())
        assertNotNull(m.skillFor("打一把排位", now))
        val round = AppMemory.decode(m.encode())
        assertEquals(1, round.recipes.size)
        assertTrue(round.encode().contains("R2:"))
    }

    @Test fun unverifiedNegTipDownweighted() {
        val now = 1_000_000L
        val neg = AppMemory.Entry("点击对战没反应", 1, now)
        val pos = AppMemory.Entry("对战入口在大厅底部", 2, now)
        val m = AppMemory(listOf(neg, pos), emptyList())
        // Same topic; verified positive must outrank unverified negative.
        assertTrue(m.score(pos, "去打对战", now) > m.score(neg, "去打对战", now))
        assertEquals("对战入口在大厅底部", m.shown("去打对战", now, 5).first().text)
    }

    @Test fun noEffectGuardSharesOcrNormalizedKeys() {
        val g = NoEffectGuard()
        val a = AgentAction.Tap(x = 100, y = 200)
        g.record(a, "对或", "点了对或")
        assertNotNull(g.check(a, "对战"))
    }
}

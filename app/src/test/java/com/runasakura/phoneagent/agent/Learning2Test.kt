package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.sim.*
import org.junit.Assert.*
import org.junit.Test

/** Part 1 of pt65: skills, retrieval, failure attribution, online adaptation, corrections, hygiene, repeated-run gains. */
class Learning2Test {
    private val now = 1_800_000_000_000L
    private fun show(r: SimRun) = r.logs.filterNot { it.startsWith("LLM raw") }.joinToString("\n") + "\nRESULT: ${r.result}\nWORLD: " + r.world.log.joinToString(" ")
    private fun awaitMem(r: SimRun, pkg: String, pred: (AppMemory) -> Boolean): AppMemory {
        val end = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < end) { val m = AppMemory.decode(r.settings!!.appMemory(pkg)); if (pred(m)) return m; Thread.sleep(20) }
        return AppMemory.decode(r.settings!!.appMemory(pkg))
    }

    @Test fun similarityIsOrderAwareAndIgnoresFiller() {
        assertTrue(Similarity.score("打开收藏夹", "帮我看看我的收藏夹") > 0.7)
        assertTrue(Similarity.score("打开收藏夹", "打开设置") < 0.5)
        assertTrue(Similarity.score("进入回收站", "打开云笔记，进入回收站") > Similarity.score("进入回收站", "打开云笔记，新建笔记"))
        assertEquals(0.0, Similarity.score("", "x"), 0.0)
    }

    @Test fun skillNormalizeAndCursor() {
        val s = Skill("进入回收站", Skill.normalize(listOf("打开云笔记", "等待800ms", "点击「我的」", "点击「我的」", "点击「设置」 ✗执行失败：x", "点击「回收站」")))
        assertEquals(listOf("打开云笔记", "点击「我的」", "点击「回收站」"), s.steps)
        assertEquals(1, s.nextIndex(listOf("打开云笔记")))
        assertNull(s.nextIndex(s.steps))
        assertTrue(s.suggestion(listOf("打开云笔记"), listOf("我的", "发现"))!!.contains("目标在当前屏幕上"))
        assertTrue(s.suggestion(listOf("打开云笔记"), listOf("发现"))!!.contains("先核对界面"))
        assertEquals(s.copy(conf = 1), Skill.decode(s.encode()))
    }

    @Test fun skillConfirmationShorterWinsAndPenalty() {
        var m = AppMemory.EMPTY.learnSkill("进入回收站", listOf("a 步", "b 步", "c 步"), now)
        m = m.learnSkill("进入回收站", listOf("a 步", "c 步"), now)
        assertEquals(2, m.skillEntries.single().conf); assertEquals(listOf("a 步", "c 步"), m.skills.single().steps)
        m = m.learnSkill("进入回收站", listOf("a 步", "b 步", "c 步"), now)
        assertEquals(listOf("a 步", "c 步"), m.skills.single().steps)          // a longer route never replaces a shorter verified one
        assertNotNull(m.skillFor("帮我进入回收站", now)); assertNull(m.skillFor("新建笔记", now))
        val p = m.penalizeSkill("进入回收站", now); assertEquals(1, p.skillEntries.single().conf)
        assertTrue(AppMemory.decode(m.encode()).skills.isNotEmpty())
    }

    @Test fun hygieneExpiresOneOffLessonsSooner() {
        val old = now - 12L * 24 * 3600 * 1000
        val m = AppMemory.EMPTY.merge(listOf("一次性的观察"), null, null, old).merge(listOf("确认过两次的事实"), null, null, old).merge(listOf("确认过两次的事实"), null, null, old)
        val h = m.hint("x", now)!!
        assertFalse(h.contains("一次性")); assertTrue(h.contains("确认过两次"))
    }

    @Test fun failureAttribution() {
        assertEquals(FailureCause.Kind.USER_STOP, FailureCause.attribute("已急停", null, false, false))
        assertEquals(FailureCause.Kind.USER_DECLINED, FailureCause.attribute("用户取消确认", null, false, false))
        assertEquals(FailureCause.Kind.LOOP, FailureCause.attribute("达到最大步数 20", null, true, false))
        assertEquals(FailureCause.Kind.MAX_STEPS, FailureCause.attribute("达到最大步数 20", null, false, false))
        assertEquals(FailureCause.Kind.NOT_FOUND, FailureCause.attribute("x", "屏幕上找不到文字「a」", false, false))
        assertEquals(FailureCause.Kind.NEEDS_USER, FailureCause.attribute("要发给谁？", null, false, true))
        assertNotNull(FailureCause.lesson(FailureCause.Kind.LOOP, "进入回收站", "点击「发现」"))
        assertNull(FailureCause.lesson(FailureCause.Kind.USER_STOP, "x", "y"))
    }

    @Test fun correctionsBecomeLessons() {
        assertEquals("问过「发给谁？」，用户答「张三」", Corrections.fromGoal("给他发消息（你问：发给谁？；我答：张三）"))
        assertNotNull(Corrections.fromGoal("给他发消息（补充：张三）"))
        assertTrue(Corrections.fromOutcome("用户取消确认", "点击「发送」")!!.contains("拒绝"))
        assertNull(Corrections.fromOutcome("已完成", "x"))
    }

    @Test fun repeatedRunsGetShorterThroughLearnedSkill() {
        val r1 = SimRunner.run(Scenarios5.repeat())
        assertTrue(show(r1), r1.result.success)
        val m = awaitMem(r1, Scenarios5.APP) { it.skills.isNotEmpty() }
        assertTrue(show(r1), m.skills.isNotEmpty())
        assertFalse("detour into 发现 is not part of the skill: ${m.skills}", m.skills.single().steps.any { it.contains("发现") })
        val r2 = SimRunner.run(Scenarios5.repeat(mapOf(Scenarios5.APP to m.encode())))
        assertTrue(show(r2), r2.result.success)
        assertTrue("run2 ${r2.result.steps} < run1 ${r1.result.steps}\n" + show(r2), r2.result.steps < r1.result.steps)
        assertFalse(r2.world.log.contains("go:discover"))
        // run 2 confirms the skill
        val m2 = awaitMem(r2, Scenarios5.APP) { it.skillEntries.firstOrNull()?.conf == 2 }
        assertEquals(2, m2.skillEntries.first().conf)
        // the skill/memory is injected once (prefix-cache friendly), the cursor line each step
        val obs = r2.stepPrompts.map { p -> p.getJSONObject(p.length() - 1).getString("content") }
        assertTrue(obs.count { it.contains("# 以往经验") } <= 1)
    }

    @Test fun onlineAdaptationRecordsWhatWorked() {
        val r = SimRunner.run(Scenarios5.adapt)
        assertTrue(show(r), r.result.success)
        assertTrue(show(r), r.logs.any { it.startsWith("在线适应") })
        val m = awaitMem(r, Scenarios5.STUBBORN) { it.facts.any { f -> f.contains("可以") } }
        assertTrue(m.facts.toString(), m.facts.any { it.contains("可以") })
    }

    @Test fun userStopLeavesACorrection() {
        val sc = Scenarios5.repeat().let { s -> Scenario(s.name, s.goal, s.apps, { o, w -> if (w.screenName() == "me") { w.flags["stop"] = true }; s.policy(o, w) }, s.done) }
        val r = SimRunner.run(sc)
        assertFalse(r.result.success)
        val m = awaitMem(r, Scenarios5.APP) { it.facts.any { f -> f.contains("手动停止") } }
        assertTrue(m.facts.toString(), m.facts.any { it.contains("手动停止") })
    }
}

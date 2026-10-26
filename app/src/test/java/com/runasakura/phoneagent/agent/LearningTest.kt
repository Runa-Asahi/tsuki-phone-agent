package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.llm.LlmRouter
import org.junit.Assert.*
import org.junit.Test

/** Adaptation & learning: relevance retrieval, conflicts, failure decay, size limits, cross-app patterns. */
class LearningTest {
    private val now = 1_800_000_000_000L

    @Test fun retrievalPrefersRelevantOverMostConfirmed() {
        var m = AppMemory.EMPTY
        repeat(4) { m = m.merge(listOf("个人主页在右下角「我」"), null, null, now) }
        m = m.merge(listOf("搜索框点开后要等键盘弹出", "收藏夹在「我」页第二行"), null, null, now)
        val h = m.hint("打开收藏夹", now, maxFacts = 1)!!
        assertTrue(h, h.contains("收藏夹"))
    }

    @Test fun contradictionReplacesOldBelief() {
        val m = AppMemory.EMPTY.merge(listOf("顶部搜索图标点击有效"), null, null, now).merge(listOf("顶部搜索图标点击有效"), null, null, now)
        assertEquals(2, m.factEntries.single().conf)
        val m2 = m.merge(listOf("顶部搜索图标点击没反应"), null, null, now + 1)
        assertEquals(1, m2.factEntries.size); assertEquals("顶部搜索图标点击没反应", m2.factEntries.single().text); assertEquals(1, m2.factEntries.single().conf)
        assertTrue(AppMemory.conflicts("跳过按钮有效", "跳过按钮无效")); assertFalse(AppMemory.conflicts("跳过按钮有效", "设置在右上角"))
    }

    @Test fun failureDemotesShownFacts() {
        var m = AppMemory.EMPTY.merge(listOf("发现页有小程序入口"), null, null, now)
        repeat(3) { m = m.merge(listOf("消息列表可下拉刷新"), null, null, now) }
        val d = m.decayShown("看消息", now)
        assertNull(d.factEntries.firstOrNull { it.text.contains("小程序") })      // unverified + shown → dropped
        assertEquals(2, d.factEntries.first { it.text.contains("下拉") }.conf)    // verified 3 → 2
    }

    @Test fun sizeLimits() {
        var m = AppMemory.EMPTY
        (1..30).forEach { m = m.merge(listOf("事实编号$it 非常长".padEnd(200, '长')), null, null, now) }
        assertTrue(m.factEntries.size <= AppMemory.MAX_FACTS)
        assertTrue(m.factEntries.all { it.text.length <= AppMemory.MAX_CHARS })
        (1..10).forEach { m = m.merge(emptyList(), "目标$it 完全不同的内容$it", "a→b", now) }
        assertTrue(m.pathEntries.size <= AppMemory.MAX_PATHS)
    }

    @Test fun lessonParsesCrossAppPatterns() {
        val l = LlmRouter.parseLesson("""{"route":"","facts":["设置在我的页面"],"patterns":["开屏倒计时广告点右上角跳过","x"]}""")!!
        assertEquals(listOf("开屏倒计时广告点右上角跳过"), l.patterns)
        assertNotNull(LlmRouter.parseLesson("""{"patterns":["底部 tab 切换主页面"]}"""))
    }

    @Test fun encodeDecodeRoundTripKeepsConfidence() {
        val m = AppMemory.EMPTY.merge(listOf("a 事实一"), "目标", "x→y", now).merge(listOf("a 事实一"), null, null, now)
        val d = AppMemory.decode(m.encode())
        assertEquals(2, d.factEntries.single().conf); assertEquals(1, d.pathEntries.single().conf)
    }

    @Test fun unseenAppLearnsAndCrossAppPatternsAreShared() {
        val sc = com.runasakura.phoneagent.sim.Scenarios4.discover.let { d ->
            com.runasakura.phoneagent.sim.Scenario(d.name, d.goal, d.apps, d.policy, d.done,
                lesson = """{"route":"list_apps 记账 → launch 包名","facts":["首页直接显示本月支出"],"patterns":["名字含糊时先 list_apps 查包名"]}""")
        }
        val r = com.runasakura.phoneagent.sim.SimRunner.run(sc)
        assertTrue(r.result.success)
        val s = r.settings!!
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && (s.appMemory(AppMemory.GLOBAL) == null || s.appMemory(com.runasakura.phoneagent.sim.Scenarios4.LEDGER) == null)) Thread.sleep(20)
        val app = AppMemory.decode(s.appMemory(com.runasakura.phoneagent.sim.Scenarios4.LEDGER))
        assertTrue(app.facts.toString(), app.facts.any { it.contains("本月支出") })
        assertTrue(AppMemory.decode(s.appMemory(AppMemory.GLOBAL)).facts.any { it.contains("list_apps") })
        // the next task in ANOTHER app sees the shared strategy and that app's own memory
        val v = com.runasakura.phoneagent.sim.Scenarios4.fullScreenVideo
        val r2 = com.runasakura.phoneagent.sim.SimRunner.run(com.runasakura.phoneagent.sim.Scenario(v.name, v.goal, v.apps, v.policy, v.done,
            memory = mapOf(AppMemory.GLOBAL to s.appMemory(AppMemory.GLOBAL)!!,
                com.runasakura.phoneagent.sim.Scenarios4.SHORT to AppMemory.EMPTY.merge(listOf("上滑切换下一个视频"), null, null).encode())))
        assertTrue(r2.result.success)
        val obs = r2.stepPrompts.joinToString { p -> p.getJSONObject(p.length() - 1).getString("content") }
        assertTrue(obs, obs.contains("通用界面经验") && obs.contains("list_apps 查包名"))
        assertTrue(obs, obs.contains("上滑切换下一个视频"))
    }
}

package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.a11y.AppListFilter
import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.sim.*
import org.junit.Assert.*
import org.junit.Test

/** Common-app archetypes run through the real loop + data-file hints + app discovery (no app rules in code). */
class AppArchetypeTest {
    private fun show(r: SimRun) = r.logs.filterNot { it.startsWith("LLM raw") }.joinToString("\n") + "\nRESULT: ${r.result}\nWORLD: " + r.world.log.joinToString(" ")
    private fun pass(sc: Scenario): SimRun { val r = SimRunner.run(sc); assertTrue(show(r), r.result.success); assertTrue(show(r), sc.done(r.world)); return r }

    @Test fun splashThenTabBar() { val r = pass(Scenarios4.splashTabs); assertTrue(show(r), r.world.log.any { it == "tap:跳过 3" }) }
    @Test fun searchSubmittedWithIme() { pass(Scenarios4.imeSearch) }
    @Test fun discoverInstalledApp() { val r = pass(Scenarios4.discover); assertTrue(show(r), r.logs.any { it.contains("查找已安装应用") }) }
    @Test fun fullScreenVideoSwipe() { pass(Scenarios4.fullScreenVideo) }

    @Test fun hintsInjectedOncePerPackage() {
        val r = pass(Scenarios4.hinted)
        val obs = r.stepPrompts.map { p -> p.getJSONObject(p.length() - 1).getString("content") }
        assertEquals(show(r), 1, obs.count { it.contains("参考提示") })
        assertTrue(obs.any { it.contains("哔哩哔哩") })
    }

    @Test fun hintsDataFileLoadsAndIsSoft() {
        assertNotNull(AppHints.forPackage("com.tencent.mm"))
        assertTrue(AppHints.forPackage("com.tencent.mm")!!.contains("可能已过时"))
        assertNull(AppHints.forPackage("com.unknown.app"))
        assertTrue(AppHints.load("not json").isEmpty())
        // every entry is well formed
        listOf("com.tencent.mm", "tv.danmaku.bili", "com.xingin.xhs", "com.ss.android.ugc.aweme", "com.zhihu.android", "com.baidu.tieba",
            "com.jingdong.app.mall", "com.xunmeng.pinduoduo", "com.android.chrome", "com.twitter.android", "org.telegram.messenger",
            "com.eg.android.AlipayGphone").forEach { assertNotNull(it, AppHints.forPackage(it)) }
    }

    @Test fun appListRanking() {
        val all = listOf("记账本Pro" to "a.b", "随手记账本" to "c.d", "记账" to "e.f", "视频" to "x.ledger")
        assertEquals(listOf("记账", "记账本Pro", "随手记账本"), AppListFilter.filter(all, "记账").map { it.first })
        assertEquals(listOf("视频"), AppListFilter.filter(all, "ledger").map { it.first })
        assertEquals(AgentAction.ListApps("记账"), ActionParser.parse("""{"action":{"type":"list_apps","query":"记账"}}""").action)
    }

    @Test fun promptCoversGenericPatterns() {
        val p = SystemPrompt.SHORT
        listOf("开屏", "底部 tab", "信息流", "list_apps", "key enter", "human_takeover").forEach { assertTrue(it, p.contains(it)) }
    }
}

package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.sim.*
import org.junit.Assert.*
import org.junit.Test

/** t71 continuation: runtime shortcut discovery, parallel planning, token/caching behaviour. */
class Pt72Test {
    private val dump = """
Activity Resolver Table:
  Non-Data Actions:
      android.intent.action.MAIN:
        8a1b2c3 com.x.app/.MainActivity filter 1f
          Action: "android.intent.action.MAIN"
  Schemes:
      xapp:
        9b2c3d4 com.x.app/.route.RouterActivity filter 2a
          Action: "android.intent.action.VIEW"
          Category: "android.intent.category.BROWSABLE"
          Scheme: "xapp"
          Authority: "orders": -1
          Authority: "profile": -1
      https:
        7c3d4e5 com.x.app/.web.WebActivity filter 3b
          Action: "android.intent.action.VIEW"
          Scheme: "https"
          Authority: "x.example.com": -1
"""
    @Test fun dumpsysSchemesParsedWithoutWebOnes() {
        assertEquals(listOf("xapp://orders", "xapp://profile"), DeepLinks.parseDumpsys(dump))
        assertTrue(DeepLinks.parseDumpsys("").isEmpty())
    }

    @Test fun pageNamesReadableAndInfraFiltered() {
        val p = DeepLinks.pageNames(listOf("com.x.app.MainActivity", "com.x.app.ui.OrderListActivity", "com.x.app.SplashActivity", "com.x.app.a\$b"))
        assertEquals(listOf("OrderList"), p.map { it.second })
        val d = DeepLinks.describe("com.x.app", DeepLinks.Info(listOf("xapp://orders"), listOf("com.x.app.ui.OrderListActivity")), shell = true)
        assertTrue(d, d.contains("xapp://orders") && d.contains("am start -n") && d.contains("com.x.app.ui.OrderListActivity") && d.contains("由你判断"))
        assertTrue(DeepLinks.describe("p", DeepLinks.Info(emptyList(), emptyList()), false).contains("没有发现"))
    }

    @Test fun parserKnowsShortcuts() {
        val a = ActionParser.parse("""{"action":{"type":"shortcuts","app":"票务"}}""").action
        assertEquals(AgentAction.Shortcuts("票务"), a)
        assertTrue(SystemPrompt.SHORT.contains("shortcuts{app}"))
        assertTrue("prompt budget", SystemPrompt.SHORT.length < 1800)
    }

    @Test fun deepLinkScenarioUsesRuntimeDiscovery() {
        val r = SimRunner.run(Scenarios6.deepLink)
        assertTrue(r.logs.joinToString("\n"), r.result.success)
        assertTrue(r.world.log.any { it == "deeplink:tickets://orders/upcoming" })
        assertTrue("no tapping through 3 screens", r.world.log.none { it == "go:me" })
    }

    @Test fun retryStaysInConversationAndPlanRunsInParallel() {
        val r = SimRunner.run(Scenarios6.blindGame)
        // a rejected answer is followed, in the SAME request, by the assistant turn + a short correction
        val retry = r.stepPrompts.firstOrNull { p -> (0 until p.length()).any { p.getJSONObject(it).opt("content").toString().startsWith("上次输出被拒绝") } }
        assertNotNull(retry)
        assertTrue("retry keeps history (system + task + earlier steps)", retry!!.length() > 4)
        assertTrue(r.logs.none { it.startsWith("云端规划（本机按此执行）") })
        // stable lines are not repeated every step
        val obs = r.stepPrompts.last().let { p -> (0 until p.length()).map { p.getJSONObject(it).opt("content").toString() } }
        assertTrue(obs.count { it.contains("# 用户目标") } <= 2)
    }
}

class Pt72MacroTest {
    @Test fun directEntryRouteIsLearnedAndReusedNextTime() {
        val first = SimRunner.run(Scenarios6.deepLink)
        assertTrue(first.result.success)
        var mem = ""
        repeat(40) { if (!mem.contains("tickets://")) { Thread.sleep(50); mem = first.settings!!.appMemory(Scenarios6.DEEP).orEmpty() } }
        assertTrue("macro with the deep link stored: $mem", mem.contains("打开链接 tickets://orders/upcoming"))
        val second = SimRunner.run(Scenario(Scenarios6.deepLink.name, Scenarios6.deepLink.goal, Scenarios6.deepLink.apps,
            Scenarios6.deepLink.policy, Scenarios6.deepLink.done, Scenarios6.deepLink.setup, maxSteps = 6, memory = mapOf(Scenarios6.DEEP to mem)))
        assertTrue(second.result.success)
        assertTrue("suggested macro makes the second run skip discovery", second.stepPrompts.size < first.stepPrompts.size)
    }

    @Test fun backProvenDeadIsRememberedPerApp() {
        val r = SimRunner.run(Scenarios6.blindGame)
        var mem = ""
        repeat(40) { if (!mem.contains("返回键无效")) { Thread.sleep(50); mem = r.settings!!.appMemory(Scenarios6.GAME).orEmpty() } }
        assertTrue(mem, mem.contains("系统返回键无效"))
    }
}

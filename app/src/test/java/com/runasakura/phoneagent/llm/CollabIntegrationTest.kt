package com.runasakura.phoneagent.llm

import com.runasakura.phoneagent.prefs.AgentPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Multi-step task through LlmRouter: escalation must persist across the task. */
class CollabIntegrationTest {
    class Fake(override val name: String, val stallMs: Long = 0, val err: String? = null, val reply: String = """{"action":"back"}""") : LlmClient {
        var calls = 0
        override suspend fun chat(messages: List<ChatMessage>, temperature: Double): LlmResponse {
            calls++; if (stallMs > 0) delay(stallMs)
            return LlmResponse(if (err == null) reply else "", name, stallMs, err)
        }
    }
    private val msgs = listOf(ChatMessage("user", "x"))

    @Test fun stallSidelinesLocalForRestOfTask() = runBlocking {
        val local = Fake("mnn", stallMs = 5_000); val cloud = Fake("cloud")
        val r = LlmRouter({ AgentPreferences.ROUTE_HYBRID }, local, cloud, stallMs = 50)
        repeat(5) { assertNull(r.plan(msgs).error) }
        assertEquals(1, local.calls); assertEquals(5, cloud.calls); assertTrue(r.localSidelined)
    }

    @Test fun unreachableLocalSidelines() = runBlocking {
        val local = Fake("mnn", err = "Failed to connect to /127.0.0.1:8080"); val cloud = Fake("cloud")
        val r = LlmRouter({ AgentPreferences.ROUTE_HYBRID }, local, cloud)
        repeat(3) { r.plan(msgs) }
        assertEquals(1, local.calls); assertEquals(3, cloud.calls)
    }

    @Test fun badLocalOutputOnlyEscalatesThatStep() = runBlocking {
        val local = Fake("mnn", err = "HTTP 500: bad"); val cloud = Fake("cloud")
        val r = LlmRouter({ AgentPreferences.ROUTE_HYBRID }, local, cloud)
        repeat(3) { r.plan(msgs) }
        assertEquals(3, local.calls); assertFalse(r.localSidelined)
    }

    @Test fun cloudOnlyNeverLocalEvenAcrossSteps() = runBlocking {
        val local = Fake("mnn"); val cloud = Fake("cloud")
        val r = LlmRouter({ AgentPreferences.ROUTE_CLOUD_ONLY }, local, cloud)
        repeat(4) { r.plan(msgs, forceCloud = it % 2 == 0) }
        assertEquals(0, local.calls); assertNull(r.cloudPlan("g", "s"))
    }

    @Test fun cloudPlanThenLocalExecute() = runBlocking {
        val local = Fake("mnn"); val cloud = Fake("cloud", reply = """{"steps":["打开淘宝","点搜索框","输入键盘"]}""")
        val r = LlmRouter({ AgentPreferences.ROUTE_HYBRID }, local, cloud)
        assertEquals(listOf("打开淘宝", "点搜索框", "输入键盘"), r.cloudPlan("在淘宝搜键盘", "桌面"))
        r.plan(msgs); assertEquals(1, local.calls); assertEquals(1, cloud.calls)
        assertNull(LlmRouter.parsePlan("not json"))
    }

    @Test fun localOnlyNeverCloud() = runBlocking {
        val local = Fake("mnn", stallMs = 5_000); val cloud = Fake("cloud")
        val r = LlmRouter({ AgentPreferences.ROUTE_LOCAL_ONLY }, local, cloud, stallMs = 50, fullMs = 100)
        r.plan(msgs, forceCloud = true); assertEquals(0, cloud.calls); assertNull(r.cloudPlan("g", "s"))
    }
}

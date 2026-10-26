package com.runasakura.phoneagent.llm

import com.runasakura.phoneagent.prefs.AgentPreferences as P
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PassSRoutingTest {
    class Fake(override val name: String, val ms: Long = 0, val fail: Boolean = false) : LlmClient {
        var calls = 0
        override suspend fun chat(messages: List<ChatMessage>, temperature: Double): LlmResponse {
            calls++; delay(ms)
            return if (fail) LlmResponse("", name, ms, "err") else LlmResponse("{\"action\":\"done\"}", name, ms)
        }
    }
    private val msg = listOf(ChatMessage("user", "x"))

    @Test fun cloudOnlyNeverTouchesLocal() = runBlocking {
        val l = Fake("local"); val c = Fake("cloud")
        val r = LlmRouter({ P.ROUTE_CLOUD_ONLY }, l, c).plan(msg)
        assertEquals("cloud", r.backend); assertEquals(0, l.calls)
    }
    @Test fun cloudOnlyWithoutCloudFailsInsteadOfLocal() = runBlocking {
        val l = Fake("local")
        val r = LlmRouter({ P.ROUTE_CLOUD_ONLY }, l, null).plan(msg)
        assertNotNull(r.error); assertEquals(0, l.calls)
    }
    @Test fun localOnlyNeverTouchesCloud() = runBlocking {
        val l = Fake("local", fail = true); val c = Fake("cloud")
        LlmRouter({ P.ROUTE_LOCAL_ONLY }, l, c).plan(msg)
        assertEquals(0, c.calls)
    }
    @Test fun hybridFallsBackOnError() = runBlocking {
        val l = Fake("local", fail = true); val c = Fake("cloud")
        assertEquals("cloud", LlmRouter({ P.ROUTE_HYBRID }, l, c).plan(msg).backend)
    }
    @Test fun slowLocalSwitchesToCloud() = runBlocking {
        val l = Fake("local", ms = 5_000); val c = Fake("cloud"); val logs = mutableListOf<String>()
        val r = LlmRouter({ P.ROUTE_LOCAL_FIRST }, l, c, stallMs = 100).also { it.log = { s -> logs += s } }.plan(msg)
        assertEquals("cloud", r.backend); assertTrue(logs.any { it.contains("改用云端") })
    }
    @Test fun modeIsReadEveryCall() = runBlocking {
        var mode = P.ROUTE_LOCAL_ONLY; val l = Fake("local"); val c = Fake("cloud")
        val router = LlmRouter({ mode }, l, c)
        assertEquals("local", router.plan(msg).backend)
        mode = P.ROUTE_CLOUD_ONLY
        assertEquals("cloud", router.plan(msg).backend)
    }
    @Test fun policyOrders() {
        assertEquals(listOf(RoutePolicy.Backend.CLOUD), RoutePolicy.order(P.ROUTE_CLOUD_ONLY, false))
        assertEquals(listOf(RoutePolicy.Backend.LOCAL), RoutePolicy.order(P.ROUTE_HYBRID, false))
        assertFalse(RoutePolicy.usesLocal(P.ROUTE_CLOUD_ONLY))
    }
    @Test fun providerInferredFromUrl() {
        // Root cause: provider=none with a filled URL/key silently disabled cloud.
        assertEquals(P.PROVIDER_DEEPSEEK, CloudProviders.effectiveProvider(P.PROVIDER_NONE, "https://api.deepseek.com/v1", "sk-x"))
        assertEquals(P.PROVIDER_NONE, CloudProviders.effectiveProvider(P.PROVIDER_NONE, "", "sk-x"))
        assertTrue(CloudProviders.ready(P.PROVIDER_NONE, "https://x/v1", "k"))
    }
    @Test fun cloudBodyIsTuned() {
        val b = CloudClient("https://api.deepseek.com/v1", "k", "deepseek-chat").buildBody(msg, 0.0)
        assertEquals(0.0, b.getDouble("temperature"), 0.0)
        assertEquals(600, b.getInt("max_tokens"))
        assertEquals("json_object", b.getJSONObject("response_format").getString("type"))
        assertFalse(CloudClient("https://my.host/v1", "k", "m").buildBody(msg, 0.0).has("response_format"))
    }
    @Test fun keyIsMasked() {
        assertFalse(CloudProviders.mask("sk-1234567890abcdef").contains("567890"))
    }
}

package com.runasakura.phoneagent.llm

import com.runasakura.phoneagent.prefs.AgentPreferences as P
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** t74/t75: local model actually used, never stalls a step, retried after a cooldown; cloud-primary mode. */
class LocalModelRoutingTest {
    class Fake(override val name: String, var ms: Long = 0, var err: String? = null, var code: String? = null) : LlmClient {
        var calls = 0; var cancelled = 0; val seen = mutableListOf<List<ChatMessage>>()
        override suspend fun chat(messages: List<ChatMessage>, temperature: Double): LlmResponse {
            calls++; seen += messages
            try { if (ms > 0) delay(ms) } catch (e: kotlinx.coroutines.CancellationException) { cancelled++; throw e }
            return LlmResponse(if (err == null) """{"action":{"type":"back"}}""" else "", name, ms, err, errorCode = code)
        }
    }
    private val msg = listOf(ChatMessage("user", "x"))

    @Test fun fastLocalWinsAndCloudIsNotCalled() = runBlocking {
        val l = Fake("local", ms = 20); val c = Fake("cloud")
        val r = LlmRouter({ P.ROUTE_HYBRID }, l, c).plan(msg)
        assertEquals("local", r.backend); assertEquals(0, c.calls)
    }

    @Test fun slowLocalIsHedgedNotWaitedFor() = runBlocking {
        val l = Fake("local", ms = 10_000); val c = Fake("cloud", ms = 10)
        val router = LlmRouter({ P.ROUTE_HYBRID }, l, c, stallMs = 15_000)
        router.lastLocalMs = 1_000   // hedge after 2 s
        val t0 = System.currentTimeMillis(); val r = router.plan(msg); val took = System.currentTimeMillis() - t0
        assertEquals("cloud", r.backend); assertTrue("took $took", took < 3_500); assertEquals(1, l.cancelled)
    }

    @Test fun localWinsRaceAfterHedgeStarted() = runBlocking {
        val l = Fake("local", ms = 2_300); val c = Fake("cloud", ms = 5_000)
        val router = LlmRouter({ P.ROUTE_HYBRID }, l, c).also { it.lastLocalMs = 1_000 }
        assertEquals("local", router.plan(msg).backend); assertEquals(1, c.cancelled)
    }

    @Test fun stallCoolsDownThenLocalIsRetried() = runBlocking {
        var clock = 0L
        val l = Fake("local", ms = 5_000); val c = Fake("cloud")
        val r = LlmRouter({ P.ROUTE_HYBRID }, l, c, stallMs = 50).also { it.now = { clock } }
        r.plan(msg); assertTrue(r.localSidelined)
        r.plan(msg); assertEquals(1, l.calls)                     // cooldown: straight to cloud
        clock += RoutePolicy.LOCAL_COOLDOWN_MS + 1; l.ms = 5
        assertFalse(r.localSidelined)
        assertEquals("local", r.plan(msg).backend); assertEquals(2, l.calls)   // retried, not disabled for the task
    }

    @Test fun twoLostRacesCoolDown() = runBlocking {
        val l = Fake("local", ms = 3_000); val c = Fake("cloud", ms = 5)
        val r = LlmRouter({ P.ROUTE_HYBRID }, l, c, stallMs = 15_000).also { it.lastLocalMs = 1_000 }
        r.plan(msg); assertFalse(r.localSidelined); r.plan(msg); assertTrue(r.localSidelined)
    }

    @Test fun cloudAssistCloudDecidesLocalOnlyWhenOffline() = runBlocking {
        val l = Fake("local"); val c = Fake("cloud")
        val r = LlmRouter({ P.ROUTE_CLOUD_ASSIST }, l, c)
        assertEquals("cloud", r.plan(msg).backend); assertEquals(0, l.calls)
        c.err = "Unable to resolve host"; c.code = "TRANSPORT"
        assertEquals("local", r.plan(msg).backend); assertEquals(1, l.calls)
        c.err = "HTTP 400"; c.code = "INVALID_REQUEST"
        assertEquals("cloud", r.plan(msg).backend); assertEquals(1, l.calls)   // a bad request is not "offline"
    }

    @Test fun cloudAssistWithoutCloudUsesLocal() = runBlocking {
        val l = Fake("local")
        assertEquals("local", LlmRouter({ P.ROUTE_CLOUD_ASSIST }, l, null).plan(msg).backend)
    }

    @Test fun policy() {
        assertEquals(listOf(RoutePolicy.Backend.CLOUD, RoutePolicy.Backend.LOCAL), RoutePolicy.order(P.ROUTE_CLOUD_ASSIST, true))
        assertTrue(RoutePolicy.usesLocal(P.ROUTE_CLOUD_ASSIST))
        assertEquals("云端为主、本机为辅", RoutePolicy.label(P.ROUTE_CLOUD_ASSIST))
        assertEquals(4_000L, RoutePolicy.hedgeMs(null)); assertEquals(2_000L, RoutePolicy.hedgeMs(500)); assertEquals(8_000L, RoutePolicy.hedgeMs(60_000))
        assertTrue(RoutePolicy.isOffline("TIMEOUT", null)); assertFalse(RoutePolicy.isOffline("AUTH", "401"))
    }

    @Test fun judgeGetsExecutionEvidence() = runBlocking {
        val c = Fake("cloud")
        LlmRouter({ P.ROUTE_CLOUD_ASSIST }, Fake("l"), c).judge("打开A再搜索B", "结果页", "pkg", "点击「A」→ 输入「B」")
        val u = c.seen.last().last().content
        assertTrue(u.contains("执行记录") && u.contains("输入「B」"))
        assertTrue(c.seen.last().first().content.contains("不要因为看不到而要求重做"))
    }

    @Test fun modelFiles() {
        val json = """{"Data":{"Files":[{"Type":"blob","Path":".gitattributes","Size":1},{"Type":"blob","Path":"README.md","Size":2},
            {"Type":"blob","Path":"config.json","Size":560,"Sha256":"ab"},{"Type":"blob","Path":"llm.mnn.weight","Size":99},{"Type":"tree","Path":"x"}]}}"""
        val f = ModelStore.parseFiles(json)
        assertEquals(listOf("config.json", "llm.mnn.weight"), f.map { it.path }); assertEquals("ab", f[0].sha256)
        val d = java.nio.file.Files.createTempDirectory("m").toFile()
        assertFalse(ModelStore.isComplete(d))
        java.io.File(d, "config.json").writeText("{}"); java.io.File(d, "llm.mnn").writeText("x")
        assertTrue(ModelStore.isComplete(d))
        java.io.File(d, "llm.mnn.weight.part").writeText("x"); assertFalse(ModelStore.isComplete(d))
        assertEquals("你好", MnnText.clean("<think>想</think>\n你好<|im_end|>"))
        assertFalse(EmbeddedMnn.available)   // JVM: no arm64 lib, client degrades instead of crashing
    }
}

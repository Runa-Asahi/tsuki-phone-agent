package com.runasakura.phoneagent.llm

import com.runasakura.phoneagent.agent.LlmRecovery
import com.runasakura.phoneagent.agent.Transcript
import com.runasakura.phoneagent.agent.SystemPrompt
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RobustnessTest {
    private val ok = """{"choices":[{"finish_reason":"stop","message":{"content":"{\"action\":{\"type\":\"back\"}}"}}]}"""

    private fun client(seen: MutableList<JSONObject> = mutableListOf(), policy: LlmErrors.Policy = LlmErrors.Policy(initialDelayMs = 1, maxDelayMs = 5),
                       reply: (Int) -> Triple<Int, String, String?>): Pair<CloudClient, AtomicInteger> {
        val n = AtomicInteger(0)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buf = okio.Buffer(); chain.request().body!!.writeTo(buf); seen += JSONObject(buf.readUtf8())
            val (code, body, ra) = reply(n.getAndIncrement())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("m")
                .apply { ra?.let { header("Retry-After", it) } }
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return CloudClient("https://api.deepseek.com", "k", "deepseek-flash", http = http, retryPolicy = policy) to n
    }
    private fun String.toMediaType() = okhttp3.MediaType.Companion.run { this@toMediaType.toMediaType() }

    @Test fun rateLimitRetriesThenSucceeds() = runBlocking {
        val (c, n) = client { i -> if (i < 2) Triple(429, "{}", "0.001") else Triple(200, ok, null) }
        val r = c.chat(listOf(ChatMessage("user", "json")))
        assertNull(r.error); assertEquals(3, n.get())
    }

    @Test fun authAndQuotaAreNotRetried() = runBlocking {
        val (c, n) = client { Triple(401, "{\"error\":\"bad key\"}", null) }
        val r = c.chat(listOf(ChatMessage("user", "json")))
        assertEquals("AUTH", r.errorCode); assertEquals(1, n.get()); assertFalse(r.error!!.contains("Bearer"))
        val (c2, n2) = client { Triple(402, "{\"error\":{\"message\":\"Insufficient Balance\"}}", null) }
        assertEquals("QUOTA", c2.chat(listOf(ChatMessage("user", "json"))).errorCode); assertEquals(1, n2.get())
    }

    @Test fun serverErrorsAreBounded() = runBlocking {
        val (c, n) = client { Triple(503, "busy", null) }
        val r = c.chat(listOf(ChatMessage("user", "json")))
        assertEquals("SERVER", r.errorCode); assertEquals(4, n.get())   // 1 + maxRetries(3)
        assertTrue(r.error!!.contains("已重试 3 次"))
    }

    @Test fun truncatedOutputRetriedWithDoubleBudget() = runBlocking {
        val seen = mutableListOf<JSONObject>()
        val cut = """{"choices":[{"finish_reason":"length","message":{"content":"{\"screen\":\"列"}}]}"""
        val (c, n) = client(seen) { i -> if (i == 0) Triple(200, cut, null) else Triple(200, ok, null) }
        val r = c.chat(listOf(ChatMessage("user", "json")))
        assertEquals(2, n.get()); assertTrue(r.content.contains("back"))
        assertEquals(seen[0].getInt("max_tokens") * 2, seen[1].getInt("max_tokens"))
    }

    @Test fun emptyContentIsRetryable() = runBlocking {
        val empty = """{"choices":[{"finish_reason":"stop","message":{"content":""}}]}"""
        val (c, n) = client { i -> if (i == 0) Triple(200, empty, null) else Triple(200, ok, null) }
        assertNull(c.chat(listOf(ChatMessage("user", "json"))).error); assertEquals(2, n.get())
    }

    @Test fun cancellationAbortsWithoutRetry() = runBlocking {
        val calls = AtomicInteger(0)
        val http = OkHttpClient.Builder().addInterceptor { chain -> calls.incrementAndGet(); Thread.sleep(5_000); chain.proceed(chain.request()) }.build()
        val c = CloudClient("https://api.deepseek.com", "k", "deepseek-flash", http = http)
        val t0 = System.currentTimeMillis()
        val r = withTimeoutOrNull(300) { c.chat(listOf(ChatMessage("user", "json"))) }
        assertNull(r); assertTrue("cancel must be prompt", System.currentTimeMillis() - t0 < 2_000)
        delay(100); assertEquals(1, calls.get())
    }

    @Test fun backoffHonoursRetryAfterAndCaps() {
        val p = LlmErrors.Policy()
        assertEquals(2000, p.delayMs(1, 2000, 0.5))
        assertEquals(500, p.delayMs(1, null, 0.5)); assertEquals(1000, p.delayMs(2, null, 0.5))
        assertEquals(10_000, p.delayMs(10, null, 1.0))
        assertEquals(500, p.delayMs(1, 60_000, 0.5))  // provider delay above cap → local backoff
        assertEquals(1500L, LlmErrors.retryAfterMs("1.5")); assertNull(LlmErrors.retryAfterMs("Wed, 21 Oct"))
    }

    @Test fun taxonomy() {
        assertEquals(LlmErrors.Code.CONTEXT_WINDOW_EXCEEDED, LlmErrors.classify(400, "This model's maximum context length is 65536 tokens"))
        assertEquals(LlmErrors.Code.INVALID_REQUEST, LlmErrors.classify(400, "bad"))
        assertEquals(LlmErrors.Code.TIMEOUT, LlmErrors.classify(null, error = java.net.SocketTimeoutException()))
        assertEquals(LlmErrors.Code.TRANSPORT, LlmErrors.classify(null, error = java.net.UnknownHostException()))
        fun resp(code: String) = LlmResponse("", "c", 0, "x", errorCode = code)
        assertEquals(LlmRecovery.Action.REOBSERVE, LlmRecovery.decide(resp("RATE_LIMIT"), 0))
        assertEquals(LlmRecovery.Action.COMPACT_AND_RETRY, LlmRecovery.decide(resp("CONTEXT_WINDOW_EXCEEDED"), 0))
        assertEquals(LlmRecovery.Action.STOP, LlmRecovery.decide(resp("AUTH"), 0))
        assertEquals(LlmRecovery.Action.STOP, LlmRecovery.decide(resp("TIMEOUT"), LlmRecovery.MAX_STEP_RETRIES))
    }

    /**
     * Cache-prefix contract: request N+1's serialized messages, minus the closing bracket, start with request N's — i.e. strictly append-only — except exactly at compaction boundaries.
     */
    @Test fun requestsAreStrictPrefixExtensionsUntilCompaction() {
        val c = CloudClient("https://api.deepseek.com", "k", "deepseek-flash")
        val head = listOf(ChatMessage("system", SystemPrompt.SHORT), ChatMessage("user", "用户目标：打开设置里的蓝牙"))
        val t = Transcript()
        var prev: String? = null; var prevSeries = t.series; var compactions = 0; var extensions = 0
        for (step in 1..30) {
            val obs = "第${step}步 屏幕元素：\n" + (1..40).joinToString("\n") { "[e$it] 项目$it-$step (100,${it * 50})" }
            // The provider caches the tokenized prompt = the serialized messages array (JSON key order of the outer
            // object differs between Android's org.json and the JVM one, so compare the messages array itself).
            val body = c.buildBody(head + t.window() + ChatMessage("user", obs), 0.0).getJSONArray("messages").toString()
            if (prev != null) {
                if (t.series == prevSeries) { assertTrue("step $step must extend step ${step - 1}", body.startsWith(prev.dropLast(1))); extensions++ }
                else { compactions++; assertTrue(body.contains("<checkpoint>")) }
            }
            prev = body; prevSeries = t.series
            t.append(obs, """{"state":"列表$step","action":{"type":"tap","node_id":"e3"},"expect":"进入$step"}""")
        }
        assertTrue(compactions >= 2); assertTrue(extensions >= 20)
        assertTrue(head[0].content === SystemPrompt.SHORT)
    }
}

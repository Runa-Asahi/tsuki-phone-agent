package com.runasakura.phoneagent.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible cloud backend (DeepSeek / custom). Tuned for agent steps:
 * one shared keep-alive HTTP/2 client, temperature 0, ≤200 tokens, JSON mode where supported,
 * bounded exponential backoff on transient failures (LlmErrors), cancellable calls, short timeouts.
 */
class CloudClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val jsonMode: Boolean = (baseUrl.contains("deepseek", true) || baseUrl.contains("openai.com", true) ||
        baseUrl.contains("dashscope", true)) && !CloudProviders.isReasoner(model),
    /** User setting: DeepSeek thinking mode (off = fast direct JSON; on = reasoning_effort low). */
    private val thinking: Boolean = false,
    private val maxTokens: Int = if (CloudProviders.isReasoner(model) || thinking) 2000 else 600,
    private val http: OkHttpClient = SHARED,
    private val retryPolicy: LlmErrors.Policy = LlmErrors.Policy()
) : LlmClient {
    override val name: String = (if (baseUrl.contains("deepseek", true)) "deepseek" else "cloud") + ":" + model

    fun buildBody(messages: List<ChatMessage>, temperature: Double, tokens: Int = maxTokens): JSONObject = JSONObject().apply {
        put("model", model)
        // Thinking mode ignores temperature (api-docs.deepseek.com/guides/thinking_mode); 0 for direct steps.
        if (!CloudProviders.isReasoner(model) && !thinking) put("temperature", temperature)
        put("stream", false)
        put("max_tokens", tokens)
        if (jsonMode) put("response_format", JSONObject().put("type", "json_object"))
        // DeepSeek V4 models think by default; agent steps want fast direct JSON.
        if (baseUrl.contains("deepseek", true) && !CloudProviders.isReasoner(model) && !thinkingRejected) {
            put("thinking", JSONObject().put("type", if (thinking) "enabled" else "disabled"))
            if (thinking) put("reasoning_effort", "low")
        }
        put("messages", JSONArray().apply {
            messages.forEach { m ->
                val img = m.imageBase64?.takeIf { !imageRejected && m.role == "user" }
                if (img != null) {
                    val parts = JSONArray()
                        .put(JSONObject().put("type", "text").put("text", m.content))
                        .put(JSONObject().put("type", "image_url").put("image_url",
                            JSONObject().put("url", "data:image/jpeg;base64,$img").put("detail", "low")))
                    put(JSONObject().put("role", m.role).put("content", parts))
                } else put(JSONObject().put("role", m.role).put("content", m.content))
            }
        })
    }

    /** Called before each backoff wait (UI/log: "限流，2.1s 后重试 1/3"). */
    var onRetry: ((LlmErrors.Code, Int, Long) -> Unit)? = null

    override suspend fun chat(messages: List<ChatMessage>, temperature: Double): LlmResponse =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank() || apiKey.isBlank()) {
                return@withContext LlmResponse("", name, 0, "云端 URL / API Key 未配置", errorCode = LlmErrors.Code.AUTH.name)
            }
            val started = System.currentTimeMillis()
            var tokens = maxTokens
            var retry = 0
            var truncRetried = false
            var lastTruncated: Pair<String, String>? = null
            while (true) {
                coroutineContext.ensureActive()
                val req = Request.Builder()
                    .url(baseUrl.trimEnd('/') + "/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(buildBody(messages, temperature, tokens).toString().toRequestBody(JSON))
                    .build()
                var status: Int? = null; var raw = ""; var retryAfter: String? = null; var failure: Throwable? = null
                try {
                    http.newCall(req).awaitResponse().use { resp ->
                        status = resp.code; retryAfter = resp.header("Retry-After"); raw = resp.body?.string().orEmpty()
                    }
                } catch (e: CancellationException) { throw e }   // 急停 / step timeout: never retry after cancel
                catch (e: Exception) { failure = e }
                val latency = System.currentTimeMillis() - started
                val code: LlmErrors.Code
                if (failure == null && status in 200..299) {
                    val content = contentOrReasoningJson(raw)
                    // Output cut by max_tokens → truncated JSON: one retry with a doubled budget.
                    if (finishReason(raw) == "length" && !truncRetried) {
                        truncRetried = true; tokens = maxTokens * 2; lastTruncated = content to raw; continue
                    }
                    if (content.isNotBlank()) return@withContext LlmResponse(content, name, latency, rawBody = raw)
                    // The doubled retry came back empty: the truncated first answer is still better than nothing.
                    lastTruncated?.takeIf { it.first.isNotBlank() }?.let { return@withContext LlmResponse(it.first, name, latency, rawBody = it.second) }
                    code = LlmErrors.Code.EMPTY_RESPONSE   // JSON mode may return empty content (api-docs json_mode)
                } else {
                    if (failure == null && status == 400 && raw.contains("thinking", true) && !thinkingRejected) {
                        thinkingRejected = true; continue   // optional `thinking` field unsupported: drop it for the process
                    }
                    if (failure == null && status == 400 && !imageRejected && messages.any { it.imageBase64 != null } &&
                        !LlmErrors.CONTEXT_WINDOW_EXCEEDED_HINT.containsMatchIn(raw)) {
                        imageRejected = true; continue      // provider/model takes no images: text-only from now on
                    }
                    code = LlmErrors.classify(status, raw, failure)
                }
                if (code in LlmErrors.RETRYABLE && retry < retryPolicy.maxRetries) {
                    val wait = retryPolicy.delayMs(retry + 1, LlmErrors.retryAfterMs(retryAfter), kotlin.random.Random.nextDouble())
                    if (latency + wait < retryPolicy.budgetMs) {
                        retry++; onRetry?.invoke(code, retry, wait); delay(wait); continue
                    }
                }
                // Never echo the key; trim provider error bodies.
                val detail = failure?.let { it.message ?: it.javaClass.simpleName } ?: "HTTP $status: ${raw.take(300)}"
                return@withContext LlmResponse("", name, latency, "${code.zh}（$detail）" + if (retry > 0) "，已重试 $retry 次" else "",
                    raw.ifBlank { null }, errorCode = code.name)
            }
            @Suppress("UNREACHABLE_CODE")
            LlmResponse("", name, 0, "unreachable")
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        fun finishReason(raw: String): String? = try {
            JSONObject(raw).getJSONArray("choices").getJSONObject(0).optString("finish_reason").ifBlank { null }
        } catch (_: Exception) { null }

        /** content, or — when content is empty — the last JSON object found in reasoning_content. */
        fun contentOrReasoningJson(raw: String): String {
            val c = LocalMnnClient.parseContent(raw)
            if (c.isNotBlank()) return c
            val rc = runCatching { JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("reasoning_content") }.getOrNull().orEmpty()
            val end = rc.lastIndexOf('}'); if (end < 0) return ""
            var depth = 0
            for (i in end downTo 0) { when (rc[i]) { '}' -> depth++; '{' -> { depth--; if (depth == 0) return rc.substring(i, end + 1) } } }
            return ""
        }

        /** Set once a provider rejects the DeepSeek `thinking` field; it is then never sent again. */
        @Volatile var thinkingRejected = false
        /** Set once the cloud model rejects image_url parts (HTTP 400); screenshots are then never sent again. */
        @Volatile var imageRejected = false
        /** One pooled client for the whole process: TLS + HTTP/2 connection reuse between steps. */
        val SHARED: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(55, TimeUnit.SECONDS)
                .connectionPool(okhttp3.ConnectionPool(2, 5, TimeUnit.MINUTES))
                .build()
        }
    }
}

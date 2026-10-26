package com.runasakura.phoneagent.llm

import kotlinx.coroutines.Dispatchers
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
 * one retry on 429/5xx with backoff, short timeouts.
 */
class CloudClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val jsonMode: Boolean = (baseUrl.contains("deepseek", true) || baseUrl.contains("openai.com", true) ||
        baseUrl.contains("dashscope", true)) && !CloudProviders.isReasoner(model),
    /** User setting: DeepSeek thinking mode (off = fast direct JSON; on = reasoning_effort low). */
    private val thinking: Boolean = false,
    private val maxTokens: Int = if (CloudProviders.isReasoner(model) || thinking) 2000 else 320,
    private val http: OkHttpClient = SHARED
) : LlmClient {
    override val name: String = (if (baseUrl.contains("deepseek", true)) "deepseek" else "cloud") + ":" + model

    fun buildBody(messages: List<ChatMessage>, temperature: Double): JSONObject = JSONObject().apply {
        put("model", model)
        // Thinking mode ignores temperature (api-docs.deepseek.com/guides/thinking_mode); 0 for direct steps.
        if (!CloudProviders.isReasoner(model) && !thinking) put("temperature", temperature)
        put("stream", false)
        put("max_tokens", maxTokens)
        if (jsonMode) put("response_format", JSONObject().put("type", "json_object"))
        // DeepSeek V4 models think by default; agent steps want fast direct JSON.
        if (baseUrl.contains("deepseek", true) && !CloudProviders.isReasoner(model) && !thinkingRejected) {
            put("thinking", JSONObject().put("type", if (thinking) "enabled" else "disabled"))
            if (thinking) put("reasoning_effort", "low")
        }
        put("messages", JSONArray().apply {
            messages.forEach { m ->
                val img = m.imageBase64?.takeIf { !imageRejected }
                if (img != null) {
                    val parts = JSONArray()
                        .put(JSONObject().put("type", "text").put("text", m.content))
                        .put(JSONObject().put("type", "image_url").put("image_url",
                            JSONObject().put("url", "data:image/jpeg;base64,$img")))
                    put(JSONObject().put("role", m.role).put("content", parts))
                } else put(JSONObject().put("role", m.role).put("content", m.content))
            }
        })
    }

    override suspend fun chat(messages: List<ChatMessage>, temperature: Double): LlmResponse =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank() || apiKey.isBlank()) {
                return@withContext LlmResponse("", name, 0, "云端 URL / API Key 未配置")
            }
            val started = System.currentTimeMillis()
            val body = buildBody(messages, temperature).toString()
            val req = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody(JSON))
                .build()
            var attempt = 0
            while (true) {
                try {
                    http.newCall(req).execute().use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        val latency = System.currentTimeMillis() - started
                        if (resp.isSuccessful) {
                            val content = contentOrReasoningJson(raw)
                            // JSON mode may occasionally return empty content (api-docs.deepseek.com/guides/json_mode): retry once.
                            if (content.isBlank() && attempt == 0) { attempt++; return@use }
                            return@withContext LlmResponse(content, name, latency, rawBody = raw)
                        }
                        if (resp.code == 400 && raw.contains("thinking", true) && !thinkingRejected) {
                            // API rejected the optional `thinking` param: drop it for the rest of the process and retry once.
                            thinkingRejected = true
                            val retry = Request.Builder().url(req.url).headers(req.headers)
                                .post(buildBody(messages, temperature).toString().toRequestBody(JSON)).build()
                            http.newCall(retry).execute().use { r2 ->
                                val raw2 = r2.body?.string().orEmpty()
                                val lat = System.currentTimeMillis() - started
                                return@withContext if (r2.isSuccessful) LlmResponse(LocalMnnClient.parseContent(raw2), name, lat, rawBody = raw2)
                                else LlmResponse("", name, lat, "HTTP ${r2.code}: ${raw2.take(300)}", raw2)
                            }
                        }
                        if (resp.code == 400 && !imageRejected && messages.any { it.imageBase64 != null }) {
                            // Model/provider doesn't take images: remember for the process, retry once text-only.
                            imageRejected = true
                            val retry = Request.Builder().url(req.url).headers(req.headers)
                                .post(buildBody(messages, temperature).toString().toRequestBody(JSON)).build()
                            http.newCall(retry).execute().use { r2 ->
                                val raw2 = r2.body?.string().orEmpty()
                                val lat = System.currentTimeMillis() - started
                                return@withContext if (r2.isSuccessful) LlmResponse(LocalMnnClient.parseContent(raw2), name, lat, rawBody = raw2)
                                else LlmResponse("", name, lat, "HTTP ${r2.code}: ${raw2.take(300)}", raw2)
                            }
                        }
                        if ((resp.code == 429 || resp.code >= 500) && attempt == 0) {
                            attempt++; delay(800L); return@use
                        }
                        // Never echo the key; trim provider error bodies.
                        return@withContext LlmResponse("", name, latency, "HTTP ${resp.code}: ${raw.take(300)}", raw)
                    }
                } catch (e: Exception) {
                    if (attempt == 0) { attempt++; delay(600L); continue }
                    return@withContext LlmResponse("", name, System.currentTimeMillis() - started, e.message ?: e.toString())
                }
            }
            @Suppress("UNREACHABLE_CODE")
            LlmResponse("", name, 0, "unreachable")
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
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
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(40, TimeUnit.SECONDS)
                .connectionPool(okhttp3.ConnectionPool(2, 5, TimeUnit.MINUTES))
                .build()
        }
    }
}

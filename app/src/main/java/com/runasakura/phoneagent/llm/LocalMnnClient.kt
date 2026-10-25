package com.runasakura.phoneagent.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible client for MNN Chat local API (default http://127.0.0.1:8080/v1).
 */
class LocalMnnClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val http: OkHttpClient = defaultClient()
) : LlmClient {
    override val name: String = "mnn_local"

    override suspend fun chat(messages: List<ChatMessage>, temperature: Double): LlmResponse =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            try {
                val body = JSONObject().apply {
                    put("model", model)
                    put("temperature", temperature)
                    put("stream", false)
                    put("messages", JSONArray().apply {
                        messages.forEach { m ->
                            put(JSONObject().put("role", m.role).put("content", m.content))
                        }
                    })
                }
                val url = baseUrl.trimEnd('/') + "/chat/completions"
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(JSON_TYPE))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    val latency = System.currentTimeMillis() - started
                    if (!resp.isSuccessful) {
                        return@withContext LlmResponse(
                            content = "",
                            backend = name,
                            latencyMs = latency,
                            error = "HTTP ${resp.code}: ${raw.take(400)}"
                        )
                    }
                    val content = parseContent(raw)
                    LlmResponse(content = content, backend = name, latencyMs = latency)
                }
            } catch (e: Exception) {
                LlmResponse(
                    content = "",
                    backend = name,
                    latencyMs = System.currentTimeMillis() - started,
                    error = e.message ?: e.javaClass.simpleName
                )
            }
        }

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        fun parseContent(raw: String): String {
            val obj = JSONObject(raw)
            val choices = obj.optJSONArray("choices") ?: return raw
            if (choices.length() == 0) return ""
            val msg = choices.getJSONObject(0).optJSONObject("message")
            return msg?.optString("content").orEmpty().ifEmpty {
                choices.getJSONObject(0).optString("text")
            }
        }
    }
}

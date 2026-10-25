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

/** Optional OpenAI-compatible cloud backend for hard GUI steps. */
class CloudClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(200, TimeUnit.SECONDS)
        .build()
) : LlmClient {
    override val name: String = "cloud"

    override suspend fun chat(messages: List<ChatMessage>, temperature: Double): LlmResponse =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank() || apiKey.isBlank()) {
                return@withContext LlmResponse(
                    content = "",
                    backend = name,
                    latencyMs = 0,
                    error = "Cloud URL/API key not configured"
                )
            }
            val started = System.currentTimeMillis()
            try {
                val body = JSONObject().apply {
                    put("model", model)
                    put("temperature", temperature)
                    put("stream", false)
                    put("max_tokens", 256)
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
                    .post(body.toString().toRequestBody(JSON))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    val latency = System.currentTimeMillis() - started
                    if (!resp.isSuccessful) {
                        return@withContext LlmResponse(
                            "", name, latency, "HTTP ${resp.code}: ${raw.take(400)}", raw
                        )
                    }
                    LlmResponse(LocalMnnClient.parseContent(raw), name, latency, rawBody = raw)
                }
            } catch (e: Exception) {
                LlmResponse("", name, System.currentTimeMillis() - started, e.message)
            }
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

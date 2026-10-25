package com.runasakura.phoneagent.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAI-compatible client for MNN Chat local API (default http://127.0.0.1:8080/v1).
 */
class LocalMnnClient(
    private val baseUrl: String,
    private val apiKey: String,
    private var model: String,
    private val http: OkHttpClient = defaultClient()
) : LlmClient {
    override val name: String = "mnn_local"
    private val resolvedModel = AtomicReference(model)

    override suspend fun chat(messages: List<ChatMessage>, temperature: Double): LlmResponse =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            try {
                ensureModel()
                val modelName = resolvedModel.get().orEmpty()
                val body = JSONObject().apply {
                    if (modelName.isNotBlank()) put("model", modelName)
                    put("temperature", temperature)
                    put("stream", false)
                    put("messages", JSONArray().apply {
                        messages.forEach { m ->
                            put(JSONObject().put("role", m.role).put("content", m.content))
                        }
                    })
                }
                val url = baseUrl.trimEnd('/') + "/chat/completions"
                Log.i(TAG, "POST $url model=$modelName bytes=${body.toString().length}")
                val req = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(JSON_TYPE))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    val latency = System.currentTimeMillis() - started
                    Log.i(TAG, "HTTP ${resp.code} in ${latency}ms bodyLen=${raw.length}")
                    if (!resp.isSuccessful) {
                        return@withContext LlmResponse(
                            content = "",
                            backend = name,
                            latencyMs = latency,
                            error = "HTTP ${resp.code}: ${raw.take(500)}",
                            rawBody = raw
                        )
                    }
                    val content = parseContent(raw)
                    LlmResponse(
                        content = content,
                        backend = name,
                        latencyMs = latency,
                        rawBody = raw
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "chat failed", e)
                LlmResponse(
                    content = "",
                    backend = name,
                    latencyMs = System.currentTimeMillis() - started,
                    error = e.message ?: e.javaClass.simpleName
                )
            }
        }

    private fun ensureModel() {
        val current = resolvedModel.get()
        if (!current.isNullOrBlank() && current != "mnn-local" && current != "auto") {
            return
        }
        // Prefer server-advertised model; mnn-local is often ignored but some builds are picky
        fetchFirstModel()?.let { resolvedModel.set(it) }
        if (resolvedModel.get().isNullOrBlank()) {
            resolvedModel.set(if (current.isNullOrBlank() || current == "auto") "" else current)
        }
    }

    private fun fetchFirstModel(): String? {
        return try {
            val url = baseUrl.trimEnd('/') + "/models"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val raw = resp.body?.string().orEmpty()
                val data = JSONObject(raw).optJSONArray("data") ?: return null
                if (data.length() == 0) return null
                data.getJSONObject(0).optString("id").ifBlank { null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetch models failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "LocalMnnClient"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS) // MNN 0.6B–4B on-device can take 30–90s+
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(200, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun parseContent(raw: String): String {
            if (raw.isBlank()) return ""
            return try {
                val obj = JSONObject(raw)
                val choices = obj.optJSONArray("choices") ?: return raw
                if (choices.length() == 0) return ""
                val c0 = choices.getJSONObject(0)
                val msg = c0.optJSONObject("message")
                val content = msg?.opt("content")
                when (content) {
                    is String -> content
                    is JSONArray -> {
                        // multimodal-style content parts
                        buildString {
                            for (i in 0 until content.length()) {
                                val part = content.opt(i)
                                when (part) {
                                    is String -> append(part)
                                    is JSONObject -> append(part.optString("text"))
                                }
                            }
                        }
                    }
                    null -> c0.optString("text").ifBlank {
                        c0.optJSONObject("delta")?.optString("content").orEmpty()
                    }
                    else -> content.toString()
                }
            } catch (e: Exception) {
                Log.w(TAG, "parseContent fallback: ${e.message}")
                raw
            }
        }
    }
}

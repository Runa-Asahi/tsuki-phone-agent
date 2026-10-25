package com.runasakura.phoneagent.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * OpenAI-compatible client for MNN Chat local API (default http://127.0.0.1:8080/v1).
 * Calls are cancellable: cancelling the coroutine (急停 / step timeout) cancels the OkHttp Call.
 */
class LocalMnnClient(
    private val baseUrl: String,
    private val apiKey: String,
    model: String,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val http: OkHttpClient = defaultClient()
) : LlmClient {
    override val name: String = "mnn_local"
    private val resolvedModel = AtomicReference(model)

    /** Resolve and return the model name the server reports (/v1/models). */
    suspend fun resolveModel(): String = withContext(Dispatchers.IO) {
        val current = resolvedModel.get().orEmpty()
        if (current.isBlank() || current == "mnn-local" || current == "auto") {
            fetchFirstModel()?.let { resolvedModel.set(it) }
        }
        resolvedModel.get().orEmpty()
    }

    override suspend fun chat(messages: List<ChatMessage>, temperature: Double): LlmResponse {
        val started = System.currentTimeMillis()
        val modelName = try { resolveModel() } catch (_: Exception) { "" }
        val body = JSONObject().apply {
            if (modelName.isNotBlank() && modelName != "mnn-local") put("model", modelName)
            put("temperature", temperature)
            put("stream", false)
            put("max_tokens", maxTokens)
            put("messages", JSONArray().apply {
                messages.forEach { m ->
                    put(JSONObject().put("role", m.role).put("content", m.content))
                }
            })
        }
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        Log.i(TAG, "POST model=$modelName max_tokens=$maxTokens bytes=${body.toString().length}")
        val call = http.newCall(req)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                Log.w(TAG, "cancelling in-flight MNN call")
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(
                        LlmResponse(
                            "", name, System.currentTimeMillis() - started,
                            if (call.isCanceled()) "cancelled" else (e.message ?: e.javaClass.simpleName)
                        )
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    val raw = try { response.body?.string().orEmpty() } catch (e: Exception) { "" }
                    val latency = System.currentTimeMillis() - started
                    response.close()
                    val r = if (!response.isSuccessful) {
                        LlmResponse("", name, latency, "HTTP ${response.code}: ${raw.take(500)}", raw)
                    } else {
                        LlmResponse(parseContent(raw), name, latency, rawBody = raw)
                    }
                    if (cont.isActive) cont.resume(r)
                }
            })
        }
    }

    private fun fetchFirstModel(): String? = try {
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else {
                val data = JSONObject(resp.body?.string().orEmpty()).optJSONArray("data")
                if (data == null || data.length() == 0) null
                else data.getJSONObject(0).optString("id").ifBlank { null }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "fetch models failed: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "LocalMnnClient"
        const val DEFAULT_MAX_TOKENS = 192
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(95, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(100, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // never re-send to a serial MNN queue
            .build()

        fun parseContent(raw: String): String {
            if (raw.isBlank()) return ""
            return try {
                val obj = JSONObject(raw)
                val choices = obj.optJSONArray("choices") ?: return raw
                if (choices.length() == 0) return ""
                val c0 = choices.getJSONObject(0)
                when (val content = c0.optJSONObject("message")?.opt("content")) {
                    is String -> content
                    is JSONArray -> buildString {
                        for (i in 0 until content.length()) {
                            when (val part = content.opt(i)) {
                                is String -> append(part)
                                is JSONObject -> append(part.optString("text"))
                            }
                        }
                    }
                    null -> c0.optString("text")
                    else -> content.toString()
                }
            } catch (e: Exception) {
                raw
            }
        }
    }
}

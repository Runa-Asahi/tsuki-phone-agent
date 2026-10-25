package com.runasakura.phoneagent.llm

import com.runasakura.phoneagent.prefs.AgentPreferences

object CloudProviders {
    data class Preset(val id: String, val label: String, val baseUrl: String, val model: String, val models: List<String> = listOf(model))

    const val PROVIDER_QWEN = "qwen"
    val ALL = listOf(
        Preset(AgentPreferences.PROVIDER_NONE, "不使用云端", "", "", emptyList()),
        Preset(AgentPreferences.PROVIDER_DEEPSEEK, "DeepSeek", AgentPreferences.DEEPSEEK_BASE, AgentPreferences.DEEPSEEK_MODEL,
            listOf("deepseek-chat", "deepseek-reasoner")),
        Preset(PROVIDER_QWEN, "通义千问（阿里云百炼）", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus",
            listOf("qwen-plus", "qwen-turbo", "qwen-max", "qwen-flash")),
        Preset(AgentPreferences.PROVIDER_OPENAI, "OpenAI 兼容（自定义）", "", "gpt-4o-mini",
            listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1"))
    )

    /** Model used for requests: the saved one if any, else the provider default. */
    fun effectiveModel(provider: String, baseUrl: String, key: String, model: String): String =
        model.trim().ifBlank { byId(effectiveProvider(provider, baseUrl, key)).model.ifBlank { AgentPreferences.DEEPSEEK_MODEL } }

    /** Reasoning models need a bigger budget and no JSON mode. */
    fun isReasoner(model: String) = model.contains("reasoner", true) || model.contains("-r1", true) || model.startsWith("o1") || model.startsWith("o3")

    /** Parse an OpenAI-style GET /models body → sorted ids. */
    fun parseModels(raw: String): List<String> = try {
        val arr = org.json.JSONObject(raw).optJSONArray("data") ?: org.json.JSONArray()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }.distinct().sorted()
    } catch (e: Exception) { emptyList() }

    /** 「获取模型列表」: GET {base}/models with the key. Returns (models, error). */
    suspend fun listModels(baseUrl: String, key: String): Pair<List<String>, String?> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (baseUrl.isBlank() || key.isBlank()) return@withContext emptyList<String>() to "请先填写地址和 Key"
            try {
                val req = okhttp3.Request.Builder().url(baseUrl.trim().trimEnd('/') + "/models")
                    .addHeader("Authorization", "Bearer ${key.trim()}").get().build()
                CloudClient.SHARED.newCall(req).execute().use { r ->
                    val raw = r.body?.string().orEmpty()
                    if (!r.isSuccessful) emptyList<String>() to "HTTP ${r.code}: ${raw.take(160)}"
                    else parseModels(raw).let { if (it.isEmpty()) it to "返回中没有模型" else it to null }
                }
            } catch (e: Exception) { emptyList<String>() to (e.message ?: e.toString()) }
        }

    fun byId(id: String) = ALL.firstOrNull { it.id == id } ?: ALL.first()

    /**
     * Provider actually in effect. Root cause of 「选了 cloud_only 还在用本地」: users who had set a
     * URL/key before the provider spinner existed (or left it on 「不使用云端」) got provider=none →
     * no cloud client. Infer it from the URL instead of silently disabling the cloud.
     */
    fun effectiveProvider(provider: String, baseUrl: String, key: String): String = when {
        provider != AgentPreferences.PROVIDER_NONE -> provider
        baseUrl.isBlank() || key.isBlank() -> AgentPreferences.PROVIDER_NONE
        baseUrl.contains("deepseek", true) -> AgentPreferences.PROVIDER_DEEPSEEK
        baseUrl.contains("dashscope", true) -> PROVIDER_QWEN
        else -> AgentPreferences.PROVIDER_OPENAI
    }

    fun ready(provider: String, baseUrl: String, key: String) =
        effectiveProvider(provider, baseUrl, key) != AgentPreferences.PROVIDER_NONE && baseUrl.isNotBlank() && key.isNotBlank()

    fun clientFor(prefs: AgentPreferences): CloudClient? {
        if (!ready(prefs.cloudProvider, prefs.cloudBaseUrl, prefs.cloudApiKey)) return null
        val m = effectiveModel(prefs.cloudProvider, prefs.cloudBaseUrl, prefs.cloudApiKey, prefs.cloudModel)
        return CloudClient(prefs.cloudBaseUrl, prefs.cloudApiKey, m)
    }

    fun mask(k: String) = when { k.isEmpty() -> "未配置"; k.length <= 8 -> "已配置(****)"; else -> "已配置(" + k.take(4) + "…" + k.takeLast(4) + ")" }

    /** One-line brain description for logs and the home card. */
    fun describe(mode: String, provider: String, baseUrl: String, model: String, key: String, localModel: String): String {
        val p = effectiveProvider(provider, baseUrl, key)
        val cloud = if (p == AgentPreferences.PROVIDER_NONE) "无" else "${byId(p).label} 模型=${effectiveModel(provider, baseUrl, key, model)} key=${mask(key)}"
        return when (mode) {
            AgentPreferences.ROUTE_CLOUD_ONLY -> "路由=${RoutePolicy.label(mode)} 提供商=$cloud"
            AgentPreferences.ROUTE_LOCAL_ONLY -> "路由=${RoutePolicy.label(mode)} 本机=$localModel"
            else -> "路由=${RoutePolicy.label(mode)} 本机=$localModel 云端=$cloud"
        }
    }

    /** 「测试连接」: tiny JSON request; returns (ok, message with latency/model). */
    suspend fun test(baseUrl: String, key: String, model: String): Pair<Boolean, String> {
        val c = CloudClient(baseUrl, key, model, maxTokens = 20)
        val r = c.chat(listOf(ChatMessage("system", "只输出 JSON。"), ChatMessage("user", "输出 {\"ok\":true}")), 0.0)
        return if (r.error == null && r.content.isNotBlank()) true to "连接成功 ${r.latencyMs}ms 模型=$model"
        else false to "失败：${r.error ?: "空响应"}（${r.latencyMs}ms）"
    }
}

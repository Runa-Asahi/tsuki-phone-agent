package com.runasakura.phoneagent.llm

import com.runasakura.phoneagent.prefs.AgentPreferences

object CloudProviders {
    data class Preset(val id: String, val label: String, val baseUrl: String, val model: String)

    val ALL = listOf(
        Preset(AgentPreferences.PROVIDER_NONE, "不使用云端", "", ""),
        Preset(AgentPreferences.PROVIDER_DEEPSEEK, "DeepSeek", AgentPreferences.DEEPSEEK_BASE, AgentPreferences.DEEPSEEK_MODEL),
        Preset(AgentPreferences.PROVIDER_OPENAI, "OpenAI 兼容（自定义）", "", "gpt-4o-mini")
    )

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
        else -> AgentPreferences.PROVIDER_OPENAI
    }

    fun ready(provider: String, baseUrl: String, key: String) =
        effectiveProvider(provider, baseUrl, key) != AgentPreferences.PROVIDER_NONE && baseUrl.isNotBlank() && key.isNotBlank()

    fun clientFor(prefs: AgentPreferences): CloudClient? {
        if (!ready(prefs.cloudProvider, prefs.cloudBaseUrl, prefs.cloudApiKey)) return null
        return CloudClient(prefs.cloudBaseUrl, prefs.cloudApiKey, prefs.cloudModel.ifBlank { AgentPreferences.DEEPSEEK_MODEL })
    }

    fun mask(k: String) = when { k.isEmpty() -> "未配置"; k.length <= 8 -> "已配置(****)"; else -> "已配置(" + k.take(4) + "…" + k.takeLast(4) + ")" }

    /** One-line brain description for logs and the home card. */
    fun describe(mode: String, provider: String, baseUrl: String, model: String, key: String, localModel: String): String {
        val p = effectiveProvider(provider, baseUrl, key)
        val cloud = if (p == AgentPreferences.PROVIDER_NONE) "无" else "${byId(p).label} $model key=${mask(key)}"
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

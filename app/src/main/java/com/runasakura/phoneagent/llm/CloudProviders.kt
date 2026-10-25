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

    fun clientFor(prefs: AgentPreferences): CloudClient? {
        if (prefs.cloudProvider == AgentPreferences.PROVIDER_NONE) return null
        if (prefs.cloudBaseUrl.isBlank() || prefs.cloudApiKey.isBlank()) return null
        return CloudClient(prefs.cloudBaseUrl, prefs.cloudApiKey, prefs.cloudModel.ifBlank { "deepseek-chat" })
    }
}

package com.runasakura.phoneagent.llm

import com.runasakura.phoneagent.prefs.AgentPreferences

class LlmRouter(
    private val prefs: AgentPreferences,
    private val local: LlmClient,
    private val cloud: LlmClient?
) {
    suspend fun plan(
        messages: List<ChatMessage>,
        forceCloud: Boolean = false,
        temperature: Double = 0.2
    ): LlmResponse {
        val mode = prefs.routeMode
        if (forceCloud && cloud != null && mode != AgentPreferences.ROUTE_LOCAL_ONLY) {
            val r = cloud.chat(messages, temperature)
            if (r.error == null) return r
            if (mode == AgentPreferences.ROUTE_HYBRID) {
                return local.chat(messages, temperature)
            }
            return r
        }
        return when (mode) {
            AgentPreferences.ROUTE_LOCAL_ONLY -> local.chat(messages, temperature)
            AgentPreferences.ROUTE_CLOUD_ONLY -> cloud?.chat(messages, temperature)
                ?: LlmResponse("", "cloud", 0, "未配置云端（设置里选择 DeepSeek / OpenAI 兼容并填写 API Key）")
            AgentPreferences.ROUTE_LOCAL_FIRST,
            AgentPreferences.ROUTE_HYBRID -> {
                val localResp = local.chat(messages, temperature)
                if (localResp.error == null && localResp.content.isNotBlank()) {
                    localResp
                } else if (mode == AgentPreferences.ROUTE_HYBRID && cloud != null) {
                    val cloudResp = cloud.chat(messages, temperature)
                    if (cloudResp.error == null) {
                        cloudResp
                    } else {
                        localResp.copy(
                            error = listOfNotNull(localResp.error, cloudResp.error)
                                .joinToString(" | ")
                        )
                    }
                } else {
                    localResp
                }
            }
            else -> local.chat(messages, temperature)
        }
    }
}

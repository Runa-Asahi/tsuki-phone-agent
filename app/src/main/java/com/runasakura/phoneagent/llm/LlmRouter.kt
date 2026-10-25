package com.runasakura.phoneagent.llm

import com.runasakura.phoneagent.prefs.AgentPreferences
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Routes each planning call. cloud_only NEVER touches the local MNN server.
 * Local calls get a short stall budget when a cloud fallback exists (MNN Chat backgrounded
 * on MagicOS can take 90–170 s), then the same step goes to the cloud.
 */
class LlmRouter(
    /** Read on EVERY call — never cached at service start. */
    private val modeOf: () -> String,
    private val local: LlmClient,
    private val cloud: LlmClient?,
    private val stallMs: Long = 15_000L,
    private val fullMs: Long = 90_000L
) {
    var log: (String) -> Unit = {}

    val mode: String get() = modeOf()

    suspend fun plan(
        messages: List<ChatMessage>,
        forceCloud: Boolean = false,
        temperature: Double = 0.2
    ): LlmResponse {
        val order = if (forceCloud && cloud != null && mode != AgentPreferences.ROUTE_LOCAL_ONLY)
            listOf(RoutePolicy.Backend.CLOUD) else RoutePolicy.order(mode, cloud != null)
        var last: LlmResponse? = null
        for ((i, b) in order.withIndex()) {
            val hasNext = i < order.lastIndex
            when (b) {
                RoutePolicy.Backend.CLOUD -> {
                    val c = cloud ?: return LlmResponse("", "cloud", 0,
                        "路由=仅云端，但云端未就绪（设置里选择提供商并填写 API Key）")
                    val r = c.chat(messages, 0.0)
                    log("云端 ${c.name} ${r.latencyMs}ms" + (r.error?.let { " 错误：$it" } ?: ""))
                    if (r.error == null && r.content.isNotBlank()) return r
                    last = r
                }
                RoutePolicy.Backend.LOCAL -> {
                    val budget = if (hasNext) stallMs else fullMs
                    val t0 = System.currentTimeMillis()
                    val r = withTimeoutOrNull(budget) { local.chat(messages, temperature) }
                    if (r == null) {
                        val msg = "本机 MNN ${budget / 1000}s 无响应（MNN Chat 在后台常被 MagicOS 限速）"
                        log(if (hasNext) "$msg，本步改用云端" else msg)
                        last = LlmResponse("", local.name, System.currentTimeMillis() - t0, "local stall ${budget}ms")
                        continue
                    }
                    log("本机 MNN ${r.latencyMs}ms" + (r.error?.let { " 错误：$it" } ?: ""))
                    if (r.error == null && r.content.isNotBlank()) return r
                    last = r
                    if (hasNext) log("本机失败，本步改用云端")
                }
            }
        }
        return last ?: LlmResponse("", "none", 0, "no backend")
    }
}

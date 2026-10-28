package com.runasakura.phoneagent.llm

import com.runasakura.phoneagent.prefs.AgentPreferences

/** Pure routing decision — unit-tested for every mode. */
object RoutePolicy {
    enum class Backend { LOCAL, CLOUD }

    fun order(mode: String, cloudReady: Boolean): List<Backend> = when (mode) {
        AgentPreferences.ROUTE_CLOUD_ONLY -> listOf(Backend.CLOUD)           // never local, even if cloud missing
        AgentPreferences.ROUTE_LOCAL_ONLY -> listOf(Backend.LOCAL)
        // Cloud decides; local is only the offline fallback for the decision (it assists elsewhere, in parallel).
        AgentPreferences.ROUTE_CLOUD_ASSIST -> if (cloudReady) listOf(Backend.CLOUD, Backend.LOCAL) else listOf(Backend.LOCAL)
        AgentPreferences.ROUTE_LOCAL_FIRST, AgentPreferences.ROUTE_HYBRID ->
            if (cloudReady) listOf(Backend.LOCAL, Backend.CLOUD) else listOf(Backend.LOCAL)
        else -> listOf(Backend.LOCAL)
    }

    /** Whether the local MNN server may be touched at all (health checks included). */
    fun usesLocal(mode: String) = mode != AgentPreferences.ROUTE_CLOUD_ONLY

    /** Slow-local stall budget: short when a cloud fallback exists, else the full step budget. */
    /**
     * Hedge delay: how long a local call runs alone before the cloud is started in parallel (first good answer wins).
     * Learns from the last local latency so a fast warm model is not pre-empted and a slow one never stalls a step.
     */
    fun hedgeMs(lastLocalMs: Long?): Long = when {
        lastLocalMs == null -> 4_000L
        else -> (lastLocalMs * 3 / 2).coerceIn(2_000L, 8_000L)
    }

    /** After a local stall, skip local for a cooldown (not the whole task) and then try it again. */
    const val LOCAL_COOLDOWN_MS = 45_000L

    /** Cloud failures that mean "offline/unreachable" (fall back to local) rather than "bad request". */
    fun isOffline(errorCode: String?, error: String?): Boolean =
        errorCode in setOf("TRANSPORT", "TIMEOUT", "SERVER") ||
            (error != null && listOf("unable to resolve", "timeout", "failed to connect", "network").any { error.contains(it, true) })

    fun localBudgetMs(order: List<Backend>, full: Long, stall: Long = 15_000L): Long =
        if (order.size > 1 && order.first() == Backend.LOCAL) stall else full

    fun label(mode: String) = when (mode) {
        AgentPreferences.ROUTE_LOCAL_ONLY -> "仅本机"
        AgentPreferences.ROUTE_LOCAL_FIRST, AgentPreferences.ROUTE_HYBRID -> "本机优先+云端兜底"
        AgentPreferences.ROUTE_CLOUD_ONLY -> "仅云端"
        AgentPreferences.ROUTE_CLOUD_ASSIST -> "云端为主、本机为辅"
        else -> mode
    }
}

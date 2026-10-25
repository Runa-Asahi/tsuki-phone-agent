package com.runasakura.phoneagent.llm

import com.runasakura.phoneagent.prefs.AgentPreferences

/** Pure routing decision — unit-tested for every mode. */
object RoutePolicy {
    enum class Backend { LOCAL, CLOUD }

    fun order(mode: String, cloudReady: Boolean): List<Backend> = when (mode) {
        AgentPreferences.ROUTE_CLOUD_ONLY -> listOf(Backend.CLOUD)           // never local, even if cloud missing
        AgentPreferences.ROUTE_LOCAL_ONLY -> listOf(Backend.LOCAL)
        AgentPreferences.ROUTE_LOCAL_FIRST, AgentPreferences.ROUTE_HYBRID ->
            if (cloudReady) listOf(Backend.LOCAL, Backend.CLOUD) else listOf(Backend.LOCAL)
        else -> listOf(Backend.LOCAL)
    }

    /** Whether the local MNN server may be touched at all (health checks included). */
    fun usesLocal(mode: String) = mode != AgentPreferences.ROUTE_CLOUD_ONLY

    /** Slow-local stall budget: short when a cloud fallback exists, else the full step budget. */
    fun localBudgetMs(order: List<Backend>, full: Long, stall: Long = 15_000L): Long =
        if (order.size > 1 && order.first() == Backend.LOCAL) stall else full

    fun label(mode: String) = when (mode) {
        AgentPreferences.ROUTE_LOCAL_ONLY -> "仅本机"
        AgentPreferences.ROUTE_LOCAL_FIRST, AgentPreferences.ROUTE_HYBRID -> "本机优先+云端兜底"
        AgentPreferences.ROUTE_CLOUD_ONLY -> "仅云端"
        else -> mode
    }
}

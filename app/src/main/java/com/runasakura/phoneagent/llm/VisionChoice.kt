package com.runasakura.phoneagent.llm

import com.runasakura.phoneagent.prefs.AgentPreferences

/**
 * Who describes screenshots locally (the 看图技能). Pure, unit-tested.
 * The model built into Tsuki wins whenever a vision model (visual.mnn) is installed and the native engine loads,
 * whatever the text engine choice is; MNN Chat's API is only a fallback when there is no built-in vision model.
 * 「仅云端」 means no local model at all, so it gets none.
 */
object VisionChoice {
    enum class Source(val label: String) { EMBEDDED("Tsuki 内置本机模型"), MNN_CHAT("MNN Chat 视觉模型（API）"), NONE("无") }

    fun pick(route: String, embeddedVisionInstalled: Boolean, embeddedAvailable: Boolean, mnnChatToggle: Boolean): Source = when {
        route == AgentPreferences.ROUTE_CLOUD_ONLY -> Source.NONE
        embeddedVisionInstalled && embeddedAvailable -> Source.EMBEDDED
        mnnChatToggle -> Source.MNN_CHAT
        else -> Source.NONE
    }

    /** One honest line for logs / Settings: every eye the agent has this run, in the order it uses them. */
    fun describe(cloudSees: Boolean, cloudModel: String, shotsAllowed: Boolean, local: Source, localModel: String?): String {
        val parts = ArrayList<String>()
        if (cloudSees && shotsAllowed) parts += "云端 $cloudModel 直接看截图"
        when (local) {
            Source.EMBEDDED -> parts += "本机 ${localModel ?: "内置模型"}（Tsuki 内置，后台描述截图，不阻塞决策）"
            Source.MNN_CHAT -> parts += "本机 MNN Chat 视觉模型（需在 MNN Chat 里加载并开启 API）"
            Source.NONE -> {}
        }
        val blocked = if (cloudSees && !shotsAllowed) "云端 $cloudModel 能看图，但未允许发送截图" else null
        return when {
            parts.isEmpty() -> "无" + (blocked?.let { "（$it）" } ?: "") + " → 用文字 OCR + 色块候选代替截图"
            else -> (parts + listOfNotNull(blocked)).joinToString("；")
        }
    }
}

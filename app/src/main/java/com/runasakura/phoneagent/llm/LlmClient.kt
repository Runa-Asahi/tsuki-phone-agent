package com.runasakura.phoneagent.llm

data class ChatMessage(val role: String, val content: String)

data class LlmResponse(
    val content: String,
    val backend: String,
    val latencyMs: Long,
    val error: String? = null
)

interface LlmClient {
    val name: String
    suspend fun chat(messages: List<ChatMessage>, temperature: Double = 0.2): LlmResponse
}

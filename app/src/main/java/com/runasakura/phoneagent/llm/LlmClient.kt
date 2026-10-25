package com.runasakura.phoneagent.llm

data class ChatMessage(
    val role: String,
    val content: String,
    /** Optional JPEG base64 screenshot (only sent to VL models). */
    val imageBase64: String? = null
)

data class LlmResponse(
    val content: String,
    val backend: String,
    val latencyMs: Long,
    val error: String? = null,
    /** Full HTTP body for on-screen debug (may be large). */
    val rawBody: String? = null
)

interface LlmClient {
    val name: String
    suspend fun chat(messages: List<ChatMessage>, temperature: Double = 0.2): LlmResponse
}

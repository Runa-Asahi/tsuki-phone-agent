package com.runasakura.phoneagent.llm

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.pow

/**
 * Provider-neutral failure taxonomy and bounded retry policy, after DeepSeek Harness
 * packages/llm/llm/src/error.ts (stable machine-routable codes), packages/llm/llm-deepseek/src/transport.ts
 * (status → code) and packages/llm/llm/src/retry-policy.ts + llm-retry (exponential backoff with symmetric
 * jitter, provider Retry-After honoured when ≤ maxDelay, cancellable waits, only transient codes retried).
 */
object LlmErrors {
    enum class Code(val zh: String) {
        AUTH("API Key 无效或无权限，请到设置里检查 Key"),
        QUOTA("账户余额或额度不足，请到服务商控制台充值"),
        RATE_LIMIT("请求太频繁被限流"),
        CONTEXT_WINDOW_EXCEEDED("上下文超长"),
        INVALID_REQUEST("请求被服务端拒绝（参数不被支持）"),
        SERVER("服务端繁忙或出错"),
        TIMEOUT("网络超时"),
        TRANSPORT("网络连接失败"),
        EMPTY_RESPONSE("模型返回了空内容"),
    }

    /** Transient classes that are safe to repeat (the attempt produced nothing durable). */
    val RETRYABLE = setOf(Code.EMPTY_RESPONSE, Code.RATE_LIMIT, Code.SERVER, Code.TIMEOUT, Code.TRANSPORT)

    val CONTEXT_WINDOW_EXCEEDED_HINT get() = CONTEXT_OVERFLOW
    private val CONTEXT_OVERFLOW = Regex("context[\\s_-]?(length|window)|maximum context|too many tokens|prompt is too long", RegexOption.IGNORE_CASE)
    private val QUOTA_TEXT = Regex("insufficient[\\s_-]?(balance|quota)|quota[\\s_-]?exceeded|余额不足", RegexOption.IGNORE_CASE)

    fun classify(status: Int?, body: String = "", error: Throwable? = null): Code = when {
        error is InterruptedIOException -> Code.TIMEOUT          // SocketTimeoutException + OkHttp call timeout
        error != null -> Code.TRANSPORT
        status == 401 || status == 403 -> Code.AUTH
        status == 402 || QUOTA_TEXT.containsMatchIn(body) -> Code.QUOTA
        status == 429 -> Code.RATE_LIMIT
        (status == 400 || status == 413) && CONTEXT_OVERFLOW.containsMatchIn(body) -> Code.CONTEXT_WINDOW_EXCEEDED
        status == 400 || status == 413 || status == 422 -> Code.INVALID_REQUEST
        status != null && status >= 500 -> Code.SERVER
        status == 408 -> Code.TIMEOUT
        else -> Code.SERVER
    }

    fun codeOf(r: LlmResponse): Code? = r.errorCode?.let { c -> Code.entries.firstOrNull { it.name == c } }

    /** Retry-After: delta-seconds only (HTTP-date is rare for LLM APIs and then falls back to local backoff). */
    fun retryAfterMs(header: String?): Long? = header?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000).toLong() }

    data class Policy(
        val maxRetries: Int = 3,
        val initialDelayMs: Long = 500,
        val maxDelayMs: Long = 10_000,
        val jitterRatio: Double = 0.1,
        /** Whole-request budget incl. retries: a phone step must not hang behind a flaky network. */
        val budgetMs: Long = 60_000,
    ) {
        /** retry is 1-based. Provider delay wins when it fits under maxDelay (harness llm-retry semantics). */
        fun delayMs(retry: Int, providerRetryAfterMs: Long?, random: Double): Long {
            if (providerRetryAfterMs != null && providerRetryAfterMs in 1..maxDelayMs) return providerRetryAfterMs
            val exp = initialDelayMs * 2.0.pow(retry - 1)
            val jitter = 1 - jitterRatio + 2 * jitterRatio * random
            return min(exp * jitter, maxDelayMs.toDouble()).toLong()
        }
    }
}

/** Cancellable OkHttp call: coroutine cancellation (急停 / step timeout) aborts the socket immediately. */
suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

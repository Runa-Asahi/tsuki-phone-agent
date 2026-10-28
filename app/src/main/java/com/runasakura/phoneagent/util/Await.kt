package com.runasakura.phoneagent.util

import kotlinx.coroutines.delay

object Await {
    /** Poll [get] every [pollMs] until non-null or [timeoutMs] elapses (e.g. accessibility re-attach). */
    suspend fun <T : Any> nonNull(timeoutMs: Long = 10_000, pollMs: Long = 250, get: () -> T?): T? {
        get()?.let { return it }
        var waited = 0L
        while (waited < timeoutMs) {
            delay(pollMs); waited += pollMs
            get()?.let { return it }
        }
        return null
    }
}

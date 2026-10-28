package com.runasakura.phoneagent.ocr

/**
 * When to take the next screenshot (replaces the old fixed "340 ms since the last shot" pre-wait).
 *  - Shizuku screencap has no rate limit → no gap at all. The accessibility API rejects shots closer than
 *    [A11Y_GAP_MS]; only then, and only for the remainder, we wait.
 *  - "Capture once the frame is stable": if the UI fired an accessibility event in the last [QUIET_MS] (animation,
 *    list settling) wait until it has been quiet that long, but never more than [MAX_SETTLE_MS] (games/video never stop).
 * Pure; unit-tested.
 */
object CapturePacing {
    const val A11Y_GAP_MS = 334L
    const val QUIET_MS = 90L
    const val MAX_SETTLE_MS = 220L

    fun waitMs(now: Long, lastShotAt: Long, lastEventAt: Long, throttled: Boolean): Long {
        val gap = if (throttled) A11Y_GAP_MS - (now - lastShotAt) else 0L
        val sinceEvent = now - lastEventAt
        val settle = if (sinceEvent in 0 until QUIET_MS) (QUIET_MS - sinceEvent).coerceAtMost(MAX_SETTLE_MS) else 0L
        return maxOf(gap, settle, 0L)
    }
}

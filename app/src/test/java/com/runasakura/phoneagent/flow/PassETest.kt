package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.a11y.AgentAccessibilityService
import com.runasakura.phoneagent.util.Await
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassETest {
    @Test fun awaitResumesWhenServiceReattaches() = runBlocking {
        var n = 0
        assertEquals("svc", Await.nonNull(2_000, 10) { if (++n >= 5) "svc" else null })
    }
    @Test fun awaitTimesOutCleanly() = runBlocking {
        val t0 = System.currentTimeMillis()
        assertNull(Await.nonNull<String>(200, 20) { null })
        assertTrue(System.currentTimeMillis() - t0 < 2_000)
    }
    @Test fun enabledInSettingsParsing() {
        val p = "com.runasakura.phoneagent.debug"
        assertTrue(AgentAccessibilityService.enabledIn("com.x/.A:$p/com.runasakura.phoneagent.a11y.AgentAccessibilityService", p))
        assertFalse(AgentAccessibilityService.enabledIn("$p.other/.S", p))
        assertFalse(AgentAccessibilityService.enabledIn(null, p))
    }
}

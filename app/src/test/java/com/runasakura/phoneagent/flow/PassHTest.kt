package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.agent.Subgoal
import com.runasakura.phoneagent.agent.SubgoalExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassHTest {
    @Test fun subgoalStopsWhenServiceDead() = runBlocking {
        val d = FakeWeChat().apply { dieAfterTaps = 0 }
        assertEquals(SubgoalExecutor.Status.DISCONNECTED, SubgoalExecutor(d, {}, { true }).run(Subgoal.Open("王五")))
        assertTrue(d.taps.isEmpty())
    }

    @Test fun subgoalFailureAfterDeathIsReportedAsDisconnect() = runBlocking {
        val d = FakeWeChat().apply { dieAfterTaps = 1 }
        val ex = SubgoalExecutor(d, {}, { true })
        // Opening works (1 tap), then the service is dead before the next step.
        ex.run(Subgoal.Open("王五"))
        assertEquals(SubgoalExecutor.Status.DISCONNECTED, ex.run(Subgoal.Back))
    }

    @Test fun messagingNeverSendsAfterDisconnect() = runBlocking {
        val d = FakeWeChat().apply { dieAfterTaps = 1 }
        val out = MessagingFlow(d, AppProfile.WECHAT, {}, { true }).run("王五", "你好")
        assertFalse(out.ok)
        assertEquals(MessagingFlow.DISCONNECTED, out.summary)
        assertTrue(d.sent.isEmpty())
    }
}

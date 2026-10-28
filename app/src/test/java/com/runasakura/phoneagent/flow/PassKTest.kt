package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.agent.Subgoal
import com.runasakura.phoneagent.agent.SubgoalExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PassKTest {
    @Test fun typeIntoNotFooledByPreexistingText() = runBlocking {
        // Every typing method silently fails; 「搜索」 is already visible as the placeholder.
        val d = FakeWeChat(state = "search", silentTypeAttempts = setOf(0, 1, 2))
        assertEquals(SubgoalExecutor.Status.FAILED, SubgoalExecutor(d, {}, { true }).run(Subgoal.TypeInto("搜索", "搜索")))
    }
    @Test fun typeIntoStillSucceedsNormally() = runBlocking {
        val d = FakeWeChat(state = "search")
        assertEquals(SubgoalExecutor.Status.OK, SubgoalExecutor(d, {}, { true }).run(Subgoal.TypeInto("搜索", "王五")))
    }
}

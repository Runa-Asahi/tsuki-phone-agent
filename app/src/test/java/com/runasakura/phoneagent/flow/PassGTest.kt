package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.agent.SubgoalExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PassGTest {
    @Test fun scrollToWaitsThroughSpinner() = runBlocking {
        val d = FakeWeChat(moreListRows = listOf("赵六", "孙七"), loadingObservations = 3)
        assertEquals("孙七", SubgoalExecutor(d, {}, { true }).scrollTo("孙七")?.label)
        assertEquals(1, d.scrolls)
    }
}

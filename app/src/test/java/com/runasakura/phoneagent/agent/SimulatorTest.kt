package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.sim.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Offline end-to-end regression harness: the REAL AgentLoop (+ CloudClient, parser, validator, safety gate,
 * transcript, recovery) drives a scripted phone with a scripted model. One test per generic scenario.
 */
class SimulatorTest {
    private fun show(r: SimRun) = r.logs.filterNot { it.startsWith("LLM raw") }.joinToString("\n") + "\nRESULT: ${r.result}\nWORLD: " + r.world.log.joinToString(" ")
    private fun ok(sc: Scenario): SimRun { val r = SimRunner.run(sc); assertTrue(show(r), r.result.success); assertTrue(show(r), sc.done(r.world)); return r }

    @Test fun musicSearchPlayWithSplashAd() {
        val r = ok(Scenarios.music)
        assertFalse("search query must not swallow 「并播放」", r.world.log.any { it.contains("晴天并播放") })
    }
    @Test fun quickSettingsToggle() { val r = ok(Scenarios.torch); assertTrue(show(r), r.logs.any { it.contains("交给模型处理") }) }
    @Test fun sendNeedsConfirmation() { val r = ok(Scenarios.sendConfirmed); assertTrue(show(r), r.confirms.any { it.contains("晚上见") }) }
    @Test fun declinedSendIsNeverSent() {
        val r = SimRunner.run(Scenarios.sendDeclined)
        assertFalse(show(r), r.result.success); assertNull(show(r), r.world.flags["sent"]); assertTrue(r.confirms.isNotEmpty())
    }
    @Test fun scrollToFind() { val r = ok(Scenarios.scrollFind); assertTrue(show(r), r.world.log.count { it.startsWith("swipe") } >= 3) }
    @Test fun longTaskCompactsWithModelCheckpoint() {
        val r = ok(Scenarios.longPaging)
        assertTrue(show(r), r.logs.any { it.contains("检查点由模型整理") }); assertTrue((r.calls["checkpoint"] ?: 0) >= 1)
    }
    @Test fun popupDismissedNotAccepted() { val r = ok(Scenarios.popup); assertNull(r.world.flags["updated"]) }
    @Test fun timedSelection() { ok(Scenarios.draft) }
    @Test fun crashThenRelaunch() { val r = ok(Scenarios.crash); assertTrue(show(r), r.world.log.contains("crash:${Scenarios.READER}")) }
    @Test fun rateLimitRetried() { val r = ok(Scenarios.rateLimited); assertTrue(show(r), r.logs.any { it.contains("限流") && it.contains("自动重试") }) }
    @Test fun outageStopsWithClearMessage() {
        val r = SimRunner.run(Scenarios.outage)
        assertFalse(r.result.success); assertTrue(show(r), r.result.summary.contains("服务端繁忙"))
        assertTrue(show(r), r.logs.any { it.contains("重新观察屏幕再试") })
    }
    @Test fun multiAppCopyPaste() {
        val r = ok(Scenarios.copyPaste)
        assertTrue(show(r), r.confirms.any { it.contains("8848") })
        assertFalse(show(r), r.logs.any { it.contains("重新启动 ${Scenarios.MEMO}") || it.contains("不是目标应用，重新启动") })
    }

    // ---- batch 2 ----
    private fun fails(sc: Scenario): SimRun { val r = SimRunner.run(sc); assertFalse(show(r), r.result.success); return r }
    @Test fun loginWallHandsOver() { val r = fails(Scenarios2.loginWall); assertTrue(show(r), r.result.summary.contains("登录")); assertFalse(r.world.log.any { it.startsWith("type:") }) }
    @Test fun permissionDialog() { ok(Scenarios2.permission) }
    @Test fun keyboardCoversButton() { val r = ok(Scenarios2.keyboardCover); assertTrue(show(r), r.world.log.contains("back:ime")) }
    @Test fun bottomSheet() { ok(Scenarios2.bottomSheet) }
    @Test fun webviewOcrOnly() { ok(Scenarios2.webviewOcr) }
    @Test fun landscapeGame() { ok(Scenarios2.landscape) }
    @Test fun settingsToggle() { ok(Scenarios2.settingsToggle) }
    @Test fun notificationReplyConfirmed() { val r = ok(Scenarios2.notificationReply); assertTrue(show(r), r.confirms.any { it.contains("马上到") }) }
    @Test fun alarmViaUiNeedsConfirm() { val r = ok(Scenarios2.alarmConfirm); assertTrue(show(r), r.confirms.isNotEmpty()) }
    @Test fun alarmDeclinedNotSaved() { val r = fails(Scenarios2.alarmDeclined); assertNull(show(r), r.world.flags["alarm"]) }
    @Test fun longChatListScroll() { ok(Scenarios2.longChatScroll) }
    @Test fun emptySearchFailsCleanly() { val r = fails(Scenarios2.emptySearch); assertTrue(show(r), r.result.summary.contains("没有结果")); assertTrue(show(r), r.result.steps <= 6) }
    @Test fun ambiguousGoalAsksUser() {
        val r = fails(Scenarios2.ambiguous); assertTrue(show(r), r.result.needsUser); assertTrue(r.result.summary.contains("张三还是李四"))
        assertNull(r.world.flags["sent"])
    }
    @Test fun networkFlapping() { ok(Scenarios2.flapping) }
    @Test fun malformedOutputRecovers() { ok(Scenarios2.malformedThenOk) }
    @Test fun malformedOutputStops() { val r = fails(Scenarios2.malformedAlways); assertTrue(show(r), r.result.steps <= 6) }
    @Test fun userStopMidTask() { val r = fails(Scenarios2.userStop); assertTrue(show(r), r.result.summary.contains("急停")); assertTrue(show(r), r.world.reads <= 4) }
    @Test fun crossAppShareSheet() {
        val r = ok(Scenarios2.shareSheet); assertTrue(show(r), r.confirms.isNotEmpty())
        assertFalse(show(r), r.logs.any { it.contains("不是目标应用，重新启动") })
    }
}

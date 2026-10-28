package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.model.AgentAction
import org.junit.Assert.*
import org.junit.Test

class AuditSafetyTest {
    @Test fun callButtonsNeedConfirm() {
        assertTrue(SafetyGate.isCallLike("视频通话")); assertTrue(SafetyGate.isCallLike("初麵通话"))
        assertTrue(SafetyGate.isCallLike("语音通活")); assertFalse(SafetyGate.isCallLike("相册"))
        assertFalse(SafetyGate.isCallLike("今天晚上我们通话聊一下项目的事情吧"))
    }
}

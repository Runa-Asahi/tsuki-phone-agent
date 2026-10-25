package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.agent.PermissionDialog
import com.runasakura.phoneagent.util.Diagnostics
import com.runasakura.phoneagent.screen.ScreenItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassBTest {
    private fun scr(vararg l: String) = Screen("com.android.permissioncontroller", 1264, 2800,
        l.mapIndexed { i, s -> ScreenItem("e$i", s, 600, 1000 + i * 150, true, false, false, null) }, "tree", null)

    @Test fun picksWhileUsing() {
        assertEquals("仅使用期间允许", PermissionDialog.pick(scr("允许“抖音”访问您的位置信息吗？", "仅使用期间允许", "始终允许", "禁止"))?.label)
    }
    @Test fun neverAlways() {
        assertNull(PermissionDialog.pick(scr("允许访问相机？", "始终允许", "禁止")))
    }
    @Test fun neverOnPayment() {
        assertNull(PermissionDialog.pick(scr("允许支付宝访问", "仅使用期间允许")))
    }
    @Test fun ignoresNormalScreens() {
        assertNull(PermissionDialog.pick(scr("聊天", "仅此一次的活动")))
    }
    @Test fun diagnosticsClipsLongLines() {
        val s = Diagnostics.clip("x".repeat(5000))
        assertTrue(s.length < 2100); assertTrue(s.endsWith("(+3000 字)"))
        assertEquals("ok", Diagnostics.clip("ok"))
    }
}

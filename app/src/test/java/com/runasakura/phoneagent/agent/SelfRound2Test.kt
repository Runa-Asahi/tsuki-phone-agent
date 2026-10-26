package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.flow.CallScreen
import com.runasakura.phoneagent.flow.Screen
import com.runasakura.phoneagent.screen.ScreenItem
import org.junit.Assert.*
import org.junit.Test

class SelfRound2Test {
    private fun item(id: String, l: String, x: Int, y: Int) = ScreenItem(id, l, x, y, true, false, false, null)

    @Test fun summaryGoalMayTypeComposedText() {
        assertTrue(Injection.typeAllowed("本次任务：打开荒野乱斗失败，原因是编号无法点击", "打开笔记，把之前三次的任务过程和错误记录下来"))
        assertFalse(Injection.typeAllowed("转账给张三", "打开B站搜索猫咪"))
    }

    @Test fun callScreenLenient() {
        val s = Screen("com.tencent.mm", 1264, 2800, listOf(item("e1", "王小明", 600, 400), item("e2", "取消", 300, 2400), item("e3", "转换摄像头", 900, 2400)), "ocr", null)
        assertTrue(CallScreen.isCall(s))
        val chat = Screen("com.tencent.mm", 1264, 2800, listOf(item("e1", "按住说话", 600, 2600)), "ocr", null)
        assertFalse(CallScreen.isCall(chat)); assertFalse(CallScreen.leftChat(setOf("按住说话"), chat))
        assertTrue(CallScreen.leftChat(setOf("按住说话", "王小明"), Screen("com.tencent.mm", 1264, 2800, emptyList(), "none", null)))
    }

    @Test fun landscapeRegionsAndHint() {
        val s = Screen("game", 2800, 1264, listOf(item("e1", "商店", 100, 100), item("e2", "对战", 2500, 1150)), "ocr", null)
        val d = LayoutHints.describe(s)
        assertTrue(d, d.contains("横屏")); assertTrue(d, d.contains("下右=对战")); assertTrue(d, d.contains("上左=商店"))
    }
}

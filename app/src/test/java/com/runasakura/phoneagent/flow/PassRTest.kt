package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.agent.ConfirmPolicy
import com.runasakura.phoneagent.agent.SearchEntry
import com.runasakura.phoneagent.agent.Subgoal
import com.runasakura.phoneagent.agent.SubgoalExecutor
import com.runasakura.phoneagent.llm.CloudProviders
import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.prefs.AgentPreferences
import com.runasakura.phoneagent.screen.ScreenItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PPG-AN00 22:14 — 拼多多「泡面 拍照搜索」入口 + ask_confirm 死循环 + DeepSeek 配置. */
class PassRTest {
    private fun item(label: String, cx: Int, cy: Int, tap: Boolean = true, edit: Boolean = false) =
        ScreenItem("", label, cx, cy, tap, edit, false, null)

    private fun pddHome() = Screen(
        "com.xunmeng.pinduoduo", 1264, 2800,
        listOf(
            item("泡面 拍照搜索", 632, 222),
            item("拍照搜索", 1183, 222),
            item("推荐", 109, 378),
            item("食品", 722, 378),
            item("首页", 126, 2636),
            item("个人中心", 1137, 2636)
        ), "tree"
    )

    @Test fun pddPlaceholderBarIsSearchEntry() {
        val hit = SearchEntry.find(pddHome())!!
        assertEquals("泡面 拍照搜索", hit.label)
        assertTrue(hit.cx < 1000) // not the camera icon
    }

    @Test fun cameraIconAloneIsNotPreferred() {
        val s = Screen("x", 1264, 2800, listOf(item("拍照搜索", 1183, 222), item("泡面", 500, 222)), "tree")
        assertEquals("泡面", SearchEntry.find(s)!!.label)
    }

    @Test fun literalSearchStillWorks() {
        val s = Screen("x", 1264, 2800, listOf(item("搜索", 560, 200, edit = true), item("拍照搜索", 1183, 222)), "tree")
        assertTrue(SearchEntry.find(s)!!.edit)
    }

    @Test fun searchForUsesPddBar() = runBlocking {
        // FakeWeChat isn't PDD, but we can drive SubgoalExecutor against a custom Device.
        val d = object : Device by FakeWeChat() {
            var state = "home"
            var typed = ""
            override suspend fun screen(): Screen = when (state) {
                "home" -> pddHome()
                "search" -> Screen("com.xunmeng.pinduoduo", 1264, 2800, listOf(
                    item(typed.ifBlank { "搜索" }, 560, 210, edit = true),
                    item("搜索", 1180, 210),
                    item("凉宫春日 手办", 600, 800)
                ), "tree")
                else -> pddHome()
            }
            override suspend fun tap(item: ScreenItem): Boolean {
                if (item.label.contains("泡面") || item.label == "搜索" && item.cx > 1000) state = "search"
                if (item.label == "搜索" && item.cx > 1000) state = "search"
                return true
            }
            override suspend fun tapXY(x: Int, y: Int) = true
            override suspend fun type(text: String, hintX: Int?, hintY: Int?, attempt: Int): Boolean {
                typed = text; state = "search"; return true
            }
            override suspend fun afterAction(maxMs: Long) {}
            override suspend fun clearField() = true.also { typed = "" }
            override suspend fun fieldText() = typed.ifBlank { null }
        }
        val logs = mutableListOf<String>()
        assertEquals(SubgoalExecutor.Status.OK, SubgoalExecutor(d, { logs += it }, { true }).run(Subgoal.Search("凉宫春日")))
        assertEquals("凉宫春日", d.typed)
        assertTrue(logs.any { it.contains("泡面") })
    }

    @Test fun vagueAskConfirmIsNotSensitive() {
        assertFalse(ConfirmPolicy.isSensitive(AgentAction.AskConfirm("需要明确目标节点")))
        assertFalse(ConfirmPolicy.isSensitive(AgentAction.AskConfirm("确保目标节点存在且符合操作需求")))
        assertTrue(ConfirmPolicy.isSensitive(AgentAction.AskConfirm("即将点击发送")))
        assertTrue(ConfirmPolicy.isSensitive(AgentAction.AskConfirm("支付确认", pendingAction = AgentAction.Tap(text = "确认支付"))))
    }

    @Test fun deepseekPreset() {
        val p = CloudProviders.byId(AgentPreferences.PROVIDER_DEEPSEEK)
        assertEquals("https://api.deepseek.com/v1", p.baseUrl)
        assertEquals("deepseek-chat", p.model)
        assertEquals(AgentPreferences.DEEPSEEK_BASE, p.baseUrl)
        assertEquals(AgentPreferences.DEEPSEEK_MODEL, p.model)
        assertTrue(CloudProviders.ALL.any { it.id == AgentPreferences.PROVIDER_DEEPSEEK })
        assertTrue(CloudProviders.ALL.any { it.id == AgentPreferences.PROVIDER_OPENAI })
        assertEquals("不使用云端", CloudProviders.byId(AgentPreferences.PROVIDER_NONE).label)
    }

    @Test fun failureDumpsTopNodes() {
        val dump = SearchEntry.dumpTop(pddHome(), 5)
        assertTrue(dump, dump.contains("泡面") && dump.contains("@222"))
    }
}

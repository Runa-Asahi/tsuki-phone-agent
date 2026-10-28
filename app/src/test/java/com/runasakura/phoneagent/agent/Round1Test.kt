package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.llm.CloudProviders
import com.runasakura.phoneagent.llm.LlmRouter
import org.junit.Assert.*
import org.junit.Test

class Round1Test {
    @Test fun galleryRemainderIsKept() {
        val g = "打开图库第一张照片"
        val app = AppResolver.extractAppName(g)!!
        assertEquals("第一张照片", AppResolver.remainderAfterApp(g, app, "图库", "com.hihonor.photos"))
        assertEquals("搜索凉宫春日", AppResolver.remainderAfterApp("打开哔哩哔哩搜索凉宫春日", "哔哩哔哩搜索凉宫春日", "哔哩哔哩", "tv.danmaku.bili"))
        assertEquals("搜索X", AppResolver.remainderAfterApp("打开B站，搜索X", "B站", "哔哩哔哩", "tv.danmaku.bili"))
        assertNull(AppResolver.remainderAfterApp("打开图库", "图库", "图库", "com.hihonor.photos"))
        assertNull(AppResolver.remainderAfterApp("打开图库应用", "图库应用", "图库", "com.hihonor.photos"))
    }

    @Test fun remainderGoesToModelNotSuccess() {
        val subs = GoalSplitter.split("第一张照片")
        assertTrue(subs.first() is Subgoal.Unknown)
    }

    @Test fun judgeParsing() {
        assertEquals(false to "还在相册网格；点第一张缩略图", LlmRouter.parseJudge("""{"done":false,"reason":"还在相册网格","next":"点第一张缩略图"}"""))
        assertEquals(true, LlmRouter.parseJudge("```json\n{\"done\":true,\"reason\":\"ok\"}\n```")!!.first)
        assertNull(LlmRouter.parseJudge("嗯"))
    }

    @Test fun timingSummaryNeverEmpty() {
        com.runasakura.phoneagent.util.TaskTiming.reset()
        assertTrue(com.runasakura.phoneagent.util.TaskTiming.summary().contains("未读屏"))
    }

    @Test fun deepseekModels() {
        val ds = CloudProviders.byId(com.runasakura.phoneagent.prefs.AgentPreferences.PROVIDER_DEEPSEEK)
        assertNull(CloudProviders.modelWarning("deepseek-flash", emptyList(), ds))
        assertNotNull(CloudProviders.modelWarning("deepseek-flsh", emptyList(), ds))
        assertNotNull(CloudProviders.modelWarning("deepseek-chat", listOf("deepseek-flash", "deepseek-v4-pro"), ds))
        val body = com.runasakura.phoneagent.llm.CloudClient("https://api.deepseek.com/v1", "k", "deepseek-flash")
            .buildBody(listOf(com.runasakura.phoneagent.llm.ChatMessage("user", "x")), 0.0)
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
    }
}

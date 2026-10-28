package com.runasakura.phoneagent.llm

import com.runasakura.phoneagent.prefs.AgentPreferences
import org.junit.Assert.*
import org.junit.Test

class PassVModelTest {
    @Test fun savedModelIsUsedAndShown() {
        val d = CloudProviders.describe(AgentPreferences.ROUTE_CLOUD_ONLY, AgentPreferences.PROVIDER_DEEPSEEK,
            AgentPreferences.DEEPSEEK_BASE, "deepseek-reasoner", "sk-1234567890", "")
        assertTrue(d, d.contains("模型=deepseek-reasoner"))
        val c = CloudClient(AgentPreferences.DEEPSEEK_BASE, "k", "deepseek-reasoner")
        val body = c.buildBody(listOf(ChatMessage("user", "hi")), 0.0)
        assertEquals("deepseek-reasoner", body.getString("model"))
        assertFalse(body.has("response_format")); assertFalse(body.has("temperature"))
        assertEquals(2000, body.getInt("max_tokens"))
        val chat = CloudClient(AgentPreferences.DEEPSEEK_BASE, "k", "deepseek-chat").buildBody(listOf(ChatMessage("user", "hi")), 0.0)
        assertTrue(chat.has("response_format")); assertEquals(600, chat.getInt("max_tokens"))
    }

    @Test fun blankModelFallsBackToProviderDefault() {
        assertEquals("qwen-plus", CloudProviders.effectiveModel(CloudProviders.PROVIDER_QWEN, "https://dashscope.aliyuncs.com/compatible-mode/v1", "k", " "))
        assertEquals("deepseek-flash", CloudProviders.effectiveModel(AgentPreferences.PROVIDER_NONE, AgentPreferences.DEEPSEEK_BASE, "k", ""))
        assertEquals("my-custom", CloudProviders.effectiveModel(AgentPreferences.PROVIDER_OPENAI, "https://x/v1", "k", "my-custom"))
    }

    @Test fun parsesModelList() {
        val raw = """{"object":"list","data":[{"id":"deepseek-reasoner","object":"model"},{"id":"deepseek-chat","object":"model"}]}"""
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), CloudProviders.parseModels(raw))
        assertTrue(CloudProviders.parseModels("not json").isEmpty())
    }

    @Test fun providerSwitchKeepsCustomValues() {
        val ds = CloudProviders.byId(AgentPreferences.PROVIDER_DEEPSEEK); val qw = CloudProviders.byId(CloudProviders.PROVIDER_QWEN)
        assertEquals(qw.baseUrl to "qwen-plus", CloudProviders.onProviderChange(ds, qw, ds.baseUrl, "deepseek-reasoner"))
        assertEquals("https://my/v1" to "my-model", CloudProviders.onProviderChange(ds, qw, "https://my/v1", "my-model"))
        val none = CloudProviders.byId(AgentPreferences.PROVIDER_NONE)
        assertEquals(ds.baseUrl to "deepseek-flash", CloudProviders.onProviderChange(none, ds, "", ""))
    }
}

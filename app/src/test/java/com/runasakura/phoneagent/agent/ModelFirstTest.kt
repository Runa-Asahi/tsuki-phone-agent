package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.flow.AppProfile
import com.runasakura.phoneagent.flow.FakeWeChat
import com.runasakura.phoneagent.flow.MessagingFlow
import com.runasakura.phoneagent.llm.CloudClient
import com.runasakura.phoneagent.llm.ChatMessage
import com.runasakura.phoneagent.llm.LlmRouter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModelFirstTest {
    private val g1 = "打开微信给王泓凯发送一个表情包"

    @Test fun modelIntentForSticker() {
        val i = IntentParser.parseJson("""{"kind":"message","app":"微信","recipient":"王泓凯","content_type":"sticker","content":null}""", g1)!!
        assertEquals("sticker", i.contentType); assertEquals("王泓凯", i.recipient); assertEquals("模型", i.source)
        // model wrongly says text → coerced to sticker
        val j = IntentParser.parseJson("""{"kind":"message","recipient":"王泓凯","content_type":"text","content":"一个表情包"}""", g1)!!
        assertEquals("sticker", j.contentType); assertNull(j.content)
    }

    @Test fun hallucinatedFieldsRejected() {
        assertNull(IntentParser.parseJson("""{"kind":"message","recipient":"王红凯","content_type":"text","content":"你好"}""", "给王泓凯发你好"))
        assertNull(IntentParser.parseJson("""{"kind":"message","recipient":"王泓凯","content_type":"text","content":"你好呀"}""", "给王泓凯发你好"))
        assertNull(IntentParser.parseJson("""{"kind":"alarm","hour":25}""", "闹钟"))
        assertNull(IntentParser.parseJson("不是json", "x"))
        assertNotNull(IntentParser.parseJson("""{"kind":"message","recipient":"💩","content_type":"text","content":"奶龙NB"}""", "打开微信给💩️发送奶龙NB"))
    }

    @Test fun genericAppIntentBecomesCanonicalGoal() {
        val i = IntentParser.parseJson("""{"kind":"app","app":"图库","steps":["打开第一张照片"]}""", "打开图库第一张照片")!!
        assertEquals("打开图库，打开第一张照片", IntentParser.canonicalGoal(i))
        val b = IntentParser.parseJson("""{"kind":"app","app":"哔哩哔哩","steps":["搜索凉宫春日","播放第一个视频"]}""", "B站看凉宫春日")!!
        assertEquals("打开哔哩哔哩，搜索凉宫春日，播放第一个视频", IntentParser.canonicalGoal(b))
    }

    @Test fun rulesStillWorkAsFallback() {
        assertEquals("sticker", IntentParser.ruleBased(g1)!!.contentType)
        assertEquals("call", IntentParser.ruleBased("给王小明打视频电话")!!.kind)
        assertEquals(7, IntentParser.ruleBased("设个明天早上7点的闹钟")!!.hour)
        assertNull(IntentParser.ruleBased("打开图库第一张照片"))
    }

    @Test fun pointParsing() {
        assertEquals(300 to 2200, LlmRouter.parsePoint("""{"x":300,"y":2200}""", 1264, 2800))
        assertNull(LlmRouter.parsePoint("""{"none":true}""", 1264, 2800))
        assertNull(LlmRouter.parsePoint("""{"x":3000,"y":1}""", 1264, 2800))
    }

    @Test fun thinkingParamDroppedAfterRejection() {
        val msg = listOf(ChatMessage("user", "x"))
        CloudClient.thinkingRejected = false
        assertTrue(CloudClient("https://api.deepseek.com/v1", "k", "deepseek-flash").buildBody(msg, 0.0).has("thinking"))
        CloudClient.thinkingRejected = true
        assertFalse(CloudClient("https://api.deepseek.com/v1", "k", "deepseek-flash").buildBody(msg, 0.0).has("thinking"))
        CloudClient.thinkingRejected = false
    }

    @Test fun favouriteStickerChosenByModelConfirmFirst() = runBlocking {
        val d = FakeWeChat().apply { showPlusLabel = false }
        val order = mutableListOf<String>()
        val f = MessagingFlow(d, AppProfile.WECHAT, {}, { order += "confirm:" + d.sent.size; true })
        f.locate = { _, what -> order += "locate"; if (what.contains("页签")) 600 to 2650 else 300 to 2200 }
        val out = f.sendSticker("王小明")
        assertTrue(out.summary, out.ok)
        assertEquals("[表情包]", d.sent.single().second)
        assertEquals(listOf("locate", "locate", "confirm:0"), order)
    }

    @Test fun favouriteStickerCancelledNeverSends() = runBlocking {
        val d = FakeWeChat().apply { showPlusLabel = false }
        val f = MessagingFlow(d, AppProfile.WECHAT, {}, { false })
        f.locate = { _, what -> if (what.contains("页签")) 600 to 2650 else 300 to 2200 }
        assertFalse(f.sendSticker("王小明").ok); assertTrue(d.sent.isEmpty())
    }
}

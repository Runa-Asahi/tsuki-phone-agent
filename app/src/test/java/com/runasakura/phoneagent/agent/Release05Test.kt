package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.llm.ChatMessage
import com.runasakura.phoneagent.llm.CloudClient
import com.runasakura.phoneagent.llm.CloudProviders
import org.junit.Assert.*
import org.junit.Test

class Release05Test {
    @Test fun deepseekFlashSeesImagesUntilRejected() {
        CloudClient.imageRejected = false
        assertTrue(CloudProviders.cloudSeesImages("deepseek-flash"))
        val c = CloudClient("https://api.deepseek.com", "k", "deepseek-flash")
        val body = c.buildBody(listOf(ChatMessage("user", "看", imageBase64 = "AAAA")), 0.0).toString()
        assertTrue(body, body.contains("image_url"))
        CloudClient.imageRejected = true
        assertFalse(CloudProviders.cloudSeesImages("deepseek-flash"))
        assertFalse(c.buildBody(listOf(ChatMessage("user", "看", imageBase64 = "AAAA")), 0.0).toString().contains("image_url"))
        CloudClient.imageRejected = false
    }

    @Test fun summaryRoutingNoFalsePositive() {
        assertFalse(SessionAnswer.aboutHistory("在备忘录记录今天的错误"))
        assertFalse(SessionAnswer.aboutHistory("打开笔记总结一下今天的会议"))
        assertTrue(SessionAnswer.aboutHistory("总结一下这次的历史日志"))
        assertTrue(SessionAnswer.aboutHistory("打开笔记，把之前三次的任务过程和错误记录下来"))
        assertTrue(SessionAnswer.aboutHistory("总结我 不用你动 包括之前的这次失败"))
    }
}

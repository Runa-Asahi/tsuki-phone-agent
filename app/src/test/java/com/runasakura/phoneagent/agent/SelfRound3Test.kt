package com.runasakura.phoneagent.agent

import org.junit.Assert.*
import org.junit.Test

class SelfRound3Test {
    @Test fun historyQuestions() {
        assertTrue(SessionAnswer.aboutHistory("总结一下这次的历史日志 复制给grokbot让他帮助修改代码"))
        assertFalse(SessionAnswer.writesIntoApp("总结一下这次的历史日志 复制给grokbot让他帮助修改代码"))
        assertTrue(SessionAnswer.aboutHistory("打开笔记，把之前三次的任务过程和错误记录下来"))
        assertTrue(SessionAnswer.writesIntoApp("打开笔记，把之前三次的任务过程和错误记录下来"))
        assertFalse(SessionAnswer.aboutHistory("打开荒野乱斗 开一局"))
        assertFalse(SessionAnswer.aboutHistory("给文件传输助手发消息说你好"))
    }
    @Test fun unwrap() {
        assertEquals("abc", SessionAnswer.unwrap("```abc```"))
        assertEquals("总结", SessionAnswer.unwrap("{\"summary\":\"总结\"}"))
    }
}

package com.runasakura.phoneagent.agent

import org.junit.Assert.*
import org.junit.Test

class ImeSubmitTest {
    @Test fun recognisesKeyboardSearchKey() {
        assertTrue(SearchEntry.isImeSubmit("搜素"))
        assertTrue(SearchEntry.isImeSubmit("123 搜素"))
        assertTrue(SearchEntry.isImeSubmit("Search"))
        assertFalse(SearchEntry.isImeSubmit("符号中"))
        assertFalse(SearchEntry.isImeSubmit("搜索网络结果"))
    }
}

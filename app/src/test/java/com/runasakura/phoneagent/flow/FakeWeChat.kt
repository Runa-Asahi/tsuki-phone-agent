package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.screen.ScreenItem

/**
 * Recorded fake WeChat (1264x2800). Screens: home(launcher), main, search, chat:<title>.
 * [resultRows] = search result rows (section headers included) shown after typing.
 * [chatTitleFor] maps tapped result label → title of the chat that opens (to simulate wrong chat).
 */
class FakeWeChat(
    var state: String = "main",
    val resultRows: List<String> = listOf("联系人", "王小明2", "王小明", "王小"),
    val chatTitleFor: (String) -> String = { it },
    val listRows: List<String> = listOf("文件传输助手", "王小明2", "王五"),
    val source: String = "ocr",
    val keyboardCandidates: List<String> = emptyList(),
    val tabs: List<String> = listOf("微信", "通讯录", "发现", "我"),
    val pkg: String = "com.tencent.mm",
    val searchLabel: String? = null,
    val resultTapWorks: Boolean = true
) : Device {
    val W = 1264; val H = 2800
    val taps = mutableListOf<String>()
    val typed = mutableListOf<String>()
    var backs = 0
    var searchText = ""
    var inputText = ""
    val sent = mutableListOf<Pair<String, String>>()
    var launches = 0

    private fun item(label: String, cx: Int, cy: Int, edit: Boolean = false) =
        ScreenItem("", label, cx, cy, true, edit, false, label)

    override suspend fun screen(): Screen {
        val items = mutableListOf<ScreenItem>()
        var imeTop: Int? = null
        when {
            state == "home" -> return Screen("com.hihonor.android.launcher", W, H, listOf(item("微信", 300, 1500)), source)
            state == "main" -> {
                items += item(tabs[0], 632, 200)
                searchLabel?.let { items += item(it, 632, 330) }
                listRows.forEachIndexed { i, r -> items += item(r, 400, 420 + i * 220) }
                tabs.forEachIndexed { i, t -> items += item(t, 158 + i * 316, 2690) }
            }
            state == "me" -> {
                items += item("设置", 300, 1800)
                listOf("微信", "通讯录", "发现", "我").forEachIndexed { i, t -> items += item(t, 158 + i * 316, 2690) }
            }
            state == "moments" -> items += item("朋友圈", 632, 200)
            state == "search" -> {
                items += item(searchText.ifBlank { "搜索" }, 560, 200, edit = source == "tree")
                items += item("取消", 1180, 200)
                if (searchText.isNotBlank()) {
                    resultRows.forEachIndexed { i, r -> items += item(r, 500, 380 + i * 200) }
                    imeTop = 1700
                    keyboardCandidates.forEach { items += item(it, 300, 1760) }
                }
            }
            state.startsWith("chat:") -> {
                items += item(state.removePrefix("chat:"), 632, 200)
                items += item("昨天 12:00", 632, 600)
                sent.filter { it.first == state }.forEachIndexed { i, m -> items += item(m.second, 900, 2300 - i * 150) }
                if (source == "tree") items += item(inputText, 560, 2660, edit = true)
                else if (inputText.isNotBlank()) items += item(inputText, 560, 2660)
                if (inputText.isNotBlank()) items += item("发送", 1180, 2660)
            }
        }
        return Screen(if (state == "home") "launcher" else pkg, W, H, items, source, imeTop)
    }

    override suspend fun tap(item: ScreenItem): Boolean = tapLabel(item.label, item.cx, item.cy)

    private fun tapLabel(label: String, x: Int, y: Int): Boolean {
        taps += label
        when {
            state == "main" && label == tabs[0] -> Unit
            state == "main" && label == searchLabel -> { state = "search"; searchText = "" }
            state == "main" && y in 120..280 && x > 1000 -> { state = "search"; searchText = "" }
            state == "main" && label in listRows -> { state = "chat:" + chatTitleFor(label); inputText = "" }
            state == "search" && label == "取消" -> state = "main"
            state == "search" && label in resultRows && !resultTapWorks -> Unit
            state == "search" && label in resultRows -> { state = "chat:" + chatTitleFor(label); inputText = "" }
            state.startsWith("chat:") && label == "发送" -> { sent += state to inputText; inputText = "" }
        }
        return true
    }

    override suspend fun tapXY(x: Int, y: Int): Boolean = tapLabel("@$x,$y", x, y)

    override suspend fun type(text: String, hintX: Int?, hintY: Int?): Boolean {
        typed += text
        when {
            state == "search" -> searchText = text
            state.startsWith("chat:") -> inputText = text
            else -> return false
        }
        return true
    }

    override suspend fun back() {
        backs++
        state = when {
            state == "search" || state.startsWith("chat:") || state == "moments" -> "main"
            state == "main" || state == "me" -> "home"
            else -> state
        }
    }

    override suspend fun bringToFront(pkg: String, label: String): Boolean { launches++; state = "main"; return true }
    var scrolls = 0
    override suspend fun scroll(forward: Boolean) { scrolls++ }
    override suspend fun sleep(ms: Long) {}
}

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
    val resultTapWorks: Boolean = true,
    /** type() attempts that silently do nothing (simulates WeChat ignoring SET_TEXT). */
    val silentTypeAttempts: Set<Int> = emptySet(),
    var voiceMode: Boolean = false,
    /** Extra search-result pages revealed by scroll (pagination). */
    var extraResultPages: List<List<String>> = emptyList(),
    /** Chat-list rows shown after the first scroll (scroll-until-visible tests). */
    val moreListRows: List<String> = emptyList(),
    /** Number of observations after the first scroll that show only a 加载中 spinner. */
    var loadingObservations: Int = 0
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
        if (state == "main" && scrolls > 0 && loadingObservations > 0) {
            loadingObservations--
            return Screen(pkg, 1264, 2800, listOf(item("加载中", 632, 1400)), "tree")
        }
        val items = mutableListOf<ScreenItem>()
        var imeTop: Int? = null
        when {
            state == "home" -> return Screen("com.hihonor.android.launcher", W, H, listOf(item("微信", 300, 1500)), source)
            state == "main" -> {
                items += item(tabs[0], 632, 200)
                searchLabel?.let { items += item(it, 632, 330) }
                val rows = if (scrolls > 0 && moreListRows.isNotEmpty()) moreListRows else listRows
                rows.forEachIndexed { i, r -> items += item(r, 400, 420 + i * 220) }
                tabs.forEachIndexed { i, t -> items += item(t, 158 + i * 316, 2690) }
            }
            state == "me" -> {
                items += item("设置", 300, 1800)
                listOf("微信", "通讯录", "发现", "我").forEachIndexed { i, t -> items += item(t, 158 + i * 316, 2690) }
            }
            state == "moments" -> items += item("朋友圈", 632, 200)
            state.startsWith("menu:") -> { items += item("置顶该聊天", 632, 1300); items += item("删除该聊天", 632, 1500) }
            state == "search" -> {
                items += item(searchText.ifBlank { "搜索" }, 560, 200, edit = source == "tree")
                items += item("取消", 1180, 200)
                if (searchText.isNotBlank()) {
                    val rows = currentSearchRows()
                    rows.forEachIndexed { i, r -> items += item(r, 500, 380 + i * 200) }
                    imeTop = 1700
                    keyboardCandidates.forEach { items += item(it, 300, 1760) }
                }
            }
            state.startsWith("chat:") -> {
                items += item(state.removePrefix("chat:"), 632, 200)
                items += item("昨天 12:00", 632, 600)
                sent.filter { it.first == state }.forEachIndexed { i, m ->
                    // Multi-line bubbles: one item per line stacked
                    m.second.lines().filter { it.isNotBlank() }.forEachIndexed { j, line ->
                        items += item(line, 900, 2300 - i * 200 - j * 50)
                    }
                }
                if (voiceMode) {
                    items += item("按住 说话", 560, 2660)
                } else {
                    if (source == "tree") {
                        inputText.lines().filter { it.isNotBlank() || inputText.isEmpty() }.ifEmpty { listOf(inputText) }.forEachIndexed { j, line ->
                            items += item(line, 560, 2660 - j * 40, edit = j == 0)
                        }
                    } else if (inputText.isNotBlank()) {
                        inputText.lines().filter { it.isNotBlank() }.forEachIndexed { j, line ->
                            items += item(line, 560, 2660 - j * 40)
                        }
                    }
                    if (inputText.isNotBlank()) items += item("发送", 1180, 2660)
                }
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
            state == "search" && label in currentSearchRows() && !resultTapWorks -> Unit
            state == "search" && label in currentSearchRows() -> { state = "chat:" + chatTitleFor(label); inputText = ""; resultPage = 0 }
            state.startsWith("chat:") && label == "发送" -> { sent += state to inputText; inputText = "" }
            state.startsWith("chat:") && voiceMode && (x < 150 || label.contains("键盘")) -> { voiceMode = false }
        }
        return true
    }

    val longPresses = mutableListOf<String>()
    override suspend fun longPress(item: ScreenItem): Boolean {
        longPresses += item.label
        if (state == "main") state = "menu:" + item.label
        return true
    }

    override suspend fun tapXY(x: Int, y: Int): Boolean = tapLabel("@$x,$y", x, y)

    val typeAttempts = mutableListOf<Int>()
    override suspend fun type(text: String, hintX: Int?, hintY: Int?, attempt: Int): Boolean {
        typeAttempts += attempt
        if (attempt in silentTypeAttempts) return true
        typed += text
        when {
            state == "search" -> searchText = text
            state.startsWith("chat:") -> {
                if (voiceMode) return false
                inputText = text
            }
            else -> return false
        }
        return true
    }

    override suspend fun back() {
        backs++
        state = when {
            state == "search" || state.startsWith("chat:") || state == "moments" || state.startsWith("menu:") -> "main"
            state == "main" || state == "me" -> "home"
            else -> state
        }
    }

    var forceLaunches = 0
    private var resultPage = 0
    private fun currentSearchRows(): List<String> =
        if (resultPage == 0) resultRows else extraResultPages.getOrNull(resultPage - 1).orEmpty()
    override suspend fun bringToFront(pkg: String, label: String, force: Boolean): Boolean {
        launches++; if (force) forceLaunches++; state = "main"; return true
    }
    override suspend fun invalidateObservation() {}
    var scrolls = 0
    override suspend fun scroll(forward: Boolean) { scrolls++; if (state == "search" && forward) resultPage++ }
    override suspend fun sleep(ms: Long) {}
    override suspend fun home() { state = "home" }
    override suspend fun afterAction(maxMs: Long) {}
}

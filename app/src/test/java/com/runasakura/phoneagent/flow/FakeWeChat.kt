package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.screen.ScreenItem

/**
 * Recorded fake WeChat (1264x2800). Screens: home(launcher), main, search, chat:<title>.
 * [resultRows] = search result rows (section headers included) shown after typing.
 * [chatTitleFor] maps tapped result label → title of the chat that opens (to simulate wrong chat).
 */
open class FakeWeChat(
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
    var loadingObservations: Int = 0,
    /** Render the search page exactly as PPG-AN00 OCR saw it (no 取消, merged placeholder). */
    val realOcrSearch: Boolean = false,
    /** IME commit appends (select-all is a no-op in WeChat) — reproduces 八嘎八嘎八嘎. */
    val imeAppends: Boolean = false,
    /** OCR glues the input text and the green button into one label: 「八嘎发送」. */
    val ocrMergesSend: Boolean = false,
    /** OCR misses the 发送 label entirely (white on green). */
    val ocrMissesSend: Boolean = false,
    /** Field content readable through the IME connection. */
    val fieldReadable: Boolean = false,
    var clearWorks: Boolean = true,
    /** Unsent draft WeChat restores when the chat opens. */
    val draft: String = "",
    /** The 发送 tap is swallowed (e.g. lands on nothing). */
    val sendTapIgnored: Boolean = false
) : Device {
    var clears = 0
    /** "" | "plus" | "sheet" — chat bottom panels for the call flow. */
    var chatPanel = ""
    var showPlusLabel = true
    /** 「+」 panel labels (row 1 at y=2135 for real-OCR cases). */
    var plusLabels = listOf("相册", "拍摄", "音视频通话", "位置")
    var plusRowY = 2300
    val calls = mutableListOf<String>()
    override suspend fun fieldText(): String? = if (!fieldReadable) null else when {
        state.startsWith("chat:") -> inputText
        state == "search" -> searchText
        else -> null
    }
    override suspend fun clearField(): Boolean {
        clears++
        if (!clearWorks) return false
        if (state.startsWith("chat:")) inputText = "" else if (state == "search") searchText = ""
        return true
    }
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

    override suspend fun screen(): Screen = rawScreen()
    open suspend fun rawScreen(): Screen {
        if (state == "main" && scrolls > 0 && loadingObservations > 0) {
            loadingObservations--
            return Screen(pkg, 1264, 2800, listOf(item("加载中", 632, 1400)), "tree")
        }
        val items = mutableListOf<ScreenItem>()
        var imeTop: Int? = null
        when {
            state.startsWith("call:") -> {
                items += item(state.substringAfterLast(":"), 632, 900)
                items += item("等待对方接受邀请", 632, 1200)
                if (state.contains("video")) items += item("切到语音通话", 632, 2200)
                items += item("取消", 632, 2450)
            }
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
            state == "splash" -> items += item("广告 跳过", 1100, 200)
            state.startsWith("menu:") -> { items += item("置顶该聊天", 632, 1300); items += item("删除该聊天", 632, 1500) }
            state == "search" && realOcrSearch && searchText.isBlank() -> {
                items += item("くQ搜索本地或网络结果", 560, 210)
                listOf("最近在搜", "展开v 向", "|0小通", "0茉莉奶白", "0吉林大学图书馆", "|0打宇板", "0打字版", "|G卡码大模型", "急使")
                    .forEachIndexed { i, l -> items += item(l, 400, 400 + i * 110) }
                items += item("●按住说出你的问题", 632, 1600)
                items += item("QwERTYuo P", 632, 2000)
            }
            state == "search" -> {
                items += item(if (realOcrSearch) "くQ$searchText" else searchText.ifBlank { "搜索" }, 560, 200, edit = source == "tree")
                if (!realOcrSearch) items += item("取消", 1180, 200)
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
                when (chatPanel) {
                    "plus" -> plusLabels.forEachIndexed { i, l -> if (l.isNotEmpty()) items += item(l, 200 + i * 300, plusRowY) }
                    "fav" -> items += item("添加", 150, 2200)
                    "emoji" -> { items += item("最近使用", 200, 2100); items += item("所有表情", 200, 2350) }
                    "sheet" -> { items += item("视频通话", 632, 2300); items += item("语音通话", 632, 2450); items += item("取消", 632, 2600) }
                }
                if (inputText.isBlank() && showPlusLabel && !voiceMode) items += item("+", 1200, 2660)
                if (voiceMode) {
                    items += item("按住 说话", 560, 2660)
                } else {
                    if (source == "tree") {
                        inputText.lines().filter { it.isNotBlank() || inputText.isEmpty() }.ifEmpty { listOf(inputText) }.forEachIndexed { j, line ->
                            items += item(line, 560, 2660 - j * 40, edit = j == 0)
                        }
                    } else if (inputText.isNotBlank() && ocrMergesSend) {
                        items += item(inputText + "发送", 700, 2660)
                    } else if (inputText.isNotBlank()) {
                        inputText.lines().filter { it.isNotBlank() }.forEachIndexed { j, line ->
                            items += item(line, 560, 2660 - j * 40)
                        }
                    }
                    if (inputText.isNotBlank() && !ocrMergesSend && !ocrMissesSend) items += item("发送", 1180, 2660)
                }
            }
        }
        return Screen(if (state == "home") "launcher" else pkg, W, H, items, source, imeTop)
    }

    /** Service "dies" after this many taps (null = never). */
    var dieAfterTaps: Int? = null
    override fun alive(): Boolean = dieAfterTaps?.let { taps.size < it } ?: true

    override suspend fun tap(item: ScreenItem): Boolean = tapLabel(item.label, item.cx, item.cy)

    private fun tapLabel(label: String, x: Int, y: Int): Boolean {
        taps += label
        when {
            state == "main" && label == tabs[0] -> Unit
            (state == "me" || state == "main") && y > 2600 && (label == "微信" || x < 300) -> { state = "main" }
            state == "main" && label == searchLabel -> { state = "search"; searchText = "" }
            state == "main" && y in 120..280 && x > 1000 -> { state = "search"; searchText = "" }
            state == "main" && label in listRows -> { state = "chat:" + chatTitleFor(label); inputText = draft }
            state == "search" && label == "取消" -> state = "main"
            state == "search" && label in currentSearchRows() && !resultTapWorks -> Unit
            state == "search" && label in currentSearchRows() -> { state = "chat:" + chatTitleFor(label); inputText = draft; resultPage = 0 }
            state.startsWith("chat:") && chatPanel == "" && label.startsWith("@") && x in 1050..1120 && y > 2500 -> chatPanel = "emoji"
            state.startsWith("chat:") && chatPanel == "emoji" && label.startsWith("@") && x in 560..640 && y > 2600 -> chatPanel = "fav"
            state.startsWith("chat:") && chatPanel == "fav" && label.startsWith("@") && x in 250..350 && y in 2150..2250 -> { sent += state to "[表情包]"; chatPanel = "" }
            state.startsWith("chat:") && chatPanel == "emoji" && label.startsWith("@") && x < 160 && y in 2150..2300 -> inputText += "😀"
            state.startsWith("chat:") && chatPanel == "" && inputText.isBlank() && (label == "+" || (x > 1150 && y in 2600..2720)) -> chatPanel = "plus"
            state.startsWith("chat:") && chatPanel == "plus" && (label == plusLabels.getOrNull(2) || (label.startsWith("@") && x in 700..900 && kotlin.math.abs(y - plusRowY) < 120)) -> chatPanel = "sheet"
            state.startsWith("chat:") && chatPanel == "sheet" && (label == "语音通话" || label == "视频通话") -> {
                val kind = if (label == "视频通话") "video" else "voice"
                calls += kind; state = "call:$kind:" + state.removePrefix("chat:"); chatPanel = ""
            }
            state.startsWith("chat:") && inputText.isNotBlank() && !sendTapIgnored && (label == "发送" || (x > 1100 && y in 2600..2720)) -> { sent += state to inputText; inputText = "" }
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
                inputText = if (imeAppends) inputText + text else text
            }
            else -> return false
        }
        return true
    }

    override suspend fun back() {
        backs++
        if (chatPanel.isNotEmpty()) { chatPanel = ""; return }
        state = when {
            state == "search" || state.startsWith("chat:") || state == "moments" || state.startsWith("menu:") -> "main"
            state == "main" || state == "me" || state == "splash" -> "home"
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

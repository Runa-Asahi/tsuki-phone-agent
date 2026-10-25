package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.screen.ScreenItem

/** Per-app knobs for the deterministic messaging flow. */
data class AppProfile(
    val pkg: String,
    val label: String,
    val mainTabs: Set<String>,
    val minTabs: Int,
    /** Bottom tab that shows the chat list. */
    val chatTab: String,
    /** Fallback search icon position (fraction of w,h) when no "搜索" text/desc is visible. */
    val searchFallback: Pair<Double, Double>?,
    /** Fallback chat input position (fraction of w,h) when no editable node is visible. */
    val inputFallback: Pair<Double, Double>
) {
    companion object {
        val WECHAT = AppProfile(
            "com.tencent.mm", "微信",
            setOf("微信", "通讯录", "发现", "我"), 3, "微信",
            0.836 to 0.071, 0.45 to 0.952
        )
        val QQ = AppProfile(
            "com.tencent.mobileqq", "QQ",
            setOf("消息", "联系人", "动态", "频道", "小世界", "空间"), 2, "消息",
            null, 0.45 to 0.952
        )
        fun forPackage(pkg: String) = if (pkg == QQ.pkg) QQ else WECHAT
    }
}

/**
 * Deterministic, verify-every-step messaging flow:
 * main screen → (exact match in list | search) → exact-match result → verify title →
 * type → verify input → confirm(contact+text) → tap 发送 → verify bubble.
 * Never taps a fuzzy match. Never calls the model.
 */
class MessagingFlow(
    private val d: Device,
    private val app: AppProfile,
    private val log: (String) -> Unit,
    private val confirm: suspend (String) -> Boolean
) {
    data class Outcome(val ok: Boolean, val summary: String, val cancelled: Boolean = false, val verified: Boolean = false)

    companion object {
        /** Result sections whose rows are NOT the contact itself. */
        val EXCLUDED_SECTIONS = setOf(
            "聊天记录", "公众号", "服务号", "小程序", "搜一搜", "文章", "朋友圈", "视频号",
            "直播", "表情", "百科", "音乐", "小说", "订阅号", "频道", "网络搜索", "收藏"
        )
        val SECTION_HEADERS = EXCLUDED_SECTIONS + setOf(
            "联系人", "群聊", "最常使用", "好友", "功能", "设备", "更多联系人", "企业微信联系人"
        )
        private const val SEND = "发送"
    }

    suspend fun run(contact: String, message: String): Outcome {
        log("消息流程：${app.label} → 「$contact」 内容「$message」")
        if (!ensureMain()) return fail("无法回到${app.label}主界面（请先手动打开${app.label}首页后重试）")

        // 1) exact match in visible chat list (only if exactly one)
        var s = d.screen()
        val listHits = exactMatches(s, contact, top = (s.h * 0.10).toInt(), excludeSections = false)
            .filter { it.cy < s.h * 0.86 }
        val entered = if (listHits.size == 1) {
            log("聊天列表中精确匹配到「$contact」，直接进入")
            d.tap(listHits[0]); d.sleep(1200); true
        } else {
            if (listHits.size > 1) log("聊天列表中有 ${listHits.size} 个同名项，改用搜索")
            when (val r = searchAndOpen(contact)) {
                null -> true
                else -> return fail(r)
            }
        }
        if (!entered) return fail("未能进入聊天")

        // 2) verify chat title
        s = d.screen()
        if (s.pkg != app.pkg) return fail("进入聊天后前台不是${app.label}（${s.pkg}）")
        if (!titleMatches(s, contact)) {
            val seen = s.items.filter { it.cy < s.h * 0.12 }.joinToString("、") { it.label }
            log("✗ 聊天标题与「$contact」不符（看到：$seen），退出")
            d.back(); d.sleep(500); d.back()
            return fail("打开的聊天标题不是「$contact」（看到：${seen.ifBlank { "无" }}），已退出，未输入任何内容")
        }
        log("✓ 聊天标题已核对：$contact")

        // 3) type message (switch out of voice mode first)
        s.items.firstOrNull { it.cy > s.h * 0.8 && Text.norm(it.label).let { n -> n.contains("按住") && n.contains("说话") } }?.let {
            log("输入栏处于语音模式，切换到键盘")
            d.tapXY((s.w * 0.06).toInt(), it.cy); d.sleep(600); s = d.screen()
        }
        val bubblesBefore = countBubbles(s, message)
        val edit = s.items.lastOrNull { it.edit && (s.imeTop == null || it.cy < s.imeTop) }
        val hx = edit?.cx ?: (s.w * app.inputFallback.first).toInt()
        val hy = edit?.cy ?: (s.h * app.inputFallback.second).toInt()
        if (!d.type(message, hx, hy)) return fail("无法在输入框输入文字")
        d.sleep(700)
        s = d.screen()
        val send = findSend(s) ?: return fail("输入后未出现「发送」按钮（可能处于语音模式或输入失败），未发送")
        if (!inputContains(s, message, send)) return fail("无法核对输入框内容为「$message」，未发送")
        log("✓ 输入框已核对")

        // 4) confirm
        if (!confirm("发送确认（${app.label}）\n收件人：$contact（已核对聊天标题）\n内容：$message\n\n确认后将点击「发送」。")) {
            log("用户取消发送")
            return Outcome(false, "用户取消发送", cancelled = true)
        }
        // re-check we are still in the same chat (the confirm dialog briefly covers the app)
        d.sleep(800)
        s = d.screen()
        if (s.pkg != app.pkg) {
            log("确认框关闭后前台=${s.pkg}，回到${app.label}")
            d.bringToFront(app.pkg, app.label); d.sleep(900); s = d.screen()
        }
        if (!titleMatches(s, contact)) return fail("确认期间界面已变化，未发送")
        val send2 = findSend(s) ?: return fail("确认后找不到「发送」按钮，未发送")
        d.tap(send2)
        log("已点击发送")
        d.sleep(1000)

        // 5) verify bubble
        s = d.screen()
        val stillPending = findSend(s)?.let { inputContains(s, message, it) } ?: false
        val bubble = countBubbles(s, message) > bubblesBefore
        return if (!stillPending && bubble) {
            log("✓ 已在聊天中看到消息")
            Outcome(true, "已发送给「$contact」：$message（已核验）", verified = true)
        } else {
            log("⚠ 已点击发送，但未能在界面核验到消息")
            Outcome(true, "已点击发送给「$contact」：$message（未能核验，请看一眼手机）")
        }
    }

    private fun fail(msg: String): Outcome { log("✗ $msg"); return Outcome(false, msg) }

    fun isMain(s: Screen): Boolean {
        if (s.pkg != app.pkg) return false
        val bottom = s.items.filter { it.cy > s.h * 0.85 }
        return bottom.count { b -> app.mainTabs.any { Text.eq(it, b.label) } } >= app.minTabs
    }

    private suspend fun ensureMain(): Boolean {
        var relaunches = 0
        for (i in 0 until 7) {
            val s = d.screen()
            if (s.source == "none" && s.pkg == app.pkg) log("界面读取为空（树与 OCR 均无文字）")
            if (s.pkg != app.pkg) {
                if (relaunches++ >= 2) return false
                log("前台=${s.pkg}，将${app.label}带到前台")
                d.bringToFront(app.pkg, app.label); d.sleep(1200); continue
            }
            if (isMain(s)) {
                s.items.firstOrNull { it.cy > s.h * 0.85 && Text.eq(it.label, app.chatTab) }?.let { d.tap(it); d.sleep(500) }
                log("✓ 已在${app.label}主界面")
                return true
            }
            log("不在主界面，返回一步")
            d.back(); d.sleep(700)
        }
        return false
    }

    /** Returns null on success (chat entered), else an error message. */
    private suspend fun searchAndOpen(contact: String): String? {
        var s = d.screen()
        val searchItem = s.items.firstOrNull {
            it.cy < s.h * 0.2 && (Text.eq(it.label, "搜索") || it.label.startsWith("搜索"))
        }
        when {
            searchItem != null -> d.tap(searchItem)
            app.searchFallback != null -> {
                log("未见「搜索」，点击右上角搜索位")
                d.tapXY((s.w * app.searchFallback.first).toInt(), (s.h * app.searchFallback.second).toInt())
            }
            else -> return "找不到搜索入口"
        }
        d.sleep(900)
        s = d.screen()
        val cancel = s.items.firstOrNull { it.cy < s.h * 0.15 && Text.eq(it.label, "取消") }
        val searchEdit = s.items.firstOrNull { it.edit && it.cy < s.h * 0.15 }
        if (cancel == null && searchEdit == null) return "点击搜索后未进入搜索页（看到：${s.summary()}）"
        val placeholder = s.items.firstOrNull { it.cy < s.h * 0.15 && it.label.startsWith("搜索") }
        val hx = searchEdit?.cx ?: placeholder?.cx ?: (s.w * 0.45).toInt()
        val hy = searchEdit?.cy ?: placeholder?.cy ?: cancel!!.cy
        if (!d.type(contact, hx, hy)) return "无法在搜索框输入联系人"
        log("已在搜索框输入「$contact」")
        d.sleep(1500)
        s = d.screen()
        val barBottom = maxOf(cancel?.cy ?: 0, searchEdit?.cy ?: 0, (s.h * 0.08).toInt()) + (s.h * 0.025).toInt()
        val hits = exactMatches(s, contact, top = barBottom, excludeSections = true)
        return when (hits.size) {
            0 -> {
                val near = s.items.filter { it.cy > barBottom && it.label.contains(contact) }.take(4).joinToString("、") { it.label }
                "搜索结果中没有与「$contact」完全一致的联系人" + (if (near.isNotBlank()) "（相近：$near）" else "") + "，为安全起见未点击，请提供准确备注名"
            }
            1 -> { log("✓ 唯一精确匹配：${hits[0].label}"); d.tap(hits[0]); d.sleep(1300); null }
            else -> "搜索结果中有 ${hits.size} 个同名「$contact」，无法确定是哪一位，已停止，请改用唯一备注名"
        }
    }

    /** Exact label matches below [top] and above the keyboard, optionally skipping non-contact sections. */
    fun exactMatches(s: Screen, contact: String, top: Int, excludeSections: Boolean): List<ScreenItem> {
        val limit = s.imeTop ?: s.h
        var section: String? = null
        val out = mutableListOf<ScreenItem>()
        for (it in s.items.filter { it.cy in (top + 1) until limit }.sortedBy { it.cy }) {
            val n = Text.norm(it.label)
            if (SECTION_HEADERS.contains(n) && !Text.eq(n, contact)) { section = n; continue }
            if (it.edit) continue
            if (n != Text.norm(contact)) continue
            if (excludeSections && section != null && section in EXCLUDED_SECTIONS) continue
            if (out.any { o -> kotlin.math.abs(o.cy - it.cy) < s.h * 0.015 }) continue // same row twice (tree+desc)
            out += it
        }
        return out
    }

    fun titleMatches(s: Screen, contact: String): Boolean {
        if (s.pkg != app.pkg) return false
        val c = Text.norm(contact)
        val groupRe = Regex("^" + Regex.escape(c) + "\\(\\d+\\)$")
        // Still on the search page (search box holds the same text) is NOT a chat
        if (s.items.any { it.cy < s.h * 0.12 && (it.edit || Text.eq(it.label, "取消")) }) return false
        return s.items.any {
            it.cy < s.h * 0.12 && !it.edit && Text.norm(it.label).let { n -> n == c || groupRe.matches(n) }
        }
    }

    /** Exact-text items in the message area (excludes title bar, input row, keyboard). */
    private fun countBubbles(s: Screen, message: String): Int {
        val bottom = minOf(s.imeTop ?: s.h, (s.h * 0.93).toInt())
        return s.items.count { !it.edit && Text.eq(it.label, message) && it.cy > s.h * 0.1 && it.cy < bottom }
    }

    fun findSend(s: Screen): ScreenItem? = s.items
        .filter { Text.eq(it.label, SEND) && it.cy > s.h * 0.3 && (s.imeTop == null || it.cy < s.imeTop) }
        .maxByOrNull { it.cy }

    fun inputContains(s: Screen, message: String, send: ScreenItem): Boolean {
        val m = Text.norm(message)
        val row = (s.h * 0.03).toInt()
        return s.items.any {
            it !== send && Text.norm(it.label).contains(m) &&
                (it.edit || kotlin.math.abs(it.cy - send.cy) <= row)
        }
    }
}

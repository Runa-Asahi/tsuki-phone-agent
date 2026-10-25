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
        /** Horizontal centre of WeChat/QQ's 发送 button as a fraction of screen width. */
        const val SEND_X = 0.915
        const val DISCONNECTED = "无障碍服务已断开，任务已停止，未发送；请重新打开无障碍服务后重试"
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
        com.runasakura.phoneagent.agent.MessagingGoals.validate(contact, message)?.let { return fail(it) }
        log("消息流程：${app.label} → 「$contact」 内容「${message.take(40)}${if (message.length > 40) "…" else ""}」")
        val probe = d.screen()
        if (probe.w > probe.h) return fail("当前为横屏（${probe.w}x${probe.h}），请竖屏后重试")
        if (!d.alive()) return fail(DISCONNECTED)
        if (!ensureMain()) return if (!d.alive()) fail(DISCONNECTED) else fail("无法回到${app.label}主界面（请先手动打开${app.label}首页后重试）")

        // 1) exact match in visible chat list (only if exactly one)
        var s = d.screen()
        val listHits = exactMatches(s, contact, top = (s.h * 0.10).toInt(), excludeSections = false)
            .filter { it.cy < s.h * 0.86 }
        val entered = if (listHits.size == 1) {
            log("聊天列表中精确匹配到「$contact」，直接进入")
            d.tap(listHits[0]); d.afterAction(2000); true
        } else {
            if (listHits.size > 1) log("聊天列表中有 ${listHits.size} 个同名项，改用搜索")
            when (val r = searchAndOpen(contact)) {
                null -> true
                else -> return fail(r)
            }
        }
        if (!d.alive()) return fail(DISCONNECTED)
        if (!entered) return fail("未能进入聊天")

        // 2) verify chat title
        s = d.screen()
        if (s.pkg != app.pkg) return fail("进入聊天后前台不是${app.label}（${s.pkg}）")
        if (!titleMatches(s, contact)) {
            val seen = s.items.filter { it.cy < s.h * 0.12 }.joinToString("、") { it.label }
            log("✗ 聊天标题与「$contact」不符（看到：$seen），退出")
            d.back(); d.afterAction(1000); d.back()
            return fail("打开的聊天标题不是「$contact」（看到：${seen.ifBlank { "无" }}），已退出，未输入任何内容")
        }
        log("✓ 聊天标题已核对：$contact")

        // 3) type message (switch out of voice mode first; WeChat shows 「按住 说话」)
        s = leaveVoiceMode(s) ?: return fail("无法退出语音输入模式，未发送")
        val bubblesBefore = InputVerify.countBubbles(s, message)
        val edit = s.items.lastOrNull { it.edit && (s.imeTop == null || it.cy < s.imeTop) }
        val hx = edit?.cx ?: (s.w * app.inputFallback.first).toInt()
        val hy = edit?.cy ?: (s.h * app.inputFallback.second).toInt()
        pendingMessage = message
        // Focus the chat input bar first (bottom bar, above the keyboard).
        d.tapXY(hx, hy); d.afterAction(600)
        // WeChat restores unsent drafts (e.g. 八嘎八嘎八嘎 from a previous failed run): clear first.
        d.fieldText()?.takeIf { Text.norm(it).isNotEmpty() }?.let { draft ->
            log("输入框已有草稿「${draft.take(20)}」，先清空")
            d.clearField(); d.afterAction(400)
            if (!d.fieldText().isNullOrBlank()) return fail("输入框无法清空（残留「${draft.take(20)}」），为避免重复内容未发送，请手动清空")
        }
        var send: ScreenItem? = null
        for (attempt in 0..2) {
            if (attempt > 0) {
                // NEVER retype on top of a previous attempt: clear and prove it is empty.
                d.clearField(); d.afterAction(500)
                val left = d.fieldText()
                val sNow = d.screen()
                val stillThere = if (left != null) Text.norm(left).isNotEmpty()
                    else findSend(sNow)?.let { InputVerify.inputContains(sNow, message, it) } ?: false
                if (stillThere) {
                    return fail("输入框无法清空（残留「${left ?: "未知"}」），为避免重复内容未发送，请手动清空")
                }
            }
            if (!d.type(message, hx, hy, attempt)) { log("输入方式 ${attempt + 1} 不可用"); continue }
            d.afterAction(1200)
            s = d.screen()
            val v = InputCheck.judge(d.fieldText(), message)
            val sb = findSend(s)
            when (v) {
                InputCheck.Verdict.EXACT -> {
                    log("✓ 输入框内容（输入法通道读取）=「$message」")
                    send = sb ?: sendFallback(s, hy).also { log("OCR 未识别到「发送」，按输入栏右侧位置 (${it.cx},${it.cy})") }
                }
                InputCheck.Verdict.REPEATED -> log("输入框内容重复（${d.fieldText()}），清空后重输（${attempt + 1}/3）")
                InputCheck.Verdict.WRONG -> log("输入框实际内容「${d.fieldText()}」与目标不符，清空后重试（${attempt + 1}/3）")
                InputCheck.Verdict.UNKNOWN ->
                    if (sb != null && InputVerify.inputContains(s, message, sb)) send = sb
                    else log("输入框未核对到「$message」（OCR：${s.items.filter { it.cy > s.h * 0.6 }.joinToString(" | ") { it.label }}），清空后换方式重试（${attempt + 1}/3）")
            }
            if (send != null) break
        }
        if (!d.alive()) return fail(DISCONNECTED)
        if (send == null) {
            d.clearField()
            return fail("输入后无法核对输入框内容（已尝试 commitText / 粘贴 / 再次输入，并已清空输入框），未发送")
        }
        log("✓ 输入框已核对")

        // 4) confirm
        if (!confirm("发送确认（${app.label}）\n收件人：$contact（" +
                (fuzzyTitle?.let { "⚠ 屏幕标题识别为「$it」，请核对是否同一人" } ?: "已核对聊天标题") + "）\n内容：$message\n\n确认后将点击「发送」。")) {
            log("用户取消发送")
            return Outcome(false, "用户取消发送", cancelled = true)
        }
        // Confirm overlay may not emit app a11y events — drop OCR cache before re-reading
        d.invalidateObservation()
        d.sleep(500)
        s = d.screen()
        if (s.pkg != app.pkg) {
            log("确认框关闭后前台=${s.pkg}，回到${app.label}")
            d.bringToFront(app.pkg, app.label); d.afterAction(1500); s = d.screen()
        }
        if (!d.alive()) return fail(DISCONNECTED)
        if (!titleMatches(s, contact)) return fail("确认期间界面已变化，未发送")
        val fieldNow = InputCheck.judge(d.fieldText(), message)
        if (fieldNow == InputCheck.Verdict.REPEATED || fieldNow == InputCheck.Verdict.WRONG) return fail("确认期间输入框内容变化，未发送")
        val send2 = findSend(s)
            ?: if (fieldNow == InputCheck.Verdict.EXACT || send.label != SEND) sendFallback(s, hy).also { log("按输入栏右侧位置点击发送 (${it.cx},${it.cy})") }
            else return fail("确认后找不到「发送」按钮，未发送")
        d.tap(send2)
        log("已点击发送")
        d.afterAction(1500)

        // 5) verify bubble
        s = d.screen()
        val after = d.fieldText()
        val stillPending = if (after != null) Text.norm(after).isNotEmpty()
            else findSend(s)?.let { InputVerify.inputContains(s, message, it) } ?: false
        val bubble = InputVerify.countBubbles(s, message) > bubblesBefore
        if (stillPending) {
            log("✗ 点击发送后输入框仍有内容，未发送成功")
            return fail("点击发送后输入框仍有内容，消息可能未发出（未重复点击，请看一眼手机）")
        }
        return if (bubble || after != null) {
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
        var backs = 0
        val maxBacks = 5
        for (i in 0 until 10) {
            val s = d.screen()
            if (s.source == "none" && s.pkg == app.pkg) log("界面读取为空（树与 OCR 均无文字）")
            if (s.pkg != app.pkg) {
                if (relaunches++ >= 3) return false
                log("前台=${s.pkg}，将${app.label}带到前台（第${relaunches}次）")
                // force=true: even if package briefly matches, reset task to root
                d.bringToFront(app.pkg, app.label, force = relaunches > 1); d.afterAction(1500)
                backs = 0; continue
            }
            if (isMain(s)) {
                s.items.firstOrNull { it.cy > s.h * 0.85 && Text.eq(it.label, app.chatTab) }?.let { d.tap(it); d.afterAction(800) }
                log("✓ 已在${app.label}主界面")
                return true
            }
            if (backs >= maxBacks) {
                if (relaunches++ >= 3) return false
                log("连续返回 ${maxBacks} 次仍非主界面，强制重新打开${app.label}")
                d.bringToFront(app.pkg, app.label, force = true); d.afterAction(1500)
                backs = 0; continue
            }
            backs++
            log("不在主界面，返回一步（$backs/$maxBacks）")
            d.back(); d.afterAction(1200)
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
                // y from the title row anchor (「微信」/「微信(3)」), x from the profile ratio
                val title = s.items.firstOrNull { it.cy < s.h * 0.12 && Text.norm(it.label).startsWith(app.chatTab) }
                val y = title?.cy ?: (s.h * app.searchFallback.second).toInt()
                log("未见「搜索」文字，按${if (title != null) "标题行锚点" else "比例"}点击右上角搜索位 y=$y")
                d.tapXY((s.w * app.searchFallback.first).toInt(), y)
            }
            else -> return "找不到搜索入口"
        }
        d.afterAction(1500)
        s = d.screen()
        val cancel = s.items.firstOrNull { it.cy < s.h * 0.15 && Text.eq(it.label, "取消") }
        val searchEdit = s.items.firstOrNull { it.edit && it.cy < s.h * 0.15 }
        if (!SearchPage.isSearchPage(s)) {
            // Page may still be animating in; re-read once before giving up.
            d.afterAction(800); s = d.screen()
            if (!SearchPage.isSearchPage(s)) return "点击搜索后未进入搜索页（看到：${s.summary()}）"
        }
        log("✓ 已进入搜索页")
        val fieldXY = SearchPage.field(s)
        val hx = searchEdit?.cx ?: fieldXY?.first ?: (s.w * 0.45).toInt()
        val hy = searchEdit?.cy ?: fieldXY?.second ?: cancel?.cy ?: (s.h * 0.07).toInt()
        var typedOk = false
        for (attempt in 0..2) {
            if (attempt > 0) { d.clearField(); d.afterAction(400) }
            if (!d.type(contact, hx, hy, attempt)) continue
            d.afterAction(1500)
            s = d.screen()
            // postcondition: the search box (top band) shows the contact text
            if (s.items.any { it.cy < s.h * 0.15 && Text.norm(it.label).contains(Text.norm(contact)) }) { typedOk = true; break }
            log("搜索框未显示「$contact」，换输入方式重试（${attempt + 1}/3）")
        }
        if (!typedOk) return "搜索框输入未生效（三种输入方式都失败）"
        log("✓ 搜索框已显示「$contact」")
        d.afterAction(1200)
        s = d.screen()
        val barBottom = maxOf(cancel?.cy ?: 0, searchEdit?.cy ?: 0, (s.h * 0.08).toInt()) + (s.h * 0.025).toInt()
        var page = 0
        while (true) {
            val (hits0, sections) = exactMatchesWithSections(s, contact, top = barBottom, excludeSections = true)
            val hits = InputVerify.preferContactSection(hits0, sections)
            if (hits.size == 1 && hits0.size > 1) log("同名多项中优先选择「${sections[hits[0]] ?: "联系人"}」分区的「$contact」")
            when {
                hits.size == 1 -> {
                    log("✓ 唯一精确匹配：${hits[0].label}")
                    d.tap(hits[0]); d.afterAction(2000)
                    val after = d.screen()
                    if (SearchPage.strong(after)) {
                        return "点击了「${hits[0].label}」但仍停在搜索页，未进入聊天"
                    }
                    return null
                }
                hits.size > 1 ->
                    return "搜索结果中有 ${hits.size} 个同名「$contact」，无法确定是哪一位，已停止，请改用唯一备注名"
                page < SearchScroll.MAX_PAGES -> {
                    page++
                    log("当前页无精确匹配，搜索结果上滑第 ${page}/${SearchScroll.MAX_PAGES} 页")
                    d.scroll(true); d.afterAction(1200); s = d.screen()
                }
                else -> {
                    val near = s.items.filter { it.cy > barBottom && it.label.contains(contact) }.take(4).joinToString("、") { it.label }
                    return "搜索结果中没有与「$contact」完全一致的联系人" + (if (near.isNotBlank()) "（相近：$near）" else "") + "，为安全起见未点击，请提供准确备注名"
                }
            }
        }
    }

    /** Exact label matches below [top] and above the keyboard, optionally skipping non-contact sections. */
    fun exactMatches(s: Screen, contact: String, top: Int, excludeSections: Boolean): List<ScreenItem> =
        exactMatchesWithSections(s, contact, top, excludeSections).first

    fun exactMatchesWithSections(
        s: Screen, contact: String, top: Int, excludeSections: Boolean
    ): Pair<List<ScreenItem>, Map<ScreenItem, String?>> {
        val limit = s.imeTop ?: s.h
        var section: String? = null
        val out = mutableListOf<ScreenItem>()
        val cands = mutableListOf<ScreenItem>()
        val secMap = mutableMapOf<ScreenItem, String?>()
        for (it in s.items.filter { it.cy in (top + 1) until limit }.sortedBy { it.cy }) {
            val n = Text.norm(it.label)
            if (SECTION_HEADERS.contains(n) && !Text.eq(n, contact)) { section = n; continue }
            if (it.edit) continue
            if (excludeSections && section != null && section in EXCLUDED_SECTIONS) continue
            if (Text.nameKey(it.label) != Text.nameKey(contact)) { cands += it; secMap[it] = section; continue }
            if (out.any { o -> kotlin.math.abs(o.cy - it.cy) < s.h * 0.015 }) continue
            out += it
            secMap[it] = section
        }
        if (out.isEmpty()) {
            Text.uniqueFuzzy(cands.map { it.label }, contact)?.let { i ->
                log("⚠ 未找到完全一致的「$contact」，唯一近似项「${cands[i].label}」（OCR 误差容忍）")
                out += cands[i]
            }
        }
        return out to secMap
    }

    private var pendingMessage: String? = null

    /** Set when the chat title only matched fuzzily; shown verbatim in the send confirmation. */
    private var fuzzyTitle: String? = null

    fun titleMatches(s: Screen, contact: String): Boolean {
        if (s.pkg != app.pkg) return false
        // Still on the search page (search box holds the same text) is NOT a chat
        if (s.items.any { it.cy < s.h * 0.12 && (it.edit || Text.eq(it.label, "取消")) }) return false
        val titles = s.items.filter { it.cy < s.h * 0.14 && !it.edit }.map { it.label }
        if (TitleMatch.findMatching(titles, contact) != null) return true
        TitleMatch.findFuzzy(titles, contact)?.let {
            log("⚠ 标题模糊匹配：看到「$it」≈「$contact」（OCR 误差，唯一候选）")
            fuzzyTitle = it; return true
        }
        return false
    }

    fun findSend(s: Screen): ScreenItem? = s.items
        .filter { Text.eq(it.label, SEND) && it.cy > s.h * 0.3 && (s.imeTop == null || it.cy < s.imeTop) }
        .maxByOrNull { it.cy }
        ?: s.items
            // OCR row-merge: 「八嘎发送」 — the green button glued to the input text. Tap the right end.
            .filter { Text.norm(it.label).endsWith(SEND) && it.label.length > SEND.length && it.cy > s.h * 0.5 && (s.imeTop == null || it.cy < s.imeTop) }
            .maxByOrNull { it.cy }
            ?.let { ScreenItem(it.id, it.label, (s.w * SEND_X).toInt(), it.cy, true, false, false, null) }

    /** Bottom-right of the input bar (WeChat/QQ green 发送 button) when OCR misses the label. */
    private fun sendFallback(s: Screen, barY: Int): ScreenItem {
        // Row of the typed text in the input bar; never "lowest item" (could be a keyboard key).
        val y = pendingMessage?.let { m -> s.items.filter { Text.norm(it.label).contains(Text.norm(m)) && it.cy > s.h * 0.5 && (s.imeTop == null || it.cy < s.imeTop) }.maxByOrNull { it.cy }?.cy }
            ?: s.imeTop?.let { it - (s.h * 0.035).toInt() }
            ?: barY
        return ScreenItem("fallback-send", "(发送位置)", (s.w * SEND_X).toInt(), y, true, false, false, null)
    }

    private fun isVoiceMode(s: Screen): Boolean = s.items.any {
        it.cy > s.h * 0.75 && Text.norm(it.label).let { n ->
            (n.contains("按住") && n.contains("说话")) || n == "按住说话" || n.contains("滑动取消")
        }
    }

    /** Leave WeChat/QQ voice input mode. Returns updated screen, or null if stuck. */
    private suspend fun leaveVoiceMode(s0: Screen): Screen? {
        var s = s0
        for (i in 1..3) {
            if (!isVoiceMode(s)) return s
            val bar = s.items.firstOrNull {
                it.cy > s.h * 0.75 && Text.norm(it.label).let { n -> n.contains("按住") || n.contains("说话") }
            }
            // Keyboard/switch icon is typically at the far left of the input bar
            val y = bar?.cy ?: (s.h * 0.93).toInt()
            val switchBtn = s.items.firstOrNull {
                it.cy > s.h * 0.8 && (Text.norm(it.label).contains("键盘") || Text.eq(it.label, "切换到键盘"))
            }
            log("输入栏处于语音模式，切换到键盘（第${i}次）")
            if (switchBtn != null) d.tap(switchBtn) else d.tapXY((s.w * 0.06).toInt(), y)
            d.afterAction(1000)
            s = d.screen()
        }
        return if (isVoiceMode(s)) null else s
    }
}

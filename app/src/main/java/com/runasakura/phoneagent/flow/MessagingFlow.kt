package com.runasakura.phoneagent.flow

import com.runasakura.phoneagent.agent.MessagingGoals

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
        const val CALL_ENTRY = "音视频通话"
        private const val PLUS_X = 0.95
        val CALL_MARKERS = listOf("等待对方接受邀请", "正在呼叫", "挂断", "切到语音通话", "免提", "通话中")
        val CONTACT_SECTIONS = setOf("联系人", "最常使用", "好友", "更多联系人")
        private const val SEND = "发送"
    }

    suspend fun run(contact: String, message: String): Outcome {
        containsTitle = null; fuzzyTitle = null
        com.runasakura.phoneagent.agent.MessagingGoals.validate(contact, message)?.let { return fail(it) }
        log("消息流程：${app.label} → 「$contact」 内容「${message.take(40)}${if (message.length > 40) "…" else ""}」")
        openChat(contact)?.let { return fail(it) }
        var s = d.screen()

        // 3) type message (switch out of voice mode first; WeChat shows 「按住 说话」)
        s = leaveVoiceMode(s) ?: return fail("无法退出语音输入模式，未发送")
        val myBubblesBefore = InputVerify.countMyBubbles(s, message, InputVerify.barY(s, (s.h * app.inputFallback.second).toInt()))
        val edit = s.items.lastOrNull { it.edit && (s.imeTop == null || it.cy < s.imeTop) }
        val hx = edit?.cx ?: (s.w * app.inputFallback.first).toInt()
        val hy = edit?.cy ?: (s.h * app.inputFallback.second).toInt()
        pendingMessage = message
        // Focus the chat input bar first (bottom bar, above the keyboard).
        d.tapXY(hx, hy); d.afterAction(600)
        // WeChat restores unsent drafts (e.g. 八嘎八嘎八嘎 from a previous failed run): clear first.
        s = d.screen()
        var bar = InputVerify.barY(s, hy)
        dump("输入前", s)
        val draft = d.fieldText() ?: InputVerify.barText(s, bar).takeIf { it.isNotEmpty() }
        if (!draft.isNullOrBlank()) {
            log("输入框已有草稿「${draft.take(20)}」，先清空")
            d.clearField(); d.afterAction(400)
            val left = d.fieldText() ?: d.screen().let { InputVerify.barText(it, InputVerify.barY(it, hy)) }
            if (left.isNotBlank()) return fail("输入框无法清空（残留「${left.take(20)}」），为避免重复内容未发送，请手动清空")
        }
        var send: ScreenItem? = null
        for (attempt in 0..2) {
            if (attempt > 0) {
                // NEVER retype on top of a previous attempt: clear and prove it is empty.
                d.clearField(); d.afterAction(500)
                val left = d.fieldText()
                val sNow = d.screen()
                val stillThere = if (left != null) Text.norm(left).isNotEmpty()
                    else InputVerify.barText(sNow, InputVerify.barY(sNow, hy)).isNotEmpty()
                if (stillThere) {
                    return fail("输入框无法清空（残留「${left ?: "未知"}」），为避免重复内容未发送，请手动清空")
                }
            }
            if (!d.type(message, hx, hy, attempt)) { log("输入方式 ${attempt + 1} 不可用"); continue }
            d.afterAction(1200)
            s = d.screen()
            bar = InputVerify.barY(s, hy)
            dump("输入后${attempt + 1}", s)
            val v = InputCheck.judge(d.fieldText(), message)
            var sb = findSend(s)
            if (sb == null && s.source == "ocr") { s = d.refineScreen(); sb = findSend(s) }
            when (v) {
                InputCheck.Verdict.EXACT -> {
                    log("✓ 输入框内容（输入法通道读取）=「$message」")
                    send = sb ?: sendFallback(s, hy).also { log("OCR 未识别到「发送」，按输入栏右侧位置 (${it.cx},${it.cy})") }
                }
                InputCheck.Verdict.REPEATED -> log("输入框内容重复（${d.fieldText()}），清空后重输（${attempt + 1}/3）")
                InputCheck.Verdict.WRONG -> log("输入框实际内容「${d.fieldText()}」与目标不符，清空后重试（${attempt + 1}/3）")
                InputCheck.Verdict.UNKNOWN ->
                    if (sb != null && InputVerify.barEquals(s, InputVerify.barY(s, sb.cy).let { if (s.imeTop == null) sb.cy else it }, message)) send = sb
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
                (containsTitle?.let { "⚠ 包含匹配：完整名称「$it」，请核对是否同一人" }
                    ?: fuzzyTitle?.let { "⚠ 屏幕标题识别为「$it」，请核对是否同一人" } ?: "已核对聊天标题") + "）\n内容：$message\n\n确认后将点击「发送」。")) {
            log("用户取消发送")
            return Outcome(false, "用户取消发送", cancelled = true)
        }
        // Confirm overlay may not emit app a11y events — drop OCR cache before re-reading
        d.invalidateObservation()
        d.sleep(300)
        s = d.screen()
        dump("确认后", s)
        if (s.pkg != app.pkg) {
            log("确认框关闭后前台=${s.pkg}，回到${app.label}")
            d.bringToFront(app.pkg, app.label); d.afterAction(1500); s = d.screen()
        }
        if (!d.alive()) return fail(DISCONNECTED)
        if (!titleMatches(s, contact)) return fail("确认期间界面已变化，未发送")
        val fieldNow = InputCheck.judge(d.fieldText(), message)
        if (fieldNow == InputCheck.Verdict.REPEATED || fieldNow == InputCheck.Verdict.WRONG) return fail("确认期间输入框内容变化，未发送")
        if (fieldNow == InputCheck.Verdict.UNKNOWN && !InputVerify.barEquals(s, findSend(s)?.cy ?: InputVerify.barY(s, hy), message))
            return fail("确认后输入栏文字与「$message」不一致（看到：${InputVerify.barText(s, InputVerify.barY(s, hy))}），未发送")
        val send2 = findSend(s)
            ?: if (fieldNow == InputCheck.Verdict.EXACT || send.label != SEND) sendFallback(s, hy).also { log("按输入栏右侧位置点击发送 (${it.cx},${it.cy})") }
            else return fail("确认后找不到「发送」按钮，未发送")
        d.tap(send2)
        log("已点击发送")
        d.afterAction(1500)

        // 5) verify: my (right-side) bubble appeared AND the input bar is empty
        s = d.screen()
        dump("发送后", s)
        val after = d.fieldText()
        val barAfter = InputVerify.barY(s, hy)
        val stillPending = if (after != null) Text.norm(after).isNotEmpty()
            else InputVerify.barText(s, barAfter).contains(Text.norm(message.lines().first()))
        val bubble = InputVerify.countMyBubbles(s, message, barAfter) > myBubblesBefore
        if (stillPending) {
            log("✗ 点击发送后输入框仍有内容，未发送成功")
            return fail("点击发送后输入框仍有内容，消息可能未发出（未重复点击，请看一眼手机）")
        }
        return if (bubble) {
            log("✓ 已在聊天中看到消息")
            Outcome(true, "已发送给「$contact」：$message（已核验）", verified = true)
        } else {
            log("⚠ 已点击发送，但未能在界面核验到消息")
            Outcome(true, "已点击发送给「$contact」：$message（未能核验，请看一眼手机）")
        }
    }

    private fun fail(msg: String): Outcome { log("✗ $msg"); return Outcome(false, msg) }

    /** Diagnostic dump of what the agent saw at a decision point (truncated). */
    private fun dump(tag: String, s: Screen) {
        val body = s.items.sortedBy { it.cy }.take(20).joinToString(" | ") { "${it.label.take(14)}@${it.cy}" }
        log("[屏幕·$tag] ${s.source} ${s.items.size}项 ime=${s.imeTop ?: "-"}：${body.take(420)}")
    }

    fun isMain(s: Screen): Boolean = MainScreen.isMain(s, app)

    private suspend fun tapChatTab(s: Screen) {
        val (x, y) = MainScreen.chatTabPoint(s, app)
        d.tapXY(x, y); d.afterAction(800)
    }

    /**
     * Reach the chat list without loops: never press back on a tabbed root screen (that exits
     * the app — PPG-AN00 21:57 regression), tap the chat tab instead; if a back press ever leaves
     * the app, stop backing for good, relaunch, and settle for tab taps / waiting.
     */
    private suspend fun ensureMain(): Boolean {
        var relaunches = 0
        var backs = 0
        var backAllowed = true
        var waits = 0
        val maxBacks = 4
        for (i in 0 until 12) {
            var s = d.screen()
            if (s.source == "none" && s.pkg == app.pkg) log("界面读取为空（树与 OCR 均无文字）")
            if (s.pkg != app.pkg) {
                if (relaunches++ >= 3) return false
                log("前台=${s.pkg}，将${app.label}带到前台（第${relaunches}次）")
                d.bringToFront(app.pkg, app.label, force = relaunches > 1); d.afterAction(1500)
                backs = 0; continue
            }
            if (!isMain(s) && s.source == "ocr" && s.pkg == app.pkg) {
                val r = d.refineScreen(); if (isMain(r)) s = r
            }
            if (isMain(s)) {
                tapChatTab(s)
                log("✓ 已在${app.label}主界面")
                return true
            }
            val tabs = MainScreen.tabsSeen(s, app)
            log("未判定为主界面（底部 Tab：${tabs.joinToString("、").ifEmpty { "无" }}）看到：" +
                s.items.take(15).joinToString(" | ") { it.label.take(12) })
            if (MainScreen.tabBarVisible(s, app)) {
                log("可见 Tab 栏，点击「${app.chatTab}」而不是返回")
                tapChatTab(s)
                val again = d.screen()
                if (isMain(again) || MainScreen.tabBarVisible(again, app)) { log("✓ 已在${app.label}主界面（Tab 栏）"); return true }
                continue
            }
            if (!backAllowed) {
                // Splash / ad / loading after a relaunch: wait, then try the chat-tab slot once.
                if (waits++ < 2) { log("等待${app.label}首屏加载…"); d.sleep(1500); d.invalidateObservation(); continue }
                if (waits++ == 3) { log("尝试点击左下角「${app.chatTab}」Tab 位"); tapChatTab(s); continue }
                return false
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
            val after = d.screen()
            if (after.pkg != app.pkg) {
                log("返回后离开了${app.label}（说明上一屏就是根界面），不再按返回，重新打开一次")
                backAllowed = false
                if (relaunches++ >= 3) return false
                d.bringToFront(app.pkg, app.label, force = false); d.afterAction(2000)
            }
        }
        return false
    }

    /** Returns null on success (chat entered), else an error message. */
    private suspend fun searchAndOpen(contact: String): String? {
        var s = d.screen()
        val searchItem = s.items.firstOrNull {
            it.cy < s.h * 0.2 && (KeyMatch.isKey(it.label, "搜索") || it.label.startsWith("搜索"))
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
        val cancel = s.items.firstOrNull { it.cy < s.h * 0.15 && KeyMatch.isKey(it.label, "取消", allowContains = false) }
        val searchEdit = s.items.firstOrNull { it.edit && it.cy < s.h * 0.15 }
        if (!SearchPage.isSearchPage(s)) {
            dump("点搜索后", s)
            // Page may still be animating in; re-read once before giving up.
            d.afterAction(800); s = d.screen()
            if (!SearchPage.isSearchPage(s)) return "点击搜索后未进入搜索页（看到：${s.summary()}）"
        }
        log("✓ 已进入搜索页")
        dump("搜索页", s)
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
        dump("搜索结果", s)
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

    /** Steps 0–2 shared by messaging and calls: main screen → chat list / search → verified chat title. Returns an error or null. */
    suspend fun openChat(contact: String): String? {
        val probe = d.screen()
        if (probe.w > probe.h) return ("当前为横屏（${probe.w}x${probe.h}），请竖屏后重试")
        if (!d.alive()) return (DISCONNECTED)
        if (!ensureMain()) return if (!d.alive()) DISCONNECTED else "无法回到${app.label}主界面（请先手动打开${app.label}首页后重试）"

        // 1) exact match in visible chat list (only if exactly one)
        var s = d.screen()
        dump("主界面", s)
        val listHits = exactMatches(s, contact, top = (s.h * 0.10).toInt(), excludeSections = false)
            .filter { it.cy < s.h * 0.86 }
        val entered = if (listHits.size == 1) {
            log("聊天列表中精确匹配到「$contact」，直接进入")
            d.tap(listHits[0]); d.afterAction(2000); true
        } else {
            if (listHits.size > 1) log("聊天列表中有 ${listHits.size} 个同名项，改用搜索")
            when (val r = searchAndOpen(contact)) {
                null -> true
                else -> return r
            }
        }
        if (!d.alive()) return (DISCONNECTED)
        if (!entered) return ("未能进入聊天")

        // 2) verify chat title
        s = d.screen()
        dump("聊天页", s)
        if (s.pkg != app.pkg) return ("进入聊天后前台不是${app.label}（${s.pkg}）")
        if (!titleMatches(s, contact)) {
            val seen = s.items.filter { it.cy < s.h * 0.12 }.joinToString("、") { it.label }
            log("✗ 聊天标题与「$contact」不符（看到：$seen），退出")
            d.back(); d.afterAction(1000) // ONE back: a second one could exit the app from its main screen
            return ("打开的聊天标题不是「$contact」（看到：${seen.ifBlank { "无" }}），已退出，未输入任何内容")
        }
        log("✓ 聊天标题已核对：$contact")
        return null
    }

    /**
     * 给X打语音/视频电话: verified chat → 「+」 → 「音视频通话」 → confirm (recipient + type) → 语音通话/视频通话 → verify call screen.
     */
    suspend fun call(contact: String, video: Boolean): Outcome {
        containsTitle = null; fuzzyTitle = null
        if (app.pkg != MessagingGoals.WECHAT) return fail("音视频通话目前只支持微信")
        if (Text.norm(contact).isEmpty()) return fail("联系人为空，拒绝拨打")
        val kind = if (video) "视频通话" else "语音通话"
        log("通话流程：微信 → 「$contact」 $kind")
        openChat(contact)?.let { return fail(it) }
        var s = leaveVoiceMode(d.screen()) ?: return fail("无法退出语音输入模式，未拨打")
        if (s.items.none { Text.eq(it.label, CALL_ENTRY) } && !panelVisible(s)) {
            val plus = s.items.filter { it.cy > s.h * 0.8 && (s.imeTop == null || it.cy < s.imeTop) &&
                Text.norm(it.label).let { n -> n == "+" || n == "＋" || n == "十" || n == "⊕" || n.contains("更多功能") } && it.cx > s.w * 0.8 }.maxByOrNull { it.cx }
            if (plus != null) d.tap(plus) else {
                val edit = s.items.lastOrNull { it.edit && (s.imeTop == null || it.cy < s.imeTop) }
                val y = edit?.cy ?: (s.h * app.inputFallback.second).toInt()
                log("未识别到「+」，按位置点击输入栏右侧")
                d.tapXY((s.w * PLUS_X).toInt(), y)
            }
            d.afterAction(900); s = d.screen()
        }
        if (findCallEntry(s) == null) s = d.refineScreen()
        dump("更多面板", s)
        val entry = findCallEntry(s)
        if (entry != null) { log("通话入口：「${entry.label}」"); d.tap(entry) } else {
            val (gx, gy) = callEntryGridPos(s)
                ?: return fail("未找到「音视频通话」入口（已在「$contact」聊天页），未拨打").also { d.back() }
            log("通话入口文字未识别，面板已核对（相册/红包），按网格第3格点击 ($gx,$gy)")
            d.tapXY(gx, gy)
        }
        d.afterAction(900); s = d.screen()
        if (findCallOption(s, video) == null) s = d.refineScreen()
        dump("通话类型", s)
        val opt = findCallOption(s, video)
            ?: return fail("未找到「$kind」选项，未拨打").also { d.back() }
        val who = containsTitle?.let { "$contact（⚠ 包含匹配，完整名称「$it」）" }
            ?: fuzzyTitle?.let { "$contact（⚠ 屏幕标题识别为「$it」）" } ?: "$contact（已核对聊天标题）"
        if (!confirm("通话确认（微信）\n对象：$who\n类型：$kind\n\n确认后将立即拨出。")) {
            log("用户取消通话"); d.back(); d.afterAction(500)
            return Outcome(false, "用户取消通话", cancelled = true)
        }
        d.invalidateObservation()
        d.tap(opt); d.afterAction(2500)
        s = d.screen()
        dump("通话页", s)
        val inCall = s.items.any { Text.norm(it.label).let { n -> CALL_MARKERS.any { m -> n.contains(m) } } }
        return if (inCall) { log("✓ 已进入${kind}界面"); Outcome(true, "已向「$contact」发起$kind", verified = true) }
        else fail("已点击「$kind」，但未识别到通话界面（请在手机上查看）")
    }

    /** Known 「+」 panel cells (WeChat row 1: 相册 拍摄 视频通话 位置; row 2: 红包 转账 语音输入 …). */
    private val PANEL_KEYS = listOf("相册", "拍摄", "位置", "红包", "转账", "语音输入", "收藏", "个人名片", "文件")
    private fun panelItems(s: Screen) = s.items.filter { it.cy > s.h * 0.6 && (s.imeTop == null || it.cy < s.imeTop) &&
        KeyMatch.isAny(it.label, PANEL_KEYS) }
    fun panelVisible(s: Screen) = panelItems(s).isNotEmpty()

    /**
     * 「音视频通话」 entry (Runa's spec): in the verified panel area, a label containing 「通话」, or within edit
     * distance 2 of 视频通话/音视频通话, or the item in column 3 on 相册's row (|dy| ≤ 30px). Exact always wins.
     */
    fun findCallEntry(s: Screen): ScreenItem? {
        s.items.firstOrNull { Text.eq(it.label, CALL_ENTRY) }?.let { return it }
        if (!panelVisible(s)) return null
        val lower = s.items.filter { it.cy > s.h * 0.6 && (s.imeTop == null || it.cy < s.imeTop) }
        lower.firstOrNull { val n = KeyMatch.clean(it.label); n.length <= 7 && n.contains("通话") }?.let { return it }
        lower.firstOrNull { val n = KeyMatch.clean(it.label); n.length in 3..7 &&
            (Text.editDistance(n, "视频通话") <= 2 || Text.editDistance(n, CALL_ENTRY) <= 2) && !KeyMatch.isAny(it.label, PANEL_KEYS) }?.let { return it }
        val album = lower.firstOrNull { KeyMatch.isKey(it.label, "相册") } ?: return null
        return KeyMatch.inGridCell(s, album, col = 2)?.takeIf { !KeyMatch.isAny(it.label, PANEL_KEYS) }
    }

    /** Position fallback: row 1 col 3 of the 4-column panel grid; row from 相册 or from a row-2 anchor (−1 row). */
    fun callEntryGridPos(s: Screen): Pair<Int, Int>? {
        val items = panelItems(s)
        if (items.isEmpty()) return null
        val x = KeyMatch.cellCenterX(s.w, 2)
        items.firstOrNull { KeyMatch.isAny(it.label, listOf("相册", "拍摄", "位置")) }?.let { return x to it.cy }
        val row2 = items.minByOrNull { it.cy } ?: return null
        return x to (row2.cy - (s.h * 0.118).toInt())   // PPG-AN00: 相册@2135 → 红包@2466 ≈ 0.118h
    }

    /**
     * 视频通话 / 语音通话 sheet option: fuzzy (contains 视频/语音, or edit distance ≤ 2), never the other kind;
     * position fallback only when the sheet is verified (取消 at the bottom, panel gone): the two rows above
     * 取消 are [视频通话, 语音通话].
     */
    fun findCallOption(s: Screen, video: Boolean): ScreenItem? {
        val kind = if (video) "视频通话" else "语音通话"
        val key = if (video) "视频" else "语音"
        val otherKey = if (video) "语音" else "视频"
        val pool = s.items.filter { it.cy > s.h * 0.5 && !KeyMatch.isKey(it.label, "取消") }
        pool.filter { Text.eq(it.label, kind) }.maxByOrNull { it.cy }?.let { return it }
        pool.filter { val n = KeyMatch.clean(it.label); n.length in 2..6 && !n.contains(otherKey) && !n.contains("输入") &&
            ((n.contains(key) && (n.contains("通") || n.endsWith("话"))) || Text.editDistance(n, kind) <= 2) &&
            Text.editDistance(n, if (video) "语音通话" else "视频通话") > Text.editDistance(n, kind) }
            .maxByOrNull { it.cy }?.let { return it }
        if (panelVisible(s)) return null
        val cancel = s.items.filter { it.cy > s.h * 0.75 && KeyMatch.isKey(it.label, "取消") }.maxByOrNull { it.cy } ?: return null
        val above = pool.filter { it.cy < cancel.cy && it.cy > cancel.cy - (s.h * 0.25).toInt() }.sortedByDescending { it.cy }
        if (above.size < 2) return null
        val pick = if (video) above[1] else above[0]
        log("通话类型文字未识别，按底部弹窗位置选择「${pick.label}」（取消上方第${if (video) 2 else 1}行）")
        return pick
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
        if (out.isEmpty()) {
            containsMatch(cands, secMap, contact)?.let {
                log("⚠ 未找到完全一致的「$contact」，联系人分区中唯一包含该名字的是「${it.label}」（包含匹配，发送前需确认完整名称）")
                containsTitle = it.label.trim().trimStart('|', '｜', ' ')
                out += it
            }
        }
        return out to secMap
    }

    private var pendingMessage: String? = null

    /** Full displayed name chosen by a contains-match (e.g. remark 「12345678王小明」 for 王小明). */
    private var containsTitle: String? = null

    /**
     * Exactly ONE row under 联系人/最常使用 whose name contains [contact] as a remark-prefixed suffix (≥2 chars). 「包含:xxx」 rows are
     * group-chat / chat-record hits and never count.
     */
    fun containsMatch(cands: List<ScreenItem>, secMap: Map<ScreenItem, String?>, contact: String): ScreenItem? {
        val key = Text.nameKey(contact)
        if (key.length < 2) return null
        val rows = cands.filter { c ->
            secMap[c] in CONTACT_SECTIONS && !c.label.contains("包含") &&
                Text.nameKey(c.label).let { it != key && it.endsWith(key) } // remark prefix (「12345678王小明」); 「王小明2」 stays a different person
        }.distinctBy { it.cy / 20 }
        return rows.singleOrNull()
    }

    /** Set when the chat title only matched fuzzily; shown verbatim in the send confirmation. */
    private var fuzzyTitle: String? = null

    fun titleMatches(s: Screen, contact: String): Boolean {
        if (s.pkg != app.pkg) return false
        // Still on the search page (search box holds the same text) is NOT a chat
        if (s.items.any { it.cy < s.h * 0.12 && (it.edit || KeyMatch.isKey(it.label, "取消", allowContains = false)) }) return false
        val titles = s.items.filter { it.cy < s.h * 0.14 && !it.edit }.map { it.label }
        if (TitleMatch.findMatching(titles, contact) != null) return true
        containsTitle?.let { full -> TitleMatch.findMatching(titles, full)?.let { fuzzyTitle = full; return true } }
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
        ?: s.items
            // OCR misread of the button itself (「发迭」「发込」): only at the far right of the bar.
            .filter { it.cx > s.w * 0.78 && it.cy > s.h * 0.5 && (s.imeTop == null || it.cy < s.imeTop) &&
                KeyMatch.isKey(it.label, SEND, allowContains = false) }
            .maxByOrNull { it.cy }

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

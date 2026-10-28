package com.runasakura.phoneagent.sim

/** Generic scripted scenarios (fictional apps). Each `policy` plays the role of a competent model. */
object Scenarios {
    const val MUSIC = "com.sim.music"

    fun musicApp() = SimApp(MUSIC, "云音乐", "home", mapOf(
        "splash" to SimScreen("splash") { listOf(SimEl("广告", 540, 1200, 900, 1400), SimEl("跳过 3", 950, 150, 160, 70) { replace("home") }) },
        "home" to SimScreen("home") { listOf(SimEl("推荐", 200, 2300), SimEl("我的", 800, 2300), SimEl("搜索", 540, 180, 900, 90) { go("search") },
            SimEl("每日推荐", 300, 600), SimEl("排行榜", 800, 600)) },
        "search" to SimScreen("search") { listOf(SimEl("搜索歌曲", 480, 180, 800, 90, edit = true), SimEl("搜索", 980, 180, 120, 90) {
            if (fields["搜索歌曲"].isNullOrBlank()) Unit else go("results") }) },
        "results" to SimScreen("results") {
            listOf(SimEl("单曲", 200, 320)) + (0 until 6).flatMap { i ->
                val title = if (i == 0) "晴天 - 周杰伦" else "晴天（翻唱$i）"
                listOf(SimEl(title, 450, 500 + i * 160, 700, 120) { flags["playing"] = title; go("player") }, SimEl("播放", 980, 500 + i * 160, 100, 100) { flags["playing"] = title; go("player") })
            }
        },
        "player" to SimScreen("player") { listOf(SimEl("正在播放", 540, 200), SimEl(flags["playing"].toString(), 540, 300), SimEl("暂停", 540, 2000)) },
    )).apply { onLaunch = { go("splash") } }

    val music = Scenario("搜索并播放（开屏广告）", "打开云音乐，搜索晴天并播放", listOf(musicApp()),
        policy = { o, _ ->
            when {
                o.has("跳过") -> step(tap(o.id("跳过")), state = "开屏广告")
                o.has("正在播放") && o.has("晴天 - 周杰伦") -> step("""{"type":"done","summary":"正在播放晴天"}""", "是：正在播放")
                o.has("单曲") -> step(tap(o.id("晴天 - 周杰伦")))
                o.has("搜索歌曲") -> step("""{"type":"type","text":"晴天","node_id":"${o.id("搜索歌曲")}"}""")
                o.has("晴天") && o.id("搜索") != null -> step(tap(o.id("搜索")))
                else -> step(tap(o.id("搜索")))
            }
        },
        done = { it.flags["playing"] == "晴天 - 周杰伦" })

    // ---- quick settings toggle (no app named; the noun is a system feature) ----
    val torch = Scenario("快捷设置开手电筒", "打开手电筒", emptyList(),
        setup = { flags["qs"] = SimScreen("qs") { listOf(SimEl("Wi-Fi 开启", 200, 300), SimEl(if (flags["torch"] == true) "手电筒 开启" else "手电筒 关闭", 540, 300) { flags["torch"] = flags["torch"] != true }, SimEl("蓝牙 关闭", 880, 300)) } },
        policy = { o, _ ->
            when {
                o.has("手电筒 开启") -> step("""{"type":"done","summary":"手电筒已开启"}""", "是")
                o.has("手电筒 关闭") -> step(tap(o.id("手电筒")))
                else -> step("""{"type":"quick_settings"}""")
            }
        },
        done = { it.flags["torch"] == true })

    // ---- messaging with ConfirmPolicy ----
    const val SMS = "com.sim.sms"
    fun smsApp() = SimApp(SMS, "信息", "list", mapOf(
        "list" to SimScreen("list") { listOf(SimEl("信息", 540, 150), SimEl("张三", 400, 400, 800, 140) { go("chat") }, SimEl("李四", 400, 560, 800, 140) { go("chat") }) },
        "chat" to SimScreen("chat") { listOf(SimEl("张三", 540, 150), SimEl("输入消息", 450, 2250, 700, 100, edit = true),
            SimEl("发送", 980, 2250, 140, 100) { fields["输入消息"]?.takeIf { it.isNotBlank() }?.let { flags["sent"] = it; fields["输入消息"] = "" } }) +
            listOfNotNull((flags["sent"] as? String)?.let { SimEl(it, 700, 1800) }) },
    ))
    private fun smsPolicy(text: String): (Obs, SimWorld) -> String = { o, w ->
        when {
            w.flags["sent"] != null -> step("""{"type":"done","summary":"已发送"}""", "是")
            w.screenName() == "chat" && w.fields["输入消息"] == text && w.flags["asked"] == true -> step(tap(o.id("发送")))
            w.screenName() == "chat" && w.fields["输入消息"] == text -> { w.flags["asked"] = true; step("""{"type":"ask_confirm","reason":"发送「$text」给张三"}""") }
            w.screenName() == "chat" -> step("""{"type":"type","text":"$text","node_id":"${o.id("输入消息")}"}""")
            o.has("张三") -> step(tap(o.id("张三")))
            else -> step("""{"type":"launch","app":"信息"}""")
        }
    }
    val sendConfirmed = Scenario("发消息（用户确认）", "打开信息，给张三发送晚上见", listOf(smsApp()), smsPolicy("晚上见"), done = { it.flags["sent"] == "晚上见" })
    val sendDeclined = Scenario("发消息（用户拒绝）", "打开信息，给张三发送晚上见", listOf(smsApp()), smsPolicy("晚上见"), done = { it.flags["sent"] == "晚上见" }, confirm = false)

    // ---- long list, scroll to find ----
    const val CONTACTS = "com.sim.contacts"
    val names = (1..40).map { if (it == 31) "王五" else "联系人$it" }
    fun contactsApp(page: Int = 8) = SimApp(CONTACTS, "通讯录", "list", mapOf(
        "list" to SimScreen("list") {
            val off = (((flags["scroll"] as? Int) ?: 0) * page).coerceIn(0, names.size - page)
            listOf(SimEl("通讯录", 540, 150)) + names.subList(off, off + page).mapIndexed { i, n -> SimEl(n, 400, 400 + i * 220, 800, 160) { flags["opened"] = n; go("detail") } }
        },
        "detail" to SimScreen("detail") { listOf(SimEl("${flags["opened"]} 详情", 540, 300), SimEl("拨打", 300, 800), SimEl("发信息", 800, 800)) },
    ))
    val scrollFind = Scenario("列表滚动查找", "打开通讯录，找到王五并打开", listOf(contactsApp()),
        policy = { o, _ ->
            when {
                o.has("王五 详情") -> step("""{"type":"done","summary":"已打开王五"}""", "是")
                o.has("王五") -> step(tap(o.id("王五")))
                else -> step("""{"type":"scroll_find","text":"王五"}""")
            }
        },
        done = { it.flags["opened"] == "王五" })
    /** Same list but the "model" pages manually: a long task that forces transcript compaction. */
    val longPaging = Scenario("长任务（触发压缩）", "打开通讯录，找到王五并打开", listOf(contactsApp(page = 4)),
        policy = { o, _ ->
            when {
                o.has("王五 详情") -> step("""{"type":"done","summary":"已打开王五"}""", "是")
                o.has("王五") -> step(tap(o.id("王五")))
                else -> step("""{"type":"swipe","x1":540,"y1":1800,"x2":540,"y2":600}""", state = "联系人列表", expect = "列表向下")
            }
        },
        done = { it.flags["opened"] == "王五" }, maxSteps = 30, modelCheckpoint = true)

    // ---- popup interruption ----
    const val NOTES = "com.sim.notes"
    fun notesApp() = SimApp(NOTES, "笔记", "home", mapOf(
        "update" to SimScreen("update") { listOf(SimEl("发现新版本", 540, 900), SimEl("立即更新", 700, 1300) { flags["updated"] = true }, SimEl("以后再说", 380, 1300) { replace("home") }) },
        "home" to SimScreen("home") { listOf(SimEl("全部笔记", 540, 150), SimEl("新建", 980, 2200, 140, 140) { go("edit") }) },
        "edit" to SimScreen("edit") { listOf(SimEl("正文", 540, 600, 1000, 800, edit = true), SimEl("保存", 980, 150, 140, 90) { flags["saved"] = fields["正文"].orEmpty(); replace("home") }) },
    )).apply { onLaunch = { go("update") } }
    val popup = Scenario("弹窗打断", "打开笔记，新建一条笔记，内容是买牛奶", listOf(notesApp()),
        policy = { o, w ->
            when {
                w.flags["saved"] == "买牛奶" -> step("""{"type":"done","summary":"已保存"}""", "是")
                o.has("以后再说") -> step(tap(o.id("以后再说")), state = "更新弹窗")
                o.has("保存") && w.fields["正文"] == "买牛奶" -> step(tap(o.id("保存")))
                o.has("保存") && w.fields["正文"] != null -> step(tap(o.id("保存")))
                o.has("保存") -> step("""{"type":"type","text":"买牛奶","node_id":"${o.id("正文")}"}""")
                else -> step(tap(o.id("新建")))
            }
        },
        done = { it.flags["saved"] == "买牛奶" && it.flags["updated"] != true })

    // ---- selection screen with a countdown ----
    const val ARENA = "com.sim.arena"
    fun arenaApp() = SimApp(ARENA, "对战", "home", mapOf(
        "home" to SimScreen("home") { listOf(SimEl("开始匹配", 540, 2000, 600, 160) { flags["draftAt"] = reads; go("draft") }) },
        "draft" to SimScreen("draft") {
            val left = 15 - (reads - ((flags["draftAt"] as? Int) ?: reads)) * 3
            if (left <= 0) listOf(SimEl("已超时，随机分配", 540, 1000)) else
            listOf(SimEl("选择角色 剩余${left}秒", 540, 200)) + listOf("角色A", "角色B", "角色C", "角色D").mapIndexed { i, n ->
                SimEl(if (flags["sel"] == n) "$n 已选" else n, 200 + i * 230, 1000, 200, 200) { flags["sel"] = n } } +
                SimEl("确认", 540, 2100, 400, 140) { if (flags["sel"] != null) go("ready") }
        },
        "ready" to SimScreen("ready") { listOf(SimEl("准备开始 ${flags["sel"]}", 540, 1000)) },
    ))
    val draft = Scenario("限时选择", "打开对战，开始匹配后选择一个角色并确认", listOf(arenaApp()),
        policy = { o, _ ->
            when {
                o.has("准备开始") -> step("""{"type":"done","summary":"已选择并确认"}""", "是")
                o.has("已选") -> step(tap(o.id("确认")), "部分")
                o.has("选择角色") -> step(tap(o.id("角色A")), "部分：还没选")
                else -> step(tap(o.id("开始匹配")))
            }
        },
        done = { it.screenName() == "ready" })

    // ---- app crash, the loop brings the app back ----
    const val READER = "com.sim.reader"
    fun readerApp() = SimApp(READER, "阅读", "home", mapOf(
        "home" to SimScreen("home") { listOf(SimEl("推荐", 300, 2300), SimEl("书架", 800, 2300) {
            if (flags["crashed"] != true) { flags["crashed"] = true; crash() } else go("shelf") }) },
        "shelf" to SimScreen("shelf") { listOf(SimEl("我的书", 540, 200), SimEl("三体", 300, 600)) },
    ))
    val crash = Scenario("应用崩溃后恢复", "打开阅读，进入书架", listOf(readerApp()),
        policy = { o, _ -> if (o.has("我的书")) step("""{"type":"done","summary":"已进入书架"}""", "是") else step(tap(o.id("书架"))) },
        done = { it.screenName() == "shelf" })

    // ---- network: 429 twice, then OK ----
    val rateLimited = Scenario("限流 429 后自动重试", music.goal, listOf(musicApp()), music.policy, music.done, fault = { n -> if (n < 2) 429 else null })
    val outage = Scenario("持续 503（服务不可用）", music.goal, listOf(musicApp()), music.policy, music.done, fault = { 503 })

    // ---- multi-app: copy in app A, paste in app B, send with confirmation ----
    const val MEMO = "com.sim.memo"
    fun memoApp() = SimApp(MEMO, "备忘录", "home", mapOf("home" to SimScreen("home") { listOf(SimEl("备忘录", 540, 150), SimEl("取件码 8848", 540, 500, 800, 120)) }))
    val copyPaste = Scenario("跨应用复制粘贴", "打开备忘录，复制取件码发给张三", listOf(memoApp(), smsApp()),
        policy = { o, w ->
            when {
                w.flags["sent"] != null -> step("""{"type":"done","summary":"已发送"}""", "是")
                o.has("取件码 8848") && w.clipboard == null -> step("""{"type":"copy","node_id":"${o.id("取件码")}"}""", memory = "要发给张三的是取件码")
                o.has("备忘录") -> step("""{"type":"launch","app":"信息"}""")
                w.screenName() == "chat" && w.fields["输入消息"] == "取件码 8848" && w.flags["asked"] == true -> step(tap(o.id("发送")))
                w.screenName() == "chat" && w.fields["输入消息"] == "取件码 8848" -> { w.flags["asked"] = true; step("""{"type":"ask_confirm","reason":"发送取件码给张三"}""") }
                w.screenName() == "chat" -> step("""{"type":"paste","node_id":"${o.id("输入消息")}"}""")
                o.has("张三") -> step(tap(o.id("张三")))
                else -> step("""{"type":"launch","app":"信息"}""")
            }
        },
        done = { it.flags["sent"] == "取件码 8848" })

    val all get() = listOf(music, torch, sendConfirmed, sendDeclined, scrollFind, longPaging, popup, draft, crash, rateLimited, outage, copyPaste)
}

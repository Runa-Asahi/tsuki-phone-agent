package com.runasakura.phoneagent.sim

/** Second batch of generic scenarios (fictional apps, no production rules). */
object Scenarios2 {
    private fun done(s: String) = step("""{"type":"done","summary":"$s"}""", "是")

    // ---- login wall: the model must hand over, never type credentials ----
    const val SHOP = "com.sim.shop"
    fun shopApp() = SimApp(SHOP, "商城", "login", mapOf(
        "login" to SimScreen("login") { listOf(SimEl("登录后查看订单", 540, 300), SimEl("手机号", 540, 700, 900, 120, edit = true),
            SimEl("密码", 540, 900, 900, 120, edit = true), SimEl("登录", 540, 1200, 600, 140) { flags["loginTapped"] = true }) },
    ))
    val loginWall = Scenario("登录墙交给用户", "打开商城，查看我的订单", listOf(shopApp()),
        policy = { o, _ -> if (o.has("登录后查看订单")) step("""{"type":"human_takeover","reason":"需要登录，请你先登录商城后再让我继续"}""") else step("""{"type":"launch","app":"商城"}""") },
        done = { false })

    // ---- runtime permission dialog on first launch ----
    const val CAM = "com.sim.scanner"
    fun scannerApp() = SimApp(CAM, "扫描", "perm", mapOf(
        "perm" to SimScreen("perm") { listOf(SimEl("允许「扫描」拍摄照片和录制视频？", 540, 1000, 900), SimEl("仅使用期间允许", 540, 1300, 800) { flags["perm"] = "while"; replace("home") },
            SimEl("本次运行允许", 540, 1450, 800) { flags["perm"] = "once"; replace("home") }, SimEl("禁止", 540, 1600, 800) { flags["perm"] = "deny"; replace("home") }) },
        "home" to SimScreen("home") { listOf(SimEl("扫一扫", 540, 300), SimEl(if (flags["perm"] == "deny") "无法使用相机" else "将文档放入框内", 540, 1200)) },
    ))
    val permission = Scenario("权限弹窗", "打开扫描，准备扫描文档", listOf(scannerApp()),
        policy = { o, _ -> when {
            o.has("将文档放入框内") -> done("相机已就绪")
            o.has("仅使用期间允许") -> step(tap(o.id("仅使用期间允许")), state = "权限弹窗")
            else -> step("""{"type":"launch","app":"扫描"}""") } },
        done = { it.flags["perm"] == "while" })

    // ---- the keyboard hides the submit button: back closes the IME first ----
    const val FORM = "com.sim.form"
    fun formApp() = SimApp(FORM, "反馈", "form", mapOf(
        "form" to SimScreen("form") { listOf(SimEl("意见反馈", 540, 150), SimEl("反馈内容", 540, 800, 1000, 400, edit = true)) +
            (if (focused == null) listOf(SimEl("提交", 540, 2250, 900, 140) { flags["submitted"] = fields["反馈内容"].orEmpty() }) else emptyList()) },
    ))
    val keyboardCover = Scenario("键盘遮挡按钮", "打开反馈，填写内容：界面很好用，然后提交", listOf(formApp()),
        policy = { o, w -> when {
            w.flags["submitted"] != null -> done("已提交")
            w.fields["反馈内容"] == "界面很好用" && o.has("提交") -> step(tap(o.id("提交")))
            w.fields["反馈内容"] == "界面很好用" -> step("""{"type":"back"}""", state = "键盘挡住了提交按钮")
            o.has("反馈内容") -> step("""{"type":"type","text":"界面很好用","node_id":"${o.id("反馈内容")}"}""")
            else -> step("""{"type":"launch","app":"反馈"}""") } },
        done = { it.flags["submitted"] == "界面很好用" })

    // ---- bottom sheet ----
    const val VIDEO = "com.sim.video"
    fun videoApp() = SimApp(VIDEO, "视频", "play", mapOf(
        "play" to SimScreen("play") { listOf(SimEl("猫咪合集", 540, 400), SimEl("点赞 1.2万", 980, 1200, 120), SimEl("分享", 980, 1500, 120) { go("sheet") }) },
        "sheet" to SimScreen("sheet") { listOf(SimEl("猫咪合集", 540, 400), SimEl("分享到", 540, 1700), SimEl("复制链接", 200, 1950, 200) { clipboard = "https://v.sim/123"; log += "copied"; replace("play") },
            SimEl("保存到相册", 540, 1950, 200), SimEl("取消", 540, 2250, 1000) { back() }) },
    ))
    val bottomSheet = Scenario("底部弹出面板", "打开视频，复制这个视频的链接", listOf(videoApp()),
        policy = { o, w -> when {
            w.clipboard != null -> done("链接已复制")
            o.has("复制链接") -> step(tap(o.id("复制链接")))
            o.has("分享") -> step(tap(o.id("分享")))
            else -> step("""{"type":"launch","app":"视频"}""") } },
        done = { it.clipboard == "https://v.sim/123" })

    // ---- webview with no accessibility tree (OCR only, tap by coordinates) ----
    const val WEB = "com.sim.web"
    fun webApp() = SimApp(WEB, "活动页", "page", mapOf(
        "page" to SimScreen("page") { listOf(SimEl("春季活动", 540, 200), SimEl("规则说明", 540, 600, 800), SimEl("立即签到", 540, 1600, 500, 140) { flags["signed"] = true },
            SimEl(if (flags["signed"] == true) "已签到 +10" else "未签到", 540, 1900)) },
    )).apply { onLaunch = { source = "ocr" } }
    val webviewOcr = Scenario("网页无无障碍树（OCR）", "打开活动页，点签到", listOf(webApp()),
        policy = { o, _ -> when {
            o.has("已签到") -> done("已签到")
            o.has("立即签到") -> step("""{"type":"tap","x":540,"y":1600}""")
            else -> step("""{"type":"launch","app":"活动页"}""") } },
        done = { it.flags["signed"] == true })

    // ---- landscape game UI: art-text buttons misread by OCR ----
    const val GAME = "com.sim.game"
    fun gameApp() = SimApp(GAME, "星际", "lobby", mapOf(
        "lobby" to SimScreen("lobby") { listOf(SimEl("商城", 200, 100), SimEl("设置", 2250, 100), SimEl("开 女台", 1900, 900, 400, 160) { go("battle") }, SimEl("体力 120", 1200, 100)) },
        "battle" to SimScreen("battle") { listOf(SimEl("战斗中", 1200, 100), SimEl("暂停", 2300, 80)) },
    )).apply { onLaunch = { source = "ocr" } }
    val landscape = Scenario("横屏游戏界面", "打开星际，开始一局", listOf(gameApp()), w = 2400, h = 1080,
        policy = { o, _ -> when {
            o.has("战斗中") -> done("已开始")
            o.has("开 女台") -> step("""{"type":"tap","x":1900,"y":900}""", state = "大厅，开始按钮是艺术字")
            else -> step("""{"type":"launch","app":"星际"}""") } },
        done = { it.screenName() == "battle" })

    // ---- toggles in a settings-like app ----
    const val PREFS = "com.sim.prefs"
    fun prefsApp() = SimApp(PREFS, "控制中心", "main", mapOf(
        "main" to SimScreen("main") { listOf(SimEl("控制中心", 540, 150), SimEl("WLAN", 540, 400, 1000) { go("wlan") }, SimEl("护眼模式", 540, 560, 1000) { go("eye") }) },
        "wlan" to SimScreen("wlan") { listOf(SimEl("WLAN", 540, 150), SimEl("WLAN 已开启", 540, 400, 1000)) },
        "eye" to SimScreen("eye") { listOf(SimEl("护眼模式", 540, 150), SimEl(if (flags["eye"] == true) "护眼模式 开启" else "护眼模式 关闭", 540, 400, 1000) { flags["eye"] = flags["eye"] != true }, SimEl("定时开启", 540, 560, 1000)) },
    ))
    val settingsToggle = Scenario("设置开关", "打开控制中心，打开护眼模式", listOf(prefsApp()),
        policy = { o, w -> when {
            w.flags["eye"] == true -> done("护眼模式已开启")
            w.screenName() == "eye" -> step(tap(o.id("护眼模式 关闭")))
            o.has("护眼模式") -> step(tap(o.id("护眼模式")))
            else -> step("""{"type":"launch","app":"控制中心"}""") } },
        done = { it.flags["eye"] == true })

    // ---- reply from the notification shade (send needs confirmation) ----
    val notificationReply = Scenario("通知栏回复", "从通知栏回复张三：马上到", emptyList(),
        setup = { flags["shade"] = SimScreen("shade") {
            if (flags["replying"] != true) listOf(SimEl("张三：在吗？", 540, 300, 1000), SimEl("回复", 300, 420, 200) { flags["replying"] = true }, SimEl("清除全部", 540, 2200))
            else listOf(SimEl("张三：在吗？", 540, 300, 1000), SimEl("回复张三", 450, 420, 800, 100, edit = true),
                SimEl("发送", 980, 420, 140) { fields["回复张三"]?.takeIf { it.isNotBlank() }?.let { flags["sent"] = it } }) } },
        policy = { o, w -> when {
            w.flags["sent"] != null -> done("已回复")
            w.fields["回复张三"] == "马上到" && w.flags["asked"] == true -> step(tap(o.id("发送")))
            w.fields["回复张三"] == "马上到" -> { w.flags["asked"] = true; step("""{"type":"ask_confirm","reason":"发送「马上到」给张三"}""") }
            o.has("回复张三") -> step("""{"type":"type","text":"马上到","node_id":"${o.id("回复张三")}"}""")
            o.has("在吗") -> step(tap(o.id("回复")))
            else -> step("""{"type":"notifications"}""") } },
        done = { it.flags["sent"] == "马上到" })

    // ---- alarm set through the clock UI (system intent unavailable) must still be confirmed ----
    const val CLOCK = "com.sim.clock"
    fun clockApp() = SimApp(CLOCK, "时钟", "alarms", mapOf(
        "alarms" to SimScreen("alarms") { listOf(SimEl("闹钟", 540, 150)) + listOfNotNull(flags["alarm"]?.let { SimEl("$it 闹钟", 540, 400, 1000) }) + SimEl("添加闹钟", 540, 2200, 160) { go("new") } },
        "new" to SimScreen("new") { listOf(SimEl("新建闹钟", 540, 150), SimEl("时间", 540, 600, 800, 140, edit = true), SimEl("保存", 980, 150, 140) { flags["alarm"] = fields["时间"].orEmpty(); replace("alarms") }) },
    ))
    val alarmConfirm = Scenario("闹钟需确认", "打开时钟，设一个 07:00 的闹钟", listOf(clockApp()),
        policy = { o, w -> when {
            w.flags["alarm"] == "07:00" -> done("闹钟已设置")
            w.fields["时间"] == "07:00" -> step(tap(o.id("保存")))
            o.has("新建闹钟") -> step("""{"type":"type","text":"07:00","node_id":"${o.id("时间")}"}""")
            o.has("添加闹钟") -> step(tap(o.id("添加闹钟")))
            else -> step("""{"type":"launch","app":"时钟"}""") } },
        done = { it.flags["alarm"] == "07:00" })
    val alarmDeclined = Scenario("闹钟拒绝", alarmConfirm.goal, listOf(clockApp()), alarmConfirm.policy, alarmConfirm.done, confirm = false)

    // ---- long chat list: rows share labels (time stamps / "免打扰"), find a deep one ----
    const val CHAT = "com.sim.chat"
    val convs = (1..60).map { if (it == 47) "王小明" else "群聊$it" }
    fun chatApp() = SimApp(CHAT, "聊天", "list", mapOf(
        "list" to SimScreen("list") {
            val off = (((flags["scroll"] as? Int) ?: 0) * 9).coerceIn(0, convs.size - 9)
            listOf(SimEl("聊天", 540, 150)) + convs.subList(off, off + 9).flatMapIndexed { i, n ->
                listOf(SimEl(n, 400, 350 + i * 200, 700, 150) { flags["opened"] = n; go("conv") }, SimEl("昨天", 980, 330 + i * 200, 120, 50), SimEl("免打扰", 980, 380 + i * 200, 120, 50)) }
        },
        "conv" to SimScreen("conv") { listOf(SimEl("${flags["opened"]}（聊天）", 540, 150), SimEl("输入消息", 450, 2250, 700, 100, edit = true)) },
    ))
    val longChatScroll = Scenario("长聊天列表滚动", "打开聊天，找到王小明的对话", listOf(chatApp()),
        policy = { o, _ -> when {
            o.has("王小明（聊天）") -> done("已打开王小明")
            o.has("王小明") -> step(tap(o.id("王小明")))
            o.has("聊天") -> step("""{"type":"scroll_find","text":"王小明","max":8}""")
            else -> step("""{"type":"launch","app":"聊天"}""") } },
        done = { it.flags["opened"] == "王小明" })

    // ---- empty search results: fail cleanly with the reason ----
    val emptySearch = Scenario("搜索无结果", "打开云音乐，搜索一首叫zzqqxx的歌并播放", listOf(Scenarios.musicApp().apply {
            onLaunch = null }),
        policy = { o, w -> when {
            w.screenName() == "results" -> step("""{"type":"fail","reason":"搜索「zzqqxx」没有结果"}""")
            o.has("搜索歌曲") && w.fields["搜索歌曲"] == "zzqqxx" -> step(tap(o.id("搜索")))
            o.has("搜索歌曲") -> step("""{"type":"type","text":"zzqqxx","node_id":"${o.id("搜索歌曲")}"}""")
            else -> step(tap(o.id("搜索"))) } },
        done = { false },
        setup = {})

    // ---- ambiguous goal: the model asks the user instead of guessing ----
    val ambiguous = Scenario("目标不明确先问用户", "打开信息，给他发个消息", listOf(Scenarios.smsApp()),
        policy = { o, _ -> if (o.has("张三")) step("""{"type":"ask_user","question":"要发给张三还是李四？发什么内容？"}""") else step("""{"type":"launch","app":"信息"}""") },
        done = { false })

    // ---- network flapping: bursts of 503 longer than one client retry budget ----
    val flapping = Scenario("网络抖动", Scenarios.music.goal, listOf(Scenarios.musicApp()), Scenarios.music.policy, Scenarios.music.done,
        fault = { n -> if (n in 2..5 || n in 9..10) 503 else null })

    // ---- malformed model output ----
    val malformedThenOk = Scenario("模型输出乱码后恢复", Scenarios.music.goal, listOf(Scenarios.musicApp()), Scenarios.music.policy, Scenarios.music.done,
        garbage = { n -> if (n in 1..2) "好的，我来帮你点击搜索按钮。" else null })
    val malformedAlways = Scenario("模型一直输出乱码", Scenarios.music.goal, listOf(Scenarios.musicApp()), Scenarios.music.policy, { false },
        garbage = { "```json\n{\"action\": {\"type\": " })

    // ---- user presses stop while the task is running ----
    val userStop = Scenario("执行中急停", "打开通讯录，找到王五并打开", listOf(Scenarios.contactsApp(page = 4)),
        setup = { flags["onRead"] = { w: SimWorld -> if (w.reads >= 3) w.flags["stop"] = true } as (SimWorld.() -> Unit) },
        policy = { _, _ -> step("""{"type":"swipe","x1":540,"y1":1800,"x2":540,"y2":600}""") },
        done = { false })

    // ---- cross-app share sheet: tapping a share target opens ANOTHER app ----
    const val GALLERY = "com.sim.gallery"
    fun galleryApp() = SimApp(GALLERY, "相册", "grid", mapOf(
        "grid" to SimScreen("grid") { listOf(SimEl("相册", 540, 150), SimEl("照片1", 200, 400, 300, 300) { go("photo") }, SimEl("照片2", 540, 400, 300, 300)) },
        "photo" to SimScreen("photo") { listOf(SimEl("照片1", 540, 150), SimEl("分享", 200, 2250, 160) { go("share") }) },
        "share" to SimScreen("share") { listOf(SimEl("分享到", 540, 1500), SimEl("信息", 200, 1800, 200) {
            replace("photo"); launch(Scenarios.SMS); go("chat"); fields["输入消息"] = "[图片]照片1" }, SimEl("蓝牙", 540, 1800, 200)) },
    ))
    val shareSheet = Scenario("跨应用分享面板", "打开相册，把照片1分享给张三", listOf(galleryApp(), Scenarios.smsApp()),
        policy = { o, w -> when {
            w.flags["sent"] != null -> done("已分享")
            w.screenName() == "chat" && w.flags["asked"] == true -> step(tap(o.id("发送")))
            w.screenName() == "chat" -> { w.flags["asked"] = true; step("""{"type":"ask_confirm","reason":"发送照片1给张三"}""") }
            o.has("分享到") -> step(tap(o.id("信息")))
            o.has("分享") -> step(tap(o.id("分享")))
            o.has("照片1") -> step(tap(o.id("照片1")))
            else -> step("""{"type":"launch","app":"相册"}""") } },
        done = { it.flags["sent"] == "[图片]照片1" })

    val all get() = listOf(loginWall, permission, keyboardCover, bottomSheet, webviewOcr, landscape, settingsToggle, notificationReply,
        alarmConfirm, alarmDeclined, longChatScroll, emptySearch, ambiguous, flapping, malformedThenOk, malformedAlways, userStop, shareSheet)
}

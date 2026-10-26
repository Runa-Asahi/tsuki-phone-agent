package com.runasakura.phoneagent.sim

/**
 * HELD-OUT generalization suite: fictional app archetypes / task types / UI conditions that were NOT used while
 * writing prompts or loop heuristics. Policies behave like a reasonable model: they read only the observation text
 * (never world state) and pick generic actions. Each case = (scenario, expected success).
 */
object HeldOut {
    private fun done(s: String) = step("""{"type":"done","summary":"$s"}""", "是")
    private fun tapL(o: Obs, l: String) = step(tap(o.id(l)))
    private fun launch(a: String) = step("""{"type":"launch","app":"$a"}""")

    // 1. English-only UI, settings 3 levels deep
    private fun photoBox() = SimApp("com.held.photobox", "PhotoBox", "home", mapOf(
        "home" to SimScreen("home") { listOf(SimEl("Albums", 300, 300), SimEl("Settings", 900, 150, 160, 120) { go("settings") }) },
        "settings" to SimScreen("settings") { listOf(SimEl("Account", 540, 300), SimEl("Appearance", 540, 450) { go("appearance") }, SimEl("Storage", 540, 600)) },
        "appearance" to SimScreen("appearance") { listOf(SimEl("Theme", 540, 200), SimEl(if (flags["dark"] == true) "Dark ✓" else "Dark", 540, 400) { flags["dark"] = true }, SimEl("Light", 540, 550) { flags["dark"] = false }) },
    ))
    val english = Scenario("英文界面设置", "打开 PhotoBox，把主题改成 Dark", listOf(photoBox()),
        policy = { o, _ -> when {
            o.has("Dark ✓") -> done("主题已是 Dark")
            o.has("Appearance") -> tapL(o, "Appearance")
            o.has("Theme") -> tapL(o, "Dark")
            o.has("Settings") -> tapL(o, "Settings")
            else -> launch("PhotoBox") } },
        done = { it.flags["dark"] == true })

    // 2. Landscape tablet-like layout: coordinates beyond the portrait width
    private fun player() = SimApp("com.held.reel", "Reel", "home", mapOf(
        "home" to SimScreen("home") { listOf(SimEl("Now playing: Ocean", 1200, 300, 600), SimEl(if (flags["paused"] == true) "Play" else "Pause", 2100, 950, 160, 120) { flags["paused"] = flags["paused"] != true }) },
    ))
    val landscape = Scenario("横屏布局", "打开 Reel，暂停正在播放的内容", listOf(player()), w = 2400, h = 1080,
        policy = { o, _ -> when {
            o.has("Play") && !o.has("Pause") -> done("已暂停")
            o.has("Pause") -> tapL(o, "Pause")
            else -> launch("Reel") } },
        done = { it.flags["paused"] == true })

    // 3. Never-seen dialogs that pop up mid-task (changelog after launch, rating prompt later)
    private fun budget() = SimApp("com.held.budget", "Ledgerly", "home", mapOf(
        "home" to SimScreen("home") { listOf(SimEl("Balance 1,204.50", 540, 300), SimEl("Add expense", 540, 2200, 400) { go("add") }) },
        "whatsnew" to SimScreen("whatsnew") { listOf(SimEl("What's new in 5.0", 540, 900, 600), SimEl("Got it", 540, 1400, 240) { replace("home") }) },
        "add" to SimScreen("add") { listOf(SimEl("Amount", 540, 400, 600, 120, edit = true), SimEl("Save", 900, 150, 160, 120) { if (!fields["Amount"].isNullOrBlank()) { flags["saved"] = fields["Amount"]!!; go("rate") } }) },
        "rate" to SimScreen("rate") { listOf(SimEl("Enjoying Ledgerly?", 540, 900, 600), SimEl("Not now", 340, 1400, 240) { replace("home") }, SimEl("Rate 5 stars", 740, 1400, 240)) },
    )).apply { onLaunch = { go("whatsnew") } }
    val dialogs = Scenario("陌生弹窗", "在 Ledgerly 记一笔 36 元的支出", listOf(budget()),
        policy = { o, _ -> when {
            o.has("Not now") -> tapL(o, "Not now")
            o.has("Got it") -> tapL(o, "Got it")
            o.has("Balance") && o.note("已保存") -> done("已记录 36")
            o.has("Save") && o.has("36") -> step(tap(o.id("Save")), memory = "已保存")
            o.has("Amount") -> step("""{"type":"type","text":"36","node_id":"${o.id("Amount")}"}""")
            o.has("Add expense") -> tapL(o, "Add expense")
            else -> launch("Ledgerly") } },
        done = { it.flags["saved"] == "36" && it.screenName() == "home" })

    // 4. OCR-only canvas (game/webview): nothing is "clickable" in the tree, taps must go by coordinates
    private fun canvas() = SimApp("com.held.canvas", "Starfold", "map", mapOf(
        "map" to SimScreen("map") { listOf(SimEl("Level 1", 200, 1200), SimEl("Level 2", 540, 1200), SimEl("Level 3", 880, 1200) { go("lvl3") }) },
        "lvl3" to SimScreen("lvl3") { listOf(SimEl("Level 3 · Start", 540, 1800, 500)) },
    ))
    val ocrOnly = Scenario("纯 OCR 画布", "打开 Starfold 进入第 3 关", listOf(canvas()), setup = { source = "ocr" },
        policy = { o, _ -> when {
            o.has("Start") -> done("已进入第 3 关")
            o.has("Level 3") -> tapL(o, "Level 3")
            else -> launch("Starfold") } },
        done = { it.screenName() == "lvl3" })

    // 5. Long list: target only appears after scrolling; generic scroll_find
    private fun contactsEn() = SimApp("com.held.roster", "Roster", "list", mapOf(
        "list" to SimScreen("list") {
            val names = listOf("Aaron", "Bella", "Chen", "Diego", "Emma", "Farah", "Gwen", "Hiro", "Ivan", "Jade", "Kenji", "Liam", "Mona", "Nadia", "Omar", "Priya", "Quinn", "Rosa")
            val off = (((flags["scroll"] as? Int) ?: 0) * 6).coerceIn(0, names.size - 6)
            names.subList(off, off + 6).mapIndexed { i, n -> SimEl(n, 540, 300 + i * 250) { flags["open"] = n; go("card") } } },
        "card" to SimScreen("card") { listOf(SimEl("Contact: ${flags["open"]}", 540, 300), SimEl("Phone 555-0199", 540, 500)) },
    ))
    val longList = Scenario("长列表查找", "在 Roster 里打开 Quinn 的联系人卡片", listOf(contactsEn()),
        policy = { o, _ -> when {
            o.has("Contact: Quinn") -> done("已打开 Quinn")
            o.has("Quinn") -> tapL(o, "Quinn")
            o.has("Aaron") || o.has("Hiro") || o.has("Mona") -> step("""{"type":"scroll_find","text":"Quinn"}""")
            else -> launch("Roster") } },
        done = { it.flags["open"] == "Quinn" && it.screenName() == "card" })

    // 6. Multi-app: copy a value in one app, paste it into another
    private fun vault() = SimApp("com.held.vault", "KeyRing", "home", mapOf(
        "home" to SimScreen("home") { listOf(SimEl("Verification code", 540, 300), SimEl("482913", 540, 450, 300) {}) },
    ))
    private fun portal() = SimApp("com.held.portal", "Portalo", "login", mapOf(
        "login" to SimScreen("login") { listOf(SimEl("Enter code", 540, 600, 600, 120, edit = true), SimEl("Verify", 540, 900) { if (fields["Enter code"] == "482913") { flags["verified"] = true; go("ok") } }) },
        "ok" to SimScreen("ok") { listOf(SimEl("Signed in", 540, 600)) },
    ))
    val multiApp = Scenario("跨应用复制粘贴", "把 KeyRing 里的验证码填到 Portalo 并验证", listOf(vault(), portal()),
        policy = { o, w -> when {
            o.has("Signed in") -> done("已验证")
            o.has("Verify") && o.has("482913") -> step(tap(o.id("Verify")), memory = "已提交")
            o.has("Enter code") && o.note("482913") -> step("""{"type":"type","text":"482913","node_id":"${o.id("Enter code")}"}""")
            o.has("Enter code") -> launch("KeyRing")
            o.has("482913") -> step("""{"type":"launch","app":"Portalo"}""", memory = "验证码 482913")
            o.note("已提交") -> done("已验证")
            else -> launch("KeyRing") } },
        done = { it.flags["verified"] == true })

    // 7. Truly missing info → ask_user, and nothing gets sent
    private fun chat() = SimApp("com.held.chirp", "Chirp", "inbox", mapOf(
        "inbox" to SimScreen("inbox") { listOf(SimEl("Mia", 540, 300) { go("conv") }, SimEl("Noah", 540, 450) { go("conv") }) },
        "conv" to SimScreen("conv") { listOf(SimEl("Message", 500, 2200, 700, 120, edit = true), SimEl("Send", 960, 2200, 140, 120) { flags["sent"] = true }) },
    ))
    val askNeeded = Scenario("真缺信息才问", "用 Chirp 告诉他我晚点到", listOf(chat()),
        policy = { o, _ -> when {
            o.has("Mia") && o.has("Noah") -> step("""{"type":"ask_user","question":"要发给 Mia 还是 Noah？"}""")
            else -> launch("Chirp") } },
        done = { false })

    // 8. Premature "done": the judge must reject it and the task still completes
    private fun timer() = SimApp("com.held.focus", "Focusly", "home", mapOf(
        "home" to SimScreen("home") { listOf(SimEl("25:00", 540, 800, 500), SimEl(if (flags["run"] == true) "Stop session" else "Start session", 540, 1600, 500) { flags["run"] = flags["run"] != true }) },
    ))
    val earlyDone = Scenario("过早完成被拦", "在 Focusly 开始一个专注计时", listOf(timer()),
        policy = { o, w -> when {
            o.has("Stop session") -> done("计时已开始")
            o.has("Start session") && w.flags["claimed"] != true -> { w.flags["claimed"] = true; done("计时已开始") }
            o.has("Start session") -> tapL(o, "Start session")
            else -> launch("Focusly") } },
        done = { it.flags["run"] == true })

    // 9. Small/low density screen
    private fun weather() = SimApp("com.held.sky", "Nimbus", "home", mapOf(
        "home" to SimScreen("home") { listOf(SimEl("Today 21°", 360, 300, 400, 80), SimEl("7-day", 600, 1500, 120, 60) { go("week") }) },
        "week" to SimScreen("week") { listOf(SimEl("Mon 22°", 360, 200, 300, 60), SimEl("Tue 19°", 360, 300, 300, 60)) },
    ))
    val smallScreen = Scenario("小屏低密度", "打开 Nimbus 看一周天气", listOf(weather()), w = 720, h = 1600,
        policy = { o, _ -> when {
            o.has("Mon 22°") -> done("周一 22°，周二 19°")
            o.has("7-day") -> tapL(o, "7-day")
            else -> launch("Nimbus") } },
        done = { it.screenName() == "week" })

    // 10. Unrelated permission request in English → deny and continue
    private fun scanner() = SimApp("com.held.scan", "Docuscan", "home", mapOf(
        "home" to SimScreen("home") { listOf(SimEl("Recent scans", 540, 300), SimEl("Invoice March", 540, 500) { go("doc") }) },
        "perm" to SimScreen("perm") { listOf(SimEl("Allow Docuscan to access this device's location?", 540, 1000, 800), SimEl("While using the app", 540, 1300, 600) { flags["loc"] = true; replace("home") }, SimEl("Don't allow", 540, 1500, 600) { replace("home") }) },
        "doc" to SimScreen("doc") { listOf(SimEl("Invoice March", 540, 200), SimEl("Total 89.00", 540, 400)) },
    )).apply { onLaunch = { go("perm") } }
    val permission = Scenario("无关权限拒绝", "打开 Docuscan 里的 Invoice March", listOf(scanner()),
        policy = { o, _ -> when {
            o.has("Don't allow") -> tapL(o, "Don't allow")
            o.has("Total 89.00") -> done("已打开，合计 89.00")
            o.has("Invoice March") -> tapL(o, "Invoice March")
            else -> launch("Docuscan") } },
        done = { it.screenName() == "doc" && it.flags["loc"] != true })

    // 11. Search with English keyboard submit (no search button on screen)
    private fun shop() = SimApp("com.held.mart", "Bazaaro", "home", mapOf(
        "home" to SimScreen("home") { listOf(SimEl("Search products", 540, 150, 800, 100, edit = true)) },
        "results" to SimScreen("results") { listOf(SimEl("Results for ${fields["Search products"]}", 540, 150, 800), SimEl("Desk lamp LED", 540, 400)) },
    )).apply { onLaunch = { flags["enter:Search products"] = { w: SimWorld -> w.go("results") } } }
    val searchEnter = Scenario("回车提交搜索", "在 Bazaaro 搜索 desk lamp", listOf(shop()),
        policy = { o, _ -> when {
            o.has("Results for") -> done("已搜索 desk lamp")
            o.has("desk lamp") && !o.has("Results") -> step("""{"type":"key","key":"enter"}""")
            o.has("Search products") -> step("""{"type":"type","text":"desk lamp","node_id":"${o.id("Search products")}"}""")
            else -> launch("Bazaaro") } },
        done = { it.screenName() == "results" })

    // 12. Partial tree: the accessibility tree is empty on one screen (custom view) — model must look / use coordinates
    private fun reader() = SimApp("com.held.reader", "Folio", "shelf", mapOf(
        "shelf" to SimScreen("shelf") { listOf(SimEl("Continue reading", 540, 400) { go("page"); flags["hiddenTree"] = true }) },
        "page" to SimScreen("page") { listOf(SimEl("Chapter 7", 540, 300), SimEl("Next ›", 980, 2250, 160, 100) { flags["next"] = true }) },
    ))
    val partialTree = Scenario("树为空的自绘页", "在 Folio 继续阅读并翻到下一页", listOf(reader()), shots = true, setup = { flags["shots"] = true },
        policy = { o, w -> when {
            w.flags["next"] == true -> done("已翻页")
            o.note("Continue reading") && o.has("Continue reading") -> tapL(o, "Continue reading")
            w.fg == "com.held.reader" && w.screenName() == "page" -> step("""{"type":"tap","x":980,"y":2250}""")
            else -> launch("Folio") } },
        done = { it.flags["next"] == true })


    // 13. Transient error state never seen before (offline page with a retry)
    private fun news() = SimApp("com.held.news", "Gazette", "offline", mapOf(
        "offline" to SimScreen("offline") { listOf(SimEl("No connection", 540, 900, 600), SimEl("Retry", 540, 1200, 240) { replace("feed") }) },
        "feed" to SimScreen("feed") { listOf(SimEl("Top stories", 540, 200), SimEl("Rivers rise in spring", 540, 500, 800) { go("article") }) },
        "article" to SimScreen("article") { listOf(SimEl("Rivers rise in spring", 540, 200, 800), SimEl("By K. Ito", 540, 350)) },
    ))
    val retryState = Scenario("陌生错误态重试", "打开 Gazette 看头条第一篇", listOf(news()),
        policy = { o, _ -> when {
            o.has("By K. Ito") -> done("已打开头条")
            o.has("Retry") -> tapL(o, "Retry")
            o.has("Top stories") -> tapL(o, "Rivers rise")
            else -> launch("Gazette") } },
        done = { it.screenName() == "article" })

    // 14. Direct link: open_url is the right tool (no confirmation for plain https)
    private fun browser() = SimApp("com.held.browser", "Wayfarer", "page", mapOf(
        "page" to SimScreen("page") { listOf(SimEl("Docs · ${flags["url"]}", 540, 200, 900)) },
    ))
    val openUrl = Scenario("直达链接", "打开 https://docs.held.example/start", listOf(browser()), setup = { flags["browserPkg"] = "com.held.browser" },
        policy = { o, _ -> when {
            o.has("Docs · https://docs.held.example/start") -> done("已打开链接")
            else -> step("""{"type":"open_url","url":"https://docs.held.example/start"}""") } },
        done = { it.flags["url"] == "https://docs.held.example/start" && it.fg == "com.held.browser" }, confirm = false)

    // 15. Keyboard covers the submit button: tapping it first must hide the keyboard (back) and then tap
    private fun feedback() = SimApp("com.held.form", "Pollster", "form", mapOf(
        "form" to SimScreen("form") { listOf(SimEl("Your answer", 540, 600, 800, 120, edit = true), SimEl("Submit", 540, 2250, 400) { if (!fields["Your answer"].isNullOrBlank()) { flags["submitted"] = true; go("thanks") } }) },
        "thanks" to SimScreen("thanks") { listOf(SimEl("Thanks for your answer", 540, 900, 800)) },
    ))
    val imeCovered = Scenario("键盘遮挡按钮", "在 Pollster 回答 yes 并提交", listOf(feedback()), setup = { flags["imeCovers"] = true },
        policy = { o, _ -> when {
            o.has("Thanks for your answer") -> done("已提交")
            o.has("yes") && o.has("Submit") -> tapL(o, "Submit")
            o.has("Your answer") -> step("""{"type":"type","text":"yes","node_id":"${o.id("Your answer")}"}""")
            else -> launch("Pollster") } },
        done = { it.flags["submitted"] == true })

    // 16. Context menu via long press, destructive → confirmation, then done
    private fun files() = SimApp("com.held.files", "Drawer", "list", mapOf(
        "list" to SimScreen("list") { (if (flags["deleted"] == true) listOf("notes.txt") else listOf("old-draft.txt", "notes.txt")).mapIndexed { i, n -> SimEl(n, 540, 300 + i * 200, 600) {} } },
        "menu" to SimScreen("menu") { listOf(SimEl("Rename", 540, 1000), SimEl("Delete", 540, 1200) { flags["deleted"] = true; replace("list") }) },
    )).apply { onLaunch = { flags["long:old-draft.txt"] = { w: SimWorld -> w.go("menu") } } }
    val longPressDelete = Scenario("长按菜单删除", "在 Drawer 删除 old-draft.txt", listOf(files()),
        policy = { o, _ -> when {
            o.has("notes.txt") && !o.has("old-draft") -> done("已删除 old-draft.txt")
            o.has("Delete") -> tapL(o, "Delete")
            o.has("old-draft.txt") -> step("""{"type":"long_press","node_id":"${o.id("old-draft.txt")}"}""")
            else -> launch("Drawer") } },
        done = { it.flags["deleted"] == true })

    // 17. Open-ended two-app goal: read in one app, write in another (info carried by the model's memory)
    private fun memo() = SimApp("com.held.memo", "Jotter", "home", mapOf(
        "home" to SimScreen("home") { listOf(SimEl("New note", 540, 2200, 400) { go("edit") }) },
        "edit" to SimScreen("edit") { listOf(SimEl("Note body", 540, 600, 900, 300, edit = true), SimEl("Done", 960, 150, 160, 120) { flags["note"] = fields["Note body"].orEmpty(); replace("home") }) },
    ))
    val readThenWrite = Scenario("跨应用读后写", "看看 Nimbus 今天多少度，记到 Jotter 里", listOf(weather(), memo()),
        policy = { o, w -> when {
            o.note("已记录") -> done("已记下 21°")
            o.has("Today 21°") -> step("""{"type":"launch","app":"Jotter"}""", memory = "今天 21°")
            o.has("21°") && o.has("Done") -> step(tap(o.id("Done")), memory = "已记录")
            o.has("Note body") -> step("""{"type":"type","text":"今天 21°","node_id":"${o.id("Note body")}"}""")
            o.has("New note") && o.note("今天 21°") -> tapL(o, "New note")
            else -> launch("Nimbus") } },
        done = { it.flags["note"]?.toString()?.contains("21°") == true })

    /** (scenario, expected success) */
    val all: List<Pair<Scenario, Boolean>> = listOf(english to true, landscape to true, dialogs to true, ocrOnly to true, longList to true, multiApp to true,
        askNeeded to false, earlyDone to true, smallScreen to true, permission to true, searchEnter to true, partialTree to true,
        retryState to true, openUrl to true, imeCovered to true, longPressDelete to true, readThenWrite to true)

    /** A case passes when the outcome matches and the world end state is right (or, for ask cases, nothing was sent). */
    fun passed(sc: Scenario, expect: Boolean, r: SimRun): Boolean =
        if (expect) r.result.success && sc.done(r.world) else !r.result.success && r.result.needsUser && r.world.flags["sent"] == null
}

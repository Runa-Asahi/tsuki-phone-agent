package com.runasakura.phoneagent.sim

/** Common-app archetypes (fictional apps): splash + tab bar, app discovery, data-file hints, full-screen video, IME search. */
object Scenarios4 {
    private fun done(s: String) = step("""{"type":"done","summary":"$s"}""", "是")

    // ---- splash countdown, then bottom tab bar navigation ----
    const val VID = "com.sim.vid"
    fun vidApp() = SimApp(VID, "视频站", "splash", mapOf(
        "splash" to SimScreen("splash") { listOf(SimEl("跳过 3", 980, 120, 160, 80) { replace("home") }, SimEl("品牌广告", 540, 1200)) },
        "home" to SimScreen("home") { listOf(SimEl("搜索视频", 540, 150, 900, 100) { go("search") }, SimEl("推荐", 540, 800), SimEl("首页", 135, 2300, 200), SimEl("动态", 405, 2300, 200), SimEl("我的", 945, 2300, 200) { go("me") }) },
        "me" to SimScreen("me") { listOf(SimEl("我的收藏", 540, 600) { go("fav") }, SimEl("历史记录", 540, 750)) },
        "fav" to SimScreen("fav") { listOf(SimEl("收藏夹", 540, 150), SimEl("猫咪合集", 540, 500)) },
        "search" to SimScreen("search") { listOf(SimEl("搜索视频", 450, 150, 800, 100, edit = true)) + (if (flags["searched"] == true) listOf(SimEl("结果：猫咪合集", 540, 600)) else emptyList()) },
    ))
    val splashTabs = Scenario("开屏+底部 tab", "打开视频站，看看我的收藏", listOf(vidApp()),
        policy = { o, w -> when {
            w.screenName() == "fav" -> done("收藏夹里有猫咪合集")
            o.has("我的收藏") -> step(tap(o.id("我的收藏")))
            o.has("我的") && w.screenName() == "home" -> step(tap(o.id("我的")))
            o.has("跳过") -> step(tap(o.id("跳过")), state = "开屏广告")
            else -> step("""{"type":"launch","app":"视频站"}""") } },
        done = { it.screenName() == "fav" })

    // ---- search with IME submit ----
    val imeSearch = Scenario("搜索并回车提交", "打开视频站搜索猫咪", listOf(vidApp()),
        setup = { flags["enter:搜索视频"] = { w: SimWorld -> w.flags["searched"] = true } },
        policy = { o, w -> when {
            o.has("结果：猫咪合集") -> done("已搜索到猫咪合集")
            w.screenName() == "search" && w.fields["搜索视频"] == "猫咪" -> step("""{"type":"key","key":"enter"}""")
            w.screenName() == "search" -> step("""{"type":"type","text":"猫咪","node_id":"${o.id("搜索视频")}"}""")
            o.has("搜索视频") -> step(tap(o.id("搜索视频")))
            o.has("跳过") -> step(tap(o.id("跳过")))
            else -> step("""{"type":"launch","app":"视频站"}""") } },
        done = { it.flags["searched"] == true })

    // ---- the user names an app vaguely: the model discovers installed apps first ----
    const val LEDGER = "com.sim.ledger"
    fun ledgerApp() = SimApp(LEDGER, "随手记账本", "home", mapOf("home" to SimScreen("home") { listOf(SimEl("本月支出 1200", 540, 400)) }))
    val discover = Scenario("发现已安装应用", "打开那个记账的应用看本月支出", listOf(ledgerApp(), vidApp()),
        policy = { o, _ -> when {
            o.has("本月支出") -> done("本月支出 1200")
            o.note("随手记账本=$LEDGER") -> step("""{"type":"launch","package":"$LEDGER"}""")
            else -> step("""{"type":"list_apps","query":"记账"}""") } },
        done = { it.fg == LEDGER })

    // ---- data-file hints appear once for a known package (fictional screens) ----
    fun hintedApp() = SimApp("tv.danmaku.bili", "小破站", "home", mapOf("home" to SimScreen("home") { listOf(SimEl("首页", 135, 2300, 200), SimEl("推荐视频", 540, 700)) }))
    val hinted = Scenario("应用提示数据", "打开小破站首页", listOf(hintedApp()),
        policy = { o, w -> if (w.fg == "tv.danmaku.bili" && o.has("推荐视频")) done("已在首页") else step("""{"type":"launch","app":"小破站"}""") },
        done = { it.fg == "tv.danmaku.bili" })

    // ---- full-screen video: almost no text; swipe up to the next video ----
    const val SHORT = "com.sim.short"
    fun shortApp() = SimApp(SHORT, "短视频", "feed", mapOf(
        "feed" to SimScreen("feed") { listOf(SimEl(if (((flags["scroll"] as? Int) ?: 0) > 0) "@狗狗" else "@猫猫", 200, 2000)) },
    ))
    val fullScreenVideo = Scenario("全屏视频流", "打开短视频，切到下一个视频", listOf(shortApp()),
        policy = { o, w -> when {
            o.has("@狗狗") -> done("已切到下一个视频")
            w.fg == SHORT -> step("""{"type":"swipe","x1":540,"y1":1800,"x2":540,"y2":600}""", state = "全屏视频，文字很少")
            else -> step("""{"type":"launch","app":"短视频"}""") } },
        done = { ((it.flags["scroll"] as? Int) ?: 0) > 0 })
}

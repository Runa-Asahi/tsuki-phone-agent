package com.runasakura.phoneagent.sim

/**
 * pt67 device-log replay, generalised (fictional app): a landscape game read by OCR only, the key control is a big
 * text-free colour button (exposed as a visual candidate), Back does nothing, and the user's model cannot see images.
 * The scripted model behaves like the logged one: asks to look, then hammers Back, and only picks something else
 * when the system lists concrete untried alternatives. It also claims "是" for a sub-step while still navigating.
 */
object Scenarios6 {
    const val GAME = "com.sim.arena"
    private const val BIG = "〔黄色大按钮·无文字〕"
    fun game() = SimApp(GAME, "竞技场", "lobby", mapOf(
        "lobby" to SimScreen("lobby") { listOf(SimEl("金币 1200", 300, 60), SimEl("活动", 2500, 300), SimEl("商店", 200, 600),
            SimEl(BIG, 2400, 1100, 420) { go("mode") }) },
        "mode" to SimScreen("mode") { listOf(SimEl("选择模式", 1400, 120), SimEl("宝石模式", 900, 600, 300) { go("match") }, SimEl("单挑", 1900, 600, 300)) },
        "match" to SimScreen("match") { listOf(SimEl("匹配中", 1400, 600), SimEl("取消", 1400, 1000)) },
    ))

    val blindGame = Scenario("横屏游戏·无看图·返回无效", "打开竞技场，开一把宝石模式", listOf(game()),
        w = 2800, h = 1264, maxSteps = 18,
        setup = { source = "ocr"; flags["noBack"] = true },
        policy = { o, w ->
            val looks = (w.flags["looks"] as? Int) ?: 0
            when {
                w.screenName() == "match" -> step("""{"type":"done","summary":"已开始匹配宝石模式"}""", "是")
                w.screenName() == "mode" -> step(tap(o.id("宝石模式")), "是：已打开竞技场")
                // Only when the system spells out untried options does this model pick the text-free button.
                o.note("可选的未试动作") && o.note(BIG) -> step(tap(o.id(BIG) ?: Regex("""\[(e\d+)]〔黄色""").find(o.text)?.groupValues?.get(1)), "是：已打开竞技场")
                looks < 2 -> { w.flags["looks"] = looks + 1; step("""{"type":"look"}""", "是：已打开竞技场") }
                else -> step("""{"type":"back"}""", "是：已打开竞技场")
            }
        },
        done = { it.screenName() == "match" })

    /**
     * Runtime shortcut discovery: the target page is several screens deep; the app's own intent filters expose a
     * deep link. The scripted model asks for the app's shortcuts once and uses one if the tool lists it.
     */
    const val DEEP = "com.sim.tickets"
    fun ticketApp() = SimApp(DEEP, "票务", "home", mapOf(
        "home" to SimScreen("home") { listOf(SimEl("首页", 540, 200), SimEl("我的", 800, 2300) { go("me") }) },
        "me" to SimScreen("me") { listOf(SimEl("订单中心", 540, 600) { go("orders") }) },
        "orders" to SimScreen("orders") { listOf(SimEl("全部订单", 540, 300), SimEl("待出行", 540, 500) { go("upcoming") }) },
        "upcoming" to SimScreen("upcoming") { listOf(SimEl("待出行订单", 540, 200), SimEl("G123 上海→北京", 540, 500)) },
    ))
    val deepLink = Scenario("运行时发现深链直达", "打开票务，查看待出行订单", listOf(ticketApp()), maxSteps = 6,
        setup = { flags["links:$DEEP"] = mapOf<String, SimWorld.() -> Unit>("tickets://orders/upcoming" to { launch(DEEP); go("upcoming") }) },
        policy = { o, w -> when {
            w.screenName() == "upcoming" -> step("""{"type":"done","summary":"已打开待出行订单"}""", "是")
            o.note("tickets://orders/upcoming") -> step("""{"type":"open_url","url":"tickets://orders/upcoming","package":"$DEEP"}""")
            w.flags["asked"] != true -> { w.flags["asked"] = true; step("""{"type":"shortcuts","app":"$DEEP"}""") }
            else -> step("""{"type":"fail","reason":"没有直达入口"}""") } },
        done = { it.screenName() == "upcoming" })
}

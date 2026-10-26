package com.runasakura.phoneagent.sim

/** Learning scenarios: the same (fictional) app/goal run repeatedly with memory carried between runs. */
object Scenarios5 {
    private fun done(s: String) = step("""{"type":"done","summary":"$s"}""", "是")
    private val SKILL_NEXT = Regex("可复用技能[^：]*：点击「([^」]+)」")

    const val APP = "com.sim.notes"
    fun app() = SimApp(APP, "云笔记", "home", mapOf(
        "home" to SimScreen("home") { listOf(SimEl("最近笔记", 540, 300), SimEl("发现", 300, 2300, 200) { go("discover") }, SimEl("我的", 800, 2300, 200) { go("me") }) },
        "discover" to SimScreen("discover") { listOf(SimEl("热门模板", 540, 300), SimEl("活动", 540, 500)) },
        "me" to SimScreen("me") { listOf(SimEl("账号", 540, 200), SimEl("回收站", 540, 700) { go("trash") }, SimEl("设置", 540, 900)) },
        "trash" to SimScreen("trash") { listOf(SimEl("回收站", 540, 150), SimEl("已删除 3 条", 540, 500)) },
    ))

    /**
     * Without memory the scripted model explores (wrong tab first). With a learned skill in the prompt it follows the
     * suggested next step — so a second run is shorter. The model still decides each step.
     */
    fun repeat(memory: Map<String, String> = emptyMap()) = Scenario("重复任务变快", "打开云笔记，进入回收站", listOf(app()), memory = memory,
        policy = { o, w ->
            val next = SKILL_NEXT.find(o.text)?.groupValues?.get(1)
            when {
                w.screenName() == "trash" -> done("已进入回收站")
                next != null && o.has(next) -> step(tap(o.id(next)), state = "按技能")
                w.screenName() == "discover" -> step("""{"type":"back"}""")
                o.has("回收站") -> step(tap(o.id("回收站")))
                w.screenName() == "home" && w.flags["explored"] != true -> { w.flags["explored"] = true; step(tap(o.id("发现"))) }
                o.has("我的") -> step(tap(o.id("我的")))
                else -> step("""{"type":"launch","app":"云笔记"}""") } },
        done = { it.screenName() == "trash" })

    // ---- a tap target that refuses node taps: the model switches to long_press on the same target (online adaptation) ----
    const val STUBBORN = "com.sim.stubborn"
    fun stubbornApp() = SimApp(STUBBORN, "顽固", "home", mapOf(
        "home" to SimScreen("home") { listOf(SimEl("打开详情", 540, 600) { if (flags["lp"] == true) go("detail") }) },
        "detail" to SimScreen("detail") { listOf(SimEl("详情页", 540, 200)) },
    ))
    val adapt = Scenario("在线适应", "打开顽固，进入详情", listOf(stubbornApp()),
        policy = { o, w -> when {
            w.screenName() == "detail" -> done("已进入详情")
            w.flags["tried"] == true && o.has("打开详情") -> { w.flags["fail:打开详情"] = false; w.flags["lp"] = true; step("""{"type":"long_press","node_id":"${o.id("打开详情")}"}""") }
            o.has("打开详情") -> { w.flags["tried"] = true; w.flags["fail:打开详情"] = true; step(tap(o.id("打开详情"))) }
            else -> step("""{"type":"launch","app":"顽固"}""") } },
        done = { it.screenName() == "detail" })
}

package com.runasakura.phoneagent.ui

/**
 * Desktop-agent style transcript (Grok Bot / Codex feel), built from the agent's log stream.
 * Pure Kotlin (unit-tested). Lives in the process so lines that arrive while the user is in
 * another app are kept; the Activity only renders [snapshot].
 */
object ChatSession {
    sealed class Item {
        abstract val id: Long
        data class User(override val id: Long, val text: String, val followUp: Boolean) : Item()
        data class Plan(override val id: Long, val lines: MutableList<String>) : Item()
        data class Step(
            override val id: Long, var title: String, val details: MutableList<String>,
            var state: State = State.RUNNING, val startMs: Long, var endMs: Long? = null, var expanded: Boolean = false,
            var insight: String? = null
        ) : Item() { val durationMs get() = (endMs ?: startMs) - startMs }
        data class Confirm(override val id: Long, val reason: String, var answer: Boolean? = null) : Item()
        data class Result(override val id: Long, val ok: Boolean, val summary: String, val hint: String?, val goal: String) : Item()
        data class Note(override val id: Long, val text: String) : Item()
    }
    enum class State { RUNNING, OK, FAIL }
    enum class Phase(val label: String) { IDLE("空闲"), THINKING("思考中"), ACTING("操作中"), CONFIRM("等待确认"), PAUSED("已暂停") }

    private val items = mutableListOf<Item>()
    private var nextId = 1L
    @Volatile var phase: Phase = Phase.IDLE; private set
    @Volatile var lastGoal: String? = null; private set
    @Volatile var lastSummary: String? = null; private set
    private var running = false

    @Synchronized fun snapshot(): List<Item> = items.toList()
    @Synchronized fun clear() { items.clear(); phase = Phase.IDLE; lastGoal = null; lastSummary = null; running = false }
    @Synchronized fun toggle(id: Long) { (items.firstOrNull { it.id == id } as? Item.Step)?.let { it.expanded = !it.expanded } }
    @Synchronized fun setPaused(p: Boolean) { if (running) phase = if (p) Phase.PAUSED else Phase.ACTING }

    /** Context for a follow-up message in the same session. */
    @Synchronized fun followUpContext(): String? = lastGoal?.let { g -> "上一任务：$g；结果：${lastSummary ?: "未知"}" }

    @Synchronized fun startTask(goal: String, now: Long = System.currentTimeMillis()) {
        val follow = lastGoal != null
        items += Item.User(nextId++, goal, follow)
        lastGoal = goal; lastSummary = null; running = true; phase = Phase.THINKING
        cap()
    }

    private fun currentStep(): Item.Step? = items.lastOrNull { it is Item.Step && it.state == State.RUNNING } as? Item.Step
    private fun closeStep(now: Long, ok: Boolean = true) { currentStep()?.let { it.state = if (ok) State.OK else State.FAIL; it.endMs = now } }
    private fun newStep(title: String, now: Long) { closeStep(now); items += Item.Step(nextId++, title, mutableListOf(), startMs = now) }
    private fun plan(): Item.Plan {
        val last = items.lastOrNull()
        if (last is Item.Plan) return last
        return Item.Plan(nextId++, mutableListOf()).also { items += it }
    }

    private val STEP = Regex("^—— 步骤 (\\d+)/(\\d+) ——$")
    private val PLAN_MARKERS = listOf("意图：", "云端规划", "按意图改写", "交给模型完成剩余目标", "目标规范化")
    private val NEW_STEP_MARKERS = listOf("子目标：", "通话流程", "表情流程", "快路径", "匹配应用", "开关意图", "闹钟意图", "启动方式")
    private val NOISE = listOf("[观察]", "OCR 缓存命中", "LLM raw:", "读屏 ", "/no_think")

    @Synchronized fun onLog(line0: String, now: Long = System.currentTimeMillis()) {
        val line = line0.trim()
        if (line.isEmpty() || NOISE.any { line.startsWith(it) }) return
        when {
            line.startsWith("小目标：") -> (currentStep() ?: run { newStep("思考", now); currentStep()!! }).insight = line.substringAfter("：").take(40)
            line.startsWith("界面类型（模型判断）：") || line.startsWith("与目标是否一致（模型判断）：") ->
                currentStep()?.details?.add(line.replace("（模型判断）", ""))
            line.startsWith("经验沉淀：") || line.startsWith("失败反思：") -> items += Item.Note(nextId++, "🧠 " + line)
            PLAN_MARKERS.any { line.startsWith(it) } -> { plan().lines += line; phase = Phase.THINKING }
            STEP.matches(line) -> { val m = STEP.find(line)!!; newStep("第 ${m.groupValues[1]} 步", now); phase = Phase.THINKING }
            NEW_STEP_MARKERS.any { line.startsWith(it) } -> { newStep(line.take(40), now); phase = Phase.ACTING }
            line.startsWith("action:") || line.startsWith("retry action:") -> {
                val s = currentStep() ?: run { newStep("操作", now); currentStep()!! }
                s.title = s.title.substringBefore(" · ") + " · " + ActionText.describe(line.substringAfter(":").trim())
                s.details += line; phase = Phase.ACTING
            }
            line.startsWith("thought:") -> (currentStep() ?: run { newStep("思考", now); currentStep()!! }).details += "想法：" + line.substringAfter(":").trim()
            line.startsWith("请求模型") || line.startsWith("模型请求已发出") || line.startsWith("云端请求已发出") || line.startsWith("意图解析") -> {
                phase = Phase.THINKING; (currentStep() ?: plan().let { null })?.details?.add(line)
            }
            line.startsWith("用户确认") || line.startsWith("用户拒绝") || line.startsWith("用户取消") -> {
                (items.lastOrNull { it is Item.Confirm && it.answer == null } as? Item.Confirm)?.answer = line.startsWith("用户确认")
                phase = Phase.ACTING; currentStep()?.details?.add(line)
            }
            line.startsWith("模型统计") || line.startsWith("耗时分解") -> items += Item.Note(nextId++, line)
            else -> {
                val s = currentStep()
                if (s != null) { s.details += line; if (s.details.size > 60) s.details.removeAt(0) }
                else plan().lines += line
                if (line.startsWith("✗")) s?.state = State.RUNNING // keep running; final result decides
            }
        }
        cap()
    }

    /** Returns true when a task result card was added (used to record history once). */
    @Synchronized fun onStatus(type: String, msg: String, now: Long = System.currentTimeMillis()): Boolean {
        var finished = false
        when (type) {
            "confirm", "confirm-overlay" -> {
                if (items.none { it is Item.Confirm && it.answer == null && it.reason == msg }) items += Item.Confirm(nextId++, msg)
                phase = Phase.CONFIRM
            }
            "done", "stopped" -> {
                if (!running) return false // duplicate end (急停 followed by 已取消)
                val ok = type == "done"
                closeStep(now, ok)
                (items.lastOrNull { it is Item.Confirm && it.answer == null } as? Item.Confirm)?.answer = false
                val summary = msg.substringAfter("): ", msg)
                lastSummary = summary
                items += Item.Result(nextId++, ok, summary, if (ok) null else FriendlyErrors.explain(summary), lastGoal.orEmpty())
                running = false; phase = Phase.IDLE; finished = true
            }
        }
        cap()
        return finished
    }

    private fun cap() { while (items.size > 200) items.removeAt(0) }
}

/** Human Chinese for model action JSON. */
object ActionText {
    private fun field(s: String, k: String) = Regex("$k=([^,)]+)").find(s)?.groupValues?.get(1)?.takeIf { it != "null" }
    fun describe(a: String): String = when {
        a.startsWith("Tap") -> "点击" + (field(a, "text")?.let { "「$it」" } ?: field(a, "nodeId")?.let { " $it" } ?: "(${field(a, "x")},${field(a, "y")})")
        a.startsWith("LongPress") -> "长按" + (field(a, "text")?.let { "「$it」" } ?: "")
        a.startsWith("Type") -> "输入「${field(a, "text") ?: ""}」"
        a.startsWith("Swipe") -> "滑动"
        a.startsWith("Back") -> "返回"
        a.startsWith("Home") -> "回到桌面"
        a.startsWith("Wait") -> "等待"
        a.startsWith("Launch") -> "打开应用" + (field(a, "appName")?.let { "「$it」" } ?: "")
        a.startsWith("Done") -> "完成"
        a.startsWith("Fail") -> "无法继续"
        a.startsWith("AskConfirm") -> "请求确认"
        a.startsWith("Global") -> field(a, "name")?.let { com.runasakura.phoneagent.agent.ActionDesc.GLOBAL_ZH[it] } ?: "系统面板"
        a.startsWith("OpenUri") -> "打开链接" + (field(a, "uri")?.let { " ${it.take(30)}" } ?: "")
        a.startsWith("Intent") -> "打开系统页" + (field(a, "action")?.let { " ${it.substringAfterLast('.')}" } ?: "")
        a.startsWith("SetClipboard") -> "复制到剪贴板"
        a.startsWith("ReadClipboard") -> "读取剪贴板"
        a.startsWith("Key") -> field(a, "name")?.let { com.runasakura.phoneagent.agent.ActionDesc.KEY_ZH[it] } ?: "按键"
        a.startsWith("ScrollFind") -> "滚动查找" + (field(a, "text")?.let { "「$it」" } ?: "")
        a.startsWith("Recents") -> "最近任务"
        else -> a.take(24)
    }
}

/** Friendly Chinese explanations + what to do next. */
object FriendlyErrors {
    private val RULES = listOf(
        "无障碍" to "无障碍服务没连上：到 设置→辅助功能→已安装的服务 里重新打开 Phone Agent，再点「重试」。",
        "云端未就绪" to "云端模型还没配置好：到右上角设置里选服务商、填 Key，并点「测试连接」。",
        "LLM 不可用" to "模型暂时不可用：检查网络或 Key；用本机模型时请把 MNN Chat 放到前台。",
        "超时" to "这一步太慢超时了：网络或模型较慢，可以直接点「重试」。",
        "读不到界面" to "这个界面读不到文字（可能是视频/游戏或安全页面），换个页面再试。",
        "用户取消" to "你取消了确认，什么都没有发送。",
        "同名" to "有多个同名联系人，请用唯一的备注名再试。",
        "没有与" to "没找到完全一致的联系人，请用微信里显示的完整备注名。",
        "无法识别名称" to "联系人名字识别不了（表情/符号），建议改成文字备注名。",
        "界面无变化" to "连续几次操作界面都没变化，已停下防止乱点；可以描述得更具体一点再试。",
        "未找到应用" to "手机上没找到这个应用，换个叫法（比如应用图标上的名字）再试。",
        "急停" to "任务已停止。",
    )
    fun explain(summary: String): String =
        RULES.firstOrNull { summary.contains(it.first) }?.second ?: "可以点「重试」，或换个说法描述目标。"
}

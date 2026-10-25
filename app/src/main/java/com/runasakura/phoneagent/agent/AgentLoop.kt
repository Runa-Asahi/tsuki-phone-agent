package com.runasakura.phoneagent.agent

import android.util.Log
import com.runasakura.phoneagent.a11y.AgentAccessibilityService
import com.runasakura.phoneagent.llm.ChatMessage
import com.runasakura.phoneagent.llm.LlmResponse
import com.runasakura.phoneagent.llm.LlmRouter
import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.model.PlanStep
import com.runasakura.phoneagent.model.UiSnapshot
import com.runasakura.phoneagent.prefs.AgentPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

class AgentLoop(
    private val prefs: AgentPreferences,
    private val router: LlmRouter,
    private val safety: SafetyGate = SafetyGate(prefs),
    private val onLog: (String) -> Unit = {},
    private val onConfirm: suspend (String) -> Boolean = { false },
    private val isStopped: () -> Boolean = { false }
) {
    data class Result(
        val success: Boolean,
        val steps: Int,
        val summary: String
    )

    suspend fun run(goal: String): Result {
        val a11y = AgentAccessibilityService.require()
            ?: run { onLog("无障碍服务未连接，等待重连（最多 10 秒）…"); com.runasakura.phoneagent.util.Await.nonNull(10_000) { AgentAccessibilityService.require() } }
            ?: return Result(false, 0, "无障碍服务未开启（等待 10 秒仍未重连，请到设置→辅助功能重新打开）")
        a11y.resetTaskState()
        val history = mutableListOf<ChatMessage>()
        history += ChatMessage("system", SystemPrompt.TEXT)
        val steps = StepHistory()
        history += ChatMessage("user", "用户目标：$goal")

        if (safety.goalNeedsConfirm(goal)) {
            onLog("目标含敏感语义，开始前请知悉：发送/支付等会二次确认")
        }

        // Fast-path: 打开设置 / 打开设置并进入X — no LLM unless deterministic path fails
        val subTarget = compoundSettingsTarget(goal)
        if (isOpenSettingsGoal(goal) || subTarget != null) {
            onLog("快路径：Intent(Settings.ACTION_SETTINGS)")
            val ok = a11y.launchSystemSettings()
            onLog(if (ok) "已发起打开系统设置" else "打开系统设置失败")
            if (ok) {
                val fg = waitForeground(a11y, 2500) { a11y.isSettingsForeground(it) }
                onLog("前台=$fg")
                if (subTarget == null) {
                    // Exact open-settings goal: done, never call LLM
                    return Result(true, 1, "已打开系统设置 ($fg)")
                }
                if (a11y.isSettingsForeground(fg)) {
                    onLog("快路径：在设置中查找「$subTarget」")
                    if (tapTextWithScroll(a11y, subTarget)) {
                        val after = waitForeground(a11y, 1500) { true }
                        return Result(true, 2, "已打开设置并进入「$subTarget」($after)")
                    }
                    onLog("未找到「$subTarget」，交给 LLM 继续")
                    history += ChatMessage("user", "系统设置已打开。剩余目标：进入「$subTarget」")
                }
            }
        }

        // ---- Deterministic paths (never call the model here) ----
        val dev = com.runasakura.phoneagent.a11y.A11yDevice(a11y, onLog)
        val msgGoal = MessagingGoals.parse(goal)
        var targetPkg: String? = null
        if (msgGoal != null && msgGoal.contact != null && msgGoal.message != null) {
            MessagingGoals.validate(msgGoal.contact, msgGoal.message)?.let { return Result(false, 0, it) }
            return runMessaging(dev, msgGoal.packageName, msgGoal.contact, msgGoal.message)
        } else if (subTarget == null) {
            // Generic: 打开/启动/进入 <任意已安装 App>[，进入X，发送Y…]
            val appName = AppResolver.extractAppName(goal)
            if (appName != null) {
                val match = AppResolver.resolve(a11y, appName)
                if (match == null) {
                    onLog("未在已安装应用中找到「$appName」")
                    return Result(false, 0, "未找到应用「$appName」")
                }
                onLog("匹配应用：${match.label} (${match.packageName}) score=${match.score}")
                targetPkg = match.packageName
                if (!dev.bringToFront(match.packageName, match.label)) {
                    return Result(false, 1, "无法把「${match.label}」带到前台（前台=${dev.foregroundPackage()}）")
                }
                onLog("✓ 前台=${match.packageName}")
                val remaining = AppResolver.remainingGoal(goal, appName)
                if (remaining == null) return Result(true, 1, "已打开${match.label}")
                onLog("剩余子目标：$remaining")
                val subs = GoalSplitter.split(remaining)
                onLog("拆分：" + subs.joinToString(" → ") { it.describe() })
                // 微信/QQ：「进入X，发送Y」= 给 X 发消息 → strict messaging flow (title check etc.)
                val isMsgApp = match.packageName == MessagingGoals.WECHAT || match.packageName == MessagingGoals.QQ
                val o = subs.getOrNull(0) as? Subgoal.Open
                val sd = subs.getOrNull(1) as? Subgoal.Send
                if (isMsgApp && o != null && sd != null && subs.size == 2) {
                    MessagingGoals.validate(o.target, sd.text)?.let { return Result(false, 0, it) }
                    return runMessaging(dev, match.packageName, o.target, sd.text, reallySend = sd.send)
                }
                val (res, left) = runSubgoals(dev, subs)
                if (res != null) return res
                onLog("确定性执行未完成，剩余交给模型（最后手段）：$left")
                history.clear()
                history += ChatMessage("system", SystemPrompt.FOCUSED)
                history += ChatMessage("user", "已打开「${match.label}」。剩余目标：$left")
            } else {
                // No app named: generic gestures/navigation on the current app (通用点击滑动)
                val subs = GoalSplitter.split(goal)
                if (subs.isNotEmpty() && subs.none { it is Subgoal.Unknown }) {
                    if (AppResolver.isAgentOrBrain(dev.foregroundPackage()) && subs.first() !is Subgoal.Home) {
                        onLog("前台是 Agent 自身，先回桌面再执行（不操作 Agent/MNN 界面）")
                        dev.home(); dev.afterAction(1500)
                    }
                    onLog("拆分：" + subs.joinToString(" → ") { it.describe() })
                    val (res, left) = runSubgoals(dev, subs)
                    if (res != null) return res
                    onLog("确定性执行未完成，剩余交给模型（最后手段）：$left")
                    history.clear()
                    history += ChatMessage("system", SystemPrompt.FOCUSED)
                    history += ChatMessage("user", "剩余目标：$left")
                }
            }
        }

        var step = 0
        var refocus = 0
        val max = prefs.maxSteps
        while (step < max) {
            coroutineContext.ensureActive()
            if (isStopped()) return Result(false, step, "已急停")

            if (AgentAccessibilityService.require() !== a11y) {
                onLog("无障碍服务已断开/重启，等待重连…")
                val again = com.runasakura.phoneagent.util.Await.nonNull(10_000) { AgentAccessibilityService.require() }
                return Result(false, step, if (again == null) "无障碍服务已断开（10 秒未重连），任务停止" else "无障碍服务已重启，为安全起见停止本次任务，请重试")
            }
            step++
            onLog("—— 步骤 $step/$max ——")
            val sc = dev.screen()
            a11y.lastItems = sc.items
            val snap = UiSnapshot(
                packageName = sc.pkg, screenWidth = sc.w, screenHeight = sc.h, tree = null,
                compressedText = buildDump(sc), nodeCount = sc.items.size,
                lowConfidence = sc.source != "tree"
            )
            onLog("前台=${sc.pkg} 来源=${sc.source} 文本=${sc.textCount}")
            val diffNote = steps.observe(sc.items.map { it.label })
            diffNote?.let { onLog(it) }
            if (steps.stuckCount >= 3) return Result(false, step, "连续多次动作后界面无变化，已停止以免乱点")
            if (sc.isEmpty() && !AppResolver.isAgentOrBrain(sc.pkg)) {
                return Result(false, step, "读不到界面（无障碍树与 OCR 均为空），为避免盲猜不调用模型")
            }

            // Goal already satisfied?
            if (goalReachedSettings(goal, snap) && a11y.isSettingsForeground(snap.packageName)) {
                onLog("目标已达成（已在设置页）")
                return Result(true, step, "已在系统设置 (${snap.packageName})")
            }

            // Never let the model see the Agent's / MNN Chat's own UI
            val tp = targetPkg
            if (tp != null && snap.packageName != tp) {
                if (++refocus > 3) return Result(false, step, "多次无法回到目标应用 $tp")
                onLog("前台(${snap.packageName})不是目标应用，重新启动 $tp（不调用模型）")
                if (!dev.bringToFront(tp, tp)) return Result(false, step, "目标应用无法到前台（${dev.foregroundPackage()}）")
                step--; continue
            }
            if (tp == null && AppResolver.isAgentOrBrain(snap.packageName)) {
                if (++refocus > 3) return Result(false, step, "无法离开 Agent/MNN 界面")
                onLog("前台是 Agent/MNN 自身，先回桌面再观察")
                a11y.home(); delay(800)
                step--; continue
            }

            val shot = if (snap.lowConfidence) {
                onLog("界面文本少于3项，附带截图（仅 VL 模型会使用）")
                try { a11y.screenshotJpegBase64() } catch (_: Exception) { null }
            } else null
            onLog("请求本机 MNN（2B 可能需数十秒，日志会心跳）…")
            val useFocused = history.firstOrNull()?.content == SystemPrompt.FOCUSED
            val sys = if (useFocused) SystemPrompt.FOCUSED else SystemPrompt.SHORT
            val msgs = if (useFocused) {
                // system + first user + last assistant turn only
                history.take(2) + history.drop(2).takeLast(1) +
                    ChatMessage("user", buildObserveMessage(goal, snap, step, diffNote), shot)
            } else {
                // Keep history short for VL: system SHORT + last user observe only
                listOf(
                    ChatMessage("system", SystemPrompt.SHORT),
                    ChatMessage("user", "目标：$goal"),
                    ChatMessage("user", buildObserveMessage(goal, snap, step, diffNote), shot)
                )
            }
            var resp = planWithTimeout(msgs, forceCloud = false)
            logLlm(resp)
            // Hybrid: if local failed and cloud is configured, one cloud retry (optional screenshot)
            if (resp.error != null && resp.content.isBlank() && prefs.routeMode == AgentPreferences.ROUTE_HYBRID && prefs.cloudBaseUrl.isNotBlank()) {
                onLog("本机 LLM 失败，hybrid 尝试云端一次…")
                val cloudMsgs = if (prefs.allowCloudScreenshot && sc.source != "tree") {
                    val shot = try { a11y.screenshotJpegBase64() } catch (_: Exception) { null }
                    if (shot != null) msgs.map { if (it.role == "user" && it == msgs.last()) it.copy(imageBase64 = shot) else it }
                    else msgs
                } else msgs
                resp = planWithTimeout(cloudMsgs, forceCloud = true)
                logLlm(resp)
            }
            if (resp.error != null && resp.content.isBlank()) {
                onLog("LLM 错误(${resp.backend}): ${resp.error}")
                return Result(false, step, "LLM 不可用: ${resp.error}")
            }

            var plan = ActionParser.parse(resp.content)
            onLog("thought: ${plan.thought}")
            onLog("action: ${plan.action}")
            var err = validationError(plan, resp.content, sc)
            var tries = 0
            while (err != null && tries < 2) {
                tries++
                onLog("动作无效：$err —— 附错误说明重问（$tries/2）")
                resp = planWithTimeout(
                    listOf(
                        ChatMessage("system", SystemPrompt.SHORT),
                        ChatMessage(
                            "user",
                            "屏幕：\n${snap.compressedText}\n目标：$goal\n上次输出错误：$err\n" +
                                "请只用上面列表中的 e 编号，只输出一行 JSON。"
                        )
                    ),
                    forceCloud = false,
                    temperature = 0.1
                )
                logLlm(resp)
                if (resp.error != null && resp.content.isBlank()) return Result(false, step, "LLM 不可用: ${resp.error}")
                plan = ActionParser.parse(resp.content)
                onLog("retry action: ${plan.action}")
                err = validationError(plan, resp.content, sc)
            }
            if (err != null) return Result(false, step, "模型连续给出无效动作（$err），已停止以免误操作")

            history += ChatMessage("assistant", resp.content)

            // Rewrite ambiguous tap on "设置" while still in our app → system settings launch
            plan = rewriteSettingsAmbiguity(plan, snap, goal)

            val gate = safety.check(plan.action, snap, goal)
            when (gate.decision) {
                SafetyDecision.BLOCK_HUMAN -> {
                    onLog("安全闸：人工接管 — ${gate.reason}")
                    return Result(false, step, gate.reason)
                }
                SafetyDecision.REQUIRE_CONFIRM -> {
                    onLog("安全闸：等待确认 — ${gate.reason}")
                    val ok = onConfirm(gate.reason)
                    if (!ok) {
                        onLog("用户取消，停止")
                        return Result(false, step, "用户取消确认")
                    }
                }
                SafetyDecision.ALLOW -> Unit
            }

            when (val action = plan.action) {
                is AgentAction.Done -> return Result(true, step, action.summary.ifBlank { "完成" })
                is AgentAction.Fail -> {
                    if (isOpenSettingsGoal(goal)) {
                        onLog("模型 Fail，回退打开系统设置")
                        if (a11y.launchSystemSettings()) {
                            delay(1000)
                            val s = a11y.dumpUiTree()
                            if (a11y.isSettingsForeground(s.packageName)) {
                                return Result(true, step, "Fail 回退成功")
                            }
                        }
                    }
                    return Result(false, step, action.reason)
                }
                is AgentAction.HumanTakeover -> return Result(false, step, action.reason)
                is AgentAction.AskConfirm -> {
                    val ok = onConfirm(action.reason)
                    if (!ok) return Result(false, step, "用户取消")
                }
                else -> {
                    val before = a11y.foregroundPackage()
                    val ok = execute(a11y, action, snap)
                    steps.recordAction(action.toString())
                    onLog(if (ok) "执行成功" else "执行失败/部分失败")
                    if (action is AgentAction.Launch && ok) {
                        val fg = waitForeground(a11y, 3000) { it != before }
                        onLog("启动后前台=$fg")
                    } else {
                        delay(900)
                    }
                    val after = a11y.dumpUiTree()
                    onLog("执行后前台=${after.packageName}")
                    if (goalReachedSettings(goal, after) && a11y.isSettingsForeground(after.packageName)) {
                        return Result(true, step, "已打开系统设置 (${after.packageName})")
                    }
                }
            }
        }
        return Result(false, step, "达到最大步数 $max")
    }

    private suspend fun planWithTimeout(
        messages: List<ChatMessage>,
        forceCloud: Boolean,
        temperature: Double = 0.2
    ): LlmResponse = coroutineScope {
        onLog(if (forceCloud) "云端请求已发出…" else "MNN 请求已发出…")
        val started = System.currentTimeMillis()
        val deferred = async {
            router.plan(messages, forceCloud = forceCloud, temperature = temperature)
        }
        val watcher = async {
            var n = 0
            while (!deferred.isCompleted) {
                delay(10_000)
                if (deferred.isCompleted) break
                n++
                onLog("MNN 仍在推理…已等待 ${n * 10}s（2B VL 可能较慢，可急停）")
            }
        }
        val r = withTimeoutOrNull(STEP_TIMEOUT_MS) { deferred.await() }
        watcher.cancel()
        if (r == null) {
            deferred.cancel()
            onLog("本步 LLM 超时 ${STEP_TIMEOUT_MS / 1000}s，已取消请求（请在 MNN Chat 重启 API 清队列）")
            return@coroutineScope LlmResponse(
                "", "mnn_local", System.currentTimeMillis() - started,
                "step timeout ${STEP_TIMEOUT_MS}ms"
            )
        }
        onLog("MNN 响应返回 ${r.latencyMs}ms")
        r
    }

    private suspend fun waitForeground(
        a11y: AgentAccessibilityService,
        timeoutMs: Long,
        pred: (String) -> Boolean
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var fg = a11y.foregroundPackage()
        while (System.currentTimeMillis() < deadline) {
            fg = a11y.foregroundPackage()
            if (pred(fg) && !AppResolver.isAgentOrBrain(fg)) return fg
            delay(200)
        }
        return fg
    }

    private suspend fun tapTextWithScroll(a11y: AgentAccessibilityService, target: String): Boolean {
        val (w, h) = a11y.screenSize()
        for (attempt in 0..3) {
            coroutineContext.ensureActive()
            val node = a11y.findByText(target)
            if (node != null) {
                val ok = a11y.tapNode(node)
                onLog("点击「$target」${if (ok) "成功" else "失败"}")
                if (ok) return true
            }
            if (attempt < 3) {
                onLog("未见「$target」，上滑 ${attempt + 1}/3")
                a11y.swipe(w / 2, (h * 0.75).toInt(), w / 2, (h * 0.30).toInt(), 350)
                delay(700)
            }
        }
        return false
    }

    /** 「打开设置并进入电池」→ "电池"; null when not a compound settings goal. */
    private fun compoundSettingsTarget(goal: String): String? {
        val m = Regex("^打开(系统)?设置[，,、\\s]*(并|然后|再|后)?[\\s]*(进入|打开|找到|点击|点开)(.+)$")
            .find(goal.trim()) ?: return null
        return m.groupValues[4].trim().removeSuffix("页面").removeSuffix("页")
            .removeSuffix("设置").trim().ifBlank { null }
    }

    private fun logLlm(resp: LlmResponse) {
        onLog("脑=${resp.backend} ${resp.latencyMs}ms err=${resp.error ?: "-"}")
        val content = resp.content
        if (content.isBlank()) {
            onLog("LLM content: <empty>")
        } else {
            onLog("LLM content: ${content.take(800)}")
            if (content.length > 800) onLog("…(content truncated, len=${content.length})")
        }
        resp.rawBody?.let { raw ->
            // Keep short: choices[0].message often enough; full body for parse debug
            onLog("LLM raw: ${raw.take(600)}")
            if (raw.length > 600) onLog("…(raw truncated, len=${raw.length})")
        }
    }

    private fun rewriteSettingsAmbiguity(
        plan: PlanStep,
        snap: UiSnapshot,
        goal: String
    ): PlanStep {
        if (!isOpenSettingsGoal(goal)) return plan
        val inOurApp = snap.packageName.contains("runasakura.phoneagent")
        val action = plan.action
        if (inOurApp && action is AgentAction.Tap) {
            val t = action.text.orEmpty()
            // Toolbar "设置" opens our SettingsActivity — redirect to system settings
            if (t == "设置" || t.contains("设置")) {
                onLog("改写：点「设置」会进本 App 设置页 → 改为 launchSystemSettings")
                return plan.copy(action = AgentAction.Launch(appName = "设置"))
            }
        }
        if (inOurApp && action is AgentAction.Launch) {
            if (action.packageName.isNullOrBlank() && action.appName.isNullOrBlank()) {
                return plan.copy(action = AgentAction.Launch(appName = "设置"))
            }
        }
        return plan
    }

    private fun isOpenSettingsGoal(goal: String): Boolean {
        val g = goal.trim()
        return g == "打开设置" ||
            g == "打开系统设置" ||
            g == "去设置" ||
            g.matches(Regex("^打开(系统)?设置$")) ||
            g.equals("open settings", ignoreCase = true)
    }

    private fun goalReachedSettings(goal: String, snap: UiSnapshot): Boolean {
        if (!isOpenSettingsGoal(goal) && !goal.contains("设置")) return false
        return AgentAccessibilityService.SETTINGS_PACKAGES.any {
            snap.packageName.equals(it, true)
        } || snap.packageName.contains("settings", ignoreCase = true)
    }

    private fun validationError(plan: PlanStep, raw: String, sc: com.runasakura.phoneagent.flow.Screen): String? {
        if (ActionParser.hasThinkWithoutJson(raw)) return "只有思考没有 JSON"
        return ActionValidator.validate(plan.action, sc.items, sc.w, sc.h)
    }

    private suspend fun runSubgoals(dev: com.runasakura.phoneagent.a11y.A11yDevice, subs: List<Subgoal>): Pair<Result?, String?> {
        val exec = SubgoalExecutor(dev, onLog, onConfirm)
        for ((i, sg) in subs.withIndex()) {
            coroutineContext.ensureActive()
            if (isStopped()) return Result(false, i, "已急停") to null
            PermissionDialog.dismissIfPresent(dev, onLog)
            when (exec.run(sg)) {
                SubgoalExecutor.Status.OK -> Unit
                SubgoalExecutor.Status.CANCELLED -> return Result(false, i, "用户取消发送") to null
                SubgoalExecutor.Status.FAILED -> return null to subs.drop(i).joinToString("，") { it.describe() }
            }
        }
        return Result(true, subs.size, "已完成：" + subs.joinToString(" → ") { it.describe() } + "（确定性执行）") to null
    }

    private fun buildDump(sc: com.runasakura.phoneagent.flow.Screen): String {
        val sb = StringBuilder()
        for (it in sc.items.take(45)) {
            val line = it.format()
            if (sb.length + line.length > 1300) break
            sb.append(line).append('\n')
        }
        return sb.toString().trimEnd().ifBlank { "(空)" }
    }

    private suspend fun runMessaging(
        dev: com.runasakura.phoneagent.a11y.A11yDevice,
        pkg: String, contact: String, message: String, reallySend: Boolean = true
    ): Result {
        val profile = com.runasakura.phoneagent.flow.AppProfile.forPackage(pkg)
        if (!reallySend) {
            onLog("目标只要求输入不发送：将进入聊天并输入，但不点发送")
        }
        val flow = com.runasakura.phoneagent.flow.MessagingFlow(dev, profile, onLog) { text ->
            if (!reallySend) false else onConfirm(text)
        }
        val out = flow.run(contact, message)
        if (!reallySend && out.cancelled) return Result(true, 1, "已在「$contact」聊天中输入：$message（未发送）")
        return Result(out.ok, 1, out.summary)
    }

    private fun itemByText(a11y: AgentAccessibilityService, text: String) =
        a11y.lastItems.firstOrNull { com.runasakura.phoneagent.flow.Text.eq(it.label, text) }
            ?: a11y.lastItems.filter { it.label.contains(text) }.minByOrNull { it.label.length }

    private fun tapTargetMissing(a11y: AgentAccessibilityService, tap: AgentAction.Tap): Boolean {
        val id = tap.nodeId
        if (!id.isNullOrBlank()) return a11y.lastItems.none { it.id == id } && tap.x == null
        val txt = tap.text
        if (!txt.isNullOrBlank()) return itemByText(a11y, txt) == null
        return false
    }

    private fun buildObserveMessage(goal: String, snap: UiSnapshot, step: Int, diffNote: String? = null): String {
        // Prefer interactive-only dump for 2B VL latency
        val compact = try {
            AgentAccessibilityService.require()?.dumpInteractiveCompact() ?: snap
        } catch (_: Exception) { snap }
        return buildString {
            appendLine("/no_think")
            appendLine("步骤:$step 目标:$goal")
            appendLine("屏:${compact.screenWidth}x${compact.screenHeight} 包:${compact.packageName}")
            diffNote?.let { appendLine(it) }
            appendLine("要点(可点/可输入):")
            appendLine(compact.compressedText.take(1600).ifBlank { "(空)" })
            appendLine("只输出一个 JSON action。发送前 ask_confirm。")
        }
    }

    private fun execute(
        a11y: AgentAccessibilityService,
        action: AgentAction,
        @Suppress("UNUSED_PARAMETER") snap: UiSnapshot
    ): Boolean {
        return try {
            when (action) {
                is AgentAction.Launch -> {
                    when {
                        !action.packageName.isNullOrBlank() -> {
                            if (action.packageName!!.contains("settings", true) ||
                                AgentAccessibilityService.SETTINGS_PACKAGES.any {
                                    it.equals(action.packageName, true)
                                }
                            ) {
                                a11y.launchSystemSettings() || a11y.launchApp(action.packageName!!)
                            } else if (action.packageName == AgentAccessibilityService.WECHAT_PACKAGE ||
                                action.packageName!!.startsWith("com.tencent.mm")
                            ) {
                                a11y.launchWeChat() || a11y.launchApp(action.packageName!!)
                            } else {
                                a11y.launchApp(action.packageName!!)
                            }
                        }
                        !action.appName.isNullOrBlank() -> {
                            when {
                                action.appName!!.contains("设置") ||
                                    action.appName!!.contains("settings", true) ->
                                    a11y.launchSystemSettings() || a11y.launchByLabel(action.appName!!)
                                action.appName!!.contains("微信") ||
                                    action.appName!!.equals("WeChat", true) ->
                                    a11y.launchWeChat() || a11y.launchByLabel(action.appName!!)
                                else -> a11y.launchByLabel(action.appName!!)
                            }
                        }
                        else -> a11y.launchSystemSettings()
                    }
                }
                is AgentAction.Tap -> {
                    when {
                        !action.nodeId.isNullOrBlank() -> {
                            val it = a11y.lastItems.firstOrNull { i -> i.id == action.nodeId } ?: return false
                            a11y.tap(it.cx, it.cy)
                        }
                        !action.text.isNullOrBlank() -> {
                            val it = itemByText(a11y, action.text!!) ?: return false
                            a11y.tap(it.cx, it.cy)
                        }
                        action.x != null && action.y != null -> a11y.tap(action.x!!, action.y!!)
                        else -> false
                    }
                }
                is AgentAction.LongPress -> {
                    when {
                        !action.nodeId.isNullOrBlank() -> {
                            val it = a11y.lastItems.firstOrNull { i -> i.id == action.nodeId } ?: return false
                            a11y.longPress(it.cx, it.cy)
                        }
                        action.x != null && action.y != null -> a11y.longPress(action.x!!, action.y!!)
                        else -> false
                    }
                }
                is AgentAction.DoubleTap -> a11y.doubleTap(action.x, action.y)
                is AgentAction.Swipe -> a11y.swipe(
                    action.x1, action.y1, action.x2, action.y2, action.durationMs
                )
                is AgentAction.Type -> {
                    val node = action.nodeId?.let { a11y.findNodeById(it) }
                    a11y.typeText(action.text, node)
                }
                is AgentAction.Clear -> {
                    val node = action.nodeId?.let { a11y.findNodeById(it) }
                    a11y.clearText(node)
                }
                AgentAction.Back -> a11y.back()
                AgentAction.Home -> a11y.home()
                AgentAction.Recents -> a11y.recents()
                is AgentAction.Wait -> {
                    Thread.sleep(action.ms.coerceIn(100, 10_000))
                    true
                }
                else -> true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "execute failed", e)
            onLog("执行异常: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "AgentLoop"
        const val STEP_TIMEOUT_MS = 90_000L
    }
}

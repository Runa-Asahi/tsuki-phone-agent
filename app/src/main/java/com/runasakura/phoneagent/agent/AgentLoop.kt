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
            ?: return Result(false, 0, "无障碍服务未开启")
        val history = mutableListOf<ChatMessage>()
        history += ChatMessage("system", SystemPrompt.TEXT)
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

        var step = 0
        val max = prefs.maxSteps
        while (step < max) {
            coroutineContext.ensureActive()
            if (isStopped()) return Result(false, step, "已急停")

            step++
            onLog("—— 步骤 $step/$max ——")
            val snap = a11y.dumpUiTree()
            onLog("前台=${snap.packageName} 节点=${snap.nodeCount} low=${snap.lowConfidence}")

            // Goal already satisfied?
            if (goalReachedSettings(goal, snap) && a11y.isSettingsForeground(snap.packageName)) {
                onLog("目标已达成（已在设置页）")
                return Result(true, step, "已在系统设置 (${snap.packageName})")
            }

            onLog("请求本机 MNN（可能需数十秒）…")
            var resp = planWithTimeout(
                history + ChatMessage("user", buildObserveMessage(goal, snap, step)),
                forceCloud = snap.lowConfidence && prefs.routeMode != AgentPreferences.ROUTE_LOCAL_ONLY
            )
            logLlm(resp)

            if (resp.error != null && resp.content.isBlank()) {
                onLog("LLM 错误(${resp.backend}): ${resp.error}")
                return Result(false, step, "LLM 不可用: ${resp.error}")
            }

            var plan = ActionParser.parse(resp.content)
            onLog("thought: ${plan.thought}")
            onLog("action: ${plan.action}")

            if (ActionParser.isInvalid(plan) || ActionParser.hasThinkWithoutJson(resp.content)) {
                onLog("解析失败，使用短提示重试一次…")
                resp = planWithTimeout(
                    listOf(
                        ChatMessage("system", SystemPrompt.SHORT),
                        ChatMessage(
                            "user",
                            "目标：$goal\n前台：${snap.packageName}\n只输出一行 JSON，例如：" +
                                """{"thought":"开设置","action":{"type":"launch","app":"设置"}}"""
                        )
                    ),
                    forceCloud = false,
                    temperature = 0.1
                )
                logLlm(resp)
                if (resp.error != null && resp.content.isBlank()) {
                    // Last resort for settings goal
                    if (isOpenSettingsGoal(goal)) {
                        onLog("重试仍失败，回退快路径打开设置")
                        if (a11y.launchSystemSettings()) {
                            delay(1000)
                            return Result(true, step, "回退打开系统设置")
                        }
                    }
                    return Result(false, step, "LLM 不可用: ${resp.error}")
                }
                plan = ActionParser.parse(resp.content)
                onLog("retry thought: ${plan.thought}")
                onLog("retry action: ${plan.action}")
                if (ActionParser.isInvalid(plan) && isOpenSettingsGoal(goal)) {
                    onLog("仍无法解析，回退打开系统设置")
                    if (a11y.launchSystemSettings()) {
                        delay(1000)
                        return Result(true, step, "解析失败回退：已打开设置")
                    }
                }
            }

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
    ): LlmResponse {
        val r = withTimeoutOrNull(STEP_TIMEOUT_MS) {
            router.plan(messages, forceCloud = forceCloud, temperature = temperature)
        }
        if (r == null) {
            onLog("本步 LLM 超时 ${STEP_TIMEOUT_MS / 1000}s，已取消请求（MNN 若仍在推理，可在 MNN Chat 重启 API 服务清队列）")
            return LlmResponse("", "mnn_local", STEP_TIMEOUT_MS, "step timeout ${STEP_TIMEOUT_MS}ms")
        }
        return r
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
            if (pred(fg) && !fg.contains("runasakura.phoneagent")) return fg
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

    private fun buildObserveMessage(goal: String, snap: UiSnapshot, step: Int): String = buildString {
        appendLine("当前步骤: $step")
        appendLine("目标: $goal")
        appendLine("屏幕: ${snap.screenWidth}x${snap.screenHeight}")
        appendLine("前台包名: ${snap.packageName}")
        appendLine("注意: 当前若在 com.runasakura.phoneagent* ，工具栏「设置」是 Agent 自己的设置页，不是系统设置。打开系统设置请用 launch + app=设置 或 package=com.android.settings。")
        appendLine("节点树(压缩):")
        // Trim huge trees for small models
        appendLine(snap.compressedText.take(4500).ifBlank { "(空)" })
        appendLine("请输出下一步 JSON action。")
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
                            } else {
                                a11y.launchApp(action.packageName!!)
                            }
                        }
                        !action.appName.isNullOrBlank() -> {
                            if (action.appName!!.contains("设置") ||
                                action.appName!!.contains("settings", true)
                            ) {
                                a11y.launchSystemSettings() || a11y.launchByLabel(action.appName!!)
                            } else {
                                a11y.launchByLabel(action.appName!!)
                            }
                        }
                        else -> a11y.launchSystemSettings()
                    }
                }
                is AgentAction.Tap -> {
                    when {
                        !action.nodeId.isNullOrBlank() -> {
                            val n = a11y.findNodeById(action.nodeId!!) ?: return false
                            a11y.tapNode(n)
                        }
                        !action.text.isNullOrBlank() -> {
                            val n = a11y.findByText(action.text!!) ?: return false
                            a11y.tapNode(n)
                        }
                        action.x != null && action.y != null -> a11y.tap(action.x!!, action.y!!)
                        else -> false
                    }
                }
                is AgentAction.LongPress -> {
                    when {
                        !action.nodeId.isNullOrBlank() -> {
                            val n = a11y.findNodeById(action.nodeId!!) ?: return false
                            a11y.longPressNode(n)
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

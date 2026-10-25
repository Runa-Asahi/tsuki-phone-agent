package com.runasakura.phoneagent.agent

import android.util.Log
import com.runasakura.phoneagent.a11y.AgentAccessibilityService
import com.runasakura.phoneagent.llm.ChatMessage
import com.runasakura.phoneagent.llm.LlmRouter
import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.model.UiSnapshot
import com.runasakura.phoneagent.prefs.AgentPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

        var step = 0
        val max = prefs.maxSteps
        while (step < max) {
            coroutineContext.ensureActive()
            if (isStopped()) return Result(false, step, "已急停")

            step++
            onLog("—— 步骤 $step/$max ——")
            val snap = a11y.dumpUiTree()
            onLog("前台=${snap.packageName} 节点=${snap.nodeCount} low=${snap.lowConfidence}")

            val observe = buildObserveMessage(goal, snap, step)
            val messages = history + ChatMessage("user", observe)
            val forceCloud = snap.lowConfidence && prefs.routeMode != AgentPreferences.ROUTE_LOCAL_ONLY
            val resp = router.plan(messages, forceCloud = forceCloud)
            if (resp.error != null && resp.content.isBlank()) {
                onLog("LLM 错误(${resp.backend}): ${resp.error}")
                return Result(false, step, "LLM 不可用: ${resp.error}")
            }
            onLog("脑=${resp.backend} ${resp.latencyMs}ms")
            val plan = ActionParser.parse(resp.content)
            onLog("thought: ${plan.thought}")
            onLog("action: ${plan.action}")
            history += ChatMessage("assistant", resp.content)

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
                is AgentAction.Fail -> return Result(false, step, action.reason)
                is AgentAction.HumanTakeover -> return Result(false, step, action.reason)
                is AgentAction.AskConfirm -> {
                    val ok = onConfirm(action.reason)
                    if (!ok) return Result(false, step, "用户取消")
                    // after confirm, continue loop (model should issue real action next)
                }
                else -> {
                    val ok = execute(a11y, action, snap)
                    onLog(if (ok) "执行成功" else "执行失败/部分失败")
                    delay(450)
                }
            }
        }
        return Result(false, step, "达到最大步数 $max")
    }

    private fun buildObserveMessage(goal: String, snap: UiSnapshot, step: Int): String = buildString {
        appendLine("当前步骤: $step")
        appendLine("目标: $goal")
        appendLine("屏幕: ${snap.screenWidth}x${snap.screenHeight}")
        appendLine("前台包名: ${snap.packageName}")
        appendLine("节点树(压缩):")
        appendLine(snap.compressedText.ifBlank { "(空)" })
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
                        !action.packageName.isNullOrBlank() -> a11y.launchApp(action.packageName!!)
                        !action.appName.isNullOrBlank() -> a11y.launchByLabel(action.appName!!)
                        else -> false
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
                is AgentAction.Swipe -> a11y.swipe(action.x1, action.y1, action.x2, action.y2, action.durationMs)
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
    }
}

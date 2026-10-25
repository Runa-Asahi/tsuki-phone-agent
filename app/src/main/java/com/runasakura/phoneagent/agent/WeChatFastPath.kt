package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.a11y.AgentAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Deterministic WeChat open / find-contact / type-message path.
 * Never taps 发送 without [onConfirm] returning true.
 */
class WeChatFastPath(
    private val a11y: AgentAccessibilityService,
    private val onLog: (String) -> Unit,
    private val onConfirm: suspend (String) -> Boolean,
    private val isStopped: () -> Boolean
) {
    data class Outcome(
        val done: Boolean,
        val steps: Int,
        val summary: String,
        /** If not done, remaining hint for a short LLM fallback. */
        val fallbackHint: String? = null
    )

    suspend fun run(goal: MessagingGoal): Outcome {
        pkg = goal.packageName
        val appLabel = goal.app
        onLog("快路径：启动$appLabel $pkg")
        var launched = a11y.launchPackage(pkg, appLabel)
        var fg = waitWeChat(3500)
        if (!isTarget(fg)) {
            onLog("$appLabel 未到前台($fg)，重试启动")
            launched = a11y.launchPackage(pkg, appLabel)
            fg = waitWeChat(4000)
        }
        onLog("前台=$fg launched=$launched")
        if (!isTarget(fg)) {
            return Outcome(false, 1, "无法打开$appLabel（未安装或被拦截）", null)
        }
        if (goal.openOnly || goal.contact.isNullOrBlank()) {
            return Outcome(true, 1, "已打开微信 ($fg)")
        }

        val contact = goal.contact!!
        val message = goal.message.orEmpty()
        onLog("快路径：查找联系人「$contact」")
        if (!openChatWith(contact)) {
            return Outcome(
                false, 2, "未找到联系人 $contact",
                "微信已打开。请搜索并打开与「$contact」的聊天，然后输入「$message」，发送前 ask_confirm。"
            )
        }
        delay(800)
        if (message.isBlank()) {
            return Outcome(true, 3, "已打开与「$contact」的聊天")
        }

        onLog("快路径：输入消息（尚未发送）")
        if (!typeIntoChat(message)) {
            return Outcome(
                false, 4, "未能输入文字",
                "已打开「$contact」聊天。请在输入框输入「$message」，点发送前必须 ask_confirm。"
            )
        }
        delay(400)

        val reason = "即将向${goal.app}联系人「$contact」发送：「$message」\n确认后才会点击发送；取消则停止。"
        onLog("安全闸：等待确认发送")
        val ok = onConfirm(reason)
        if (!ok) {
            return Outcome(false, 5, "用户取消发送")
        }
        if (!tapSend()) {
            return Outcome(
                false, 5, "未找到发送按钮",
                "消息已输入。请点击「发送」（若需确认请 ask_confirm）。"
            )
        }
        delay(600)
        return Outcome(true, 5, "已向「$contact」发送「$message」")
    }

    private var pkg: String = MessagingGoals.WECHAT
    private fun isTarget(fg: String) = fg == pkg || fg.startsWith("$pkg:")

    private suspend fun waitWeChat(timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var fg = a11y.foregroundPackage()
        while (System.currentTimeMillis() < deadline) {
            if (isStopped()) break
            fg = a11y.foregroundPackage()
            if (isTarget(fg)) return fg
            delay(200)
        }
        return fg
    }

    private suspend fun openChatWith(contact: String): Boolean {
        // 1) Already visible in recent chats?
        if (tapContactLabel(contact)) return true

        // 2) Open search
        if (!openSearch()) {
            onLog("未找到搜索入口，尝试通讯录")
            a11y.findByText("通讯录")?.let { a11y.tapNode(it) }
            delay(700)
            if (tapContactLabel(contact)) return true
            return false
        }
        delay(500)
        // Type contact name
        if (!a11y.typeText(contact, null)) {
            // try focus search edit
            val edit = a11y.findByText("搜索") ?: a11y.findByContentDesc("搜索")
            if (edit != null) a11y.tapNode(edit)
            delay(300)
            if (!a11y.typeText(contact, null)) {
                onLog("搜索框输入失败")
                return false
            }
        }
        delay(900)
        // Tap result — prefer exact / starts-with match, avoid "搜索网络结果" etc.
        if (tapContactLabel(contact)) return true
        // Scroll results a bit
        val (w, h) = a11y.screenSize()
        for (i in 0 until 2) {
            a11y.swipe(w / 2, (h * 0.7).toInt(), w / 2, (h * 0.35).toInt(), 300)
            delay(600)
            if (tapContactLabel(contact)) return true
        }
        return false
    }

    private suspend fun openSearch(): Boolean {
        val candidates = listOf("搜索", "Search", "查找")
        for (c in candidates) {
            a11y.findByContentDesc(c)?.let {
                if (a11y.tapNode(it)) {
                    onLog("点击搜索(desc=$c)")
                    return true
                }
            }
            a11y.findByText(c)?.let {
                if (a11y.tapNode(it)) {
                    onLog("点击搜索(text=$c)")
                    return true
                }
            }
        }
        // Top-right heuristic on PPG-AN00 portrait 1264x2800
        val (w, h) = a11y.screenSize()
        if (a11y.tap((w * 0.836).toInt(), (h * 0.071).toInt())) {
            onLog("点击右上角启发式搜索位")
            delay(400)
            return true
        }
        return false
    }

    private fun tapContactLabel(contact: String): Boolean {
        val node = a11y.findByText(contact) ?: return false
        // Avoid tapping the search field that already contains the query
        if (node.isEditable) return false
        return a11y.tapNode(node)
    }

    private suspend fun typeIntoChat(message: String): Boolean {
        // Focus input: often contentDesc 输入 or hint 发送消息 / 按住说话 switch
        val hints = listOf("输入", "发送消息", "Type", "说点什么")
        for (h in hints) {
            (a11y.findByContentDesc(h) ?: a11y.findByText(h))?.let {
                a11y.tapNode(it)
                delay(300)
            }
        }
        // Switch from hold-to-talk to keyboard if needed
        a11y.findByContentDesc("切换到键盘")?.let { a11y.tapNode(it); delay(300) }
        a11y.findByContentDesc("键盘")?.let { a11y.tapNode(it); delay(300) }
        return a11y.typeText(message, null)
    }

    private fun tapSend(): Boolean {
        for (label in listOf("发送", "Send", "傳送")) {
            a11y.findByText(label)?.let {
                if (!it.isEditable && a11y.tapNode(it)) {
                    onLog("已点击「$label」")
                    return true
                }
            }
            a11y.findByContentDesc(label)?.let {
                if (a11y.tapNode(it)) {
                    onLog("已点击发送(desc)")
                    return true
                }
            }
        }
        return false
    }
}

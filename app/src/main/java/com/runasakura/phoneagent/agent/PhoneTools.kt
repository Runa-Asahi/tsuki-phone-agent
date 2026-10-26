package com.runasakura.phoneagent.agent

/**
 * Generic phone-level tools beyond tapping (harness-style tool catalog: small, orthogonal, model-chosen).
 * Policy here is scheme/intent based only — no app or keyword rules — and keeps ConfirmPolicy:
 * anything that dials, messages, mails, installs/uninstalls, or pays needs the user's confirmation.
 */
object PhoneTools {
    /** Global system surfaces the model can open. Values are AccessibilityService.GLOBAL_ACTION_* ids. */
    val GLOBAL = linkedMapOf(
        "notifications" to 4, "quick_settings" to 5, "recents" to 3, "power_dialog" to 6,
        "lock_screen" to 8, "take_screenshot" to 9, "split_screen" to 7, "back" to 1, "home" to 2
    )
    val KEYS = setOf("enter", "volume_up", "volume_down", "mute", "unmute")
    val DIRECTIONS = setOf("up", "down", "left", "right")

    private val CONFIRM_SCHEMES = setOf("tel", "sms", "smsto", "mms", "mmsto", "mailto", "wtai", "alipays", "weixin", "upi", "payto",
        // intent:// URIs can start any component with any action; javascript:/data: are never plain navigation.
        "intent", "javascript", "data", "file", "content")
    private val CONFIRM_INTENT = Regex("CALL|DIAL|SEND|SENDTO|DELETE|UNINSTALL|INSTALL_PACKAGE|PAY|PURCHASE", RegexOption.IGNORE_CASE)

    fun scheme(uri: String): String = uri.trim().substringBefore(':', "").lowercase().takeIf { Regex("[a-z][a-z0-9+.-]*").matches(it) }.orEmpty()

    /** Why this URI needs confirmation, or null when it is a plain view/navigation. */
    fun uriNeedsConfirm(uri: String): String? {
        val s = scheme(uri)
        if (s.isBlank()) return "无法识别的链接：$uri"
        if (s in CONFIRM_SCHEMES) return "将打开 $s 链接（可能拨号/发消息/付款）：$uri"
        if ((s == "market" || uri.contains("uninstall", true))) return "将打开应用安装/卸载相关页面：$uri"
        return null
    }

    /** Full policy for an intent: action class AND its data URI (VIEW + tel: is still a call). */
    fun intentConfirm(action: String, data: String?): String? =
        intentNeedsConfirm(action) ?: data?.takeIf { it.isNotBlank() }?.let { uriNeedsConfirm(it) }
            ?: if (intentAllowedWithoutConfirm(action)) null else "将执行系统操作 $action"

    fun intentNeedsConfirm(action: String): String? =
        if (CONFIRM_INTENT.containsMatchIn(action.substringAfterLast('.'))) "将执行系统操作 $action（拨号/发送/删除/卸载/支付类）" else null

    /** Only settings-style and VIEW intents may be fired directly; everything else goes through confirmation. */
    fun intentAllowedWithoutConfirm(action: String): Boolean =
        intentNeedsConfirm(action) == null && (action.startsWith("android.settings.") || action == "android.intent.action.VIEW" ||
            action.startsWith("android.provider.Settings"))
}

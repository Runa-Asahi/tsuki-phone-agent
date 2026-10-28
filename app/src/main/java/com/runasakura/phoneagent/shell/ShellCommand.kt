package com.runasakura.phoneagent.shell

import com.runasakura.phoneagent.agent.PhoneTools

/**
 * The model's `shell{cmd}` tool. Pure and JVM-testable:
 *  - [parse] tokenizes with quotes only — pipes, redirects, `;`, `&`, backticks, `$(`, newlines are rejected, and the
 *    argv is exec'd directly (no /system/bin/sh), so there is no injection surface;
 *  - [validate] allows a small set of system tools/sub-commands (generic: no app or keyword rules);
 *  - [confirmReason] extends ConfirmPolicy to dangerous shell actions.
 * Gestures (`input tap/swipe/text`) are deliberately NOT allowed here: the model uses tap/swipe/type, which carry the
 * on-screen send/pay/call confirmation; the backend itself injects them through Shizuku when it is active.
 */
object ShellCommand {
    sealed class Parsed {
        data class Ok(val argv: List<String>) : Parsed()
        data class Bad(val why: String) : Parsed()
    }

    private val META = Regex("[;|&<>`\\n\\r]|\\$\\(|\\$\\{")

    fun tokenize(cmd: String): List<String>? {
        val out = ArrayList<String>(); val cur = StringBuilder(); var q: Char? = null; var has = false
        for (c in cmd.trim()) {
            when {
                q != null -> if (c == q) q = null else cur.append(c)
                c == '"' || c == '\'' -> { q = c; has = true }
                c.isWhitespace() -> { if (cur.isNotEmpty() || has) { out += cur.toString(); cur.clear(); has = false } }
                else -> cur.append(c)
            }
        }
        if (q != null) return null
        if (cur.isNotEmpty() || has) out += cur.toString()
        return out
    }

    private val TOOLS = setOf("am", "pm", "settings", "cmd", "svc", "dumpsys", "getprop", "wm", "reboot", "monkey")
    private val AM = setOf("start", "start-activity", "force-stop", "kill", "stack", "get-current-user")
    private val PM = setOf("list", "path", "dump", "resolve-activity", "query-activities", "uninstall", "clear",
        "disable", "disable-user", "enable", "grant", "revoke", "reset-permissions", "default-state", "hide", "unhide", "suspend", "unsuspend")
    private val SETTINGS = setOf("get", "list", "put", "delete", "reset")
    private val CMD = setOf("wifi", "bluetooth_manager", "connectivity", "statusbar", "package", "activity", "location",
        "notification", "media_session", "audio", "power", "display", "uimode", "netpolicy", "role", "appops", "overlay", "battery", "nfc", "phone")
    private val SVC = setOf("wifi", "data", "bluetooth", "power", "nfc", "usb")

    fun parse(cmd: String): Parsed {
        val c = cmd.trim().removePrefix("adb shell ").removePrefix("shell ").trim()
        if (c.isBlank()) return Parsed.Bad("命令为空")
        if (META.containsMatchIn(c)) return Parsed.Bad("不支持管道/重定向/多条命令（; | & > < \$( ` 换行），一次只执行一条")
        val argv = tokenize(c) ?: return Parsed.Bad("引号未闭合")
        return validate(argv)
    }

    fun validate(argv: List<String>): Parsed {
        val tool = argv.firstOrNull().orEmpty()
        val sub = argv.getOrNull(1).orEmpty()
        when (tool) {
            "input" -> return Parsed.Bad("手势请用 tap/swipe/type/key 动作（它们会自动走 Shizuku），shell 不直接注入输入")
            "uiautomator", "screencap" -> return Parsed.Bad("界面树和截图由系统自动获取，不需要 shell；看不清就用 look")
            !in TOOLS -> return Parsed.Bad("不允许的命令：$tool（允许：${TOOLS.joinToString("/")}）")
        }
        val ok = when (tool) {
            "am" -> sub in AM
            "pm" -> sub in PM
            "settings" -> sub in SETTINGS && (sub == "list" || sub == "reset" || argv.getOrNull(2) in setOf("system", "secure", "global"))
            "cmd" -> sub in CMD
            "svc" -> sub in SVC
            "monkey" -> argv.contains("-p") && argv.lastOrNull() == "1"   // launch an app's launcher activity only
            else -> true                                                   // dumpsys / getprop / wm / reboot
        }
        if (!ok) return Parsed.Bad("不允许的子命令：${argv.take(3).joinToString(" ")}")
        return Parsed.Ok(argv)
    }

    /** Settings keys whose change weakens security/privacy or hands control to another app. */
    private val SECURITY_KEY = Regex("adb|development|install|unknown_sources|lock|password|accessibility|enabled_accessibility|" +
        "default_input_method|enabled_input_methods|location|mock|verifier|package_verifier|device_provisioned|user_setup|" +
        "airplane|vpn|private_dns|secure_frp|usb|bluetooth_discoverab|sms|dialer|assistant|notification_listener|device_admin|screen_lock", RegexOption.IGNORE_CASE)

    private fun opt(argv: List<String>, vararg flags: String): String? =
        argv.indices.firstOrNull { argv[it] in flags }?.let { argv.getOrNull(it + 1) }

    /** Why this (already validated) command needs the user's confirmation, or null. */
    fun confirmReason(argv: List<String>): String? {
        val tool = argv.firstOrNull().orEmpty(); val sub = argv.getOrNull(1).orEmpty()
        val all = argv.joinToString(" ")
        return when {
            tool == "reboot" -> "将重启手机"
            tool == "svc" && sub == "power" && argv.getOrNull(2) in setOf("reboot", "shutdown") -> "将重启/关机"
            tool == "cmd" && sub == "power" && argv.any { it in setOf("reboot", "shutdown") } -> "将重启/关机"
            tool == "pm" && sub == "uninstall" -> "将卸载应用：${argv.last()}"
            (tool == "pm" && sub == "clear") || (tool == "cmd" && sub == "package" && argv.getOrNull(2) == "clear") -> "将清除应用全部数据：${argv.last()}"
            tool == "pm" && sub in setOf("disable", "disable-user", "hide", "suspend", "grant", "revoke", "reset-permissions", "default-state") ->
                "将修改应用状态/权限：$all"
            tool == "cmd" && sub == "package" && argv.getOrNull(2) in setOf("uninstall", "disable", "disable-user", "grant", "revoke", "suspend", "hide") -> "将修改应用：$all"
            tool == "cmd" && sub in setOf("role", "appops", "overlay") && argv.getOrNull(2) !in setOf("get", "list", "query", "get-role-holders", "lookup") -> "将修改系统角色/权限：$all"
            tool == "settings" && sub in setOf("put", "delete", "reset") &&
                (argv.getOrNull(2) == "secure" || SECURITY_KEY.containsMatchIn(argv.getOrNull(3).orEmpty()) || sub == "reset") ->
                "将修改安全相关的系统设置：$all"
            tool == "cmd" && sub == "phone" -> "将调用电话服务：$all"
            tool == "am" && sub in setOf("start", "start-activity") -> amConfirm(argv)
            else -> null
        }
    }

    /** am start: same intent/scheme policy as the intent/open_url tools (call/send/pay → confirm; https → no confirm). */
    private fun amConfirm(argv: List<String>): String? {
        val action = opt(argv, "-a").orEmpty()
        val data = opt(argv, "-d")
        if (action.isNotBlank()) PhoneTools.intentNeedsConfirm(action)?.let { return it }
        data?.let { d -> PhoneTools.uriNeedsConfirm(d)?.let { return it } }
        return null
    }

    /** Commands that only read state (their output is fed back to the model as data). */
    fun isReadOnly(argv: List<String>): Boolean {
        val tool = argv.firstOrNull(); val sub = argv.getOrNull(1)
        return tool in setOf("dumpsys", "getprop") || (tool == "pm" && sub in setOf("list", "path", "dump", "resolve-activity", "query-activities")) ||
            (tool == "settings" && sub in setOf("get", "list")) || (tool == "wm" && argv.size <= 2)
    }

    /** Keys the backend can inject with `input keyevent` (names or KEYCODE_*). */
    fun keyCode(name: String): String? {
        val n = name.trim().uppercase().removePrefix("KEYCODE_")
        if (n.isBlank() || !Regex("[A-Z0-9_]{1,30}").matches(n)) return null
        return "KEYCODE_$n"
    }

    fun keyConfirm(code: String): String? = when (code.removePrefix("KEYCODE_")) {
        "CALL", "ENDCALL", "POWER", "SLEEP", "SOFT_SLEEP" -> "将发送系统按键 $code（拨号/挂断/电源）"
        else -> null
    }
}

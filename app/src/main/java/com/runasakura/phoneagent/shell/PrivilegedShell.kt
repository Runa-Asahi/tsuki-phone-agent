package com.runasakura.phoneagent.shell

/** Result of one privileged command. [out] is raw bytes (screencap returns a PNG). */
class ShellResult(val code: Int, val out: ByteArray, val err: String = "") {
    val ok: Boolean get() = code == 0
    val text: String get() = String(out, Charsets.UTF_8)
}

/**
 * A privileged (adb-shell identity) command runner. The real one is [ShizukuShell]; tests/simulator supply fakes.
 * Commands are argv lists (never a shell string), so nothing the model writes is interpreted by /system/bin/sh.
 */
interface PrivilegedShell {
    fun exec(argv: List<String>, timeoutMs: Long = 6000): ShellResult
    /** Best-effort clipboard read with shell identity (no Android 10+ background restriction). */
    fun readClipboard(): String? = null
}

/** Which backend drives privileged operations. Decided per task from the user toggle + live Shizuku status. */
enum class ShizukuStatus(val label: String) {
    NOT_INSTALLED("未安装 Shizuku"),
    NOT_RUNNING("Shizuku 未运行（荣耀重启后需用无线调试重新激活）"),
    NO_PERMISSION("Shizuku 运行中，尚未授权本应用"),
    READY("Shizuku 已就绪"),
}

object BackendSelector {
    enum class Backend { SHIZUKU, ACCESSIBILITY }

    /** Shizuku only when the user enabled it AND it is running with permission; anything else → a11y + OCR. */
    fun select(enabled: Boolean, status: ShizukuStatus): Backend =
        if (enabled && status == ShizukuStatus.READY) Backend.SHIZUKU else Backend.ACCESSIBILITY

    /** Wraps a shell so a failing binder (Shizuku killed mid-task) degrades to "no shell" instead of crashing. */
    fun guarded(shell: PrivilegedShell?): PrivilegedShell? = shell?.let { s ->
        object : PrivilegedShell {
            override fun exec(argv: List<String>, timeoutMs: Long): ShellResult {
                val caller = ShellLog.caller()
                if (ShellLog.usesUiAutomation(argv) && runCatching { ShellLog.a11yActive() }.getOrDefault(true)) {
                    ShellLog.record(argv, caller, "已拦截：无障碍服务在线时不运行 UiAutomation 命令（会使系统断开无障碍服务）")
                    return ShellResult(-1, ByteArray(0), "blocked: accessibility service active")
                }
                val r = try { s.exec(argv, timeoutMs) } catch (e: Exception) { ShellResult(-1, ByteArray(0), e.message ?: e.toString()) }
                ShellLog.record(argv, caller, "exit=${r.code}")
                return r
            }
            override fun readClipboard(): String? = try { s.readClipboard() } catch (_: Exception) { null }
        }
    }
}

/** Keeps command output small for the prompt: drop blank lines, cap lines and characters (head + tail). */
object ShellOutput {
    fun compact(s: String, maxLines: Int = 40, maxChars: Int = 1500): String {
        val lines = s.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
        val kept = if (lines.size <= maxLines) lines else lines.take(maxLines - 6) + "…（省略 ${lines.size - maxLines + 6} 行）" + lines.takeLast(5)
        val j = kept.joinToString("\n")
        return if (j.length <= maxChars) j else j.take(maxChars - 200) + "\n…\n" + j.takeLast(180)
    }
}

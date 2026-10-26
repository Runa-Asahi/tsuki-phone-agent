package com.runasakura.phoneagent.shell

/**
 * Audit trail of every privileged (Shizuku) command: wall-clock time, a redacted command line and the calling code
 * site, kept in a small ring buffer and exported with the diagnostics log. Also the single place that refuses
 * UiAutomation-based commands while our accessibility service is enabled/connected: `uiautomator dump` (and
 * friends) register a UiAutomation connection that makes the system unbind third-party accessibility services.
 */
object ShellLog {
    data class Entry(val atMs: Long, val cmd: String, val caller: String, val result: String)

    private const val MAX = 120
    private val ring = ArrayDeque<Entry>()

    /** Set at app start: true while our accessibility service is connected or enabled in settings. */
    @Volatile var a11yActive: () -> Boolean = { false }
    /** Extra sink (diagnostics log); never receives secrets — commands are redacted first. */
    @Volatile var sink: ((String) -> Unit)? = null

    private val UIA = setOf("uiautomator", "uiautomator2")

    /** Commands that open a UiAutomation connection (and so knock our accessibility service off). */
    fun usesUiAutomation(argv: List<String>): Boolean {
        val a = argv.map { it.trim().lowercase() }
        val head = a.firstOrNull()?.substringAfterLast('/') ?: return false
        if (head in UIA) return true
        // `am instrument` runs a UiAutomation-backed test runner; `cmd uiautomation`/`dumpsys … uiautomation` too.
        if (head == "am" && a.getOrNull(1) == "instrument") return true
        if (head == "cmd" && a.getOrNull(1)?.contains("uiautomation") == true) return true
        return false
    }

    /** Redact typed text and anything after `input text` so user content never lands in the log. */
    fun redact(argv: List<String>): String {
        val a = argv.toMutableList()
        val i = a.indexOf("text")
        if (a.firstOrNull() == "input" && i >= 0) for (k in i + 1 until a.size) a[k] = "<${a[k].length}字符>"
        for (k in 1 until a.size) if (a[k - 1] == "-d" || a[k - 1] == "--es" || a[k - 1] == "--ei") a[k] = "<参数>"
        return a.joinToString(" ").take(160)
    }

    fun caller(stack: Array<StackTraceElement> = Throwable().stackTrace): String =
        stack.firstOrNull { e -> val c = e.className
            !c.startsWith("com.runasakura.phoneagent.shell.ShellLog") && !c.startsWith("com.runasakura.phoneagent.shell.BackendSelector") &&
                !c.startsWith("java.") && !c.startsWith("kotlin.") && !c.contains("ShizukuShell") }
            ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "?"

    @Synchronized fun record(argv: List<String>, caller: String, result: String, now: Long = System.currentTimeMillis()) {
        val e = Entry(now, redact(argv), caller, result)
        ring.addLast(e); while (ring.size > MAX) ring.removeFirst()
        sink?.invoke("Shizuku 命令 ${fmt(now)} [${e.caller}] ${e.cmd} → $result")
    }

    @Synchronized fun entries(): List<Entry> = ring.toList()
    @Synchronized fun clear() = ring.clear()

    fun fmt(ms: Long): String = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(ms))

    fun export(): String = entries().joinToString("\n") { "${fmt(it.atMs)} [${it.caller}] ${it.cmd} → ${it.result}" }.ifEmpty { "（无）" }
}

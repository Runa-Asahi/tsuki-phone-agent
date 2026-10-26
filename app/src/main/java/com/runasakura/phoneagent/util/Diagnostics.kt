package com.runasakura.phoneagent.util

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory diagnostics for the last task (exportable from the main screen). */
object Diagnostics {
    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile var goal: String = ""
    @Volatile var model: String = ""
    @Volatile var selfTest: String = "(未运行自检)"
    private var ocrCount = 0
    private var treeCount = 0

    @Synchronized fun reset(goal: String) {
        lines.clear(); this.goal = goal; ocrCount = 0; treeCount = 0
    }

    @Synchronized fun log(msg: String) {
        if (lines.size > 3000) lines.subList(0, 500).clear()
        lines += "${fmt.format(Date())} ${clip(msg)}"
    }

    /** Per-line cap so model dumps can't blow the export past a few MB (3000 × 2000 chars max). */
    fun clip(msg: String, max: Int = 2000): String =
        if (msg.length <= max) msg else msg.take(max) + "…(+${msg.length - max} 字)"

    @Synchronized fun observe(pkg: String, source: String, texts: Int) {
        if (source.startsWith("ocr")) ocrCount++ else treeCount++
        log("[观察] 包=$pkg 来源=$source 文本=$texts")
    }

    @Synchronized fun export(appVersion: String, a11yInfo: String): String = buildString {
        appendLine("=== Phone Agent 诊断日志 ===")
        appendLine("版本: $appVersion  设备: ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("模型: $model")
        appendLine("无障碍: $a11yInfo")
        appendLine("任务: $goal")
        appendLine("观察次数: 树=$treeCount OCR=$ocrCount")
        appendLine("--- 自检 ---")
        appendLine(selfTest)
        appendLine("--- 无障碍连接记录 ---")
        appendLine(com.runasakura.phoneagent.a11y.A11yEvents.export())
        appendLine("--- Shizuku 命令记录（时间/调用方/命令） ---")
        appendLine(com.runasakura.phoneagent.shell.ShellLog.export())
        appendLine("--- 日志 ---")
        lines.forEach { appendLine(it) }
    }
}

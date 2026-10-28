package com.runasakura.phoneagent.agent

/**
 * One coherent, user-readable account of what this run can do and how the backends coexist — the same text goes
 * to the task log and the diagnostics export, each gap with the concrete fix (where in the app to change it).
 */
object Capabilities {
    data class Input(
        val a11yConnected: Boolean, val shizuku: Boolean, val cloudModel: String?, val cloudSeesImages: Boolean,
        val allowShots: Boolean, val localUsable: Boolean, val visionHelper: Boolean,
    )

    fun lines(i: Input): List<String> = buildList {
        add("操作：" + if (i.a11yConnected) "无障碍（点击/输入/读界面）" else "✗ 无障碍未连接 → 设置→辅助功能→已安装的服务→开启本应用")
        add("特权：" + if (i.shizuku) "Shizuku（按键/系统命令；不会在无障碍在线时运行 uiautomator）" else "未启用 Shizuku（可选：在 设置→特权模式 开启，游戏里的按键/截图更稳）")
        add("思考：" + (i.cloudModel?.let { "云端 $it（仅使用你选的模型）" } ?: if (i.localUsable) "本机 MNN" else "✗ 没有可用模型 → 设置→模型 填写 API Key 或启动本机 MNN"))
        add("看图：" + when {
            i.cloudModel != null && i.cloudSeesImages && i.allowShots -> "云端模型直接看截图"
            i.visionHelper -> "视觉辅助模型描述截图"
            i.cloudModel != null && i.cloudSeesImages -> "无（模型支持图片，但未勾选「允许向云端发送截图」）→ 改用 OCR + 色块候选"
            else -> "无（当前模型不支持图片，且无可用视觉辅助）→ 改用 OCR + 色块候选；游戏/图标界面成功率会下降"
        })
    }
}

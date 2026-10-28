package com.runasakura.phoneagent.agent

import android.provider.Settings

/**
 * Deterministic, read-only settings pages (open only — never toggles anything).
 * 「打开蓝牙设置」「打开WLAN设置」「查看电池」… → direct Settings intent.
 */
object SettingsPages {
    data class Page(val label: String, val action: String, val keys: List<String>)

    val ALL = listOf(
        Page("WLAN", Settings.ACTION_WIFI_SETTINGS, listOf("wlan", "wifi", "无线网", "无线局域网")),
        Page("蓝牙", Settings.ACTION_BLUETOOTH_SETTINGS, listOf("蓝牙", "bluetooth")),
        Page("移动网络", Settings.ACTION_DATA_ROAMING_SETTINGS, listOf("移动网络", "移动数据", "流量")),
        Page("显示和亮度", Settings.ACTION_DISPLAY_SETTINGS, listOf("显示", "亮度", "屏幕")),
        Page("声音和振动", Settings.ACTION_SOUND_SETTINGS, listOf("声音", "铃声", "振动", "音量")),
        Page("电池", Intent_BATTERY, listOf("电池", "耗电", "省电")),
        Page("应用", Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, listOf("应用管理", "应用和服务", "应用设置")),
        Page("存储", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, listOf("存储", "内存", "储存")),
        Page("位置信息", Settings.ACTION_LOCATION_SOURCE_SETTINGS, listOf("位置", "定位", "gps")),
        Page("关于手机", Settings.ACTION_DEVICE_INFO_SETTINGS, listOf("关于手机", "本机信息", "版本号")),
        Page("日期和时间", Settings.ACTION_DATE_SETTINGS, listOf("日期", "时间设置")),
        Page("语言和输入法", Settings.ACTION_LOCALE_SETTINGS, listOf("语言")),
        Page("无障碍", Settings.ACTION_ACCESSIBILITY_SETTINGS, listOf("无障碍", "辅助功能")),
        Page("通知", "android.settings.ALL_APPS_NOTIFICATION_SETTINGS", listOf("通知设置", "通知管理")),
        Page("开发者选项", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, listOf("开发者")),
    )

    private const val Intent_BATTERY = "android.intent.action.POWER_USAGE_SUMMARY"
    private val VERB = Regex("^(?:请)?(?:帮我)?(?:打开|进入|去|查看|看看|看一下)")
    private val MUTATE = Regex("(关闭|开启|关掉|打开.{0,4}(开关)|调到|设为|设置为|改成|连接|断开|删除|清除|重置|恢复出厂)")

    /** Only pure "open/view the X settings page" goals; anything that changes a setting goes to the agent. */
    fun match(goal: String): Page? {
        val g = goal.trim().lowercase()
        if (!VERB.containsMatchIn(g) || MUTATE.containsMatchIn(g.replace(Regex("^(?:请)?(?:帮我)?打开"), ""))) return null
        if (g.length > 20) return null
        return ALL.firstOrNull { p -> p.keys.any { g.contains(it) } }
    }
}

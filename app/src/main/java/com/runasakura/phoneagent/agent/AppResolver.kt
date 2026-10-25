package com.runasakura.phoneagent.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Resolve a spoken app name to an installed launcher app (no hardcoded per-app logic). */
object AppResolver {
    data class AppMatch(val label: String, val packageName: String, val score: Int)

    /** alias -> candidate packages (used only to boost; label matching still works without). */
    private val ALIASES: Map<String, List<String>> = mapOf(
        "微信" to listOf("com.tencent.mm"), "wechat" to listOf("com.tencent.mm"),
        "qq" to listOf("com.tencent.mobileqq", "com.tencent.tim"),
        "支付宝" to listOf("com.eg.android.AlipayGphone"),
        "淘宝" to listOf("com.taobao.taobao"),
        "抖音" to listOf("com.ss.android.ugc.aweme"),
        "b站" to listOf("tv.danmaku.bili"), "哔哩哔哩" to listOf("tv.danmaku.bili"), "bilibili" to listOf("tv.danmaku.bili"),
        "小红书" to listOf("com.xingin.xhs"),
        "设置" to listOf("com.android.settings"),
        "相机" to listOf("com.hihonor.camera", "com.android.camera"),
        "浏览器" to listOf("com.hihonor.browser", "com.android.browser"),
        "备忘录" to listOf("com.hihonor.notepad", "com.example.android.notepad"),
        "京东" to listOf("com.jingdong.app.mall"),
        "美团" to listOf("com.sankuai.meituan"),
        "高德" to listOf("com.autonavi.minimap"), "高德地图" to listOf("com.autonavi.minimap"),
        "网易云" to listOf("com.netease.cloudmusic"), "网易云音乐" to listOf("com.netease.cloudmusic"),
    )

    private val SELF_OR_BRAIN = listOf("com.runasakura.phoneagent", "com.alibaba.mnnllm")

    fun isAgentOrBrain(pkg: String) = SELF_OR_BRAIN.any { pkg.startsWith(it) }

    /** "打开QQ并…" / "启动抖音" / "进入B站，…" -> app name; null if goal names no app. */
    fun extractAppName(goal: String): String? {
        val g = goal.trim()
        val m = Regex("^(?:请)?(?:帮我)?(?:打开|启动|进入|开启|用|去)\\s*(.+)$").find(g) ?: return null
        var rest = m.groupValues[1]
        val cut = Regex("(并且|并|然后|再|后|给|向|跟|对|，|,|、|。|\\s)").find(rest)
        if (cut != null && cut.range.first > 0) rest = rest.substring(0, cut.range.first)
        rest = rest.removeSuffix("应用").removeSuffix("App").removeSuffix("app").trim()
        return rest.ifBlank { null }
    }

    /** Remaining subgoal after the app-opening prefix, or null if nothing remains. */
    fun remainingGoal(goal: String, appName: String): String? {
        val idx = goal.indexOf(appName)
        if (idx < 0) return null
        val rest = goal.substring(idx + appName.length)
            .trimStart('并', '且', '然', '后', '再', '，', ',', '、', ' ', '。')
            .removePrefix("然后").trim()
        return rest.ifBlank { null }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[\\s·・\\-_]"), "")

    fun resolve(context: Context, name: String): AppMatch? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val acts = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val q = norm(name)
        val aliasPkgs = ALIASES[q] ?: ALIASES.entries.firstOrNull { norm(it.key) == q }?.value ?: emptyList()
        var best: AppMatch? = null
        for (ri in acts) {
            val pkg = ri.activityInfo.packageName
            if (isAgentOrBrain(pkg)) continue
            val label = ri.loadLabel(pm).toString()
            val l = norm(label)
            val score = when {
                pkg in aliasPkgs -> 100 - aliasPkgs.indexOf(pkg)
                l == q -> 90
                l.startsWith(q) -> 75
                l.contains(q) -> 60
                q.contains(l) && l.length >= 2 -> 50
                pkg.lowercase().contains(q) -> 40
                else -> 0
            }
            if (score > 0 && (best == null || score > best.score)) best = AppMatch(label, pkg, score)
        }
        return best
    }
}

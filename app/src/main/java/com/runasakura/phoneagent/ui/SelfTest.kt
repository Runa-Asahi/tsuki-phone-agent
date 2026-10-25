package com.runasakura.phoneagent.ui

import android.content.Context
import android.content.Intent
import com.runasakura.phoneagent.PhoneAgentApp
import com.runasakura.phoneagent.a11y.A11yDevice
import com.runasakura.phoneagent.a11y.AgentAccessibilityService
import com.runasakura.phoneagent.agent.MessagingGoals
import com.runasakura.phoneagent.ocr.OcrEngine
import com.runasakura.phoneagent.util.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One-tap on-device verification of everything unit tests cannot cover. */
class SelfTest(private val ctx: Context, private val progress: (String) -> Unit) {
    data class Check(val name: String, val ok: Boolean, val detail: String)

    suspend fun run(probeApps: Boolean): List<Check> {
        val out = mutableListOf<Check>()
        fun add(name: String, ok: Boolean, detail: String) { out += Check(name, ok, detail); progress("${if (ok) "✅" else "❌"} $name：$detail") }
        val a = AgentAccessibilityService.require()
        add("无障碍服务", a != null, if (a != null) "已连接" else "未开启（设置→辅助功能→已安装的服务）")
        if (a != null) {
            val ws = try { a.windows.size } catch (_: Exception) { 0 }
            add("读取窗口列表", ws > 0, "窗口数 $ws")
            add("无障碍浮窗（确认框/急停）", a.overlayWorks(), "TYPE_ACCESSIBILITY_OVERLAY")
            add("无障碍输入法通道", a.imeChannelEnabled(), if (android.os.Build.VERSION.SDK_INT >= 33) "flagInputMethodEditor" else "需 Android 13+")
            val bmp = withContext(Dispatchers.IO) { a.takeScreenshotBitmap() }
            add("截图权限", bmp != null, bmp?.let { "${it.width}x${it.height}" } ?: "失败（重新开关无障碍服务）")
            if (bmp != null) {
                val t0 = System.currentTimeMillis()
                val lines = withTimeoutOrNull(15_000) { OcrEngine.recognizeLines(bmp) }
                val ms = System.currentTimeMillis() - t0
                add("端侧 OCR（ML Kit 中文，离线）", !lines.isNullOrEmpty(),
                    if (lines == null) "失败/超时" else "识别 ${lines.size} 行，${ms}ms，例：${lines.take(3).joinToString("、") { it.text }}")
            }
        }
        val (mnnOk, mnnDetail) = withContext(Dispatchers.IO) { probeMnn() }
        add("MNN API", mnnOk, mnnDetail)
        val pm = ctx.packageManager
        for ((label, pkg) in listOf("微信" to MessagingGoals.WECHAT, "QQ" to MessagingGoals.QQ)) {
            val i = pm.getLaunchIntentForPackage(pkg)
            add("可启动 $label", i != null, i?.component?.className ?: "未安装或不可见")
        }
        if (probeApps && a != null) {
            for ((label, pkg) in listOf("微信" to MessagingGoals.WECHAT, "QQ" to MessagingGoals.QQ)) {
                if (pm.getLaunchIntentForPackage(pkg) == null) continue
                val dev = A11yDevice(a) { progress("  $it") }
                val fg = dev.bringToFront(pkg, label)
                add("把 $label 带到前台", fg, "前台=${dev.foregroundPackage()}")
                if (fg) {
                    delay(1500)
                    val s = dev.screen()
                    add("$label 界面读取", s.textCount >= 3, "来源=${s.source} 文字=${s.textCount}：${s.summary(8)}")
                }
            }
            (a as Context).startActivity(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        val report = out.joinToString("\n") { "${if (it.ok) "PASS" else "FAIL"} ${it.name}: ${it.detail}" }
        Diagnostics.selfTest = report
        Diagnostics.log("[自检]\n$report")
        return out
    }

    private fun probeMnn(): Pair<Boolean, String> = try {
        val p = PhoneAgentApp.instance.prefs
        val base = p.mnnBaseUrl.trimEnd('/')
        val c = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
        c.newCall(Request.Builder().url("$base/models").header("Authorization", "Bearer ${p.mnnApiKey}").build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) false to "HTTP ${r.code}" else {
                val id = JSONObject(body).optJSONArray("data")?.optJSONObject(0)?.optString("id").orEmpty()
                true to "可达，模型=${id.ifBlank { "(未返回)" }}"
            }
        }
    } catch (e: Exception) {
        false to "不可达：${e.message?.take(60)}（在 MNN Chat 启动 API 服务）"
    }
}

package com.runasakura.phoneagent.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.runasakura.phoneagent.R
import com.runasakura.phoneagent.overlay.OverlayPill
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the redesigned screens on the JVM (Robolectric native graphics) and writes PNGs to
 * build/ui-shots (and /workspace/deliverables/ui-shots when it exists). Also asserts structure so the test is
 * a real UI regression test, not just a picture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
abstract class ShotBase {
    lateinit var act: Activity
    @Before fun setUp() {
        act = Robolectric.buildActivity(Activity::class.java).setup().get()
        act.setTheme(R.style.Theme_HonorPhoneAgent)
        ChatSession.clear()
    }
    fun save(name: String, v: View, w: Int = act.resources.displayMetrics.widthPixels, h: Int? = act.resources.displayMetrics.heightPixels) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            if (h == null) View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED) else View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            if (h == null) View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED) else View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val bmp = Bitmap.createBitmap(v.measuredWidth, maxOf(1, v.measuredHeight), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(androidx.core.content.ContextCompat.getColor(act, R.color.bg))
        v.draw(Canvas(bmp))
        val dirs = listOf(File("build/ui-shots"), File("/workspace/deliverables/ui-shots")).filter { it.mkdirs() || it.isDirectory }
        for (d in dirs) File(d, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    /** Fill the settings form like SettingsActivity does (spinners, fields, switches) so the shot shows real content. */
    fun settingsContent(): View {
        val root = LayoutInflater.from(act).inflate(R.layout.activity_settings, FrameLayout(act), false)
        val content = (root.findViewById<View>(R.id.btnSave).parent as LinearLayout).getChildAt(1) as ScrollView
        val inner = content.getChildAt(0); content.removeView(inner)
        val prov = com.runasakura.phoneagent.llm.CloudProviders.ALL
        inner.findViewById<android.widget.Spinner>(R.id.cloudProvider).adapter = android.widget.ArrayAdapter(act, R.layout.item_spinner, prov.map { it.label })
        inner.findViewById<android.widget.Spinner>(R.id.cloudProvider).setSelection(prov.indexOfFirst { it.id.contains("deepseek", true) }.coerceAtLeast(0), false)
        inner.findViewById<android.widget.Spinner>(R.id.routeMode).adapter = android.widget.ArrayAdapter(act, R.layout.item_spinner,
            listOf(com.runasakura.phoneagent.prefs.AgentPreferences.ROUTE_CLOUD_ONLY).map { com.runasakura.phoneagent.llm.RoutePolicy.label(it) })
        inner.findViewById<android.widget.TextView>(R.id.cloudUrl).text = "https://api.deepseek.com"
        inner.findViewById<android.widget.TextView>(R.id.cloudKey).text = "sk-••••••••"
        inner.findViewById<android.widget.TextView>(R.id.cloudModel).text = "deepseek-chat"
        inner.findViewById<android.widget.TextView>(R.id.maxSteps).text = "25"
        inner.findViewById<android.widget.TextView>(R.id.cloudTestResult).text = "✅ 连接正常（deepseek-chat，0.6s）"
        inner.findViewById<android.widget.CompoundButton>(R.id.confirmAlarm).isChecked = true
        inner.findViewById<android.widget.CompoundButton>(R.id.cloudShot).isChecked = true
        return inner
    }
    private fun renderer() = ChatRenderer(act, {}, {}, {}, {}, animate = false)

    fun main(name: String, fill: () -> Unit, running: Boolean): View {
        val root = LayoutInflater.from(act).inflate(R.layout.activity_main, FrameLayout(act), false)
        fill()
        val list = root.findViewById<LinearLayout>(R.id.chatList)
        renderer().render(list, ChatSession.snapshot(), ChatSession.phase, listOf("打开蓝牙" to true, "打开B站，搜索猫咪" to false), MainActivity.SUGGESTIONS)
        root.findViewById<View>(R.id.btnStart).visibility = if (running) View.GONE else View.VISIBLE
        root.findViewById<View>(R.id.btnStop).visibility = if (running) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.btnPause).visibility = if (running) View.VISIBLE else View.GONE
        (root.findViewById<android.widget.TextView>(R.id.a11yStatus)).apply { text = "已连接"; setTextColor(androidx.core.content.ContextCompat.getColor(act, R.color.ok_green)) }
        (root.findViewById<android.widget.TextView>(R.id.mnnStatus)).apply { text = "云端 deepseek-chat"; setTextColor(androidx.core.content.ContextCompat.getColor(act, R.color.ok_green)) }
        root.findViewById<android.widget.TextView>(R.id.statusText).text = ChatSession.phase.label
        save(name, root)
        return root
    }

    fun runningSession() {
        val t = 1_000L
        ChatSession.startTask("打开云音乐，搜索晴天并播放", t)
        listOf("意图：打开应用（模型）", "交给模型完成剩余目标：搜索晴天，播放", "—— 步骤 1/25 ——", "小目标：关闭开屏广告", "thought: 开屏广告挡住了首页，先跳过",
            "action: Tap(x=null, y=null, nodeId=e2, text=跳过 3)", "—— 步骤 2/25 ——", "小目标：进入搜索", "thought: 顶部搜索栏",
            "action: Tap(x=null, y=null, nodeId=e3, text=搜索)", "—— 步骤 3/25 ——", "thought: 输入歌名", "action: Type(text=晴天, nodeId=e1)",
            "—— 步骤 4/25 ——", "小目标：选择正确版本", "thought: 第一条是原唱", "action: Tap(x=null, y=null, nodeId=e4, text=晴天 - 周杰伦)")
            .forEachIndexed { i, l -> ChatSession.onLog(l, t + 600L * i) }
    }
}

class UiScreenshotTest : ShotBase() {
    @Test fun emptyState() {
        val root = main("01_main_empty_light", {}, running = false)
        val list = root.findViewById<LinearLayout>(R.id.chatList)
        assertEquals("empty", list.getChildAt(0).tag)
    }
    @Test fun runningTimeline() {
        val root = main("02_main_running_light", { runningSession() }, running = true)
        val list = root.findViewById<LinearLayout>(R.id.chatList)
        assertEquals("pill", list.getChildAt(list.childCount - 1).tag)
        assertTrue(list.childCount >= 6)
    }
    @Test fun confirmCard() {
        main("03_main_confirm_light", {
            ChatSession.startTask("给张三发消息说晚上见", 0)
            listOf("—— 步骤 1/25 ——", "thought: 进入会话", "action: Tap(x=null, y=null, nodeId=e2, text=张三)", "—— 步骤 2/25 ——",
                "action: Type(text=晚上见, nodeId=e5)", "—— 步骤 3/25 ——", "action: AskConfirm(reason=发送「晚上见」给张三, pendingAction=null)").forEachIndexed { i, l -> ChatSession.onLog(l, 700L * i) }
            ChatSession.onStatus("confirm", "发送「晚上见」给张三\n当前应用：信息")
        }, running = true)
    }
    @Test fun resultsAndQuestion() {
        main("04_main_results_light", {
            ChatSession.startTask("打开设置，把蓝牙打开", 0)
            listOf("—— 步骤 1/25 ——", "action: Tap(x=null, y=null, nodeId=e4, text=蓝牙)", "—— 步骤 2/25 ——", "action: Done(summary=蓝牙已开启)").forEachIndexed { i, l -> ChatSession.onLog(l, 900L * i) }
            ChatSession.onStatus("done", "结束(2步): 蓝牙已开启（已验收）", 2500)
            ChatSession.startTask("给他发个消息", 3000)
            listOf("—— 步骤 1/25 ——", "action: AskUser(question=要发给张三还是李四？发什么内容？)").forEachIndexed { i, l -> ChatSession.onLog(l, 3500L + 600 * i) }
            ChatSession.onStatus("stopped", "结束(1步): ${ChatSession.ASK_MARK}要发给张三还是李四？发什么内容？", 4500)
        }, running = false)
        assertEquals("给他发个消息（补充：张三，晚上见）", ChatSession.composeGoal("张三，晚上见"))
    }
    @Test fun settings() {
        val inner = settingsContent()
        save("05_settings_full_light", inner, h = null)
        assertNotNull(inner.findViewById<View>(R.id.modelCheckpoint))
    }
    @Test fun overlayPill() {
        val bg = FrameLayout(act).apply { setBackgroundColor(0xFFE9EEF5.toInt()) }
        val (pill, lbl) = OverlayPill.build(act, {}, { true })
        lbl.text = "第 4 步 · 点击「晴天 - 周杰伦」"
        bg.addView(pill, FrameLayout.LayoutParams(-2, -2).apply { topMargin = 300; leftMargin = 0 })
        save("06_overlay_pill", bg, h = 900)
    }
}

@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class UiScreenshotDarkTest : ShotBase() {
    @Test fun dark() {
        main("07_main_empty_dark", {}, running = false)
        ChatSession.clear()
        main("08_main_running_dark", { runningSession() }, running = true)
        save("09_settings_full_dark", settingsContent(), h = null)
    }
}

package com.runasakura.phoneagent.agent

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.runasakura.phoneagent.R
import com.runasakura.phoneagent.a11y.AgentAccessibilityService
import com.runasakura.phoneagent.ui.TsukiTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * On-the-job training mode (Grok Bot "learn from demonstration" counterpart on the phone).
 *
 * While active, a small floating coach bar rides above every app:「记录此步」snapshots the current package +
 * a11y/OCR labels (+ optional user note),「结束示范」hands the trajectory back to [TeachActivity] for LLM
 * compression into a [Recipe]. Games with an empty a11y tree still work — the user marks each key screen
 * themselves, the same way Grok Bot's teach recording relies on the user performing the steps.
 *
 * Single process-wide session; the coach bar uses the overlay permission (already required for the orb).
 */
object TeachSession {
    data class Step(
        val n: Int,
        val pkg: String,
        val labels: List<String>,
        val note: String?,
        val source: String,   // "a11y" | "ocr" | "none"
        val at: Long = System.currentTimeMillis(),
    ) {
        /** One line for the LLM / offline fallback. Pure. */
        fun line(): String = buildString {
            append("$n. 包=$pkg")
            if (labels.isNotEmpty()) append(" 可见[${source}]：" + labels.take(12).joinToString("、") { it.take(14) })
            if (!note.isNullOrBlank()) append(" 说明：$note")
        }
    }

    data class State(val goal: String, val pkgHint: String?, val steps: List<Step>)

    interface Listener {
        fun onStep(s: State)
        fun onFinished(s: State)
        fun onCancelled()
    }

    @Volatile private var goal: String = ""
    @Volatile private var pkgHint: String? = null
    private val steps = ArrayList<Step>()
    @Volatile private var listener: Listener? = null
    @Volatile private var noteSupplier: (() -> String?)? = null
    private var bar: android.view.View? = null
    private var wm: WindowManager? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val active get() = goal.isNotBlank()
    fun snapshot(): State? = if (!active) null else State(goal, pkgHint, steps.toList())
    fun trace(): String = steps.joinToString("\n") { it.line() }

    /**
     * Start coaching. [noteSupplier] is read each time the user marks a step (e.g. the last line of an EditText).
     * Returns false when the overlay permission is missing (caller should open the system sheet).
     */
    fun start(ctx: Context, goal: String, pkgHint: String?, noteSupplier: (() -> String?)?, listener: Listener): Boolean {
        if (goal.trim().length < 2) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) return false
        stop(cancel = true)
        this.goal = goal.trim(); this.pkgHint = pkgHint?.trim()?.ifBlank { null }
        this.noteSupplier = noteSupplier; this.listener = listener; steps.clear()
        showBar(ctx.applicationContext)
        return true
    }

    fun cancel(ctx: Context) { stop(cancel = true); Toast.makeText(ctx, "已取消示范", Toast.LENGTH_SHORT).show() }

    fun finish() {
        val s = snapshot() ?: return
        val l = listener
        val ctx = bar?.context
        stop(cancel = false)
        when {
            s.steps.isEmpty() -> l?.onCancelled()
            l != null -> try { l.onFinished(s) } catch (_: Throwable) { handOff(ctx, s) }
            else -> handOff(ctx, s)
        }
    }

    /** Activity was killed while the user demonstrated: reopen Teach with the captured trace. */
    private fun handOff(ctx: android.content.Context?, s: State) {
        val c = ctx?.applicationContext ?: return
        c.startActivity(android.content.Intent(c, com.runasakura.phoneagent.ui.TeachActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(com.runasakura.phoneagent.ui.TeachActivity.EXTRA_GOAL, s.goal)
            .putExtra(com.runasakura.phoneagent.ui.TeachActivity.EXTRA_TRACE, s.steps.joinToString("\n") { it.line() })
            .putExtra(com.runasakura.phoneagent.ui.TeachActivity.EXTRA_PKG, dominantPkg(s.steps) ?: s.pkgHint))
    }

    /** Capture one step from whatever is currently on screen (main-thread entry; work hops to Default). */
    fun mark(ctx: Context) {
        if (!active) return
        scope.launch {
            val step = withContext(Dispatchers.Default) { capture(ctx) } ?: run {
                Toast.makeText(ctx, "请先打开要教的应用", Toast.LENGTH_LONG).show(); return@launch
            }
            steps += step
            if (pkgHint.isNullOrBlank()) pkgHint = step.pkg
            refreshBar()
            listener?.onStep(State(goal, pkgHint, steps.toList()))
            Toast.makeText(ctx, "上岗第 ${step.n} 步已记下", Toast.LENGTH_SHORT).show()
        }
    }

    private fun capture(ctx: Context): Step? {
        val a11y = AgentAccessibilityService.require()
        val pkg = a11y?.foregroundPackage()?.takeIf { it.isNotBlank() && !AppResolver.isAgentOrBrain(it) }
            ?: pkgHint
        if (pkg.isNullOrBlank()) return null
        var source = "none"; var labels = emptyList<String>()
        runCatching {
            a11y?.let { svc ->
                val phone = com.runasakura.phoneagent.a11y.ServicePhone(svc)
                val items = phone.screenItems()
                if (items.isNotEmpty()) {
                    labels = items.map { it.label }.filter { it.isNotBlank() }.distinct().take(16)
                    // OCR-sourced items use synthetic ids like "o1"; tree nodes use view ids / "#n".
                    source = if (items.all { it.id.startsWith("o") || it.id.isBlank() }) "ocr" else "a11y"
                }
            }
        }
        return Step(steps.size + 1, pkg, labels, noteSupplier?.invoke()?.trim()?.takeIf { it.isNotBlank() }?.take(40), source)
    }

    private fun stop(cancel: Boolean) {
        hideBar()
        val l = listener
        goal = ""; pkgHint = null; listener = null; noteSupplier = null; steps.clear()
        if (cancel) l?.onCancelled()
    }

    private fun showBar(ctx: Context) {
        val k = TsukiTheme.current(ctx)
        val dens = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * dens).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat(); setColor(k.surface); setStroke(dp(1), k.outline)
            }
            elevation = dp(8).toFloat()
        }
        val title = TextView(ctx).apply {
            textSize = 13f; setTextColor(k.onSurface); paint.isFakeBoldText = true
            tag = "title"
        }
        box.addView(title)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun b(t: String, on: () -> Unit) = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = t; textSize = 12f; minHeight = dp(40); minimumWidth = dp(72)
            setOnClickListener { on() }
        }
        row.addView(b("记录此步") { mark(ctx) })
        row.addView(b("结束示范") { finish() })
        row.addView(b("取消") { cancel(ctx) })
        box.addView(row)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(48) }
        val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { w.addView(box, lp) }.onFailure { Toast.makeText(ctx, "无法显示上岗教练条（需要悬浮窗权限）", Toast.LENGTH_LONG).show(); return }
        wm = w; bar = box; refreshBar()
    }

    private fun refreshBar() {
        val box = bar as? LinearLayout ?: return
        val title = box.findViewWithTag<TextView>("title") ?: return
        title.text = "上岗中 · ${goal.take(18)}" + if (steps.isEmpty()) " · 操作目标应用后点「记录此步」" else " · 已记 ${steps.size} 步"
    }

    private fun hideBar() {
        val v = bar; val w = wm; bar = null; wm = null
        if (v != null && w != null) runCatching { w.removeView(v) }
    }

    /** Pure: pick the dominant package from recorded steps (the one that appears most after step 1). */
    fun dominantPkg(steps: List<Step>): String? {
        if (steps.isEmpty()) return null
        return steps.groupingBy { it.pkg }.eachCount().maxByOrNull { it.value }?.key
    }
}

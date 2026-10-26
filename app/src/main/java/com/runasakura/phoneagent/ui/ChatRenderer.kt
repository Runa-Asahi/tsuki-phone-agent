package com.runasakura.phoneagent.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.runasakura.phoneagent.R

/** Which icon a step gets, from its (Chinese) title. Pure: unit-tested. */
object ToolIcon {
    enum class Kind { THINK, TAP, TYPE, SWIPE, BACK, HOME, LAUNCH, WAIT, DONE, FAIL, CONFIRM, COPY, SEARCH, LOOK, ASK }
    fun of(title: String): Kind {
        val t = title.substringAfter(" · ", title)
        return when {
            t.startsWith("长按") || t.startsWith("点击") || t.startsWith("双击") -> Kind.TAP
            t.startsWith("输入") || t.startsWith("键盘") -> Kind.TYPE
            t.startsWith("滚动查找") || t.contains("搜索") -> Kind.SEARCH
            t.startsWith("滑动") -> Kind.SWIPE
            t.startsWith("返回") -> Kind.BACK
            t.startsWith("回到桌面") || t.startsWith("桌面") -> Kind.HOME
            t.startsWith("打开") || t.startsWith("匹配应用") || t.startsWith("启动") || t.contains("通知栏") || t.contains("快捷设置") -> Kind.LAUNCH
            t.startsWith("等待") -> Kind.WAIT
            t.startsWith("完成") -> Kind.DONE
            t.startsWith("无法继续") -> Kind.FAIL
            t.startsWith("请求确认") -> Kind.CONFIRM
            t.startsWith("复制") || t.startsWith("粘贴") || t.contains("剪贴板") -> Kind.COPY
            t.startsWith("看图") || t.startsWith("截屏") -> Kind.LOOK
            t.startsWith("问你") -> Kind.ASK
            else -> Kind.THINK
        }
    }
    fun drawable(k: Kind): Int = when (k) {
        Kind.THINK -> R.drawable.ic_tool_think; Kind.TAP -> R.drawable.ic_tool_tap; Kind.TYPE -> R.drawable.ic_tool_type
        Kind.SWIPE -> R.drawable.ic_tool_swipe; Kind.BACK -> R.drawable.ic_tool_back; Kind.HOME -> R.drawable.ic_tool_home
        Kind.LAUNCH -> R.drawable.ic_tool_launch; Kind.WAIT -> R.drawable.ic_tool_wait; Kind.DONE -> R.drawable.ic_tool_done
        Kind.FAIL -> R.drawable.ic_tool_fail; Kind.CONFIRM -> R.drawable.ic_tool_confirm; Kind.COPY -> R.drawable.ic_tool_copy
        Kind.SEARCH -> R.drawable.ic_tool_search; Kind.LOOK -> R.drawable.ic_tool_look; Kind.ASK -> R.drawable.ic_tool_ask
    }
}

/**
 * Builds the conversation (Codex / Grok style) as plain Views: user bubbles, a plan card, a step timeline with one
 * icon per tool and collapsible thought/details, confirm / result / question cards, and an empty state with
 * suggested tasks. Keyed by item id: unchanged items keep their views, new ones fade in (no full re-inflate).
 */
class ChatRenderer(
    private val ctx: Context,
    private val onConfirm: (Boolean) -> Unit,
    private val onRetry: (String) -> Unit,
    private val onPick: (String) -> Unit,
    private val onToggle: (Long) -> Unit,
    /** false in screenshot tests (no infinite animators). */
    private val animate: Boolean = true,
) {
    private val views = HashMap<Long, Pair<String, View>>()

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    fun color(id: Int) = ContextCompat.getColor(ctx, id)
    fun round(bg: Int, r: Int = 16, stroke: Int? = null) = GradientDrawable().apply {
        setColor(bg); cornerRadius = dp(r).toFloat(); stroke?.let { setStroke(dp(1), it) }
    }
    fun text(s: CharSequence, size: Float = 14f, c: Int = R.color.text_primary, bold: Boolean = false) = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(color(c)); setLineSpacing(0f, 1.15f)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun lp(top: Int = 0, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, gravity: Int = Gravity.START) =
        LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top); this.gravity = gravity }

    /** Round tinted icon badge. */
    fun badge(icon: Int, fg: Int, bg: Int, size: Int = 28) = FrameLayout(ctx).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(bg)) }
        addView(ImageView(ctx).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(color(fg)) },
            FrameLayout.LayoutParams(dp(size * 4 / 7), dp(size * 4 / 7), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    fun render(list: LinearLayout, items: List<ChatSession.Item>, phase: ChatSession.Phase,
               recent: List<Pair<String, Boolean>>, suggestions: List<String>) {
        if (items.isEmpty()) { views.clear(); list.removeAllViews(); list.addView(emptyState(recent, suggestions)); return }
        if (list.childCount > 0 && list.getChildAt(0).tag == "empty") list.removeAllViews()
        val wanted = ArrayList<View>()
        for ((i, it) in items.withIndex()) {
            // The timeline connector only links a step to a following step.
            val connects = items.getOrNull(i + 1) is ChatSession.Item.Step
            val sig = it.toString() + (if (it is ChatSession.Item.Step) "|${it.expanded}|$connects" else "")
            val old = views[it.id]
            val v = if (old != null && old.first == sig) old.second else build(it, !connects).also { nv ->
                if (old == null && animate) { nv.alpha = 0f; nv.translationY = dp(8).toFloat(); nv.animate().alpha(1f).translationY(0f).setDuration(220).start() }
            }
            views[it.id] = sig to v; wanted += v
        }
        if (phase != ChatSession.Phase.IDLE) wanted += livePill(phase)
        val keep = items.map { it.id }.toSet(); views.keys.retainAll(keep)
        // Re-attach in order, reusing views.
        list.removeAllViews()
        wanted.forEach { (it.parent as? ViewGroup)?.removeView(it); list.addView(it) }
    }

    private fun build(item: ChatSession.Item, lastStep: Boolean): View = when (item) {
        is ChatSession.Item.User -> userBubble(item)
        is ChatSession.Item.Plan -> planCard(item)
        is ChatSession.Item.Step -> stepRow(item, lastStep)
        is ChatSession.Item.Confirm -> confirmCard(item)
        is ChatSession.Item.Result -> resultCard(item)
        is ChatSession.Item.Note -> text(item.text, 11f, R.color.text_tertiary).apply { layoutParams = lp(6); setPadding(dp(40), 0, 0, 0) }
    }

    private fun userBubble(u: ChatSession.Item.User) = text(u.text, 15f, R.color.user_text).apply {
        background = GradientDrawable().apply {
            setColor(color(R.color.user_bubble)); val r = dp(20).toFloat(); val s = dp(6).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, s, s, r, r)
        }
        setPadding(dp(16), dp(10), dp(16), dp(10)); maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.78).toInt()
        layoutParams = lp(18, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END)
    }

    private fun planCard(p: ChatSession.Item.Plan) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = round(color(R.color.surface_variant), 16); setPadding(dp(14), dp(12), dp(14), dp(12))
        val head = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(badge(R.drawable.ic_tool_think, R.color.insight, R.color.card_bg, 22))
        head.addView(text("计划", 13f, R.color.text_secondary, bold = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        addView(head)
        p.lines.takeLast(4).forEach { l -> addView(text(l.replace("：", "：\u200B"), 13f, R.color.text_primary).apply { maxLines = 3 }, lp(6)) }
        layoutParams = lp(12)
    }

    private fun stepRow(s: ChatSession.Item.Step, lastStep: Boolean): View {
        val kind = ToolIcon.of(s.title)
        val (fg, bg) = when (s.state) {
            ChatSession.State.RUNNING -> R.color.primary to R.color.primary_container
            ChatSession.State.OK -> R.color.text_secondary to R.color.surface_variant
            ChatSession.State.FAIL -> R.color.err_red to R.color.err_bg
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = lp(0) }
        // Rail: icon + connector line (the timeline).
        val rail = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val b = badge(ToolIcon.drawable(if (s.state == ChatSession.State.FAIL) ToolIcon.Kind.FAIL else kind), fg, bg)
        rail.addView(b)
        if (!lastStep) rail.addView(View(ctx).apply { setBackgroundColor(color(R.color.outline)) }, LinearLayout.LayoutParams(dp(2), 0, 1f).apply { topMargin = dp(2) })
        row.addView(rail, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = dp(8) })
        if (s.state == ChatSession.State.RUNNING && animate) ObjectAnimator.ofFloat(b, View.ALPHA, 1f, 0.45f).apply {
            duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start() }

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), 0, dp(10)) }
        val head = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val (num, act) = s.title.substringBefore(" · ") to s.title.substringAfter(" · ", "")
        head.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(act.ifBlank { num }, 14f, if (s.state == ChatSession.State.FAIL) R.color.err_red else R.color.text_primary, bold = true).apply { maxLines = 2 })
            if (act.isNotBlank()) addView(text(num, 11f, R.color.text_tertiary))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val dur = if (s.endMs != null) "%.1fs".format(s.durationMs / 1000.0) else ""
        head.addView(text(dur, 12f, R.color.text_tertiary))
        if (s.details.isNotEmpty()) head.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_chevron); imageTintList = ColorStateList.valueOf(color(R.color.text_tertiary))
            rotation = if (s.expanded) 90f else 0f
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(4) })
        body.addView(head)
        s.insight?.let { body.addView(text("→ $it", 12f, R.color.insight), lp(3)) }
        val thought = s.details.lastOrNull { it.startsWith("想法：") }?.substringAfter("想法：")
        if (!s.expanded) thought?.takeIf { it.isNotBlank() && it.length > 1 }?.let {
            body.addView(text(it, 12.5f, R.color.text_secondary).apply { maxLines = 2; setTypeface(typeface, Typeface.ITALIC) }, lp(4))
        }
        if (s.expanded) body.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = round(color(R.color.surface_variant), 12); setPadding(dp(10), dp(8), dp(10), dp(8))
            s.details.takeLast(30).forEach { d -> addView(text(d, 11.5f, if (d.startsWith("✗") || d.contains("错误")) R.color.err_red else R.color.text_secondary).apply {
                typeface = if (d.startsWith("想法：")) Typeface.DEFAULT else Typeface.MONOSPACE; setTextIsSelectable(true) }, lp(2)) }
        }, lp(6))
        row.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
        row.setOnClickListener { onToggle(s.id) }
        return row
    }

    private fun confirmCard(c: ChatSession.Item.Confirm) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = round(color(R.color.confirm_bg), 20, color(R.color.confirm_stroke)); setPadding(dp(16), dp(14), dp(16), dp(14))
        val head = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(badge(R.drawable.ic_tool_confirm, R.color.warn_amber, R.color.card_bg, 30))
        head.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("需要你确认", 15f, R.color.text_primary, bold = true))
            addView(text("确认前不会发出、支付或删除任何东西", 12f, R.color.text_secondary))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        addView(head)
        addView(text(c.reason, 15f).apply { background = round(color(R.color.card_bg), 12); setPadding(dp(12), dp(10), dp(12), dp(10)) }, lp(12))
        if (c.answer == null) {
            val row = LinearLayout(ctx)
            row.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = ctx.getString(R.string.confirm_no); cornerRadius = dp(14); setOnClickListener { onConfirm(false) } }, LinearLayout.LayoutParams(0, dp(48), 1f))
            row.addView(MaterialButton(ctx).apply {
                text = ctx.getString(R.string.confirm_yes); cornerRadius = dp(14); setOnClickListener { onConfirm(true) } },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(10) })
            addView(row, lp(12))
        } else addView(text(if (c.answer == true) "✓ 已允许" else "✕ 已拒绝", 13f, if (c.answer == true) R.color.ok_green else R.color.err_red, bold = true), lp(10))
        layoutParams = lp(12)
    }

    private fun resultCard(r: ChatSession.Item.Result) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val ask = r.ask
        val (bg, fg, icon, title) = when {
            ask -> Quad(R.color.ask_bg, R.color.primary, R.drawable.ic_tool_ask, "需要你补充")
            r.ok -> Quad(R.color.ok_bg, R.color.ok_green, R.drawable.ic_tool_done, "已完成")
            else -> Quad(R.color.err_bg, R.color.err_red, R.drawable.ic_tool_fail, "未完成")
        }
        background = round(color(bg), 20); setPadding(dp(16), dp(14), dp(16), dp(14))
        val head = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(badge(icon, fg, R.color.card_bg, 30))
        head.addView(text(title, 15f, fg, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        addView(head)
        addView(text(r.summary.removePrefix("❓").trim(), 15f), lp(10))
        if (ask) addView(text("直接在下方输入框回复，我会接着做。", 12.5f, R.color.text_secondary), lp(6))
        else r.hint?.let { addView(text(it, 12.5f, R.color.text_secondary), lp(6)) }
        if (!r.ok && !ask && r.goal.isNotBlank()) addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "重试"; cornerRadius = dp(14); setOnClickListener { onRetry(r.goal) } }, lp(10, ViewGroup.LayoutParams.WRAP_CONTENT))
        layoutParams = lp(14)
    }
    private data class Quad(val a: Int, val b: Int, val c: Int, val d: String)

    /** Live status pill under the timeline while a task runs. */
    fun livePill(phase: ChatSession.Phase) = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL; tag = "pill"
        background = round(color(R.color.surface_variant), 20); setPadding(dp(12), dp(6), dp(14), dp(6))
        val dot = View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(
            if (phase == ChatSession.Phase.CONFIRM || phase == ChatSession.Phase.PAUSED) R.color.warn_amber else R.color.primary)) } }
        addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)))
        if (animate) ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.2f).apply { duration = 600; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start() }
        addView(text(phase.label + "…", 12.5f, R.color.text_secondary, bold = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        layoutParams = lp(12, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(38) }
    }

    private fun emptyState(recent: List<Pair<String, Boolean>>, suggestions: List<String>) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; tag = "empty"; setPadding(dp(4), dp(28), dp(4), dp(8))
        addView(badge(R.drawable.ic_tool_think, R.color.on_primary, R.color.primary, 48))
        addView(text("今天想让我做什么？", 24f, bold = true), lp(16))
        addView(text("用一句话描述目标，我会一步步操作手机，并实时展示每一步。发送、通话、支付、闹钟等操作一定会先问你。", 14f, R.color.text_secondary), lp(8))
        addView(text("试试这些", 13f, R.color.text_tertiary, bold = true), lp(22))
        suggestions.take(4).chunked(2).forEach { pair ->
            val row = LinearLayout(ctx)
            pair.forEachIndexed { i, s -> row.addView(suggestionCard(s), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i == 1) marginStart = dp(10) }) }
            if (pair.size == 1) row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(10) })
            addView(row, lp(10))
        }
        if (recent.isNotEmpty()) {
            addView(text("最近", 13f, R.color.text_tertiary, bold = true), lp(22))
            recent.take(3).forEach { (g, ok) ->
                addView(LinearLayout(ctx).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8))
                    addView(badge(if (ok) R.drawable.ic_tool_done else R.drawable.ic_tool_fail, if (ok) R.color.ok_green else R.color.err_red, R.color.surface_variant, 24))
                    addView(text(g, 14f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
                    setOnClickListener { onPick(g) }
                }, lp(2))
            }
        }
    }

    private fun suggestionCard(s: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = round(color(R.color.card_bg), 16, color(R.color.outline)); setPadding(dp(14), dp(12), dp(14), dp(14))
        addView(ImageView(ctx).apply { setImageResource(ToolIcon.drawable(ToolIcon.of(s).let { if (it == ToolIcon.Kind.THINK) ToolIcon.Kind.LAUNCH else it }))
            imageTintList = ColorStateList.valueOf(color(R.color.primary)) }, LinearLayout.LayoutParams(dp(20), dp(20)))
        addView(text(s, 13.5f).apply { maxLines = 3 }, lp(10))
        isClickable = true; isFocusable = true
        foreground = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let { a -> a.getDrawable(0).also { a.recycle() } }
        setOnClickListener { onPick(s) }
    }
}

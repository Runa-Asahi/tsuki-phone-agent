package com.runasakura.phoneagent.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate

/**
 * Tsuki look: three palettes (Night Moon default, Sakura, Pure Moonlight) × light/dark (or follow system).
 * Every colour role (sky, surfaces, text, bubbles, buttons, particles) comes from the palette; views inflated
 * with static @color values are retinted by [retint]. Stored in the "ui" prefs; no network, no secrets.
 */
object TsukiTheme {
    const val PERSONA = "Tsuki"

    enum class Palette(val label: String) { NIGHT_MOON("夜月"), SAKURA("樱"), PURE_MOON("纯月") }
    enum class Mode(val label: String, val night: Int) {
        SYSTEM("跟随系统", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM), DARK("深色", AppCompatDelegate.MODE_NIGHT_YES), LIGHT("浅色", AppCompatDelegate.MODE_NIGHT_NO)
    }

    enum class Particle { MOTES, PETALS, SPARKLES }

    /** All colours are ARGB ints. Every role is palette-driven so switching palettes changes the whole screen. */
    data class Colors(
        val accent: Int, val onAccent: Int, val accentContainer: Int, val skyTop: Int, val skyBottom: Int,
        val glow: Int, val star: Int, val crimson: Int, val gold: Int, val bubble: Int, val onBubble: Int,
        val bg: Int, val surface: Int, val surfaceVariant: Int, val outline: Int,
        val onSurface: Int, val text2: Int, val text3: Int, val particle: Particle, val fullMoon: Boolean = false,
    ) {
        /** Palette value for a colour-resource name, or null when that role stays static (ok/err/warn…). */
        fun role(name: String): Int? = when (name) {
            "primary", "step_accent" -> accent
            "on_primary" -> onAccent
            "primary_container", "ask_bg" -> accentContainer
            "on_primary_container", "text_primary", "log_text" -> onSurface
            "bg" -> bg
            "card_bg" -> surface
            "surface_variant", "log_bg" -> surfaceVariant
            "outline" -> outline
            "text_secondary" -> text2
            "text_tertiary" -> text3
            "user_bubble" -> bubble
            "user_text" -> onBubble
            "crimson", "stop_red" -> crimson
            "moon_gold", "insight" -> gold
            "sky_top" -> skyTop
            "sky_bottom" -> skyBottom
            "moon_glow" -> glow
            "star" -> star
            else -> null
        }
    }

    private fun c(hex: Long) = hex.toInt()

    /** 纯月 is a bright pearl theme: it is always light (see [effectiveNight]). */
    fun colors(p: Palette, dark: Boolean): Colors = when (p) {
        // deep navy → silver mist, silver + crimson accents, drifting silver motes, crescent
        Palette.NIGHT_MOON -> if (dark) Colors(c(0xFFDCE2F2), c(0xFF0E142C), c(0xFF27326A), c(0xFF070C26), c(0xFF2C3560), c(0x66BFCBEA), c(0xDDF1F4FF), c(0xFFF06A7C), c(0xFFC9D3EE), c(0xFF2E3B74), c(0xFFF4F6FF),
                c(0xFF0A1030), c(0xEE141D45), c(0xFF1D2854), c(0xFF35417C), c(0xFFEEF1FA), c(0xFFB2BAD6), c(0xFF8A93B6), Particle.MOTES)
            else Colors(c(0xFF233062), c(0xFFFFFFFF), c(0xFFDDE3F3), c(0xFFBFC9E4), c(0xFFF1F4FB), c(0x664F5F96), c(0x99707DA6), c(0xFFB8233A), c(0xFF5E6788), c(0xFF233062), c(0xFFF4F6FF),
                c(0xFFF1F4FB), c(0xFFFFFFFF), c(0xFFE4E9F4), c(0xFFD0D7EA), c(0xFF101836), c(0xFF4A5576), c(0xFF6E7898), Particle.MOTES)
        // sakura pink / plum night, falling petals
        Palette.SAKURA -> if (dark) Colors(c(0xFFF6A9C6), c(0xFF3A0F24), c(0xFF5C2A55), c(0xFF22091F), c(0xFF6E3060), c(0x77F7B6CF), c(0xCCFFE3EE), c(0xFFFF7F98), c(0xFFF3C98B), c(0xFF7E3463), c(0xFFFFF5F9),
                c(0xFF24102A), c(0xEE351A3E), c(0xFF45244F), c(0xFF6C3B72), c(0xFFF8EAF2), c(0xFFE1BACD), c(0xFFB88EA6), Particle.PETALS)
            else Colors(c(0xFFB0346A), c(0xFFFFFFFF), c(0xFFFAD9E7), c(0xFFF7C6DB), c(0xFFFFF4F8), c(0x77F08DB4), c(0x99D98AAD), c(0xFFB8233A), c(0xFF9A5B2E), c(0xFFB0346A), c(0xFFFFF6FA),
                c(0xFFFFF3F8), c(0xFFFFFFFF), c(0xFFF8E1EC), c(0xFFEDC5D8), c(0xFF3A1430), c(0xFF7A4064), c(0xFF94667F), Particle.PETALS)
        // bright moonlight pearl, soft silver-blue, full moon + sparkles; light-only
        Palette.PURE_MOON -> Colors(c(0xFF3F5F94), c(0xFFFFFFFF), c(0xFFE2EAF6), c(0xFFFFFFFF), c(0xFFDCE6F4), c(0x88A9C3E6), c(0xAA9DB4D6), c(0xFFB8233A), c(0xFF8A6A2A), c(0xFFDCE7F7), c(0xFF1C2740),
                c(0xFFFBFCFF), c(0xFFFFFFFF), c(0xFFEDF1F8), c(0xFFD9E1EE), c(0xFF1C2433), c(0xFF515C72), c(0xFF6F7990), Particle.SPARKLES, fullMoon = true)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ui", 0)
    fun palette(ctx: Context) = runCatching { Palette.valueOf(prefs(ctx).getString("palette", null) ?: "") }.getOrDefault(Palette.NIGHT_MOON)
    fun mode(ctx: Context) = runCatching { Mode.valueOf(prefs(ctx).getString("mode", null) ?: "") }.getOrDefault(Mode.SYSTEM)
    fun setPalette(ctx: Context, p: Palette) { prefs(ctx).edit().putString("palette", p.name).apply(); applyNight(ctx) }
    fun setMode(ctx: Context, m: Mode) { prefs(ctx).edit().putString("mode", m.name).apply(); applyNight(ctx) }
    /** Night mode actually used: 纯月 is always light; the others follow the chosen mode. Pure. */
    fun effectiveNight(p: Palette, m: Mode) = if (p == Palette.PURE_MOON) AppCompatDelegate.MODE_NIGHT_NO else m.night
    private fun applyNight(ctx: Context) {
        val n = effectiveNight(palette(ctx), mode(ctx)); if (AppCompatDelegate.getDefaultNightMode() != n) AppCompatDelegate.setDefaultNightMode(n)
    }

    fun isDark(ctx: Context) = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    fun current(ctx: Context) = colors(palette(ctx), isDark(ctx))

    /** Colour for a resource id under the current palette (falls back to the static resource). */
    fun color(ctx: Context, id: Int): Int =
        runCatching { current(ctx).role(ctx.resources.getResourceEntryName(id)) }.getOrNull() ?: androidx.core.content.ContextCompat.getColor(ctx, id)

    /**
     * Retint an inflated view tree: any text colour, hint, background shape/colour or image tint that equals a
     * static palette-role resource is swapped to the palette's value. The original is remembered in a tag, so
     * this is idempotent and can be re-run after every palette switch (live, no recreate).
     */
    fun retint(root: android.view.View) {
        val ctx = root.context; val k = current(ctx)
        val names = listOf("bg", "card_bg", "surface_variant", "outline", "text_primary", "text_secondary", "text_tertiary", "primary", "on_primary",
            "primary_container", "on_primary_container", "user_bubble", "user_text", "ask_bg", "insight", "log_bg", "log_text", "crimson", "moon_gold")
        val rev = HashMap<Int, String>()
        for (n in names) { val id = ctx.resources.getIdentifier(n, "color", ctx.packageName); if (id != 0) rev.putIfAbsent(androidx.core.content.ContextCompat.getColor(ctx, id), n) }
        fun map(orig: Int): Int? = rev[orig]?.let { k.role(it) }
        val tagKey = com.runasakura.phoneagent.R.id.tsuki_orig
        fun walk(v: android.view.View) {
            @Suppress("UNCHECKED_CAST") val o = (v.getTag(tagKey) as? IntArray) ?: IntArray(3) { 1 }.also { arr ->
                if (v is android.widget.TextView) { arr[0] = v.currentTextColor; arr[1] = v.currentHintTextColor }
                arr[2] = when (val d = v.background) { is android.graphics.drawable.ColorDrawable -> d.color
                    is android.graphics.drawable.GradientDrawable -> d.color?.defaultColor ?: 1; else -> 1 }
                v.setTag(tagKey, arr)
            }
            if (v is android.widget.TextView) { map(o[0])?.let(v::setTextColor); map(o[1])?.let(v::setHintTextColor) }
            map(o[2])?.let { nc -> when (val d = v.background) { is android.graphics.drawable.ColorDrawable -> v.setBackgroundColor(nc)
                is android.graphics.drawable.GradientDrawable -> d.mutate().let { (it as android.graphics.drawable.GradientDrawable).setColor(nc) } } }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    /** Call from Application.onCreate (night mode must be set before the first Activity inflates). */
    fun init(ctx: Context) { AppCompatDelegate.setDefaultNightMode(effectiveNight(palette(ctx), mode(ctx))) }

    /** Status/navigation bars follow the sky. */
    fun applyBars(a: Activity) {
        val k = current(a)
        @Suppress("DEPRECATION") run { a.window.statusBarColor = k.skyTop; a.window.navigationBarColor = k.skyBottom }
        a.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(k.bg))
    }

    /** "Reduce motion": system animator scale 0 (developer option / accessibility 'remove animations'). */
    fun reduceMotion(ctx: Context): Boolean =
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

    /** Time-of-day greeting from the persona. Pure: unit-tested. */
    fun greeting(hour: Int): String = when (hour) {
        in 5..10 -> "早上好"
        in 11..13 -> "中午好"
        in 14..17 -> "下午好"
        in 18..22 -> "晚上好"
        else -> "夜深了"
    }

    /** Moon phase 0..1 (0 = new, 0.5 = full) for a frame index; drives the loading indicator. Pure. */
    fun phase(frame: Long, period: Int = 48): Float = (frame % period).toFloat() / period
}

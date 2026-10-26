package com.runasakura.phoneagent.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate

/**
 * Tsuki look: three palettes (Night Moon default, Sakura, Pure Moonlight) × light/dark (or follow system).
 * Palette colours are applied in code (sky background, accents, buttons), the neutral surfaces come from
 * values/values-night colour resources. Stored in the "ui" prefs; no network, no secrets.
 */
object TsukiTheme {
    const val PERSONA = "Tsuki"

    enum class Palette(val label: String) { NIGHT_MOON("夜月"), SAKURA("樱"), PURE_MOON("纯月") }
    enum class Mode(val label: String, val night: Int) {
        SYSTEM("跟随系统", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM), DARK("深色", AppCompatDelegate.MODE_NIGHT_YES), LIGHT("浅色", AppCompatDelegate.MODE_NIGHT_NO)
    }

    /** All colours are ARGB ints. */
    data class Colors(
        val accent: Int, val onAccent: Int, val accentContainer: Int, val skyTop: Int, val skyBottom: Int,
        val glow: Int, val star: Int, val crimson: Int, val gold: Int, val bubble: Int, val onBubble: Int,
    )

    private fun c(hex: Long) = hex.toInt()

    fun colors(p: Palette, dark: Boolean): Colors = when (p) {
        Palette.NIGHT_MOON -> if (dark) Colors(c(0xFFDCE1F0), c(0xFF0E142C), c(0xFF26305A), c(0xFF070B1E), c(0xFF2A3358), c(0x55E6C77A), c(0xCCF4F1E4), c(0xFFE0475B), c(0xFFE6C77A), c(0xFF2B3666), c(0xFFF4F5FA))
            else Colors(c(0xFF27325E), c(0xFFFFFFFF), c(0xFFE3E6F2), c(0xFFDCE1F0), c(0xFFF8F8FC), c(0x55C9A45C), c(0x99A9B3D1), c(0xFFC0283E), c(0xFFB8913F), c(0xFF27325E), c(0xFFF4F5FA))
        Palette.SAKURA -> if (dark) Colors(c(0xFFF2A7C3), c(0xFF2A0F1C), c(0xFF46284A), c(0xFF140C24), c(0xFF3D2A4D), c(0x55F7C6D9), c(0xCCFBE3EC), c(0xFFE0475B), c(0xFFE6C77A), c(0xFF4A2B4E), c(0xFFFBEFF4))
            else Colors(c(0xFFAE3F69), c(0xFFFFFFFF), c(0xFFF8E1EA), c(0xFFF6E3EC), c(0xFFFFFAFC), c(0x55F2A7C3), c(0x99D9A3B8), c(0xFFC0283E), c(0xFFB8913F), c(0xFFAE3F69), c(0xFFFFF6FA))
        Palette.PURE_MOON -> if (dark) Colors(c(0xFFF2F2F6), c(0xFF15161B), c(0xFF2C2D35), c(0xFF0E0F13), c(0xFF2B2C33), c(0x44FFFFFF), c(0xCCFFFFFF), c(0xFFE0475B), c(0xFFE6C77A), c(0xFF2C2D35), c(0xFFF2F2F6))
            else Colors(c(0xFF33364A), c(0xFFFFFFFF), c(0xFFECECF2), c(0xFFEDEFF4), c(0xFFFFFFFF), c(0x44B9BCCB), c(0x99B9BCCB), c(0xFFC0283E), c(0xFFB8913F), c(0xFF33364A), c(0xFFF7F7FA))
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ui", 0)
    fun palette(ctx: Context) = runCatching { Palette.valueOf(prefs(ctx).getString("palette", null) ?: "") }.getOrDefault(Palette.NIGHT_MOON)
    fun mode(ctx: Context) = runCatching { Mode.valueOf(prefs(ctx).getString("mode", null) ?: "") }.getOrDefault(Mode.SYSTEM)
    fun setPalette(ctx: Context, p: Palette) = prefs(ctx).edit().putString("palette", p.name).apply()
    fun setMode(ctx: Context, m: Mode) { prefs(ctx).edit().putString("mode", m.name).apply(); AppCompatDelegate.setDefaultNightMode(m.night) }

    fun isDark(ctx: Context) = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    fun current(ctx: Context) = colors(palette(ctx), isDark(ctx))

    /** Call from Application.onCreate (night mode must be set before the first Activity inflates). */
    fun init(ctx: Context) { AppCompatDelegate.setDefaultNightMode(mode(ctx).night) }

    /** Status/navigation bars follow the sky. */
    fun applyBars(a: Activity) {
        val k = current(a)
        @Suppress("DEPRECATION") run { a.window.statusBarColor = k.skyTop; a.window.navigationBarColor = k.skyBottom }
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

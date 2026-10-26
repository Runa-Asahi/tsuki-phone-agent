package com.runasakura.phoneagent.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TsukiThemeTest {
    private fun lum(c: Int): Double {
        fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(c shr 16 and 255) + 0.7152 * ch(c shr 8 and 255) + 0.0722 * ch(c and 255)
    }
    private fun contrast(a: Int, b: Int): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }

    @Test fun everyPaletteHasReadableAccentAndBubble() {
        for (p in TsukiTheme.Palette.entries) for (dark in listOf(true, false)) {
            val k = TsukiTheme.colors(p, dark)
            assertTrue("$p dark=$dark accent", contrast(k.accent, k.onAccent) >= 4.5)
            assertTrue("$p dark=$dark bubble", contrast(k.bubble, k.onBubble) >= 4.5)
            assertTrue("$p dark=$dark crimson on sky", contrast(k.crimson, k.skyBottom) >= 3.0)
        }
    }

    @Test fun everyPaletteHasReadableSurfaces() {
        for (p in TsukiTheme.Palette.entries) for (dark in listOf(true, false)) {
            val k = TsukiTheme.colors(p, dark); val tag = "$p dark=$dark"
            for (bg in listOf(k.bg, k.surfaceVariant, k.accentContainer, k.skyBottom)) {
                assertTrue("$tag text on ${Integer.toHexString(bg)}", contrast(k.onSurface, bg) >= 7.0)
                assertTrue("$tag text2", contrast(k.text2, bg) >= 4.5)
            }
            assertTrue("$tag text3", contrast(k.text3, k.bg) >= 3.0)
            assertTrue("$tag accent on bg", contrast(k.accent, k.bg) >= 3.0)
        }
    }

    /** The bug Runa saw: switching palettes looked identical. Every pair must differ visibly in bg, sky, accent and particles. */
    @Test fun palettesDifferMaterially() {
        fun dist(a: Int, b: Int): Double { val dr = (a shr 16 and 255) - (b shr 16 and 255); val dg = (a shr 8 and 255) - (b shr 8 and 255); val db = (a and 255) - (b and 255); return Math.sqrt((dr * dr + dg * dg + db * db).toDouble()) }
        for (dark in listOf(true, false)) {
            val ks = TsukiTheme.Palette.entries.map { TsukiTheme.colors(it, dark) }
            for (i in ks.indices) for (j in i + 1 until ks.size) {
                val a = ks[i]; val b = ks[j]; val tag = "dark=$dark $i/$j"
                val score = listOf(a.bg to b.bg, a.skyTop to b.skyTop, a.skyBottom to b.skyBottom, a.accent to b.accent, a.bubble to b.bubble, a.surfaceVariant to b.surfaceVariant).sumOf { dist(it.first, it.second) }
                assertTrue("$tag score $score", score > 200)
                assertTrue("$tag accent", dist(a.accent, b.accent) > 40)
                assertNotEquals(tag, a.particle, b.particle)
            }
        }
        assertEquals(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO, TsukiTheme.effectiveNight(TsukiTheme.Palette.PURE_MOON, TsukiTheme.Mode.DARK))
        assertEquals(TsukiTheme.Mode.DARK.night, TsukiTheme.effectiveNight(TsukiTheme.Palette.SAKURA, TsukiTheme.Mode.DARK))
        assertEquals(0xFF24102A.toInt(), TsukiTheme.colors(TsukiTheme.Palette.SAKURA, true).role("bg"))
        assertNull(TsukiTheme.colors(TsukiTheme.Palette.SAKURA, true).role("ok_green"))
    }

    @Test fun neutralTextContrastInBothModes() {
        fun colors(f: String) = Regex("<color name=\"(\\w+)\">#([0-9A-Fa-f]{8})</color>").findAll(File(f).readText())
            .associate { it.groupValues[1] to java.lang.Long.parseLong(it.groupValues[2], 16).toInt() }
        for (f in listOf("src/main/res/values/colors.xml", "src/main/res/values-night/colors.xml")) {
            val c = colors(f)
            for (t in listOf("text_primary", "text_secondary")) assertTrue("$f $t", contrast(c.getValue(t), c.getValue("bg")) >= 4.5)
            assertTrue("$f tertiary", contrast(c.getValue("text_tertiary"), c.getValue("bg")) >= 3.0)
        }
    }

    @Test fun greetingAndPhase() {
        assertEquals("早上好", TsukiTheme.greeting(7)); assertEquals("晚上好", TsukiTheme.greeting(21)); assertEquals("夜深了", TsukiTheme.greeting(2))
        assertEquals(0f, TsukiTheme.phase(0), 0f); assertEquals(0.5f, TsukiTheme.phase(24), 0f); assertEquals(0f, TsukiTheme.phase(48), 0f)
    }

    @Test fun iconSetComplete() {
        for (d in listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")) for (n in listOf("ic_launcher", "ic_launcher_round", "ic_launcher_foreground", "ic_launcher_monochrome"))
            assertTrue("$d/$n", File("src/main/res/mipmap-$d/$n.webp").isFile)
        val adaptive = File("src/main/res/mipmap-anydpi-v26/ic_launcher.xml").readText()
        assertTrue(adaptive.contains("<monochrome") && adaptive.contains("<foreground") && adaptive.contains("<background"))
        assertTrue(File("src/main/res/values-v31/themes.xml").readText().contains("windowSplashScreenAnimatedIcon"))
        // compressed: the whole art set stays small
        val total = File("src/main/res").walk().filter { it.extension == "webp" }.sumOf { it.length() }
        assertTrue("art $total bytes", total < 700_000)
    }
}

class TsukiOrbTest {
    @Test fun snapsToNearestEdge() {
        assertEquals(16, com.runasakura.phoneagent.overlay.TsukiOrb.snapX(300, 150, 1080, 16))
        assertEquals(1080 - 150 - 16, com.runasakura.phoneagent.overlay.TsukiOrb.snapX(700, 150, 1080, 16))
    }
    @Test fun stepBadge() {
        assertEquals(4, com.runasakura.phoneagent.overlay.TsukiOrb.stepOf("第 4 步 · 点击「晴天」"))
        assertEquals(12, com.runasakura.phoneagent.overlay.TsukiOrb.stepOf("第12步"))
        assertNull(com.runasakura.phoneagent.overlay.TsukiOrb.stepOf("运行中"))
    }
}

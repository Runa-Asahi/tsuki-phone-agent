package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.a11y.A11yEvents
import com.runasakura.phoneagent.a11y.A11yHealth
import com.runasakura.phoneagent.ocr.OcrLine
import com.runasakura.phoneagent.ocr.OcrTiles
import com.runasakura.phoneagent.ocr.VisualCandidates
import com.runasakura.phoneagent.screen.ScreenItem
import com.runasakura.phoneagent.sim.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** pt67: landscape game / no vision / Back loop / evidence-based lessons / Shizuku+a11y coexistence / safe self-heal. */
class Pt67Test {
    private fun it(l: String, x: Int, y: Int) = ScreenItem("e", l, x, y, true, false, false, null, 200, 60)
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    // ---- visual candidates on synthetic pixels (landscape thumbnail 360x162) ----
    private fun canvas(w: Int, h: Int, bg: Int = rgb(30, 40, 60)) = IntArray(w * h) { bg }
    private fun fill(px: IntArray, w: Int, l: Int, t: Int, r: Int, b: Int, c: Int) { for (y in t until b) for (x in l until r) px[y * w + x] = c }

    @Test fun bigTextFreeButtonBecomesTappableCandidate() {
        val w = 360; val h = 162; val px = canvas(w, h)
        fill(px, w, 280, 120, 350, 156, rgb(250, 200, 20))           // big yellow button, bottom-right
        val blobs = VisualCandidates.detect(px, w, h)
        assertEquals(1, blobs.size); assertEquals("黄", blobs[0].color)
        val items = VisualCandidates.toItems(blobs, 2800f / 360, listOf(it("商店", 200, 600)))
        assertEquals(1, items.size)
        val b = items[0]
        assertTrue(b.label, b.label.startsWith("〔黄色") && b.label.contains("无文字"))
        assertTrue(b.tap); assertEquals("e2", b.id)
        assertTrue(b.cx in 2200..2700 && b.cy in 950..1264)
    }

    @Test fun candidateUnderTextIsSkippedAndBackgroundIgnored() {
        val w = 360; val h = 162; val px = canvas(w, h)
        fill(px, w, 20, 20, 90, 60, rgb(30, 200, 60))
        val blobs = VisualCandidates.detect(px, w, h)
        assertEquals(1, blobs.size)
        val k = 2800f / 360
        val text = it("开始", ((20 + 90) / 2 * k).toInt(), ((20 + 60) / 2 * k).toInt())
        assertTrue(VisualCandidates.toItems(blobs, k, listOf(text)).isEmpty())
        assertTrue("plain background has no blobs", VisualCandidates.detect(canvas(w, h), w, h).isEmpty())
        val full = canvas(w, h, rgb(250, 30, 30))
        assertTrue("a full-screen colour is background, not a button", VisualCandidates.detect(full, w, h).isEmpty())
    }

    @Test fun cornerCloseMarkFound() {
        val w = 360; val h = 162; val px = canvas(w, h, rgb(20, 20, 20))
        for (d in 0 until 12) { px[(6 + d) * w + 340 + d] = rgb(255, 255, 255); px[(6 + d) * w + 351 - d] = rgb(255, 255, 255) }
        val c = VisualCandidates.corners(px, w, h)
        assertTrue(c.isNotEmpty()); assertEquals("角落图标", c[0].kind)
        assertTrue(c[0].l > w / 2 && c[0].t < h / 3)
    }

    // ---- native-resolution tiles for landscape OCR ----
    @Test fun tilesCoverWidthWithOverlap() {
        val cols = OcrTiles.columns(2800, 1600, 120)
        assertEquals(0, cols.first().first); assertEquals(2800, cols.last().second)
        assertTrue(cols.all { it.second - it.first <= 1600 })
        cols.zipWithNext().forEach { (a, b) -> assertTrue(b.first < a.second) }
        assertEquals(listOf(0 to 1000), OcrTiles.columns(1000, 1600, 120))
    }

    @Test fun tileLinesReplaceBlurryGlobalLinesAndKeepUniqueOnes() {
        val global = listOf(OcrLine("宝石争", 100, 100, 300, 140), OcrLine("金币", 2000, 50, 2100, 90))
        val tiles = listOf(OcrLine("宝石争霸", 98, 100, 302, 141), OcrLine("开始", 2400, 1100, 2600, 1160))
        val m = OcrTiles.merge(global, tiles).map { it.text }.toSet()
        assertEquals(setOf("宝石争霸", "金币", "开始"), m)
    }

    // ---- OCR diff: in-place content switch is a change; OCR garbling is not ----
    @Test fun inPlaceContentSwitchIsMeaningful() {
        val before = listOf(it("活动", 1400, 300), it("限时挑战", 1400, 500), it("商店", 200, 600))
        val after = listOf(it("宝石争霸", 1400, 300), it("3对3收集宝石", 1400, 500), it("商店", 200, 600))
        assertTrue(SemanticDiff.compare(before, after).meaningful)
        val jitter = listOf(it("活動", 1405, 302), it("限时挑战", 1400, 500), it("商店", 201, 600))
        assertFalse(SemanticDiff.compare(before, jitter).meaningful)
    }

    // ---- reflection: unsupported "X 无效" claims are dropped, the rest tagged unverified ----
    @Test fun lessonsNeedEvidence() {
        val facts = listOf("点击活动无效", "点击「对战」无反应", "宝石争霸在活动页里")
        val kept = LessonEvidence.filter(facts, dead = listOf("对战"))
        assertEquals(listOf("点击「对战」无反应（待验证）", "宝石争霸在活动页里（待验证）"), kept)
    }

    @Test fun alternativesListUntriedAndVisualAndGenericMoves() {
        val items = listOf(it("活动", 2500, 300), it("商店", 200, 600), ScreenItem("e9", "〔黄色大按钮·无文字〕", 2400, 1100, true, false, false, null, 400, 150))
        val s = Alternatives.untried(items, dead = listOf("活动"), tapped = setOf("商店"), shell = true)
        assertTrue(s, s.contains("[e9]〔黄色大按钮·无文字〕"))
        assertFalse(s, s.contains("活动")); assertFalse(s, s.contains("[e]商店"))
        assertTrue(s.contains("swipe")); assertTrue(s.contains("escape"))
        assertFalse(Alternatives.untried(items, emptyList(), emptySet(), shell = false).contains("escape"))
    }

    // ---- (B) self-heal never rewrites the list while our service is present ----
    private val pkg = "com.runasakura.phoneagent"
    private val full = "com.runasakura.phoneagent/com.runasakura.phoneagent.a11y.AgentAccessibilityService"

    @Test fun selfHealDecision() {
        val other = "com.other/.Svc"
        assertEquals(A11yHealth.HealAction.NONE, A11yHealth.decide(true, null, false, pkg))
        assertEquals(A11yHealth.HealAction.NONE, A11yHealth.decide(false, "$other:$full", true, pkg))
        assertEquals(A11yHealth.HealAction.NONE, A11yHealth.decide(false, "$pkg/.a11y.AgentAccessibilityService", true, pkg))
        assertEquals(A11yHealth.HealAction.MASTER_ONLY, A11yHealth.decide(false, full, false, pkg))
        assertEquals(A11yHealth.HealAction.APPEND, A11yHealth.decide(false, other, true, pkg))
        assertEquals(A11yHealth.HealAction.APPEND, A11yHealth.decide(false, "$pkg/.a11y.OtherService", true, pkg))
        // debug applicationId suffix: short form is relative to the code namespace
        val dbg = "$pkg.debug"
        assertTrue(A11yHealth.same("$dbg/.a11y.AgentAccessibilityService", dbg))
        assertTrue(A11yHealth.same("$dbg/com.runasakura.phoneagent.a11y.AgentAccessibilityService", dbg))
        assertFalse(A11yHealth.same("$pkg/com.runasakura.phoneagent.a11y.AgentAccessibilityService", dbg))
        assertEquals("$other:$full", A11yHealth.merged("$other:$full", pkg))
    }

    @Test fun disconnectCauseIsEvidenceBasedNotApkGuess() {
        com.runasakura.phoneagent.shell.ShellLog.clear()
        com.runasakura.phoneagent.shell.ShellLog.record(listOf("uiautomator", "dump", "/sdcard/w.xml"), "ShellAugmentedDevice.augment:90", "exit=0", now = 1_000)
        A11yEvents.record("解绑(onUnbind)", now = 3_000)
        val c = A11yEvents.cause(A11yEvents.lastDisconnect(), enabledInSettings = true)
        assertTrue(c, c.contains("UiAutomation") && c.contains("uiautomator"))
        assertTrue(A11yEvents.cause(null, false).contains("开关已被关闭"))
        assertFalse(A11yEvents.cause(null, true).contains("常见于刚更新"))
        com.runasakura.phoneagent.shell.ShellLog.clear()
    }

    /** Source-level guards: nothing tied to the activity may disable/stop the accessibility service. */
    @Test fun noDisableSelfAndActivityNeverStopsServiceOnDisconnect() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val src = root.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(src.none { f -> f.readText().contains("disableSelf(") })
        val main = src.first { it.name == "MainActivity.kt" }.readText()
        assertFalse(main.contains("常见于刚更新 APK"))
        assertFalse(main.contains("AgentForegroundService.stop(this@MainActivity, \"无障碍服务断开\")"))
        val svc = src.first { it.name == "AgentAccessibilityService.kt" }.readText()
        assertFalse("service must not stop itself", svc.contains("stopSelf("))
    }

    // ---- simulator: landscape game, text-free button, Back swallowed, no vision ----
    @Test fun blindLandscapeGameRecoversViaAlternatives() {
        val r = SimRunner.run(Scenarios6.blindGame)
        assertTrue(r.logs.joinToString("\n"), r.result.success)
        assertTrue(r.logs.any { it.startsWith("看图能力：无") })
        assertTrue(r.logs.any { it.contains("没有看图能力：改为高精度") })
        assertEquals("a sub-step 是 while navigating must not trigger the judge before the end", true, (r.calls["judge"] ?: 0) <= 2)
    }
}

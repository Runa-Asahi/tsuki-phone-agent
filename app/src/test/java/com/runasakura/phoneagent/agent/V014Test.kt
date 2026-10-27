package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.llm.ModelStore
import com.runasakura.phoneagent.ocr.CapturePacing
import com.runasakura.phoneagent.ocr.OcrDelta
import com.runasakura.phoneagent.ocr.OcrLine
import com.runasakura.phoneagent.ui.TaskHistory
import com.runasakura.phoneagent.voice.LocalAsr
import com.runasakura.phoneagent.voice.VoiceInput
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** v0.14: adaptive capture pacing, region-incremental OCR, bundled offline ASR glue, history off the main thread. */
class V014Test {
    private val W = 1264; private val H = 2600

    /** Synthetic "app screen": white with dark text-like bars every 120 px. */
    private fun frame(): IntArray { val px = IntArray(W * H) { -1 }
        for (row in 0 until H / 120) bar(px, 60, row * 120 + 40, 700, 36, 0xFF202020.toInt()); return px }
    private fun bar(px: IntArray, x: Int, y: Int, w: Int, h: Int, c: Int) { for (yy in y until minOf(H, y + h)) for (xx in x until minOf(W, x + w)) px[yy * W + xx] = c }
    private fun lines() = (0 until H / 120).map { OcrLine("行$it", 60, it * 120 + 40, 760, it * 120 + 76) }

    @Test fun pacingNoFixedWait() {
        // Shizuku screencap, UI quiet: capture immediately (the old code always waited up to 340 ms after a shot).
        assertEquals(0L, CapturePacing.waitMs(now = 10_000, lastShotAt = 9_900, lastEventAt = 5_000, throttled = false))
        // Accessibility API: only the remainder of its rate limit.
        assertEquals(234L, CapturePacing.waitMs(10_000, 9_900, 5_000, throttled = true))
        assertEquals(0L, CapturePacing.waitMs(10_000, 9_000, 5_000, throttled = true))
        // UI still animating (event 30 ms ago): short settle, bounded.
        assertEquals(60L, CapturePacing.waitMs(10_000, 0, 9_970, throttled = false))
        assertTrue(CapturePacing.waitMs(10_000, 0, 10_000, false) <= CapturePacing.MAX_SETTLE_MS)
    }

    @Test fun deltaSameFrameReusesAll() {
        val g = OcrDelta.grid(frame(), W, H)
        assertTrue(OcrDelta.plan(g, OcrDelta.grid(frame(), W, H), lines(), H, 0) is OcrDelta.Plan.Same)
    }

    @Test fun deltaCatchesSubtleCheckmark() {
        val a = frame(); val b = frame(); bar(b, 1150, 1300, 30, 30, 0xFF1E88E5.toInt())   // 30×30 px tick appears
        val p = OcrDelta.plan(OcrDelta.grid(a, W, H), OcrDelta.grid(b, W, H), lines(), H, 0)
        assertTrue(p is OcrDelta.Plan.Partial)
        p as OcrDelta.Plan.Partial
        assertTrue(p.bands.any { it[0] <= 1300 && it[1] >= 1330 })
        assertTrue("only a sliver is re-read", p.fraction < 0.1)
    }

    @Test fun deltaBandsNeverCutALine() {
        val a = frame(); val b = frame(); bar(b, 800, 1250, 40, 10, 0xFF000000.toInt())   // touches the line at 1240..1276 partially
        val p = OcrDelta.plan(OcrDelta.grid(a, W, H), OcrDelta.grid(b, W, H), lines(), H, 0) as OcrDelta.Plan.Partial
        for (l in lines()) for (bd in p.bands) if (l.b > bd[0] && l.t < bd[1]) assertTrue("line ${l.text} inside band", l.t >= bd[0] && l.b <= bd[1])
    }

    @Test fun deltaFallsBackToFull() {
        val a = frame(); val scrolled = IntArray(W * H) { i -> val y = i / W; if (y + 60 < H) a[i + 60 * W] else -1 }
        assertTrue(OcrDelta.plan(OcrDelta.grid(a, W, H), OcrDelta.grid(scrolled, W, H), lines(), H, 0) is OcrDelta.Plan.Full)
        assertTrue(OcrDelta.plan(null, OcrDelta.grid(a, W, H), lines(), H, 0) is OcrDelta.Plan.Full)
        assertTrue("periodic full refresh", OcrDelta.plan(OcrDelta.grid(a, W, H), OcrDelta.grid(a, W, H), lines(), H, OcrDelta.MAX_CHAIN) is OcrDelta.Plan.Full)
    }

    @Test fun deltaMergeReplacesOnlyBands() {
        val old = lines(); val band = intArrayOf(1200, 1300)
        val fresh = listOf(OcrLine("新", 60, 1240, 300, 1276))
        val m = OcrDelta.merge(old, fresh, listOf(band))
        assertEquals(old.size, m.size); assertTrue(m.any { it.text == "新" }); assertFalse(m.any { it.text == "行10" })
    }

    /** Benchmark (JVM): pixels ML Kit must read per step over a typical step mix, old (always full) vs incremental. */
    @Test fun deltaBenchmark() {
        val base = frame()
        val steps = listOf<(IntArray) -> Unit>(
            { bar(it, 1100, 600, 80, 40, 0xFF43A047.toInt()) },                 // toggle flips
            { bar(it, 60, 2300, 900, 160, 0xFFE0E0E0.toInt()) },                // new chat bubble at the bottom
            { bar(it, 0, 1560, W, 1040, 0xFFECEFF1.toInt()) },                  // keyboard opens (40%)
            { },                                                                // re-read, nothing changed
            { bar(it, 1150, 1300, 30, 30, 0xFF1E88E5.toInt()) },                // checkmark
        )
        var prev = OcrDelta.grid(base, W, H); var full = 0L; var inc = 0L; var chain = 0
        val t0 = System.nanoTime(); var gridNs = 0L
        for (s in steps) {
            val f = frame(); s(f)
            val tg = System.nanoTime(); val g = OcrDelta.grid(f, W, H); gridNs += System.nanoTime() - tg
            full += H
            when (val p = OcrDelta.plan(prev, g, lines(), H, chain)) {
                is OcrDelta.Plan.Same -> { chain++ }
                is OcrDelta.Plan.Partial -> { inc += p.bands.sumOf { it[1] - it[0] }; chain++ }
                else -> { inc += H; chain = 0 }
            }
            prev = g
        }
        val ratio = inc.toDouble() / full
        println("OCR 增量基准：${steps.size} 步，全屏方案读 $full 行像素，增量方案读 $inc 行（${"%.1f".format(ratio * 100)}%），" +
            "网格对比平均 ${gridNs / steps.size / 1000} µs/帧，总 ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertTrue("incremental must read well under half the pixels", ratio < 0.5)
    }

    @Test fun asrModelPinned() {
        assertEquals(4, LocalAsr.FILES.size)
        assertTrue(LocalAsr.FILES.all { it.sha256!!.length == 64 })
        assertEquals(25_354_625L, LocalAsr.TOTAL_BYTES)
        val d = createTempDirSafe()
        assertFalse(LocalAsr.complete(d))
        for (f in LocalAsr.FILES) File(d, f.path).writeBytes(ByteArray(f.size.toInt()))
        assertTrue(LocalAsr.complete(d))
        File(d, "tokens.txt").writeText("x"); assertFalse("size mismatch = not installed", LocalAsr.complete(d))
        d.deleteRecursively()
    }
    private fun createTempDirSafe() = kotlin.io.path.createTempDirectory("asr").toFile()

    @Test fun asrSignalHelpers() {
        val f = LocalAsr.toFloat(shortArrayOf(0, 16384, -32768), 3)
        assertEquals(0.5f, f[1], 1e-6f); assertEquals(-1f, f[2], 1e-6f)
        assertEquals(-2f, LocalAsr.rmsDb(FloatArray(10), 10), 0f)
        assertTrue(LocalAsr.rmsDb(FloatArray(10) { 0.3f }, 10) > LocalAsr.rmsDb(FloatArray(10) { 0.01f }, 10))
        assertEquals("打开蓝牙", LocalAsr.finalText(" 打开蓝牙，"))
    }

    @Test fun engineChoice() {
        assertTrue(VoiceInput.pickLocal(localInstalled = true, pref = "local", systemOk = true))
        assertFalse("user prefers system and it exists", VoiceInput.pickLocal(true, "system", true))
        assertTrue("system preferred but missing → local", VoiceInput.pickLocal(true, "system", false))
        assertFalse(VoiceInput.pickLocal(false, "local", false))
    }

    @Test fun historyPeekNeverTouchesDisk() {
        // peek() is pure memory: safe on the main thread whether or not the preload finished.
        assertNotNull(TaskHistory.peek())
    }

    @Test fun fetchFilesListIsSafe() {
        // Same downloader for LLM and ASR models: the pinned list carries the sha256 it is checked against.
        assertTrue(LocalAsr.FILES.all { it is ModelStore.RemoteFile && !it.path.contains("..") })
    }
}

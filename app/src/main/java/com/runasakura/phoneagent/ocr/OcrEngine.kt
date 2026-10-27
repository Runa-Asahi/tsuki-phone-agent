package com.runasakura.phoneagent.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.runasakura.phoneagent.screen.ScreenItem
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * On-device OCR (ML Kit Chinese, bundled model: works offline and without Google Play services,
 * so it runs on Honor/MagicOS). Returns one ScreenItem per text line, in screen coordinates.
 */
object OcrEngine {
    private const val TAG = "OcrEngine"
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** Raw OCR lines (screen coords, [yOffset] added back after cropping). */
    @Volatile private var warmed = false

    /**
     * First ML Kit call loads the bundled Chinese model (PPG-AN00: 7–11 s when it coincided with
     * WeChat's cold start). Run once on a tiny bitmap at app/task start so the real read is fast.
     */
    suspend fun warmUp() {
        if (warmed) return
        val t0 = System.currentTimeMillis()
        // Realistic size + real CJK glyphs: a blank tiny bitmap let ML Kit skip recognizer init, so the
        // first real read still paid ~1.3 s on PPG-AN00.
        val b = Bitmap.createBitmap(432, 960, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
            val c = android.graphics.Canvas(this)
            val p = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; textSize = 36f; isAntiAlias = true }
            listOf("微信 通讯录 发现 我", "搜索 发送 取消", "相册 拍摄 视频通话").forEachIndexed { i, t -> c.drawText(t, 20f, 120f + i * 300f, p) }
        }
        try { recognizeLines(b) } catch (_: Exception) {} finally { b.recycle() }
        warmed = true
        android.util.Log.i(TAG, "warm-up ${System.currentTimeMillis() - t0}ms")
    }

    /** Live status for the home-screen pill: engine readiness and real recognition latency (not a fixed label). */
    @Volatile var lastMs = -1L; private set
    @Volatile var avgMs = -1L; private set
    @Volatile var reads = 0; private set
    @Volatile var lastFailed = false; private set
    val isReady get() = warmed

    /** e.g. 「就绪 · 230ms」/「预热中」/「识别失败」. Pure formatting — unit-tested. */
    fun statusText(ready: Boolean = warmed, avg: Long = avgMs, failed: Boolean = lastFailed): String = when {
        failed -> "识别失败"
        !ready -> "待预热"
        avg > 0 -> "就绪 · ${avg}ms"
        else -> "就绪"
    }

    private fun record(ms: Long, ok: Boolean) {
        lastFailed = !ok
        if (!ok) return
        lastMs = ms; reads++
        avgMs = if (avgMs < 0) ms else (avgMs * 3 + ms) / 4   // EMA: recent speed, not first cold read
    }

    suspend fun recognizeLines(bmp: Bitmap, yOffset: Int = 0): List<OcrLine>? {
        val t0 = System.currentTimeMillis()
        return recognizeRaw(bmp, yOffset).also { if (warmed) record(System.currentTimeMillis() - t0, it != null) }
    }

    private suspend fun recognizeRaw(bmp: Bitmap, yOffset: Int): List<OcrLine>? =
        suspendCancellableCoroutine { cont ->
            try {
                recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { t ->
                        val out = mutableListOf<OcrLine>()
                        for (b in t.textBlocks) for (l in b.lines) {
                            val box = l.boundingBox ?: continue
                            out += OcrLine(l.text, box.left, box.top + yOffset, box.right, box.bottom + yOffset)
                        }
                        if (cont.isActive) cont.resume(out)
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "OCR failed: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    }
            } catch (e: Throwable) {
                Log.w(TAG, "OCR error: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
}

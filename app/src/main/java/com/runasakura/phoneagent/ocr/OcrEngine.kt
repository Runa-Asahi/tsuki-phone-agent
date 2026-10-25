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

    suspend fun recognize(bmp: Bitmap, ignore: Set<String> = emptySet()): List<ScreenItem>? =
        suspendCancellableCoroutine { cont ->
            try {
                recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { t ->
                        val lines = mutableListOf<ScreenItem>()
                        for (b in t.textBlocks) for (l in b.lines) {
                            val box = l.boundingBox ?: continue
                            val label = l.text.trim()
                            if (label.isEmpty() || label in ignore) continue
                            lines += ScreenItem("", label, box.centerX(), box.centerY(), true, false, false, null)
                        }
                        val sorted = lines.sortedWith(compareBy({ it.cy }, { it.cx }))
                            .mapIndexed { i, it -> it.copy(id = "e${i + 1}") }
                        if (cont.isActive) cont.resume(sorted)
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

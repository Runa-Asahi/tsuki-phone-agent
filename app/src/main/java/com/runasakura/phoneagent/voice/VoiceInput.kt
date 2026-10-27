package com.runasakura.phoneagent.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * Voice input over the system [SpeechRecognizer] (no bundled ASR, nothing leaves the device unless the
 * recogniser the ROM provides does so). Detection is explicit because MagicOS / phones without Google services often
 * have NO recognition service: callers then fall back to [Fallback.RECOGNIZER_ACTIVITY] (a voice-assistant app that
 * handles RecognizerIntent) or [Fallback.KEYBOARD_VOICE] (the input method's own voice key, e.g. 百度/讯飞/搜狗/荣耀输入法).
 *
 * Must be used on the main thread (SpeechRecognizer requirement).
 */
class VoiceInput(private val ctx: Context, private val l: Listener) {
    interface Listener {
        fun onListening(on: Boolean)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
        fun onLevel(rmsDb: Float) {}
    }

    enum class Fallback { NONE, RECOGNIZER_ACTIVITY, KEYBOARD_VOICE }

    private var sr: SpeechRecognizer? = null
    var listening = false; private set
    private var lastPartial = ""
    /** Prefer the private on-device recogniser; flips off for the session when it can't do Chinese. */
    private var onDevice = true

    /** Starts listening; returns false (with [Listener.onError]) when no recogniser can be used. */
    fun start(): Boolean {
        if (listening) return true
        if (!hasMicPermission(ctx)) { l.onError("需要麦克风权限"); return false }
        val r = sr ?: create(ctx, onDevice)?.also { sr = it } ?: run { l.onError("本机没有可用的系统语音识别服务"); return false }
        lastPartial = ""; cancelled = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { listening = true; l.onListening(true) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { l.onLevel(rmsdB) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                listening = false; l.onListening(false)
                if (cancelled) return   // our own cancel(): not an error worth showing
                // On-device model without Chinese (or not downloaded): retry once on the regular system recogniser.
                if (onDevice && error in setOf(12, 13, SpeechRecognizer.ERROR_SERVER) && lastPartial.isBlank() && systemAvailable(ctx)) {
                    onDevice = false; runCatching { sr?.destroy() }; sr = null; start(); return
                }
                // A partial transcript already shown is still a result (common: silence timeout right after speaking).
                if (lastPartial.isNotBlank() && (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH)) l.onFinal(lastPartial)
                else l.onError(errorText(error))
            }
            override fun onResults(b: Bundle?) {
                listening = false; l.onListening(false)
                val t = best(b) ?: lastPartial
                if (t.isNotBlank()) l.onFinal(t) else l.onError(errorText(SpeechRecognizer.ERROR_NO_MATCH))
            }
            override fun onPartialResults(b: Bundle?) { best(b)?.takeIf { it.isNotBlank() }?.let { lastPartial = it; l.onPartial(it) } }
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        return try { r.startListening(intent()); true } catch (e: Exception) {
            listening = false; l.onError("无法启动语音识别：${e.message}"); false
        }
    }

    /** Stop listening and deliver what was heard (hold-to-talk release / second tap). */
    fun stop() { runCatching { sr?.stopListening() } }
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true; runCatching { sr?.cancel() }; if (listening) { listening = false; l.onListening(false) } }
    fun destroy() { runCatching { sr?.destroy() }; sr = null; listening = false }

    private fun best(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    companion object {
        fun hasMicPermission(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Recognition services installed (package names), for the honest capability line. */
        fun services(ctx: Context): List<String> = runCatching {
            ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).map { it.serviceInfo.packageName }.distinct()
        }.getOrDefault(emptyList())

        fun onDeviceAvailable(ctx: Context) = Build.VERSION.SDK_INT >= 33 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrDefault(false)
        fun systemAvailable(ctx: Context) = runCatching { SpeechRecognizer.isRecognitionAvailable(ctx) }.getOrDefault(false)
        fun available(ctx: Context) = onDeviceAvailable(ctx) || systemAvailable(ctx)

        /** On-device recogniser first (offline, private), else the default system one. */
        private fun create(ctx: Context, preferOnDevice: Boolean): SpeechRecognizer? = runCatching {
            when {
                preferOnDevice && onDeviceAvailable(ctx) -> SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                systemAvailable(ctx) -> SpeechRecognizer.createSpeechRecognizer(ctx)
                else -> null
            }
        }.getOrNull()

        fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)

        /** What to do when [available] is false. */
        fun fallback(ctx: Context): Fallback = when {
            available(ctx) -> Fallback.NONE
            runCatching { Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(ctx.packageManager) != null }.getOrDefault(false) -> Fallback.RECOGNIZER_ACTIVITY
            else -> Fallback.KEYBOARD_VOICE
        }

        /** One line for settings/self-test: which path voice input will use on this phone. */
        fun describe(ctx: Context): String = when {
            onDeviceAvailable(ctx) -> "系统离线识别（本机）"
            systemAvailable(ctx) -> "系统语音识别（" + services(ctx).joinToString("、").ifBlank { "默认服务" } + "）"
            fallback(ctx) == Fallback.RECOGNIZER_ACTIVITY -> "语音助手识别页（无后台识别服务）"
            else -> "无系统识别服务：改用输入法的语音键"
        }

        /** Pure: SpeechRecognizer error code → short Chinese message. */
        fun errorText(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清，再说一次"
            SpeechRecognizer.ERROR_AUDIO -> "录音失败（麦克风被占用？）"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要麦克风权限"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "识别服务需要网络，当前不可用"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙，稍后再试"
            SpeechRecognizer.ERROR_CLIENT -> "语音识别已取消"
            SpeechRecognizer.ERROR_SERVER -> "识别服务出错"
            13 /* ERROR_LANGUAGE_NOT_SUPPORTED */, 12 /* ERROR_LANGUAGE_UNAVAILABLE */ -> "识别服务不支持中文"
            else -> "语音识别失败（$code）"
        }

        /** Pure: merge a transcript into what is already typed (append with a separator, never clobber). */
        fun merge(existing: String, heard: String): String {
            val e = existing.trimEnd(); val h = heard.trim()
            if (h.isEmpty()) return existing
            if (e.isEmpty()) return h
            val sep = if (e.last() in "，。,.!！?？；;、 ") "" else "，"
            return e + sep + h
        }
    }
}

package com.runasakura.phoneagent.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.runasakura.phoneagent.R
import com.runasakura.phoneagent.voice.VoiceInput

/**
 * The composer's mic: tap = start/stop, press-and-hold = talk while held. Live transcript goes into the input
 * (appended to what was typed); optionally sends when final. Falls back honestly when the phone has no recogniser:
 * a voice-assistant RecognizerIntent page, else the keyboard's own voice key. Create in onCreate (result launchers).
 */
class MicController(
    private val act: AppCompatActivity,
    private val btn: ImageButton,
    private val input: EditText,
    private val autoSend: () -> Boolean,
    private val canSend: () -> Boolean,
    private val send: () -> Unit,
) : VoiceInput.Listener {
    private val voice = VoiceInput(act, this)
    private var base = ""
    private var hold = false
    private var startOnGrant = false

    private val perm = act.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok && startOnGrant) begin() else if (!ok) toast("没有麦克风权限，无法语音输入（可在系统设置→应用→Tsuki→权限中开启）")
        startOnGrant = false
    }
    private val sysPage = act.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { onFinal(it) }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun bind() {
        val longMs = ViewConfiguration.getLongPressTimeout().toLong()
        val holdStart = Runnable { hold = true; btn.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); if (!voice.listening) begin() }
        btn.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { hold = false; v.postDelayed(holdStart, longMs) }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(holdStart)
                    if (hold) voice.stop()                       // hold-to-talk released
                    else if (voice.listening) voice.stop()       // second tap ends
                    else { v.performClick(); begin() }          // tap starts
                }
                MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(holdStart); if (hold) voice.stop() }
            }
            true
        }
    }

    private fun begin() {
        if (!VoiceInput.hasMicPermission(act)) { startOnGrant = true; perm.launch(android.Manifest.permission.RECORD_AUDIO); return }
        when (VoiceInput.fallback(act)) {
            VoiceInput.Fallback.NONE -> { base = input.text?.toString().orEmpty(); voice.start() }
            VoiceInput.Fallback.RECOGNIZER_ACTIVITY -> runCatching { sysPage.launch(VoiceInput.intent()) }.onFailure { keyboardVoice() }
            VoiceInput.Fallback.KEYBOARD_VOICE -> keyboardVoice()
        }
    }

    /** No recogniser on this ROM (common without Google services): the IME's voice key is the working path. */
    private fun keyboardVoice() {
        input.requestFocus()
        (act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0)
        toast("这台手机没有系统语音识别服务：请点输入法键盘上的🎤语音键说话（荣耀/百度/讯飞/搜狗输入法均支持离线语音）")
    }

    override fun onListening(on: Boolean) {
        btn.isActivated = on
        btn.animate().scaleX(if (on) 1.12f else 1f).scaleY(if (on) 1.12f else 1f).setDuration(160).start()
        btn.contentDescription = act.getString(if (on) R.string.voice_listening else R.string.voice_input)
        input.hint = if (on) act.getString(R.string.voice_listening) else act.getString(R.string.goal_hint)
        tint(on)
    }

    override fun onLevel(rmsDb: Float) {
        if (!voice.listening || TsukiTheme.reduceMotion(act)) return
        val s = 1.08f + ((rmsDb + 2f) / 12f).coerceIn(0f, 1f) * 0.14f   // breathes with the voice
        btn.scaleX = s; btn.scaleY = s
    }

    override fun onPartial(text: String) { set(VoiceInput.merge(base, text)) }

    override fun onFinal(text: String) {
        set(VoiceInput.merge(base, text))
        if (autoSend() && canSend()) send()
    }

    override fun onError(message: String) = toast(message)   // any partial transcript stays in the input

    private fun set(t: String) { input.setText(t); input.setSelection(t.length) }
    private fun toast(t: String): Unit = Toast.makeText(act, t, Toast.LENGTH_LONG).show().let { }

    fun tint(listening: Boolean = voice.listening) {
        val k = TsukiTheme.current(act)
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(if (listening) k.crimson else k.accentContainer)
        btn.imageTintList = android.content.res.ColorStateList.valueOf(if (listening) 0xFFFFFFFF.toInt() else k.onSurface)
    }

    fun pause() { if (voice.listening) voice.cancel() }
    fun destroy() = voice.destroy()
}

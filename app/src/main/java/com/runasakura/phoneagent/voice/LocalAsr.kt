package com.runasakura.phoneagent.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.runasakura.phoneagent.llm.ModelStore
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Bundled offline Chinese speech recognition: k2-fsa sherpa-onnx (arm64 JNI in the APK) + a 25 MB int8 streaming
 * zipformer (zh-14M) downloaded once from ModelScope (resumable, every file sha256-pinned). Audio never leaves the phone.
 * Works everywhere the app can record (in-app composer and the floating orb inside the microphone-typed FGS),
 * independent of whether the ROM ships a system recogniser.
 */
object LocalAsr {
    const val REPO = "HZZSCIENCE/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23"
    const val SAMPLE_RATE = 16000
    val FILES = listOf(
        ModelStore.RemoteFile("encoder-epoch-99-avg-1.int8.onnx", 21621684, "1c556ea57cec304e55ec4b72e52c1cc098bb01476ed7d90f3de939fe126487b1"),
        ModelStore.RemoteFile("decoder-epoch-99-avg-1.int8.onnx", 1888682, "22f123bb8cba9b38974b3df18a3f45e7081f4985ebb2e075d9f21f618c468bbf"),
        ModelStore.RemoteFile("joiner-epoch-99-avg-1.int8.onnx", 1795562, "a7cf9d82757bdcf786059454495a9ca95e4bd7347f72473fc08d794475c36169"),
        ModelStore.RemoteFile("tokens.txt", 48697, "8b294db9045d6e5f94647f4c1eec1af4da143a75053c399611444b378ff966ac"),
    )
    val TOTAL_BYTES = FILES.sumOf { it.size }

    fun root(ctx: Context) = File(ctx.filesDir, "asr")
    fun dir(ctx: Context) = File(root(ctx), "zh-14M")
    /** Pure: every pinned file present at its exact size (sha256 was checked before rename, so size is enough here). */
    fun complete(d: File): Boolean = FILES.all { f -> File(d, f.path).let { it.isFile && it.length() == f.size } }
    // Cached so the mic tap (main thread) does no disk stat; refreshed on download/delete and at app start.
    @Volatile private var installedCache: Boolean? = null
    fun installed(ctx: Context): Boolean = installedCache ?: complete(dir(ctx)).also { installedCache = it }
    fun refresh(ctx: Context) { installedCache = complete(dir(ctx)) }
    fun partialBytes(ctx: Context): Long = dir(ctx).takeIf { it.isDirectory }?.walk()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    suspend fun download(ctx: Context, onProgress: (ModelStore.Progress) -> Unit): File =
        ModelStore.fetchFiles(REPO, FILES, dir(ctx), onProgress).also { refresh(ctx) }

    fun delete(ctx: Context) { release(); root(ctx).deleteRecursively(); installedCache = false }

    // ---- recogniser lifetime: built on first use (~150 ms), freed after IDLE_MS so 60 MB of RAM is not pinned ----
    const val IDLE_MS = 90_000L
    private var rec: com.k2fsa.sherpa.onnx.OnlineRecognizer? = null
    private val lock = Any()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val idle = Runnable { synchronized(lock) { if (busy == 0) { runCatching { rec?.release() }; rec = null } } }
    private var busy = 0
    val loaded get() = rec != null

    internal fun acquire(ctx: Context): com.k2fsa.sherpa.onnx.OnlineRecognizer = synchronized(lock) {
        main.removeCallbacks(idle); busy++
        rec ?: build(dir(ctx)).also { rec = it }
    }
    internal fun releaseLater() = synchronized(lock) { busy = (busy - 1).coerceAtLeast(0); main.removeCallbacks(idle); main.postDelayed(idle, IDLE_MS) }
    fun release() = synchronized(lock) { main.removeCallbacks(idle); if (busy == 0) { runCatching { rec?.release() }; rec = null } }

    private fun build(d: File): com.k2fsa.sherpa.onnx.OnlineRecognizer {
        val cfg = com.k2fsa.sherpa.onnx.OnlineRecognizerConfig().apply {
            featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(SAMPLE_RATE, 80, 0f)
            modelConfig = com.k2fsa.sherpa.onnx.OnlineModelConfig().apply {
                transducer = com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig().apply {
                    encoder = File(d, FILES[0].path).absolutePath; decoder = File(d, FILES[1].path).absolutePath; joiner = File(d, FILES[2].path).absolutePath
                }
                tokens = File(d, FILES[3].path).absolutePath
                numThreads = 2; provider = "cpu"; modelType = ""   // read from the model metadata
            }
            enableEndpoint = true
            // rule1: 2.4 s of silence with nothing said; rule2: 1.0 s silence after speech; rule3: 30 s cap.
            endpointConfig = com.k2fsa.sherpa.onnx.EndpointConfig(
                com.k2fsa.sherpa.onnx.EndpointRule(false, 2.4f, 0f),
                com.k2fsa.sherpa.onnx.EndpointRule(true, 1.0f, 0f),
                com.k2fsa.sherpa.onnx.EndpointRule(false, 0f, 30f))
            decodingMethod = "greedy_search"
        }
        return com.k2fsa.sherpa.onnx.OnlineRecognizer(null, cfg)
    }

    /** Pure: PCM16 → float [-1,1). */
    fun toFloat(pcm: ShortArray, n: Int, out: FloatArray = FloatArray(n)): FloatArray { for (i in 0 until n) out[i] = pcm[i] / 32768f; return out }
    /** Pure: RMS level in the same rough dB range SpeechRecognizer.onRmsChanged reports (-2..10). */
    fun rmsDb(f: FloatArray, n: Int): Float { if (n <= 0) return -2f; var s = 0.0; for (i in 0 until n) s += f[i] * f[i]
        val rms = sqrt(s / n); return if (rms <= 1e-6) -2f else (20 * log10(rms) + 50).toFloat() / 4f }
    /** Pure: what the session should deliver when it ends. */
    fun finalText(heard: String): String = heard.trim().trimEnd('，', ',')

    /**
     * One utterance: AudioRecord 16 kHz mono on its own thread, streamed into the recogniser; callbacks on the main
     * thread. [endpointStops] = tap mode (stops by itself after a pause); hold mode keeps listening until [stop].
     */
    class Session(private val ctx: Context, private val l: VoiceInput.Listener, private val endpointStops: Boolean) {
        @Volatile private var stopping = false
        @Volatile private var cancelled = false
        @Volatile var running = false; private set
        private val main = Handler(Looper.getMainLooper())

        @SuppressLint("MissingPermission")   // callers check RECORD_AUDIO first
        fun start(): Boolean {
            if (running) return true
            running = true
            Thread({ loop() }, "tsuki-asr").apply { priority = Thread.MAX_PRIORITY - 1 }.start()
            return true
        }
        fun stop() { stopping = true }
        fun cancel() { cancelled = true; stopping = true }

        @SuppressLint("MissingPermission")
        private fun loop() {
            var ar: AudioRecord? = null; var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null; var acquired = false
            var text = ""
            try {
                val r = acquire(ctx); acquired = true
                stream = r.createStream("")
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE / 5 * 2))
                check(ar.state == AudioRecord.STATE_INITIALIZED) { "录音失败（麦克风被占用？）" }
                ar.startRecording()
                check(ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "录音失败（麦克风被占用？）" }
                main.post { if (!cancelled) l.onListening(true) }
                val chunk = SAMPLE_RATE / 10; val pcm = ShortArray(chunk); val f = FloatArray(chunk)
                val t0 = System.currentTimeMillis(); var lastLevel = 0L
                while (!stopping && System.currentTimeMillis() - t0 < 60_000) {
                    val n = ar.read(pcm, 0, chunk); if (n <= 0) { check(n == 0) { "录音失败（$n）" }; continue }
                    toFloat(pcm, n, f)
                    stream.acceptWaveform(if (n == chunk) f else f.copyOf(n), SAMPLE_RATE)
                    while (r.isReady(stream)) r.decode(stream)
                    val now = System.currentTimeMillis()
                    if (now - lastLevel > 80) { lastLevel = now; val db = rmsDb(f, n); main.post { if (!cancelled) l.onLevel(db) } }
                    val t = r.getResult(stream).text
                    if (t != text && t.isNotBlank()) { text = t; main.post { if (!cancelled) l.onPartial(t) } }
                    if (r.isEndpoint(stream) && (endpointStops || text.isBlank() && now - t0 > 8_000)) break
                }
                runCatching { ar.stop() }
                // Flush: a little silence so the last syllable is decoded.
                stream.acceptWaveform(FloatArray(SAMPLE_RATE * 3 / 10), SAMPLE_RATE); stream.inputFinished()
                while (r.isReady(stream)) r.decode(stream)
                text = r.getResult(stream).text.ifBlank { text }
                val fin = finalText(text)
                main.post {
                    l.onListening(false)
                    if (cancelled) return@post
                    if (fin.isNotBlank()) l.onFinal(fin) else l.onError("没听清，再说一次")
                }
            } catch (e: Throwable) {
                val msg = e.message ?: e.javaClass.simpleName
                main.post { l.onListening(false); if (!cancelled) l.onError(if (e is UnsatisfiedLinkError) "本地识别引擎加载失败：$msg" else msg) }
            } finally {
                runCatching { ar?.release() }; runCatching { stream?.release() }
                if (acquired) releaseLater()
                running = false
            }
        }
    }
}

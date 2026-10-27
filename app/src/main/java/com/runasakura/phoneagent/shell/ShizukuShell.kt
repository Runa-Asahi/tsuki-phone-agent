package com.runasakura.phoneagent.shell

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Optional privileged backend through Shizuku (adb-shell identity). Everything here is best-effort: any failure
 * returns null/false and the agent keeps using the accessibility service + OCR path.
 */
object ShizukuBackend {
    private const val TAG = "ShizukuBackend"
    const val MANAGER_PKG = "moe.shizuku.privileged.api"
    const val REQUEST_CODE = 7311

    private const val PREFS = "agent_backend"
    private const val KEY = "use_shizuku"

    /** User toggle (default on: it only takes effect once Shizuku is running AND has granted this app). */
    fun isEnabled(ctx: Context?): Boolean = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(KEY, true) ?: false
    fun setEnabled(ctx: Context, on: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()

    fun status(ctx: Context?): ShizukuStatus {
        val running = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        if (!running) {
            val installed = ctx?.let {
                try { it.packageManager.getPackageInfo(MANAGER_PKG, 0); true } catch (_: Exception) { false }
            } ?: false
            return if (installed) ShizukuStatus.NOT_RUNNING else ShizukuStatus.NOT_INSTALLED
        }
        val granted = try { !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }
        return if (granted) ShizukuStatus.READY else ShizukuStatus.NO_PERMISSION
    }

    /** Ask Shizuku for permission (shows Shizuku's own dialog). False when Shizuku isn't running. */
    fun requestPermission(): Boolean = try {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) false
        else if (Shizuku.shouldShowRequestPermissionRationale()) false    // user chose "deny and don't ask": must grant in Shizuku app
        else { Shizuku.requestPermission(REQUEST_CODE); true }
    } catch (_: Throwable) { false }

    fun backend(ctx: Context?): BackendSelector.Backend = BackendSelector.select(isEnabled(ctx), status(ctx))

    /** The live shell when the backend is selected, else null (→ accessibility path). */
    fun shellOrNull(ctx: Context?): PrivilegedShell? =
        if (backend(ctx) == BackendSelector.Backend.SHIZUKU) BackendSelector.guarded(ShizukuShell) else null

    internal fun log(msg: String, e: Throwable? = null) { try { Log.w(TAG, msg, e) } catch (_: Throwable) {} }
}

/** argv exec via Shizuku's remote process (no /system/bin/sh -c: the argv is executed as-is). */
object ShizukuShell : PrivilegedShell {
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            .apply { isAccessible = true }
    }

    override fun exec(argv: List<String>, timeoutMs: Long): ShellResult {
        val p = newProcess.invoke(null, argv.toTypedArray(), null, null) as Process
        return try {
            p.outputStream.close()
            val out = java.io.ByteArrayOutputStream()
            val err = StringBuilder()
            val errT = Thread { try { err.append(p.errorStream.bufferedReader().readText().take(2000)) } catch (_: Exception) {} }.apply { start() }
            val outT = Thread { try { p.inputStream.copyTo(out) } catch (_: Exception) {} }.apply { start() }
            val deadline = System.currentTimeMillis() + timeoutMs
            outT.join(timeoutMs); errT.join(maxOf(1, deadline - System.currentTimeMillis()))
            val code = if (outT.isAlive) { p.destroy(); 124 } else try { p.waitFor() } catch (_: Exception) { -1 }
            ShellResult(code, out.toByteArray(), err.toString())
        } finally { try { p.destroy() } catch (_: Exception) {} }
    }

    /**
     * Stream a command's stdout (for large reads such as copying a model file with `cat`). The caller must close the
     * returned stream; the process is destroyed with it. Recorded in the Shizuku command log like every exec.
     */
    fun openStream(argv: List<String>): java.io.InputStream {
        val p = newProcess.invoke(null, argv.toTypedArray(), null, null) as Process
        p.outputStream.close()
        ShellLog.record(argv, ShellLog.caller(), "stream")
        return object : java.io.FilterInputStream(p.inputStream) {
            override fun close() { try { super.close() } finally { try { p.destroy() } catch (_: Exception) {} } }
        }
    }

    /**
     * Clipboard with shell identity: IClipboard over Shizuku's binder wrapper (the shell UID is exempt from the
     * Android 10+ background-read restriction). Signatures differ per Android version → reflection, best-effort.
     */
    override fun readClipboard(): String? = try {
        val binder = rikka.shizuku.ShizukuBinderWrapper(rikka.shizuku.SystemServiceHelper.getSystemService("clipboard"))
        val stub = Class.forName("android.content.IClipboard\$Stub")
        val cb = stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        val m = cb.javaClass.methods.first { it.name == "getPrimaryClip" }
        val args: Array<Any?> = m.parameterTypes.mapIndexed { i, t ->
            when {
                t == String::class.java && i == 0 -> "com.android.shell"
                t == String::class.java -> null
                t == Int::class.javaPrimitiveType -> 0   // userId 0 (owner) / deviceId 0 (default display)
                else -> null
            }
        }.toTypedArray()
        val clip = m.invoke(cb, *args) as? android.content.ClipData
        clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    } catch (e: Throwable) { ShizukuBackend.log("clipboard read failed", e); null }
}

/** Shell-backed device helpers used by the accessibility service when the backend is active. */
object ShellOps {
    fun tap(sh: PrivilegedShell, x: Int, y: Int) = sh.exec(listOf("input", "tap", "$x", "$y"), 3000).ok
    fun swipe(sh: PrivilegedShell, x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) =
        sh.exec(listOf("input", "swipe", "$x1", "$y1", "$x2", "$y2", "${ms.coerceIn(50, 3000)}"), ms + 3000).ok
    fun longPress(sh: PrivilegedShell, x: Int, y: Int) = swipe(sh, x, y, x, y, 650)
    fun key(sh: PrivilegedShell, code: String) = sh.exec(listOf("input", "keyevent", code), 3000).ok
    fun forceStop(sh: PrivilegedShell, pkg: String) = sh.exec(listOf("am", "force-stop", pkg), 4000).ok

    /** PNG via screencap (no MediaProjection / a11y-screenshot throttle). */
    fun screencap(sh: PrivilegedShell): ByteArray? = sh.exec(listOf("screencap", "-p"), 6000).takeIf { it.ok && it.out.size > 1000 }?.out

    /**
     * Raw RGBA screencap (no `-p`): skips the device-side PNG encode + our PNG decode (~300–500 ms on a 1080p+
     * panel). Null when the header is not understood → caller falls back to [screencap].
     */
    fun screencapRaw(sh: PrivilegedShell): ByteArray? = sh.exec(listOf("screencap"), 6000).takeIf { it.ok && it.out.size > 1000 }?.out
}

/** Parses `screencap` raw output: [w][h][format] (+[colorspace] on Android 12+) little-endian ints, then RGBA_8888. */
object RawFrame {
    data class Header(val w: Int, val h: Int, val offset: Int)
    private fun le(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
        ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)

    fun header(b: ByteArray): Header? {
        if (b.size < 16) return null
        val w = le(b, 0); val h = le(b, 4); val fmt = le(b, 8)
        if (w !in 64..8192 || h !in 64..8192 || fmt != 1) return null   // 1 = RGBA_8888
        val px = w.toLong() * h * 4
        return when (b.size.toLong()) {
            16 + px -> Header(w, h, 16)
            12 + px -> Header(w, h, 12)
            else -> null
        }
    }

    fun toBitmap(b: ByteArray): android.graphics.Bitmap? {
        val hd = header(b) ?: return null
        val bmp = android.graphics.Bitmap.createBitmap(hd.w, hd.h, android.graphics.Bitmap.Config.ARGB_8888)
        // ARGB_8888 is stored as RGBA bytes in memory, matching screencap's RGBA_8888 layout.
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(b, hd.offset, hd.w * hd.h * 4))
        return bmp
    }
}

package com.runasakura.phoneagent.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AgentPreferences(context: Context) : AgentSettings {
    private val sp: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** API keys only — never written to the plain prefs file or the diagnostic log. */
    private val secure: SharedPreferences = try {
        val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, PREFS_SECURE, master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        // Emulators / missing StrongBox: fall back to private prefs (still MODE_PRIVATE).
        context.getSharedPreferences(PREFS_SECURE_FALLBACK, Context.MODE_PRIVATE)
    }

    init {
        // Pass R moved keys to the encrypted store; carry over a key saved in the plain file.
        sp.getString(KEY_CLOUD_KEY, null)?.takeIf { it.isNotBlank() }?.let { old ->
            if (secure.getString(KEY_CLOUD_KEY, "").isNullOrBlank()) secure.edit().putString(KEY_CLOUD_KEY, old).apply()
            sp.edit().remove(KEY_CLOUD_KEY).apply()
        }
    }

    var mnnBaseUrl: String
        get() = sp.getString(KEY_MNN_URL, DEFAULT_MNN_URL) ?: DEFAULT_MNN_URL
        set(v) = sp.edit().putString(KEY_MNN_URL, v.trimEnd('/')).apply()

    var mnnApiKey: String
        get() = secure.getString(KEY_MNN_KEY, "mnn") ?: "mnn"
        set(v) = secure.edit().putString(KEY_MNN_KEY, v).apply()

    var mnnModel: String
        get() = sp.getString(KEY_MNN_MODEL, "mnn-local") ?: "mnn-local"
        set(v) = sp.edit().putString(KEY_MNN_MODEL, v).apply()

    var localEngine: String
        get() = sp.getString("local_engine", LOCAL_EMBEDDED) ?: LOCAL_EMBEDDED
        set(v) = sp.edit().putString("local_engine", v).apply()

    var routeMode: String
        get() = sp.getString(KEY_ROUTE, null)
            ?: if (cloudApiKey.isNotBlank()) ROUTE_CLOUD_ASSIST else ROUTE_LOCAL_ONLY // recommended default with a key
        set(v) = sp.edit().putString(KEY_ROUTE, v).apply()

    /** none | deepseek | openai_compatible */
    var cloudProvider: String
        get() = sp.getString(KEY_CLOUD_PROVIDER, PROVIDER_NONE) ?: PROVIDER_NONE
        set(v) = sp.edit().putString(KEY_CLOUD_PROVIDER, v).apply()

    var cloudBaseUrl: String
        get() = sp.getString(KEY_CLOUD_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_CLOUD_URL, v.trimEnd('/')).apply()

    var cloudApiKey: String
        get() = secure.getString(KEY_CLOUD_KEY, "") ?: ""
        set(v) = secure.edit().putString(KEY_CLOUD_KEY, v).apply()

    override var cloudModel: String
        get() = sp.getString(KEY_CLOUD_MODEL, "deepseek-chat") ?: "deepseek-chat"
        set(v) = sp.edit().putString(KEY_CLOUD_MODEL, v).apply()

    override var maxSteps: Int
        get() = sp.getInt(KEY_MAX_STEPS, 25)
        set(v) = sp.edit().putInt(KEY_MAX_STEPS, v.coerceIn(1, 100)).apply()

    override var allowCloudScreenshot: Boolean
        get() = sp.getBoolean(KEY_CLOUD_SHOT, false)
        set(v) = sp.edit().putBoolean(KEY_CLOUD_SHOT, v).apply()

    /** Optional vision helper (OpenAI-compatible, e.g. 阿里云百炼 qwen3-vl-plus) for screens text can't describe. */
    var visionBaseUrl: String
        get() = sp.getString("vision_url", "") ?: ""
        set(v) = sp.edit().putString("vision_url", v.trim().trimEnd('/')).apply()
    var visionApiKey: String
        get() = secure.getString("vision_key", "") ?: ""
        set(v) = secure.edit().putString("vision_key", v.trim()).apply()
    var visionModel: String
        get() = sp.getString("vision_model", "") ?: ""
        set(v) = sp.edit().putString("vision_model", v.trim()).apply()

    /** Use the on-device MNN VL model as a "看图" skill (describes the screenshot for a text brain). */
    var visionLocal: Boolean
        get() = sp.getBoolean("vision_local", false)
        set(v) = sp.edit().putBoolean("vision_local", v).apply()
    var visionLocalModel: String
        get() = sp.getString("vision_local_model", "") ?: ""
        set(v) = sp.edit().putString("vision_local_model", v.trim()).apply()
    /** Confirm before creating an alarm (default ON; user can turn it off). */
    /** Per-app learned memory (see AppMemory); plain UI facts only. */
    override fun appMemory(pkg: String): String? = sp.getString("appmem_$pkg", null)
    override fun setAppMemory(pkg: String, v: String) = sp.edit().putString("appmem_$pkg", v.take(4000)).apply()
    fun allAppMemory(): Map<String, String> = sp.all.filterKeys { it.startsWith("appmem_") }
        .mapKeys { it.key.removePrefix("appmem_") }.mapValues { it.value?.toString().orEmpty() }
    fun clearAppMemory(pkg: String) = sp.edit().remove("appmem_$pkg").apply()
    fun clearAppMemory() { val e = sp.edit(); sp.all.keys.filter { it.startsWith("appmem_") }.forEach { e.remove(it) }; e.apply() }

    /** DeepSeek thinking mode for agent steps (user choice; default off = faster). */
    var cloudThinking: Boolean
        get() = sp.getBoolean("cloud_thinking", false)
        set(v) = sp.edit().putBoolean("cloud_thinking", v).apply()

    /** Summarise compacted transcript spans with one cheap model call (default off: deterministic checkpoint). */
    override var modelCheckpoint: Boolean
        get() = sp.getBoolean("model_checkpoint", false)
        set(v) = sp.edit().putBoolean("model_checkpoint", v).apply()

    override var confirmAlarm: Boolean
        get() = sp.getBoolean("confirm_alarm", true)
        set(v) = sp.edit().putBoolean("confirm_alarm", v).apply()

    override var sensitivePackages: Set<String>
        get() = sp.getStringSet(KEY_SENSITIVE_PKGS, DEFAULT_SENSITIVE) ?: DEFAULT_SENSITIVE
        set(v) = sp.edit().putStringSet(KEY_SENSITIVE_PKGS, v).apply()

    /** Masked form for diagnostics / UI — never the raw key. */
    fun cloudKeyMasked(): String {
        val k = cloudApiKey
        return when {
            k.isEmpty() -> "(未设置)"
            k.length <= 8 -> "****"
            else -> k.take(4) + "…" + k.takeLast(4)
        }
    }

    companion object {
        private const val PREFS = "honor_phone_agent"
        private const val PREFS_SECURE = "honor_phone_agent_secure"
        private const val PREFS_SECURE_FALLBACK = "honor_phone_agent_secure_fallback"
        private const val KEY_MNN_URL = "mnn_url"
        private const val KEY_MNN_KEY = "mnn_key"
        private const val KEY_MNN_MODEL = "mnn_model"
        private const val KEY_ROUTE = "route_mode"
        private const val KEY_CLOUD_PROVIDER = "cloud_provider"
        private const val KEY_CLOUD_URL = "cloud_url"
        private const val KEY_CLOUD_KEY = "cloud_key"
        private const val KEY_CLOUD_MODEL = "cloud_model"
        private const val KEY_MAX_STEPS = "max_steps"
        private const val KEY_CLOUD_SHOT = "cloud_shot"
        private const val KEY_SENSITIVE_PKGS = "sensitive_pkgs"

        const val DEFAULT_MNN_URL = "http://127.0.0.1:8080/v1"
        const val ROUTE_LOCAL_FIRST = "local_first"
        const val ROUTE_HYBRID = "hybrid"
        const val ROUTE_LOCAL_ONLY = "local_only"
        const val ROUTE_CLOUD_ONLY = "cloud_only"
        /** Cloud decides every step; the on-device model assists in parallel (screen reading, offline fallback). */
        const val ROUTE_CLOUD_ASSIST = "cloud_assist"
        /** Local engine: embedded (MNN inside Tsuki) or the MNN Chat HTTP API. */
        const val LOCAL_EMBEDDED = "embedded"
        const val LOCAL_MNN_CHAT = "mnn_chat"

        const val PROVIDER_NONE = "none"
        const val PROVIDER_DEEPSEEK = "deepseek"
        const val PROVIDER_OPENAI = "openai_compatible"

        const val DEEPSEEK_BASE = "https://api.deepseek.com/v1"
        const val DEEPSEEK_MODEL = "deepseek-flash"

        val DEFAULT_SENSITIVE = setOf(
            "com.eg.android.AlipayGphone",
            "com.unionpay",
            "cmb.pb",
            "com.chinamworld.main",
            "com.android.bankabc",
            "com.icbc",
            "com.chinamworld.bocmbci"
        )
    }
}

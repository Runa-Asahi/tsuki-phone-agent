package com.runasakura.phoneagent.prefs

import android.content.Context
import android.content.SharedPreferences

class AgentPreferences(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var mnnBaseUrl: String
        get() = sp.getString(KEY_MNN_URL, DEFAULT_MNN_URL) ?: DEFAULT_MNN_URL
        set(v) = sp.edit().putString(KEY_MNN_URL, v.trimEnd('/')).apply()

    var mnnApiKey: String
        get() = sp.getString(KEY_MNN_KEY, "mnn") ?: "mnn"
        set(v) = sp.edit().putString(KEY_MNN_KEY, v).apply()

    var mnnModel: String
        get() = sp.getString(KEY_MNN_MODEL, "mnn-local") ?: "mnn-local"
        set(v) = sp.edit().putString(KEY_MNN_MODEL, v).apply()

    var routeMode: String
        get() = sp.getString(KEY_ROUTE, ROUTE_HYBRID) ?: ROUTE_HYBRID
        set(v) = sp.edit().putString(KEY_ROUTE, v).apply()

    var cloudBaseUrl: String
        get() = sp.getString(KEY_CLOUD_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_CLOUD_URL, v.trimEnd('/')).apply()

    var cloudApiKey: String
        get() = sp.getString(KEY_CLOUD_KEY, "") ?: ""
        set(v) = sp.edit().putString(KEY_CLOUD_KEY, v).apply()

    var cloudModel: String
        get() = sp.getString(KEY_CLOUD_MODEL, "gui-plus") ?: "gui-plus"
        set(v) = sp.edit().putString(KEY_CLOUD_MODEL, v).apply()

    var maxSteps: Int
        get() = sp.getInt(KEY_MAX_STEPS, 25)
        set(v) = sp.edit().putInt(KEY_MAX_STEPS, v.coerceIn(1, 100)).apply()

    var allowCloudScreenshot: Boolean
        get() = sp.getBoolean(KEY_CLOUD_SHOT, false)
        set(v) = sp.edit().putBoolean(KEY_CLOUD_SHOT, v).apply()

    var sensitivePackages: Set<String>
        get() = sp.getStringSet(KEY_SENSITIVE_PKGS, DEFAULT_SENSITIVE) ?: DEFAULT_SENSITIVE
        set(v) = sp.edit().putStringSet(KEY_SENSITIVE_PKGS, v).apply()

    companion object {
        private const val PREFS = "honor_phone_agent"
        private const val KEY_MNN_URL = "mnn_url"
        private const val KEY_MNN_KEY = "mnn_key"
        private const val KEY_MNN_MODEL = "mnn_model"
        private const val KEY_ROUTE = "route_mode"
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

        val DEFAULT_SENSITIVE = setOf(
            "com.eg.android.AlipayGphone",
            // WeChat: allow UI automation; send still needs confirm (SafetyGate)
            "com.unionpay",
            "cmb.pb",
            "com.chinamworld.main",
            "com.android.bankabc",
            "com.icbc",
            "com.chinamworld.bocmbci"
        )
    }
}

package com.runasakura.phoneagent.prefs

/**
 * The settings the agent loop and safety gate read. AgentPreferences (SharedPreferences) implements it on the
 * phone; the offline simulator supplies an in-memory one, so the real loop runs on the JVM.
 */
interface AgentSettings {
    val cloudModel: String
    val maxSteps: Int
    val allowCloudScreenshot: Boolean
    val confirmAlarm: Boolean
    val sensitivePackages: Set<String>
    /** Optional: summarise compacted transcript spans with one cheap model call (default off). */
    val modelCheckpoint: Boolean
    /** When stuck, deepen reasoning (look + diagnose + try a new strategy). Default on. */
    val deepThink: Boolean
    fun appMemory(pkg: String): String?
    fun setAppMemory(pkg: String, v: String)
}

/** In-memory settings (simulator/tests). */
class MemorySettings(
    override var cloudModel: String = "deepseek-flash",
    override var maxSteps: Int = 25,
    override var allowCloudScreenshot: Boolean = false,
    override var confirmAlarm: Boolean = true,
    override var sensitivePackages: Set<String> = emptySet(),
    override var modelCheckpoint: Boolean = false,
    override var deepThink: Boolean = true,
) : AgentSettings {
    val mem = HashMap<String, String>()
    override fun appMemory(pkg: String) = mem[pkg]
    override fun setAppMemory(pkg: String, v: String) { mem[pkg] = v.take(4000) }
}

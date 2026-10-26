package com.runasakura.phoneagent.sim

import com.runasakura.phoneagent.agent.AgentLoop
import com.runasakura.phoneagent.agent.SafetyGate
import com.runasakura.phoneagent.agent.SystemPrompt
import com.runasakura.phoneagent.llm.*
import com.runasakura.phoneagent.prefs.AgentPreferences
import com.runasakura.phoneagent.prefs.MemorySettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject

/** What the scripted model sees: the newest observation's element list, with id lookup by label. */
class Obs(val text: String) {
    private val single = Regex("""\[(e\d+)] ([^\n(]*?) \(""")
    private val group = Regex("""「([^」]+)」×\d+：((?:\[e\d+]\(\d+,\d+\) ?)+)""")
    fun id(label: String): String? =
        single.findAll(text).firstOrNull { it.groupValues[2].trim().contains(label) }?.groupValues?.get(1)
            ?: group.findAll(text).firstOrNull { it.groupValues[1].contains(label) }?.let { Regex("""\[(e\d+)]""").find(it.groupValues[2])?.groupValues?.get(1) }
    fun has(s: String) = text.substringAfter("# 屏幕元素").contains(s)
    fun note(s: String) = text.contains(s)
}

fun step(action: String, goalMatch: String = "否", state: String = "", expect: String = "", memory: String? = null): String =
    """{"screen":"x","state":"$state","goal_match":"$goalMatch","subgoal":"s","thought":"t","action":$action,"expect":"$expect"${memory?.let { ",\"memory\":\"$it\"" } ?: ""}}"""
fun tap(id: String?) = """{"type":"tap","node_id":"$id"}"""

class Scenario(
    val name: String,
    val goal: String,
    val apps: List<SimApp>,
    val policy: (Obs, SimWorld) -> String,
    val done: (SimWorld) -> Boolean,
    val setup: SimWorld.() -> Unit = {},
    val confirm: Boolean = true,
    val intent: String = """{"kind":"other"}""",
    /** HTTP status to inject for the n-th step request (0-based), e.g. 429. */
    val fault: (Int) -> Int? = { null },
    val maxSteps: Int = 20,
    val modelCheckpoint: Boolean = false,
    val w: Int = 1080, val h: Int = 2400,
    /** Fault for the n-th step request returning a 200 with this body instead of the policy (malformed output). */
    val garbage: (Int) -> String? = { null },
    /** Shizuku backend available (a [SimShell] over the world). */
    val shell: Boolean = false,
)

class SimRun(val result: AgentLoop.Result, val logs: List<String>, val world: SimWorld, val stepPrompts: List<JSONArray>,
             val confirms: List<String>, val calls: Map<String, Int>)

object SimRunner {
    fun run(sc: Scenario): SimRun {
        val world = SimWorld(sc.apps, sc.w, sc.h).also(sc.setup)
        val phone = SimPhone(world)
        if (sc.shell) phone.shellImpl = SimShell(world)
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>(); val prompts = java.util.concurrent.CopyOnWriteArrayList<JSONArray>(); val confirms = java.util.concurrent.CopyOnWriteArrayList<String>()
        val calls = java.util.concurrent.ConcurrentHashMap<String, Int>()
        var stepN = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buf = okio.Buffer(); chain.request().body!!.writeTo(buf)
            val body = JSONObject(buf.readUtf8()); val msgs = body.getJSONArray("messages")
            val sys = msgs.getJSONObject(0).optString("content")
            val last = msgs.getJSONObject(msgs.length() - 1).let { m -> m.opt("content").let { c -> if (c is JSONArray) c.getJSONObject(0).optString("text") else c.toString() } }
            val kind = when {
                sys.startsWith(SystemPrompt.SHORT) -> "step"
                sys.contains("解析成 JSON") -> "intent"
                sys.contains("规划器") -> "plan"
                sys.contains("验收员") -> "judge"
                sys.contains("经验整理员") -> "lesson"
                sys.contains("进度记录员") -> "checkpoint"
                else -> "other"
            }
            calls[kind] = (calls[kind] ?: 0) + 1
            var code = 200
            val content = when (kind) {
                "step" -> { val n = stepN++; val f = sc.fault(n); if (f != null) { code = f; "" } else { prompts += msgs; sc.garbage(n) ?: sc.policy(Obs(last), world) } }
                "intent" -> sc.intent
                "plan" -> """{"steps":["按目标操作"]}"""
                "judge" -> if (sc.done(world)) """{"done":true,"reason":"屏幕显示已完成"}""" else """{"done":false,"reason":"还没完成","next":"继续"}"""
                "lesson" -> """{"route":"","facts":[]}"""
                "checkpoint" -> """{"done":["已完成前几步"],"facts":["进度由模型整理"],"next":"继续目标"}"""
                else -> """{"none":true}"""
            }
            val prompt = (0 until msgs.length()).sumOf { LlmRouter.approxTokens(msgs.getJSONObject(it).opt("content").toString()) }
            val payload = if (code != 200) """{"error":{"message":"rate limited"}}""" else JSONObject()
                .put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", content))))
                .put("usage", JSONObject().put("prompt_tokens", prompt).put("completion_tokens", LlmRouter.approxTokens(content))).toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .apply { if (code == 429) header("Retry-After", "0.01") }
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val cloud = CloudClient("https://api.deepseek.com", "sk-test", "deepseek-flash", http = http,
            retryPolicy = LlmErrors.Policy(initialDelayMs = 5, maxDelayMs = 20))
        cloud.onRetry = { c, n, w -> logs += "云端${c.zh}，${w}ms 后自动重试（$n/3）" }
        val local = object : LlmClient { override val name = "mnn_local"
            override suspend fun chat(messages: List<ChatMessage>, temperature: Double) = LlmResponse("", name, 0, "unused") }
        val router = LlmRouter({ AgentPreferences.ROUTE_CLOUD_ONLY }, local, cloud).also { it.log = { m -> logs += m } }
        val settings = MemorySettings(maxSteps = sc.maxSteps, modelCheckpoint = sc.modelCheckpoint)
        val loop = AgentLoop(settings, router, SafetyGate(settings), onLog = { logs += it },
            onConfirm = { confirms += it; sc.confirm }, isStopped = { world.flags["stop"] == true }, phoneProvider = { phone }, pace = 0.0)
        val r = runBlocking { withTimeout(60_000) { loop.run(sc.goal) } }
        return SimRun(r, logs, world, prompts, confirms, calls)
    }
}

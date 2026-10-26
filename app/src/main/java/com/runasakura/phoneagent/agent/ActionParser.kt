package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.model.PlanStep
import org.json.JSONObject

object ActionParser {
    fun parse(raw: String): PlanStep {
        if (raw.isBlank()) {
            return PlanStep(
                thought = "empty_response",
                action = AgentAction.Fail("LLM returned empty content")
            )
        }
        val jsonText = extractJson(raw)
        val noThink = stripThink(raw)
        val obj = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            // Heuristic: model said launch settings in prose
            val lower = raw.lowercase()
            if (noThink.contains("打开设置") || lower.contains("launch") && lower.contains("settings") && noThink.isNotBlank()) {
                return PlanStep(
                    thought = "heuristic_from_prose",
                    action = AgentAction.Launch(appName = "设置")
                )
            }
            return PlanStep(
                thought = "parse_error",
                action = AgentAction.Fail(
                    "Cannot parse model JSON: ${e.message}; raw=${raw.take(300)}"
                )
            )
        }
        val thought = obj.optString("thought").ifBlank { obj.optString("reasoning") }
        var needShot = obj.optBoolean("need_screenshot", false)
        val backend = obj.nullableString("backend")

        val actionObj: JSONObject = when {
            obj.has("action") && obj.get("action") is JSONObject -> obj.getJSONObject("action")
            obj.has("action") && obj.get("action") is String -> {
                // {"action":"launch","package":"..."} flat-ish
                obj
            }
            obj.has("type") || obj.has("name") -> obj
            else -> obj
        }

        var type = actionObj.optString("type").ifBlank {
            actionObj.optString("name")
        }.ifBlank {
            // {"action":"tap", ...} where action is string
            if (obj.opt("action") is String) obj.optString("action") else ""
        }.lowercase().trim()

        // "look": the model asks to see the screenshot next step (does nothing on screen)
        if (type in setOf("look", "request_screenshot", "screenshot", "see", "observe")) { needShot = true; type = "look" }
        // Normalize aliases
        type = when (type) {
            "click", "press", "tap_text", "click_text", "touch" -> "tap"
            "longpress", "long-press" -> "long_press"
            "doubletap", "double-tap" -> "double_tap"
            "scroll" -> "swipe"
            "input", "enter", "enter_text", "set_text", "settext" -> "type"
            "finish", "complete", "success" -> "done"
            "error" -> "fail"
            "confirm" -> "ask_confirm"
            "takeover" -> "human_takeover"
            "open", "start_app" -> "launch"
            else -> type
        }

        // Thought-only / missing type: try recover from thought + fields
        if (type.isBlank()) {
            when {
                actionObj.has("package") || actionObj.has("app") || actionObj.has("app_name") ->
                    type = "launch"
                actionObj.has("text") && (actionObj.has("x") || actionObj.has("node_id")) ->
                    type = "tap"
                actionObj.has("text") && thought.contains("点击") -> type = "tap"
                thought.contains("打开设置") || thought.contains("启动设置") -> type = "launch"
                thought.contains("完成") || thought.contains("已打开") -> type = "done"
                else -> type = ""
            }
        }

        val action = when (type) {
            "launch" -> AgentAction.Launch(
                packageName = actionObj.nullableString("package")
                    ?: obj.nullableString("package"),
                appName = actionObj.nullableString("app")
                    ?: actionObj.nullableString("app_name")
                    ?: obj.nullableString("app")
                    ?: if (thought.contains("设置")) "设置" else null
            )
            "tap" -> AgentAction.Tap(
                x = actionObj.optIntOrNull("x") ?: obj.optIntOrNull("x"),
                y = actionObj.optIntOrNull("y") ?: obj.optIntOrNull("y"),
                nodeId = actionObj.nullableString("node_id") ?: obj.nullableString("node_id"),
                text = actionObj.nullableString("text") ?: obj.nullableString("text")
            )
            "long_press" -> AgentAction.LongPress(
                x = actionObj.optIntOrNull("x"),
                y = actionObj.optIntOrNull("y"),
                nodeId = actionObj.nullableString("node_id")
            )
            "double_tap" -> AgentAction.DoubleTap(
                x = actionObj.optInt("x"),
                y = actionObj.optInt("y")
            )
            "swipe" -> AgentAction.Swipe(
                x1 = actionObj.optInt("x1", actionObj.optInt("start_x")),
                y1 = actionObj.optInt("y1", actionObj.optInt("start_y")),
                x2 = actionObj.optInt("x2", actionObj.optInt("end_x")),
                y2 = actionObj.optInt("y2", actionObj.optInt("end_y")),
                durationMs = actionObj.optLong("duration_ms", 300)
            )
            "type" -> AgentAction.Type(
                text = actionObj.optString("text").ifBlank { obj.optString("text") },
                nodeId = actionObj.nullableString("node_id")
            )
            "clear" -> AgentAction.Clear(actionObj.nullableString("node_id"))
            "back" -> AgentAction.Back
            "home" -> AgentAction.Home
            "recents" -> AgentAction.Recents
            "notifications", "quick_settings", "power_dialog", "lock_screen", "take_screenshot", "split_screen" -> AgentAction.Global(type)
            "global" -> AgentAction.Global(actionObj.optString("name").lowercase().trim())
            "open_url", "open_uri", "deeplink", "deep_link" -> AgentAction.OpenUri(
                actionObj.optString("url").ifBlank { actionObj.optString("uri") }.trim(), actionObj.nullableString("package"))
            "intent" -> AgentAction.Intent(actionObj.optString("action").trim(), actionObj.nullableString("data"))
            "copy" -> actionObj.nullableString("node_id")?.let { AgentAction.Copy(it) } ?: AgentAction.SetClipboard(actionObj.optString("text"))
            "set_clipboard" -> AgentAction.SetClipboard(actionObj.optString("text"))
            "paste" -> AgentAction.Paste(actionObj.nullableString("node_id"))
            "read_clipboard", "paste_read" -> AgentAction.ReadClipboard
            "key", "press_key" -> AgentAction.Key(actionObj.optString("key").ifBlank { actionObj.optString("name") }.lowercase().trim())
            "scroll_find", "scroll_to" -> AgentAction.ScrollFind(actionObj.optString("text"),
                actionObj.optString("direction").ifBlank { "down" }.lowercase().trim(), actionObj.optInt("max", 6).coerceIn(1, 15))
            "wait" -> AgentAction.Wait(actionObj.optLong("ms", 800))
            "look" -> AgentAction.Wait(100)
            "done" -> AgentAction.Done(
                actionObj.optString("summary").ifBlank { thought }
            )
            "fail" -> AgentAction.Fail(
                actionObj.optString("reason").ifBlank { "model failed" }
            )
            "ask_confirm" -> AgentAction.AskConfirm(
                actionObj.optString("reason").ifBlank { "需要确认" }
            )
            "ask_user", "ask", "clarify", "question" -> AgentAction.AskUser(actionObj.optString("question").ifBlank { actionObj.optString("text") }.ifBlank { actionObj.optString("reason") }.trim())
            "shell", "adb", "adb_shell", "run_command", "exec" -> AgentAction.Shell(
                actionObj.optString("cmd").ifBlank { actionObj.optString("command") }.ifBlank { actionObj.optString("text") }.trim())
            "force_stop", "kill_app", "close_app" -> AgentAction.Shell("am force-stop " + (actionObj.nullableString("package") ?: actionObj.optString("app")).trim())
            "list_apps", "apps", "find_app", "installed_apps" -> AgentAction.ListApps(actionObj.optString("query").ifBlank { actionObj.optString("app") }.ifBlank { actionObj.optString("text") }.trim())
            "human_takeover" -> AgentAction.HumanTakeover(
                actionObj.optString("reason").ifBlank { "需要人工接管" }
            )
            else -> AgentAction.Fail(
                "Unknown action type='$type'; raw=${raw.take(240)}"
            )
        }
        return PlanStep(
            thought = thought,
            action = action,
            needScreenshot = needShot,
            backendHint = backend,
            expect = obj.nullableString("expect")?.take(80),
            memory = obj.nullableString("memory")?.take(80),
            subgoal = obj.nullableString("subgoal")?.take(60),
            screen = obj.nullableString("screen")?.take(30),
            goalMatch = obj.nullableString("goal_match")?.take(60)
        )
    }

    fun isInvalid(plan: PlanStep): Boolean =
        plan.action is AgentAction.Fail && (
            plan.thought == "parse_error" ||
                plan.thought == "empty_response" ||
                (plan.action as AgentAction.Fail).reason.startsWith("Unknown action") ||
                (plan.action as AgentAction.Fail).reason.startsWith("Cannot parse") ||
                (plan.action as AgentAction.Fail).reason.startsWith("LLM returned empty")
            )

    /**
     * Extract a JSON object from raw model output:
     * - bare JSON
     * - markdown fences
     * - leading/trailing prose
     * - first balanced {...}
     */
    fun extractJson(raw: String): String {
        var trimmed = raw.trim()
          .removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return trimmed

        // Strip <think>...</think> blocks some models emit
        trimmed = stripThink(trimmed)

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }

        val fence = Regex("```(?:json|JSON)?\\s*([\\s\\S]*?)```")
        fence.find(trimmed)?.groupValues?.getOrNull(1)?.trim()?.let { inner ->
            if (inner.startsWith("{")) return balancedObject(inner) ?: inner
        }

        balancedObject(trimmed)?.let { return it }

        // Last resort: from first { to last }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1)
        return trimmed
    }

    /** Remove <think>…</think>; also an unclosed <think> (truncated by max_tokens). */
    fun stripThink(s: String): String {
        var r = s.replace(Regex("(?s)<think>.*?</think>"), "")
        val open = r.indexOf("<think>")
        if (open >= 0) r = r.substring(0, open)
        return r.replace("</think>", "").trim()
    }

    fun hasThinkWithoutJson(raw: String): Boolean =
        raw.contains("<think>") && !stripThink(raw).contains("{")

    private fun balancedObject(s: String): String? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var escape = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.nullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).ifBlank { null }
    }
}

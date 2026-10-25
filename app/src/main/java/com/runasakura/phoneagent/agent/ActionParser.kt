package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.model.PlanStep
import org.json.JSONObject

object ActionParser {
    fun parse(raw: String): PlanStep {
        val jsonText = extractJson(raw)
        val obj = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            return PlanStep(
                thought = "parse_error",
                action = AgentAction.Fail("Cannot parse model JSON: ${raw.take(200)}")
            )
        }
        val thought = obj.optString("thought").ifBlank { obj.optString("reasoning") }
        val needShot = obj.optBoolean("need_screenshot", false)
        val backend = obj.nullableString("backend")
        val actionObj = when {
            obj.has("action") && obj.get("action") is JSONObject -> obj.getJSONObject("action")
            else -> obj
        }
        val type = actionObj.optString("type").ifBlank {
            actionObj.optString("name")
        }.lowercase()
        val action = when (type) {
            "launch" -> AgentAction.Launch(
                packageName = actionObj.nullableString("package"),
                appName = actionObj.nullableString("app") ?: actionObj.nullableString("app_name")
            )
            "tap", "click" -> AgentAction.Tap(
                x = actionObj.optIntOrNull("x"),
                y = actionObj.optIntOrNull("y"),
                nodeId = actionObj.nullableString("node_id"),
                text = actionObj.nullableString("text")
            )
            "long_press", "longpress" -> AgentAction.LongPress(
                x = actionObj.optIntOrNull("x"),
                y = actionObj.optIntOrNull("y"),
                nodeId = actionObj.nullableString("node_id")
            )
            "double_tap", "doubletap" -> AgentAction.DoubleTap(
                x = actionObj.optInt("x"),
                y = actionObj.optInt("y")
            )
            "swipe", "scroll" -> AgentAction.Swipe(
                x1 = actionObj.optInt("x1", actionObj.optInt("start_x")),
                y1 = actionObj.optInt("y1", actionObj.optInt("start_y")),
                x2 = actionObj.optInt("x2", actionObj.optInt("end_x")),
                y2 = actionObj.optInt("y2", actionObj.optInt("end_y")),
                durationMs = actionObj.optLong("duration_ms", 300)
            )
            "type", "input", "set_text" -> AgentAction.Type(
                text = actionObj.optString("text"),
                nodeId = actionObj.nullableString("node_id")
            )
            "clear" -> AgentAction.Clear(actionObj.nullableString("node_id"))
            "back" -> AgentAction.Back
            "home" -> AgentAction.Home
            "recents" -> AgentAction.Recents
            "wait" -> AgentAction.Wait(actionObj.optLong("ms", 800))
            "done", "finish", "complete" -> AgentAction.Done(
                actionObj.optString("summary").ifBlank { thought }
            )
            "fail", "error" -> AgentAction.Fail(
                actionObj.optString("reason").ifBlank { "model failed" }
            )
            "ask_confirm", "confirm" -> AgentAction.AskConfirm(
                actionObj.optString("reason").ifBlank { "需要确认" }
            )
            "human_takeover", "takeover" -> AgentAction.HumanTakeover(
                actionObj.optString("reason").ifBlank { "需要人工接管" }
            )
            else -> AgentAction.Fail("Unknown action type: $type")
        }
        return PlanStep(thought = thought, action = action, needScreenshot = needShot, backendHint = backend)
    }

    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) return trimmed
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        fence.find(trimmed)?.groupValues?.getOrNull(1)?.trim()?.let { return it }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1)
        return trimmed
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.nullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).ifBlank { null }
    }
}

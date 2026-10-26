package com.runasakura.phoneagent.model

/**
 * Structured action the LLM must return as JSON.
 * Schema documented in prompts/SYSTEM_PROMPT.md
 */
sealed class AgentAction {
    data class Launch(val packageName: String? = null, val appName: String? = null) : AgentAction()
    data class Tap(val x: Int? = null, val y: Int? = null, val nodeId: String? = null, val text: String? = null) : AgentAction()
    data class LongPress(val x: Int? = null, val y: Int? = null, val nodeId: String? = null) : AgentAction()
    data class DoubleTap(val x: Int, val y: Int) : AgentAction()
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Long = 300) : AgentAction()
    data class Type(val text: String, val nodeId: String? = null) : AgentAction()
    data class Clear(val nodeId: String? = null) : AgentAction()
    data object Back : AgentAction()
    data object Home : AgentAction()
    data object Recents : AgentAction()
    /** System surface: notifications / quick_settings / power_dialog / lock_screen / take_screenshot / split_screen. */
    data class Global(val name: String) : AgentAction()
    /** Open a URI / deep link (ACTION_VIEW). Dial/message/pay schemes require confirmation. */
    data class OpenUri(val uri: String, val packageName: String? = null) : AgentAction()
    /** Fire a system intent action (e.g. a settings page). */
    data class Intent(val action: String, val data: String? = null) : AgentAction()
    data class SetClipboard(val text: String) : AgentAction()
    /** Copy the exact text of an on-screen element (multi-app tasks: carry it to another app). */
    data class Copy(val nodeId: String) : AgentAction()
    /** Type what [Copy] captured into the focused / given field. */
    data class Paste(val nodeId: String? = null) : AgentAction()
    data object ReadClipboard : AgentAction()
    /** enter (IME action on the focused field) / volume_up / volume_down / mute / unmute. */
    data class Key(val name: String) : AgentAction()
    /** Scroll (up|down|left|right) until an element containing [text] is visible, at most [max] swipes. */
    data class ScrollFind(val text: String, val direction: String = "down", val max: Int = 6) : AgentAction()
    data class Wait(val ms: Long = 800) : AgentAction()
    data class Done(val summary: String = "") : AgentAction()
    data class Fail(val reason: String) : AgentAction()
    data class AskConfirm(val reason: String, val pendingAction: AgentAction? = null) : AgentAction()
    data class HumanTakeover(val reason: String) : AgentAction()
    /** The goal is ambiguous / missing information only the user has: stop and ask instead of guessing. */
    data class AskUser(val question: String) : AgentAction()
    /** Privileged system command through the optional Shizuku backend (validated argv; see ShellCommand). */
    data class Shell(val cmd: String) : AgentAction()
    /** Discover installed apps (label + package) matching [query] — for launch / deep links into unknown apps. */
    data class ListApps(val query: String = "") : AgentAction()
}

data class PlanStep(
    val thought: String = "",
    val action: AgentAction,
    val needScreenshot: Boolean = false,
    val backendHint: String? = null,
    /** Scratchpad: what the model expects to see after the action (checked next step). */
    val expect: String? = null,
    /** A UI fact the model learned (kept in task memory, e.g. "今日不再弹出是复选框"). */
    val memory: String? = null,
    val subgoal: String? = null,
    /** Model's own classification of the screen (generic, no fixed vocabulary). */
    val screen: String? = null,
    /** Model's own judgement whether the current state exactly matches the user's goal. */
    val goalMatch: String? = null
)

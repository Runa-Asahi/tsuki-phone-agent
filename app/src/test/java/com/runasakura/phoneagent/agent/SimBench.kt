package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.llm.LlmRouter
import com.runasakura.phoneagent.sim.*
import org.json.JSONArray
import org.junit.Test

/**
 * Measurement (not an assertion): runs every simulator scenario and reports success, steps, prompt tokens per model
 * step, prefix-cache hit rate (DeepSeek-style: the longest common prefix with the previous request, 64-token blocks)
 * and a simulated per-step latency = 250ms + 0.25ms/uncached tok + 0.03ms/cached tok + read cost.
 * Written to $TMP/sim_bench.txt for before/after comparison.
 */
class SimBench {
    private fun text(p: JSONArray) = (0 until p.length()).joinToString("\u0001") { p.getJSONObject(it).opt("content").toString() }

    @Test fun bench() {
        val list: List<Pair<String, Scenario>> = Scenarios.all.map { "S1" to it } + Scenarios2.all.map { "S2" to it } +
            HeldOut.all.map { "HO" to it.first } + listOf("S6" to Scenarios6.blindGame)
        var ok = 0; var runs = 0; var steps = 0L; var calls = 0L; var tok = 0L; var cached = 0L; var lat = 0.0; var allCalls = 0L; var taskLat = 0.0
        val per = StringBuilder(); val kinds = sortedMapOf<String, Int>()
        for ((tag, sc) in list) {
            val r = try { SimRunner.run(sc) } catch (e: Throwable) { per.append("$tag ${sc.name} CRASH ${e.javaClass.simpleName}\n"); continue }
            runs++; if (r.result.success) ok++
            steps += r.result.steps
            val seen = ArrayList<String>(); var scTok = 0L; var scCached = 0L
            val reads = r.logs.count { it.startsWith("前台=") }
            for (p in r.stepPrompts) {
                val t = text(p); val total = LlmRouter.approxTokens(t).toLong()
                // Provider caches every earlier request's prefix: best common prefix with any previous request.
                var k = 0
                for (prev in seen) { var j = 0; val m = minOf(t.length, prev.length); while (j < m && t[j] == prev[j]) j++; if (j > k) k = j }
                val hit = (LlmRouter.approxTokens(t.substring(0, k)) / 64) * 64L
                scTok += total; scCached += minOf(hit, total); seen += t
            }
            calls += r.stepPrompts.size; tok += scTok; cached += scCached
            // Serial (blocking) non-step calls: intent, judge, checkpoint, and plans that ran before a step
            // (old: "云端规划（本机按此执行）"; replans "新规划："). Lessons run after the task (async).
            val serialPlans = r.logs.count { it.startsWith("云端规划（本机按此执行）") || it.startsWith("新规划：") }
            val other = (r.calls["intent"] ?: 0) + (r.calls["judge"] ?: 0) + (r.calls["checkpoint"] ?: 0) + serialPlans
            allCalls += r.calls.values.sum(); r.calls.forEach { (k, v) -> kinds[k] = (kinds[k] ?: 0) + v }
            taskLat += r.stepPrompts.size * 250.0 + (scTok - scCached) * 0.25 + scCached * 0.03 + other * 700.0 + reads * (if (sc.w > sc.h) 160.0 else 90.0)
            lat += r.stepPrompts.size * 250.0 + (scTok - scCached) * 0.25 + scCached * 0.03 + reads * (if (sc.w > sc.h) 160.0 else 90.0)
            per.append("$tag ${sc.name} ok=${r.result.success} steps=${r.result.steps} calls=${r.stepPrompts.size} tok=$scTok hit=${if (scTok > 0) scCached * 100 / scTok else 0}%\n")
        }
        val out = "RUNS=$runs OK=$ok MODEL_CALLS=$calls STEPS=$steps AVG_PROMPT_TOK_PER_CALL=${tok / maxOf(calls, 1)} " +
            "CACHE_HIT=${if (tok > 0) cached * 1000 / tok / 10.0 else 0.0}% SIM_LATENCY_PER_CALL_MS=${(lat / maxOf(calls, 1)).toInt()} " +
            "TOTAL_TOK=$tok ALL_MODEL_CALLS=$allCalls SIM_TASK_LATENCY_AVG_MS=${(taskLat / maxOf(runs, 1)).toInt()} KINDS=$kinds\n" + per
        java.io.File(System.getProperty("java.io.tmpdir"), "sim_bench.txt").writeText(out)
    }
}

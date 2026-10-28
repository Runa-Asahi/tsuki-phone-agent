package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.llm.LlmRouter
import com.runasakura.phoneagent.sim.*
import org.junit.Test

/** Prints prompt-size statistics per simulator scenario (measurement, not an assertion). */
class SimTokenReport {
    @Test fun report() {
        val out = StringBuilder()
        var totAll = 0L; var totNew = 0L; var n = 0
        val list = Scenarios.all
        for (sc in list) {
            val r = SimRunner.run(sc)
            for (p in r.stepPrompts) {
                val toks = (0 until p.length()).map { LlmRouter.approxTokens(p.getJSONObject(it).opt("content").toString()) }
                totAll += toks.sum(); totNew += toks.last(); n++
            }
            if (sc === Scenarios.music) r.stepPrompts.lastOrNull()?.let { p -> out.append("SAMPLE_LAST_MSG:\n").append(p.getJSONObject(p.length() - 1).opt("content")).append('\n') }
        }
        out.append("TOKENS steps=$n avgPrompt=${totAll / maxOf(n, 1)} avgNewestObs=${totNew / maxOf(n, 1)}\n")
        var a2 = 0L; var b2 = 0L; var n2 = 0
        for (sc in Scenarios.all + Scenarios2.all) for (p in SimRunner.run(sc).stepPrompts) {
            val toks = (0 until p.length()).map { LlmRouter.approxTokens(p.getJSONObject(it).opt("content").toString()) }
            a2 += toks.sum(); b2 += toks.last(); n2++
        }
        out.append("TOKENS_ALL30 steps=$n2 avgPrompt=${a2 / maxOf(n2, 1)} avgNewestObs=${b2 / maxOf(n2, 1)}\n")
        java.io.File(System.getProperty("java.io.tmpdir"), "sim_tokens.txt").writeText(out.toString())
        println(out)
    }
}

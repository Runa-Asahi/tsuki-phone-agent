package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.sim.*
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldOutTest {
    @Test fun heldOutGeneralizationSuite() {
        val res = HeldOut.all.map { (sc, exp) ->
            val r = runCatching { SimRunner.run(sc) }.getOrNull()
            Triple(sc.name, r != null && HeldOut.passed(sc, exp, r), r)
        }
        val pass = res.count { it.second }
        val report = "HELDOUT pass=$pass/${res.size}\n" + res.joinToString("\n") { (n, ok, r) ->
            "${if (ok) "PASS" else "FAIL"} $n :: ${r?.result} :: ${r?.world?.log?.joinToString(" ")}" }
        java.io.File("/tmp/heldout.txt").writeText(report + "\n\n" + res.filter { !it.second }.joinToString("\n\n=====\n") { (n, _, r) ->
            "## $n\n" + r?.logs?.filterNot { it.startsWith("LLM raw") || it.startsWith("云端") || it.contains("响应返回") || it.startsWith("脑=") || it.startsWith("模型请求") }?.joinToString("\n") })
        assertTrue(report, pass == res.size)
    }
}

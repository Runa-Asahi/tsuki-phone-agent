package com.runasakura.phoneagent.agent

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Semantic lesson retrieval with REAL vectors: bge_vecs.tsv holds bge-large-zh-MNN outputs (host MNN build, same
 * model files the app downloads) for 14 lessons and 12 phone goals (query role with bge's retrieval instruction).
 */
class SemanticRetrievalTest {
    private val rows = javaClass.classLoader!!.getResourceAsStream("bge_vecs.tsv")!!.bufferedReader().readLines().map { l ->
        val p = l.split('\t'); Triple(p[0] == "q", p[1], p[2].split(',').map { it.toFloat() }.toFloatArray())
    }
    private val docs = rows.filter { !it.first }.map { it.second }
    private val queries = rows.filter { it.first }.map { it.second }
    private val vec = rows.associate { (if (it.first) "q:" else "d:") + it.second to it.third }
    private var encodes = 0
    private fun index(store: File? = null) = SemanticIndex("bge-test", { t, q -> encodes++; vec[(if (q) "q:" else "d:") + t] }, store, 0.55, 0.80)

    /** query → expected lesson. */
    private val gold = mapOf(0 to 0, 1 to 1, 2 to 2, 3 to 5, 4 to 6, 5 to 7, 6 to 8, 7 to 9, 8 to 10, 9 to 12, 10 to 13, 11 to 3,
        // Paraphrases with little or no character overlap with the lesson — where bigram retrieval fails.
        12 to 2, 13 to 5, 14 to 6, 15 to 7, 16 to 1, 17 to 9, 18 to 13, 19 to 0)

    @After fun reset() { Relevance.index = null; Relevance.clearBoost() }

    private fun top1(score: (String, String) -> Double) = gold.count { (qi, di) ->
        val q = queries[qi]; docs.indices.maxByOrNull { score(docs[it], q) } == di || (qi == 9 && docs.indices.maxByOrNull { score(docs[it], q) } == 1)
    }

    @Test fun semanticBeatsBigram() {
        val idx = index(); idx.prepare(null, docs); queries.forEach { idx.prepare(it, emptyList()) }
        val bigram = top1 { d, q -> Similarity.score(d, q) }
        Relevance.index = idx
        val blended = top1 { d, q -> Relevance.score(d, q) }
        println("top-1: bigram $bigram/${gold.size}, semantic+bigram $blended/${gold.size}")
        assertTrue("semantic $blended vs bigram $bigram", blended >= 17 && bigram <= 13)
    }

    @Test fun fallbackWithoutModelIsBigram() {
        Relevance.index = null
        assertEquals(Similarity.score(docs[2], queries[2]), Relevance.score(docs[2], queries[2]), 1e-9)
    }

    @Test fun scoringNeverEncodesAndCachePersists() {
        val f = File.createTempFile("vec", ".bin"); f.delete()
        val a = index(f); Relevance.index = a
        assertNull(a.score(docs[0], queries[0]))                    // not prepared → no inference during scoring
        assertEquals(0, encodes)
        assertEquals(docs.size + 1, a.prepare(queries[0], docs))
        assertTrue(f.isFile && f.length() in 60_000..160_000)        // float32: ~4 KB per vector
        val b = index(f); encodes = 0
        assertEquals(0, b.prepare(queries[0], docs))                 // reloaded from disk, nothing re-encoded
        assertEquals(0, encodes)
        assertEquals(a.cosine(docs[0], queries[0])!!, b.cosine(docs[0], queries[0])!!, 1e-9)
        // Persisted vectors reproduce the float cosine (tsv has 4 decimals).
        val exact = vec["d:" + docs[0]]!!.zip(vec["q:" + queries[0]]!!).sumOf { (x, y) -> (x * y).toDouble() }
        assertEquals(exact, b.cosine(docs[0], queries[0])!!, 1e-6)
        // A different model id never reuses these vectors.
        assertEquals(0, SemanticIndex("other", { _, _ -> null }, f, 0.6, 0.8).size())
    }

    @Test fun budgetStopsEncoding() {
        var t = 0L
        val idx = SemanticIndex("bge-test", { tx, q -> t += 400; vec[(if (q) "q:" else "d:") + tx] }, null, 0.55, 0.80)
        val n = idx.prepare(queries[0], docs, budgetMs = 1000) { t }
        assertTrue("encoded $n", n in 2..4)
    }

    @Test fun retrievalUsesSemanticInAppMemory() {
        val idx = index(); Relevance.index = idx
        val now = 1_800_000_000_000L
        val mem = AppMemory(docs.take(12).map { AppMemory.Entry(it, 2, now) }, emptyList())
        idx.prepare(queries[2], mem.retrievalTexts())
        val hint = mem.hint(queries[2], now, maxFacts = 1)!!
        assertTrue(hint, hint.contains("闹钟页右下角"))
        // confirmShown verifies the alarm lesson (semantically relevant), not unrelated ones.
        val c = mem.confirmShown(queries[2], now)
        assertEquals(3, c.factEntries.first { it.text == docs[2] }.conf)
        assertEquals(2, c.factEntries.first { it.text == docs[4] }.conf)
    }

    @Test fun rerankBoost() {
        val cands = listOf("a", "b", "c")
        assertEquals(mapOf("c" to 1.0, "a" to 0.8), Relevance.boostsFrom(cands, listOf(2, 0, 9)))
        Relevance.setBoost("g", mapOf("设置页底部有退出登录" to 1.0))
        assertTrue(Relevance.score("设置页底部有退出登录", "g") >= 0.5)
        assertEquals(0.0, Relevance.score("设置页底部有退出登录", "other goal"), 0.2)
    }
}

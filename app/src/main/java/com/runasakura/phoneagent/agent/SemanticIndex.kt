package com.runasakura.phoneagent.agent

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Semantic relevance for learned lessons / routes / cross-app patterns.
 *
 * - [Encoder] turns text into an L2-normalised vector (on-device bge-large-zh via MNN; null when the model is absent).
 * - Vectors are cached per (model, role, text) as float32 (4 KB for 1024-d) and persisted to disk, so a lesson
 *   is encoded once, ever; queries (the goal) are encoded once per task.
 * - Scoring NEVER computes: [score] reads the cache only, so prompt building cannot stall on inference. [prepare]
 *   (called off the main thread at task start / after learning, with a time budget) fills the cache.
 * - Cosine is calibrated per model to 0..1 ([lo], [hi]) so it is comparable with the character-bigram score that
 *   remains the fallback. Final relevance = bigram + 0.5·semantic (+ optional LLM-rerank boost): on real bge vectors
 *   for 20 phone goals × 14 lessons, top-1 went bigram 13/20 → semantic only 14/20 → this fusion 17/20.
 */
class SemanticIndex(
    val modelId: String,
    private val encoder: Encoder,
    private val store: File?,
    val lo: Double,
    val hi: Double,
    private val maxEntries: Int = 3000,
) {
    fun interface Encoder { fun encode(text: String, query: Boolean): FloatArray? }

    /** Unit vector stored as float32 (4 KB for 1024-d; int8 lost up to ±0.04 cosine on bge's outlier dimensions). */
    class Vec(val v: FloatArray) {
        fun dot(o: Vec): Double { var s = 0.0; val n = minOf(v.size, o.v.size); for (i in 0 until n) s += v[i] * o.v[i]; return s }
    }

    private val cache = LinkedHashMap<String, Vec>(256, 0.75f, true)
    private var loaded = false
    private var dirty = false
    @Volatile var lastPrepareMs = 0L; private set

    private fun key(text: String, query: Boolean): String {
        val md = MessageDigest.getInstance("SHA-1").digest(("$modelId|${if (query) 'q' else 'd'}|${norm(text)}").toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(24)
    }

    @Synchronized private fun ensureLoaded() {
        if (loaded) return; loaded = true
        val f = store ?: return
        if (!f.isFile) return
        runCatching {
            DataInputStream(f.inputStream().buffered()).use { ins ->
                if (ins.readInt() != MAGIC || ins.readUTF() != modelId) return
                val n = ins.readInt()
                repeat(n) {
                    val k = ins.readUTF(); val len = ins.readInt()
                    cache[k] = Vec(FloatArray(len) { ins.readFloat() })
                }
            }
        }.onFailure { cache.clear() }
    }

    @Synchronized fun save() {
        val f = store ?: return
        if (!dirty) return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { o ->
                o.writeInt(MAGIC); o.writeUTF(modelId); o.writeInt(cache.size)
                for ((k, v) in cache) { o.writeUTF(k); o.writeInt(v.v.size); v.v.forEach(o::writeFloat) }
            }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            dirty = false
        }
    }

    @Synchronized private fun get(text: String, query: Boolean): Vec? { ensureLoaded(); return cache[key(text, query)] }
    @Synchronized private fun put(text: String, query: Boolean, v: Vec) {
        cache[key(text, query)] = v; dirty = true
        while (cache.size > maxEntries) cache.remove(cache.keys.first())
    }
    @Synchronized fun size(): Int { ensureLoaded(); return cache.size }

    /**
     * Encode [goal] (query role) and any [texts] (document role) not cached yet, within [budgetMs].
     * Returns how many vectors were computed. Safe to call repeatedly; persists when something changed.
     */
    fun prepare(goal: String?, texts: Collection<String>, budgetMs: Long = 1500, queries: Collection<String> = emptyList(),
                clock: () -> Long = System::currentTimeMillis): Int {
        val t0 = clock(); var n = 0
        fun one(t: String, q: Boolean): Boolean {
            if (t.isBlank() || get(t, q) != null) return true
            if (clock() - t0 > budgetMs) return false
            val v = runCatching { encoder.encode(norm(t), q) }.getOrNull() ?: return false
            put(t, q, Vec(v)); n++; return true
        }
        if (goal != null && !one(goal, true)) { lastPrepareMs = clock() - t0; return n }
        for (t in texts.distinct()) if (!one(t, false)) break
        for (t in queries.distinct()) if (!one(t, true)) break
        lastPrepareMs = clock() - t0
        if (n > 0) save()
        return n
    }

    /** Raw cosine from cached vectors only; null when either side is not cached. */
    fun cosine(text: String, goal: String): Double? {
        val a = get(text, false) ?: return null; val b = get(goal, true) ?: return null
        return a.dot(b)
    }

    /** Cosine between two goals (query role, e.g. a skill's goal vs the current goal); null when not cached. */
    fun cosineQQ(a: String, b: String): Double? { val x = get(a, true) ?: return null; val y = get(b, true) ?: return null; return x.dot(y) }

    /** Cosine between two stored lessons (document role) — used for diversity (MMR); null when not cached. */
    fun docCosine(a: String, b: String): Double? { val x = get(a, false) ?: return null; val y = get(b, false) ?: return null; return x.dot(y) }

    /** Calibrated 0..1 semantic relevance from cache; null when not available. */
    fun score(text: String, goal: String): Double? = cosine(text, goal)?.let { ((it - lo) / (hi - lo)).coerceIn(0.0, 1.0) }

    companion object {
        private const val MAGIC = 0x54534B32 // "TSK2"
        /** Canonical text for keys and encoding: drop bookkeeping tags (（本应用实测）/（未验证）) and collapse spaces. */
        fun norm(s: String) = s.replace(TAGS, "").trim().replace(Regex("\\s+"), " ").take(400)
        private val TAGS = Regex("（(本应用实测|未验证|已验证\\d*次?)）")
    }
}

/**
 * The one relevance function used by learned-memory retrieval. Without an index (no embedding model) this is exactly
 * the old character-bigram [Similarity.score], so behaviour degrades gracefully.
 */
object Relevance {
    @Volatile var index: SemanticIndex? = null
    /** User setting: let the cloud model rerank the top lessons once per task. */
    @Volatile var llmRerank = false
    /** Hook after a prepare() batch (device: unload the embedder when the big local LLM is resident). */
    @Volatile var afterPrepare: (() -> Unit)? = null
    /** Optional per-goal boost from a cloud LLM rerank (text → 0..1), cleared every task. */
    @Volatile private var boost: Pair<String, Map<String, Double>>? = null

    fun setBoost(goal: String, m: Map<String, Double>) { boost = goal to m }
    /** Rerank order → boost: first 1.0, then 0.8, 0.6 … ; lessons the reranker left out get none. */
    fun boostsFrom(candidates: List<String>, order: List<Int>): Map<String, Double> =
        order.mapIndexedNotNull { rank, i -> candidates.getOrNull(i)?.let { it to (1.0 - rank * 0.2).coerceAtLeast(0.2) } }.toMap()
    fun clearBoost() { boost = null }

    fun semantic(text: String, goal: String): Double? = index?.score(text, goal)

    private fun boostOf(text: String, goal: String) = boost?.takeIf { it.first == goal }?.second?.get(text) ?: 0.0

    /** Ranking score (0..1). Without an index this is exactly [Similarity.score]. */
    fun score(text: String, goal: String): Double {
        val b = Similarity.score(text, goal)
        val s = semantic(text, goal) ?: 0.0
        return minOf(1.0, b + s * 0.5 + boostOf(text, goal) * 0.5)
    }

    /**
     * Evidence gate (used to verify lessons after a success): stricter than ranking so an unrelated lesson cannot
     * farm confidence — bigram ≥ [bigramMin], or a clearly-related meaning (semantic ≥ 0.5), or picked by the reranker.
     */
    fun relevant(text: String, goal: String, bigramMin: Double): Boolean =
        Similarity.score(text, goal) >= bigramMin || (semantic(text, goal) ?: 0.0) >= 0.5 || boostOf(text, goal) > 0

    /**
     * Slots that make two goals different tasks even when they mean "the same kind of thing": quoted text, numbers,
     * Latin words, and the 1–4 Han characters after a recipient/target marker (给/跟/和/向/叫/找/问/打给/发给).
     * Pure; unit-tested ("给妈妈发微信" vs "给爸爸发微信" → different).
     */
    fun slots(goal: String): Set<String> {
        val out = HashSet<String>()
        Regex("[「“\"『]([^」”\"』]{1,30})[」”\"』]").findAll(goal).forEach { out += "q:" + it.groupValues[1] }
        Regex("\\d+(?:[:.：点]\\d+)?").findAll(goal).forEach { out += "n:" + it.value }
        Regex("[A-Za-z][A-Za-z0-9._-]+").findAll(goal).forEach { out += "w:" + it.value.lowercase() }
        Regex("(?:打给|发给|给|跟|和|向|叫|找|问)([\\u4e00-\\u9fa5]{1,4}?)(?=发|说|打|聊|转|回|讲|的|一|，|,|$|\\s)").findAll(goal).forEach { out += "p:" + it.groupValues[1] }
        return out
    }

    /**
     * Goal-to-goal match for skills (replayable step suggestions): bigram as before, or — with an index — the same
     * meaning (semantic ≥ 0.6) AND identical slots, so "给妈妈发微信" never reuses "给爸爸发微信"'s skill.
     */
    fun sameTask(a: String, b: String, bigramMin: Double): Boolean {
        if (Similarity.score(a, b) >= bigramMin) return slots(a) == slots(b) || Similarity.score(a, b) >= 0.95
        val s = index?.let { idx -> idx.cosineQQ(a, b)?.let { ((it - idx.lo) / (idx.hi - idx.lo)).coerceIn(0.0, 1.0) } } ?: return false
        return s >= 0.6 && slots(a) == slots(b)
    }

    /** Near-duplicate test for diversity: same wording (bigram ≥ 0.7) or same meaning (bge doc cosine ≥ 0.92). */
    fun redundant(a: String, b: String): Boolean = Similarity.score(a, b) >= 0.7 || (index?.docCosine(a, b) ?: 0.0) >= 0.92

    /** Short human description for logs / settings. */
    fun mode(): String = index?.let { "语义检索（${it.modelId}，本机）+ 字符相似度" } ?: "字符相似度（未安装语义模型）"
}

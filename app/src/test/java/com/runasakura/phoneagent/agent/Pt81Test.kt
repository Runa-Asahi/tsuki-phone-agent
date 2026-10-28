package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.llm.ModelStore
import com.runasakura.phoneagent.llm.TextEmbedder
import com.runasakura.phoneagent.util.Idle
import com.runasakura.phoneagent.util.StorageJanitor
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** pt81: retrieval quality (slots guard, MMR, tag-normalisation), memory/storage hygiene policies. */
class Pt81Test {
    @After fun reset() { Relevance.index = null; Relevance.clearBoost() }

    @Test fun slotsSeparatePeopleNumbersQuotes() {
        assertNotEquals(Relevance.slots("给妈妈发微信说我晚点回家"), Relevance.slots("给爸爸发微信说我晚点回家"))
        assertEquals(Relevance.slots("给妈妈发微信说晚点回"), Relevance.slots("给妈妈发微信，说晚点回"))
        assertNotEquals(Relevance.slots("把闹钟改到7点"), Relevance.slots("把闹钟改到8点"))
        assertNotEquals(Relevance.slots("搜索「晴天」"), Relevance.slots("搜索「七里香」"))
        assertNotEquals(Relevance.slots("open bilibili"), Relevance.slots("open youtube"))
    }

    @Test fun skillNeverReusedForAnotherPerson() {
        val now = 1_800_000_000_000L
        val sk = Skill("给妈妈发微信说我晚点回家", listOf("点「妈妈」", "输入文字", "点「发送」"))
        val m = AppMemory(emptyList(), emptyList(), listOf(AppMemory.Entry(sk.encode(), 3, now)))
        assertNotNull(m.skillFor("给妈妈发微信说我晚点回家", now))
        assertNull(m.skillFor("给爸爸发微信说我晚点回家", now))
    }

    @Test fun skillSemanticMatchNeedsSameSlots() {
        val vec = mapOf("q:打开微信扫一扫" to floatArrayOf(1f, 0f), "q:用微信扫个码" to floatArrayOf(0.98f, 0.199f),
            "q:给妈妈打视频" to floatArrayOf(0f, 1f), "q:跟爸爸视频通话" to floatArrayOf(0.05f, 0.9987f))
        val idx = SemanticIndex("t", { t, q -> vec[(if (q) "q:" else "d:") + t] }, null, 0.55, 0.80)
        idx.prepare(null, emptyList(), queries = listOf("打开微信扫一扫", "用微信扫个码", "给妈妈打视频", "跟爸爸视频通话"))
        Relevance.index = idx
        assertTrue(Relevance.sameTask("打开微信扫一扫", "用微信扫个码", 0.62))     // paraphrase, no slots
        assertFalse(Relevance.sameTask("给妈妈打视频", "跟爸爸视频通话", 0.62))    // close meaning, different person
    }

    @Test fun mmrSkipsNearDuplicates() {
        val now = 1_800_000_000_000L
        val mem = AppMemory(listOf(AppMemory.Entry("搜索框在首页顶部", 3, now), AppMemory.Entry("首页顶部有搜索框", 3, now),
            AppMemory.Entry("搜索结果第一个就是歌曲", 2, now)), emptyList())
        val vec = mapOf("d:搜索框在首页顶部" to floatArrayOf(1f, 0f), "d:首页顶部有搜索框" to floatArrayOf(0.99f, 0.141f),
            "d:搜索结果第一个就是歌曲" to floatArrayOf(0.6f, 0.8f))
        val idx = SemanticIndex("t", { t, q -> vec[(if (q) "q:" else "d:") + t] }, null, 0.55, 0.80)
        idx.prepare(null, mem.retrievalTexts()); Relevance.index = idx
        val shown = mem.shown("搜索歌曲", now, 2).map { it.text }
        assertEquals(2, shown.size)
        assertTrue(shown.toString(), "搜索结果第一个就是歌曲" in shown)
    }

    @Test fun tagsDoNotChangeVectorsOrKeys() {
        assertEquals("系统返回键无效", SemanticIndex.norm("（本应用实测）系统返回键无效"))
        assertEquals("先点关闭", SemanticIndex.norm("先点关闭（未验证）"))
        var n = 0
        val idx = SemanticIndex("t", { _, _ -> n++; floatArrayOf(1f) }, null, 0.5, 0.8)
        idx.prepare(null, listOf("（本应用实测）系统返回键无效", "系统返回键无效"))
        assertEquals(1, n)
    }

    @Test fun probeRejectsBadVectors() {
        assertTrue(TextEmbedder.probeOk(floatArrayOf(0.6f, 0.8f)))
        assertFalse(TextEmbedder.probeOk(floatArrayOf(Float.NaN, 1f)))
        assertFalse(TextEmbedder.probeOk(floatArrayOf(3f, 4f)))
        assertFalse(TextEmbedder.probeOk(FloatArray(0)))
        assertFalse(TextEmbedder.probeOk(null))
    }

    @Test fun idlePolicy() {
        assertNull(Idle.llmIdleMs(appVisible = true, taskRunning = true))
        assertEquals(10 * 60_000L, Idle.llmIdleMs(appVisible = true, taskRunning = false))
        assertEquals(2 * 60_000L, Idle.llmIdleMs(appVisible = false, taskRunning = false))
    }

    @Test fun duplicateModelDetection() {
        val a = listOf("config.json" to 300L, "llm.mnn" to 5_000L, "llm.mnn.weight" to 1_500_000_000L)
        assertTrue(ModelStore.sameFiles(a, a.reversed() + ("x.part" to 10L)))
        assertFalse(ModelStore.sameFiles(a, a.dropLast(1) + ("llm.mnn.weight" to 1_400_000_000L)))
        assertFalse(ModelStore.sameFiles(emptyList(), emptyList()))
    }

    @Test fun janitorPlanIsSafe() {
        val tmp = createTempDir("jan")
        fun f(name: String, ageMs: Long) = File(tmp, name).apply { writeText("x"); setLastModified(System.currentTimeMillis() - ageMs) }
        val img = f("mnn_img_0.jpg", 0); val oldPart = f("w.part", StorageJanitor.STALE_PART_MS + 60_000); val newPart = f("v.part", 60_000)
        val mmap = File(tmp, "mnn_mmap").apply { mkdirs() }; val vec = f("vectors_x.bin", 0)
        val now = System.currentTimeMillis()
        val auto = StorageJanitor.plan(listOf(img), listOf(oldPart, newPart), emptyList(), mmap, listOf(vec), now, downloading = false, manual = false, modelLoaded = false)
        assertEquals(setOf(img, oldPart), auto.delete.toSet())                      // fresh .part, caches and vectors kept
        val dl = StorageJanitor.plan(listOf(img), listOf(oldPart), emptyList(), mmap, listOf(vec), now, downloading = true, manual = true, modelLoaded = false)
        assertFalse(oldPart in dl.delete)                                           // never touch .part during a download
        val loaded = StorageJanitor.plan(emptyList(), emptyList(), emptyList(), mmap, listOf(vec), now, downloading = false, manual = true, modelLoaded = true)
        assertFalse(mmap in loaded.delete); assertTrue(vec in loaded.delete)        // mmap cache only when nothing is loaded
        val manual = StorageJanitor.plan(emptyList(), listOf(newPart), emptyList(), mmap, emptyList(), now, downloading = false, manual = true, modelLoaded = false)
        assertTrue(newPart in manual.delete && mmap in manual.delete)
        tmp.deleteRecursively()
    }
}

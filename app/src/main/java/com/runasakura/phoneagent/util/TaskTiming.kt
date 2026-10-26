package com.runasakura.phoneagent.util

/** Per-task wall-time breakdown (读屏/OCR/等待界面/模型/复用次数), logged at task end. */
object TaskTiming {
    private val ms = linkedMapOf<String, Long>()
    private val n = linkedMapOf<String, Int>()
    @Volatile private var start = 0L

    @Synchronized fun reset() { ms.clear(); n.clear(); start = System.currentTimeMillis() }
    @Synchronized fun add(cat: String, dt: Long) { ms[cat] = (ms[cat] ?: 0) + dt; n[cat] = (n[cat] ?: 0) + 1 }
    @Synchronized fun count(cat: String) { n[cat] = (n[cat] ?: 0) + 1 }
    inline fun <T> time(cat: String, block: () -> T): T {
        val t0 = System.currentTimeMillis()
        try { return block() } finally { add(cat, System.currentTimeMillis() - t0) }
    }

    @Synchronized fun summary(): String {
        val total = if (start == 0L) 0 else System.currentTimeMillis() - start
        val parts = n.keys.joinToString("，") { k -> ms[k]?.let { "$k ${it}ms/${n[k]}次" } ?: "$k ${n[k]}次" }
        return "总 ${total}ms：" + parts.ifBlank { "（未读屏、未调用模型）" }
    }
}

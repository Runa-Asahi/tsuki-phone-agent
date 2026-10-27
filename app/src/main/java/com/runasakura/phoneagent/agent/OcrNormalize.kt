package com.runasakura.phoneagent.agent

/**
 * Soft OCR cleanup for labels shown to the model. Games / custom fonts produce systematic misreads
 * (对战→对或/对載, 排位赛→非位賽). We never invent text that isn't close to what OCR saw: only apply a
 * small confusion table + keep the original in parentheses when changed, so the model can still verify.
 *
 * Pure / unit-tested. Generic — no game-specific branches beyond shared CJK glyph confusions.
 */
object OcrNormalize {
    /** OCR glyph / bigram confusions → intended reading. Longest keys first. */
    private val MAP = listOf(
        "非位賽" to "排位赛", "非位赛" to "排位赛", "排位賽" to "排位赛",
        "对載" to "对战", "对或" to "对战", "対战" to "对战", "對戰" to "对战", "対戦" to "对战",
        "建康" to "健康", "沉进" to "沉迷", "适龄提堤示" to "适龄提示", "适岭提堤示" to "适龄提示",
        "领敢" to "领取", "領取" to "领取", "跳過程" to "跳过", "跳過" to "跳过", "关閑" to "关闭", "關閉" to "关闭",
        "戲" to "戏", "戰" to "战", "業" to "业", "開" to "开", "關" to "关", "時" to "时",
        "長" to "长", "東" to "东", "來" to "来", "對" to "对", "買" to "买", "賣" to "卖",
    ).sortedByDescending { it.first.length }

    fun word(raw: String): String {
        var s = raw
        for ((a, b) in MAP) if (s.contains(a)) s = s.replace(a, b)
        return s
    }

    /** Display form: "对战（OCR:对或）" when cleaned, else the original. */
    fun display(raw: String): String {
        val c = word(raw)
        return if (c != raw) "$c（OCR:$raw）" else raw
    }

    fun items(labels: List<String>): List<String> = labels.map { display(it) }
}

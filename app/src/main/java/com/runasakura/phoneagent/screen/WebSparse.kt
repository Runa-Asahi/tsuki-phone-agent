package com.runasakura.phoneagent.screen

/**
 * Web pages often expose almost nothing to accessibility while they load or when the site draws with canvas/divs
 * (PPG-AN00 Chrome log: 7 texts on a fully loaded page, so the model could not tell "loading" from "loaded").
 * When a WebView covers much of the screen but its subtree has few texts, OCR the page and merge what is inside it.
 */
object WebSparse {
    data class Web(val l: Int, val t: Int, val r: Int, val b: Int, val texts: Int)

    const val MIN_AREA = 0.35
    const val MAX_TEXTS = 6

    /** The WebView that should be OCR'd, or null. Pure. */
    fun target(webs: List<Web>, w: Int, h: Int): Web? = webs.filter { (it.r - it.l).toLong() * (it.b - it.t) >= MIN_AREA * w * h }
        .maxByOrNull { (it.r - it.l).toLong() * (it.b - it.t) }?.takeIf { it.texts <= MAX_TEXTS }

    /** Tree items + OCR items inside [web] that do not duplicate a tree label nearby. Ids of added items: w1, w2… */
    fun merge(tree: List<ScreenItem>, ocr: List<ScreenItem>, web: Web): List<ScreenItem> {
        val added = ocr.filter { o -> o.cx in web.l..web.r && o.cy in web.t..web.b &&
            tree.none { t -> t.label.isNotBlank() && (t.label.contains(o.label) || o.label.contains(t.label)) && kotlin.math.abs(t.cy - o.cy) < 80 } }
        return tree + added.mapIndexed { i, o -> o.copy(id = "w${i + 1}") }
    }
}

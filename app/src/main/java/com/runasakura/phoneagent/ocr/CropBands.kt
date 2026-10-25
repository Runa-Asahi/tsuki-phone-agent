package com.runasakura.phoneagent.ocr

/**
 * Status / nav crop for screenshots. Honor PPG-AN00 (1264×2800) in gesture-nav mode often
 * reports navigation_bar_height=0; soft-nav is ~48–56dp. Defaults are fractions of height.
 */
object CropBands {
    /** @return (topInclusive, bottomExclusive) in the same pixel space as [bmpH]. */
    fun compute(bmpH: Int, statusBarPx: Int, navBarPx: Int): Pair<Int, Int> {
        val status = (if (statusBarPx > 0) statusBarPx else (bmpH * 0.032).toInt()).coerceIn(0, bmpH / 8)
        val nav = (if (navBarPx > 0) navBarPx else (bmpH * 0.028).toInt()).coerceIn(0, bmpH / 8)
        return status to (bmpH - nav).coerceAtLeast(status + 1)
    }
}

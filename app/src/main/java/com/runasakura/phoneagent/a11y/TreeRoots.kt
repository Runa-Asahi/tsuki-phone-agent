package com.runasakura.phoneagent.a11y

object TreeRoots {
    /** Keep only roots of [fgPkg]; if none match (unknown fg), keep all. Empty fg → keep all. */
    fun <T> select(fgPkg: String, roots: List<T>, pkgOf: (T) -> String?): List<T> {
        if (fgPkg.isBlank()) return roots
        val mine = roots.filter { pkgOf(it) == fgPkg }
        return if (mine.isNotEmpty()) mine else if (roots.any { pkgOf(it) != null && pkgOf(it) != fgPkg }) emptyList() else roots
    }
}

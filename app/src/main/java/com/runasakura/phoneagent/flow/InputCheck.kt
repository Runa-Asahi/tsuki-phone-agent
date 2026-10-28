package com.runasakura.phoneagent.flow

/** Judge the real field content (from the IME connection) against the intended message. */
object InputCheck {
    enum class Verdict { EXACT, REPEATED, WRONG, UNKNOWN }

    fun judge(field: String?, message: String): Verdict {
        if (field == null) return Verdict.UNKNOWN
        val f = Text.norm(field); val m = Text.norm(message)
        if (m.isEmpty()) return Verdict.WRONG
        if (f == m) return Verdict.EXACT
        if (f.length > m.length && f.length % m.length == 0 && f == m.repeat(f.length / m.length)) return Verdict.REPEATED
        return Verdict.WRONG
    }
}

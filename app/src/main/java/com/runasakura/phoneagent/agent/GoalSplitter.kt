package com.runasakura.phoneagent.agent

sealed class Subgoal {
    data class Open(val target: String) : Subgoal()
    data class Send(val text: String, val send: Boolean) : Subgoal()
    data class Unknown(val raw: String) : Subgoal()

    fun describe(): String = when (this) {
        is Open -> "进入$target"
        is Send -> (if (send) "发送" else "输入") + text
        is Unknown -> raw
    }
}

object GoalSplitter {
    private const val VERBS = "进入|打开|点击|点开|找到|去|发送|发|说|输入|填写|搜索"
    // Connectives only split when followed by an action verb (so 「再见」「合并」 stay intact).
    private val SEP = Regex("[，,、；;。]|(?:然后|接着|并且|并|再)(?=(?:$VERBS))")
    private val SEND = Regex("^(?:然后|接着|再|并)?(?:发送|发|说)[：:\\s]*(.+)$", RegexOption.DOT_MATCHES_ALL)
    private val TYPE = Regex("^(?:然后|接着|再|并)?(?:输入|填写|打字)[：:\\s]*(.+)$", RegexOption.DOT_MATCHES_ALL)

    fun split(goal: String): List<Subgoal> {
        val g = goal.trim()
        val segs = mutableListOf<Pair<Int, String>>() // start offset, text
        var last = 0
        for (m in SEP.findAll(g)) { segs += last to g.substring(last, m.range.first); last = m.range.last + 1 }
        segs += last to g.substring(last)
        val out = mutableListOf<Subgoal>()
        for ((start, raw) in segs) {
            val seg = raw.trim()
            if (seg.isBlank()) continue
            // A send/type segment owns the rest of the goal verbatim (messages may contain ，再 etc.)
            val lead = raw.length - raw.trimStart().length
            val m = SEND.find(seg) ?: TYPE.find(seg)
            if (m != null) {
                val bodyStart = start + lead + m.groups[1]!!.range.first
                val text = g.substring(bodyStart).trim().trimEnd('。')
                out += Subgoal.Send(text, m.value.let { SEND.matches(seg) })
                break
            }
            out += classify(seg)
        }
        return out
    }

    fun classify(seg: String): Subgoal {
        Regex("^(?:然后|接着|再|并)?(?:进入|打开|点击|点开|点|找到|去)(.+)$").find(seg)?.let {
            return Subgoal.Open(it.groupValues[1].trim().removeSuffix("页面").removeSuffix("页").trim())
        }
        SEND.find(seg)?.let { return Subgoal.Send(it.groupValues[1].trim(), true) }
        TYPE.find(seg)?.let { return Subgoal.Send(it.groupValues[1].trim(), false) }
        return Subgoal.Unknown(seg)
    }
}

package com.runasakura.phoneagent.agent

sealed class Subgoal {
    data class Open(val target: String) : Subgoal()
    data class Send(val text: String, val send: Boolean) : Subgoal()
    data class Unknown(val raw: String) : Subgoal()
    /** forward = reveal content further down (finger moves up, 上滑). */
    data class Scroll(val forward: Boolean) : Subgoal()
    data object Back : Subgoal()
    /** Undo the previous subgoal: back and verify the earlier screen returns. */
    data object Undo : Subgoal()
    data object Home : Subgoal()
    data class Search(val query: String) : Subgoal()
    data class LongPress(val target: String) : Subgoal()
    /** Type [text] into the field labelled/placeholder [field] (no send). */
    data class TypeInto(val field: String, val text: String) : Subgoal()
    /** Scroll until [target] is visible (no tap). */
    data class ScrollTo(val target: String) : Subgoal()

    fun describe(): String = when (this) {
        is Open -> "进入$target"
        is Send -> (if (send) "发送" else "输入") + text
        is Unknown -> raw
        is Scroll -> if (forward) "上滑" else "下滑"
        Back -> "返回"
        Undo -> "撤销上一步"
        Home -> "回到桌面"
        is Search -> "搜索$query"
        is LongPress -> "长按$target"
        is TypeInto -> "在${field}输入$text"
        is ScrollTo -> "滑到$target"
    }
}

object GoalSplitter {
    private const val VERBS = "进入|打开|点击|点开|找到|去|发送|发|说|输入|填写|搜索|上滑|下滑|向上滑|向下滑|往上滑|往下滑|滑动|返回|回到|回桌面|长按|滑到|滚动到|翻到|撤销|在|播放|收藏|关注|点赞|评论|分享|下载|选择|选|关闭|开启|切换|复制|粘贴"
    // Connectives only split when followed by an action verb (so 「再见」「合并」 stay intact).
    private val SEP = Regex("[，,、；;。]|(?:然后|接着|并且|并|再)(?=(?:$VERBS))")
    private val SEND = Regex("^(?:然后|接着|再|并)?(?:发送|发|说)[：:\\s]*(.+)$", RegexOption.DOT_MATCHES_ALL)
    private val TYPE_IN = Regex("^(?:然后|接着|再|并)?在(.{1,12}?)(?:里|中|内|上)?(?:输入|填写|填入)[：:\\s]*(.+)$", RegexOption.DOT_MATCHES_ALL)
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
            TYPE_IN.find(seg)?.let { ti ->
                val bodyStart = start + lead + ti.groups[2]!!.range.first
                out += Subgoal.TypeInto(ti.groupValues[1].trim(), g.substring(bodyStart).trim().trimEnd('。'))
                return out
            }
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

    fun classify(seg0: String): Subgoal {
        val seg = seg0.replace(Regex("^(?:然后|接着|再|并且|并)"), "").trim()
        when {
            Regex("^(?:向上|往上)?上?滑(?:动)?(?:一下|一页|屏幕)?$|^上滑.*|^下一页$").matches(seg) && !seg.startsWith("下滑") ->
                return Subgoal.Scroll(true)
            Regex("^(?:向下|往下)滑.*|^下滑.*|^上一页$").matches(seg) -> return Subgoal.Scroll(false)
            Regex("^(?:返回|后退)(?:上一页|上一级)?$").matches(seg) -> return Subgoal.Back
            Regex("^撤销(?:上一步|刚才的?操作|操作)?$").matches(seg) -> return Subgoal.Undo
            Regex("^(?:回到|返回|回)(?:主页|桌面|主屏幕)$|^回桌面$|^主页$").matches(seg) -> return Subgoal.Home
        }
        Regex("^长按(.+)$").find(seg)?.let { return Subgoal.LongPress(it.groupValues[1].trim()) }
        Regex("^(?:滑到|滚动到|翻到)(.+)$").find(seg)?.let { return Subgoal.ScrollTo(it.groupValues[1].trim()) }
        Regex("^搜索[：:\\s]*(.+)$").find(seg)?.let { return Subgoal.Search(it.groupValues[1].trim()) }
        Regex("^(?:然后|接着|再|并)?(?:进入|打开|点击|点开|点|找到|去)(.+)$").find(seg)?.let {
            return Subgoal.Open(it.groupValues[1].trim().removeSuffix("页面").removeSuffix("页").trim())
        }
        SEND.find(seg)?.let { return Subgoal.Send(it.groupValues[1].trim(), true) }
        TYPE.find(seg)?.let { return Subgoal.Send(it.groupValues[1].trim(), false) }
        return Subgoal.Unknown(seg)
    }
}

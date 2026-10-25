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
    private val SEP = Regex("[，,、；;。]|然后|接着|再|并且|并")

    fun split(goal: String): List<Subgoal> =
        goal.split(SEP).map { it.trim() }.filter { it.isNotBlank() }.map { classify(it) }

    fun classify(seg: String): Subgoal {
        Regex("^(?:进入|打开|点击|点开|点|找到|去)(.+)$").find(seg)?.let {
            return Subgoal.Open(it.groupValues[1].trim().removeSuffix("页面").removeSuffix("页").trim())
        }
        Regex("^(?:发送|发|说)[：:\\s]*(.+)$").find(seg)?.let { return Subgoal.Send(it.groupValues[1].trim(), true) }
        Regex("^(?:输入|填写|打字)[：:\\s]*(.+)$").find(seg)?.let { return Subgoal.Send(it.groupValues[1].trim(), false) }
        return Subgoal.Unknown(seg)
    }
}

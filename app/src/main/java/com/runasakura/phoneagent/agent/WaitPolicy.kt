package com.runasakura.phoneagent.agent

/** Pure: a wait may end early once the screen differs from where it started and two polls in a row agree. */
object WaitPolicy {
    fun settled(base: Int, prev: Int, cur: Int): Boolean = cur != base && cur == prev
}

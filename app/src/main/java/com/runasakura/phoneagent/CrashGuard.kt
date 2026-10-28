package com.runasakura.phoneagent

/** Pure crash policy + last-crash record (shown in diagnostics) — see PhoneAgentApp.installCrashGuard. */
object CrashGuard {
    /** Worker-thread exceptions are swallowed unless fatal for the VM (OOM/StackOverflow can't be recovered from). */
    fun shouldSwallow(mainThread: Boolean, e: Throwable): Boolean = !mainThread && e !is VirtualMachineError

    fun record(ctx: android.content.Context, thread: String, e: Throwable) {
        ctx.getSharedPreferences("agent_backend", android.content.Context.MODE_PRIVATE).edit()
            .putString("last_crash", "${System.currentTimeMillis()} [$thread] ${e.javaClass.simpleName}: ${e.message?.take(200)}").commit()
    }

    fun last(ctx: android.content.Context): String? = ctx.getSharedPreferences("agent_backend", android.content.Context.MODE_PRIVATE).getString("last_crash", null)
}

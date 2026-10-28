package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.CrashGuard
import com.runasakura.phoneagent.a11y.A11yHealth
import org.junit.Assert.*
import org.junit.Test

/** Keeping the accessibility service on: settings merge never clobbers other services; crash policy. */
class A11yHealthTest {
    private val pkg = "com.runasakura.phoneagent"
    private val ours = "$pkg/com.runasakura.phoneagent.a11y.AgentAccessibilityService"

    @Test fun appendsWithoutClobbering() {
        assertEquals(ours, A11yHealth.merged(null, pkg))
        assertEquals(ours, A11yHealth.merged("", pkg))
        assertEquals("com.x/.Talk:$ours", A11yHealth.merged("com.x/.Talk", pkg))
        assertEquals("com.x/.A:com.y/.B:$ours", A11yHealth.merged("com.x/.A::com.y/.B:", pkg))
    }

    @Test fun alreadyListedIsUnchanged() {
        assertEquals("com.x/.A:$ours", A11yHealth.merged("com.x/.A:$ours", pkg))
        assertTrue(A11yHealth.isListed("com.x/.A:$pkg/.a11y.AgentAccessibilityService", pkg))
        assertEquals("com.x/.A:$pkg/.a11y.AgentAccessibilityService", A11yHealth.merged("com.x/.A:$pkg/.a11y.AgentAccessibilityService", pkg))
        assertFalse(A11yHealth.isListed("com.x/.A", pkg))
        assertFalse(A11yHealth.isListed("$pkg.other/.Svc", pkg))
    }

    @Test fun healCommandsAreArgvWithMasterSwitch() {
        val c = A11yHealth.healCommands("com.x/.A", pkg)
        assertEquals(listOf("settings", "put", "secure", "enabled_accessibility_services", "com.x/.A:$ours"), c[0])
        assertEquals(listOf("settings", "put", "secure", "accessibility_enabled", "1"), c[1])
    }

    @Test fun stateLabels() {
        assertEquals(A11yHealth.State.CONNECTED, A11yHealth.state(true, false, false))
        assertEquals(A11yHealth.State.ENABLED_NOT_BOUND, A11yHealth.state(false, true, true))
        assertEquals(A11yHealth.State.DISABLED, A11yHealth.state(false, true, false))
        assertEquals(A11yHealth.State.DISABLED, A11yHealth.state(false, false, true))
    }

    @Test fun crashGuardSwallowsWorkerButNotMainOrVmErrors() {
        assertTrue(CrashGuard.shouldSwallow(false, IllegalStateException("x")))
        assertFalse(CrashGuard.shouldSwallow(true, IllegalStateException("x")))
        assertFalse(CrashGuard.shouldSwallow(false, OutOfMemoryError()))
    }

    @Test fun shizukuCanEnableOurServiceWithoutConfirmButModelCannot() {
        // the model's shell tool changing accessibility services always needs confirmation
        val argv = (com.runasakura.phoneagent.shell.ShellCommand.parse("settings put secure enabled_accessibility_services x/y") as com.runasakura.phoneagent.shell.ShellCommand.Parsed.Ok).argv
        assertNotNull(com.runasakura.phoneagent.shell.ShellCommand.confirmReason(argv))
    }
}

package com.runasakura.phoneagent.agent

import com.runasakura.phoneagent.flow.Device
import com.runasakura.phoneagent.flow.Screen
import com.runasakura.phoneagent.model.AgentAction
import com.runasakura.phoneagent.model.UiSnapshot
import com.runasakura.phoneagent.prefs.MemorySettings
import com.runasakura.phoneagent.screen.ScreenItem
import com.runasakura.phoneagent.shell.*
import com.runasakura.phoneagent.shell.ShellCommand.Parsed
import com.runasakura.phoneagent.sim.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Optional Shizuku backend: selection, fallback, command validation, confirm coverage, and end-to-end sim paths. */
class ShizukuTest {
    private fun ok(cmd: String) = (ShellCommand.parse(cmd) as? Parsed.Ok)?.argv ?: error("rejected: $cmd → ${ShellCommand.parse(cmd)}")
    private fun bad(cmd: String) = assertTrue(cmd, ShellCommand.parse(cmd) is Parsed.Bad)
    private fun confirm(cmd: String) = ShellCommand.confirmReason(ok(cmd))

    // ---------- backend selection ----------
    @Test fun selectsShizukuOnlyWhenEnabledRunningAndGranted() {
        assertEquals(BackendSelector.Backend.SHIZUKU, BackendSelector.select(true, ShizukuStatus.READY))
        for (s in listOf(ShizukuStatus.NOT_INSTALLED, ShizukuStatus.NOT_RUNNING, ShizukuStatus.NO_PERMISSION))
            assertEquals(BackendSelector.Backend.ACCESSIBILITY, BackendSelector.select(true, s))
        assertEquals(BackendSelector.Backend.ACCESSIBILITY, BackendSelector.select(false, ShizukuStatus.READY))
    }

    @Test fun guardedShellTurnsBinderDeathIntoFailure() {
        val dead = object : PrivilegedShell { override fun exec(argv: List<String>, timeoutMs: Long): ShellResult = throw IllegalStateException("binder died") }
        val r = BackendSelector.guarded(dead)!!.exec(listOf("getprop"))
        assertFalse(r.ok); assertTrue(r.err.contains("binder"))
        assertNull(BackendSelector.guarded(null))
    }

    @Test fun statusLabelsMentionHonorReactivation() { assertTrue(ShizukuStatus.NOT_RUNNING.label.contains("无线调试")) }

    // ---------- command validation ----------
    @Test fun tokenizerKeepsQuotedArgsAndRejectsMeta() {
        assertEquals(listOf("am", "start", "-a", "android.intent.action.VIEW", "-d", "https://a.b/c d"), ok("am start -a android.intent.action.VIEW -d 'https://a.b/c d'"))
        assertEquals(listOf("settings", "get", "global", "wifi_on"), ok("adb shell settings get global wifi_on"))
        listOf("pm list packages | grep x", "settings get global a; reboot", "getprop > /sdcard/x", "am start \$(echo x)", "dumpsys `id`",
            "svc wifi enable && reboot", "getprop\nreboot", "am start 'unterminated").forEach { bad(it) }
    }

    @Test fun onlyAllowedToolsAndSubcommands() {
        listOf("rm -rf /sdcard", "sh -c id", "su", "chmod 777 /data", "input tap 1 2", "uiautomator dump", "screencap -p",
            "am broadcast -a x", "pm install /sdcard/a.apk", "settings put foo k v", "cmd unknown_svc x", "svc reboot", "").forEach { bad(it) }
        listOf("am force-stop com.a", "pm list packages -3", "settings put system screen_brightness 100", "cmd wifi set-wifi-enabled disabled",
            "svc bluetooth enable", "dumpsys battery", "getprop ro.build.version.release", "wm size", "monkey -p com.a -c android.intent.category.LAUNCHER 1").forEach { ok(it) }
    }

    // ---------- ConfirmPolicy for shell ----------
    @Test fun dangerousShellNeedsConfirm() {
        listOf("pm uninstall com.a", "pm clear com.a", "cmd package clear com.a", "pm disable-user com.a", "pm grant com.a android.permission.READ_SMS",
            "pm revoke com.a android.permission.CAMERA", "reboot", "svc power reboot", "svc power shutdown",
            "settings put secure enabled_accessibility_services x/y", "settings put global adb_enabled 1", "settings put global install_non_market_apps 1",
            "settings delete secure lock_screen_owner_info", "settings put system lockscreen_x 1", "cmd appops set com.a CAMERA allow",
            "am start -a android.intent.action.CALL -d tel:10086", "am start -a android.intent.action.SENDTO -d smsto:10086",
            "am start -a android.intent.action.VIEW -d alipays://platformapi/startapp", "am start -d weixin://dl/scan", "cmd role add-role-holder x com.a",
        ).forEach { assertNotNull(it, confirm(it)) }
    }

    @Test fun ordinaryShellAndHttpsDoNotConfirm() {
        listOf("am start -a android.intent.action.VIEW -d https://example.com/pay?x=1", "am force-stop com.a", "pm list packages",
            "settings put system screen_brightness 80", "settings put system screen_off_timeout 60000", "settings get secure android_id",
            "svc wifi disable", "cmd bluetooth_manager enable", "dumpsys window", "cmd statusbar expand-notifications",
            "am start -n com.a/.Main", "cmd appops get com.a").forEach { assertNull(it, confirm(it)) }
    }

    @Test fun safetyGateCoversShellAndKeys() {
        val gate = SafetyGate(MemorySettings())
        val snap = UiSnapshot("com.a", screenWidth = 1080, screenHeight = 2400, tree = null, compressedText = "[e1] 设置 (1,1)", nodeCount = 1)
        assertEquals(SafetyDecision.REQUIRE_CONFIRM, gate.check(AgentAction.Shell("pm uninstall com.a"), snap, "卸载 a").decision)
        assertEquals(SafetyDecision.ALLOW, gate.check(AgentAction.Shell("svc wifi disable"), snap, "关闭 wifi").decision)
        assertEquals(SafetyDecision.ALLOW, gate.check(AgentAction.OpenUri("https://example.com"), snap, "打开网页").decision)
        assertEquals(SafetyDecision.REQUIRE_CONFIRM, gate.check(AgentAction.Key("CALL"), snap, "x").decision)
        assertEquals(SafetyDecision.REQUIRE_CONFIRM, gate.check(AgentAction.Key("KEYCODE_POWER"), snap, "x").decision)
        assertEquals(SafetyDecision.ALLOW, gate.check(AgentAction.Key("MEDIA_PLAY_PAUSE"), snap, "x").decision)
        // existing rules untouched
        assertEquals(SafetyDecision.REQUIRE_CONFIRM, gate.check(AgentAction.Tap(text = "发送"), snap, "发消息").decision)
        assertTrue(ConfirmPolicy.isSensitive(AgentAction.AskConfirm("将重启手机")))
        assertTrue(ConfirmPolicy.isSensitive(AgentAction.AskConfirm("x", AgentAction.Shell("pm clear com.a"))))
        assertFalse(ConfirmPolicy.isSensitive(AgentAction.AskConfirm("x", AgentAction.Shell("svc wifi disable"))))
    }

    @Test fun keyCodesAndValidatorDependOnBackend() {
        assertEquals("KEYCODE_MEDIA_PLAY_PAUSE", ShellCommand.keyCode("media_play_pause"))
        assertNull(ShellCommand.keyCode("x; reboot"))
        assertNotNull(ActionValidator.validate(AgentAction.Key("MEDIA_PLAY_PAUSE"), emptyList(), 1080, 2400))
        assertNull(ActionValidator.validate(AgentAction.Key("MEDIA_PLAY_PAUSE"), emptyList(), 1080, 2400, shell = true))
        assertTrue(ActionValidator.validate(AgentAction.Shell("svc wifi disable"), emptyList(), 1080, 2400)!!.contains("Shizuku"))
        assertNull(ActionValidator.validate(AgentAction.Shell("svc wifi disable"), emptyList(), 1080, 2400, shell = true))
        assertNotNull(ActionValidator.validate(AgentAction.Shell("rm -rf /"), emptyList(), 1080, 2400, shell = true))
    }

    @Test fun parserMapsShellAliases() {
        assertEquals(AgentAction.Shell("svc wifi disable"), ActionParser.parse("""{"action":{"type":"shell","cmd":"svc wifi disable"}}""").action)
        assertEquals(AgentAction.Shell("dumpsys battery"), ActionParser.parse("""{"action":{"type":"adb","command":"dumpsys battery"}}""").action)
        assertEquals(AgentAction.Shell("am force-stop com.a"), ActionParser.parse("""{"action":{"type":"force_stop","package":"com.a"}}""").action)
    }

    @Test fun promptAdvertisesShellOnlyWhenActive() {
        assertEquals(SystemPrompt.SHORT, SystemPrompt.forBackend(false))
        assertTrue(SystemPrompt.forBackend(true).startsWith(SystemPrompt.SHORT))
        assertTrue(SystemPrompt.forBackend(true).contains("shell{cmd}"))
    }

    @Test fun outputIsCompacted() {
        val big = (1..200).joinToString("\n") { "line $it" }
        val c = ShellOutput.compact(big)
        assertTrue(c.lines().size <= 41); assertTrue(c.contains("line 1")); assertTrue(c.contains("line 200"))
    }

    // ---------- uiautomator dump ----------
    @Test fun parsesUiAutomatorXml() {
        val xml = """<?xml version='1.0' ?><hierarchy rotation="0"><node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.x" content-desc="" clickable="false" bounds="[0,0][1080,2400]"><node index="0" text="张三 &amp; 李四" resource-id="com.x:id/name" class="android.widget.TextView" package="com.x" content-desc="" clickable="true" bounds="[0,300][1080,500]" /><node index="1" text="" class="android.widget.EditText" package="com.x" content-desc="" clickable="true" bounds="[0,2200][900,2300]" /><node index="2" text="" class="android.widget.ImageView" package="com.x" content-desc="更多" clickable="true" bounds="[980,100][1060,180]"></node></node></hierarchy>"""
        val (roots, pkg) = UiAutomatorDump.parse(xml)
        assertEquals("com.x", pkg); assertEquals(1, roots.size); assertEquals(3, roots[0].children.size)
        val items = com.runasakura.phoneagent.screen.ScreenItems.leaves(roots, 1080, 2400)
        val name = items.first { it.label.contains("张三") }
        assertEquals("张三 & 李四", name.label); assertEquals(540, name.cx); assertEquals(400, name.cy); assertTrue(name.tap)
        assertTrue(items.any { it.edit }); assertTrue(items.any { it.label == "更多" })
    }

    private class FakeDev(var s: Screen) : Device {
        override suspend fun screen() = s
        override suspend fun tap(item: ScreenItem) = true
        override suspend fun tapXY(x: Int, y: Int) = true
        override suspend fun longPress(item: ScreenItem) = true
        override suspend fun type(text: String, hintX: Int?, hintY: Int?, attempt: Int) = true
        override suspend fun back() {}
        override suspend fun home() {}
        override suspend fun afterAction(maxMs: Long) {}
        override suspend fun scroll(forward: Boolean) {}
        override suspend fun bringToFront(pkg: String, label: String, force: Boolean) = true
        override suspend fun sleep(ms: Long) {}
        override suspend fun invalidateObservation() {}
    }
    private val xmlShell = object : PrivilegedShell {
        var calls = 0
        override fun exec(argv: List<String>, timeoutMs: Long): ShellResult { calls++
            return if (argv[0] == "cat") ShellResult(0, """<hierarchy><node text="Alpha" package="p" clickable="true" bounds="[0,300][100,400]" /><node text="Bravo" bounds="[0,500][100,600]" /><node text="Charlie" bounds="[0,700][100,800]" /></hierarchy>""".toByteArray())
            else ShellResult(0, ByteArray(0)) }
    }

    @Test fun augmentedDeviceFillsEmptyTreeAndKeepsRichTree() = runBlocking {
        val empty = Screen("p", 1080, 2400, emptyList(), "none")
        val aug = ShellAugmentedDevice(FakeDev(empty), { xmlShell }, {})
        val s = aug.screen()
        assertEquals("uia", s.source); assertEquals(listOf("Alpha", "Bravo", "Charlie"), s.items.map { it.label })
        val rich = Screen("p", 1080, 2400, (1..5).map { ScreenItem("e$it", "T$it", 1, it * 10, true, false, false, null) }, "tree")
        val before = xmlShell.calls
        assertSame(rich, ShellAugmentedDevice(FakeDev(rich), { xmlShell }, {}).screen())
        assertEquals(before, xmlShell.calls)
        // no shell → unchanged (seamless fallback to the a11y/OCR result)
        assertSame(empty, ShellAugmentedDevice(FakeDev(empty), { null }, {}).screen())
    }

    @Test fun augmentedDeviceStopsTryingAfterRepeatedFailures() = runBlocking {
        var n = 0
        val failing = object : PrivilegedShell { override fun exec(argv: List<String>, timeoutMs: Long): ShellResult { n++; return ShellResult(1, ByteArray(0), "ERROR: could not get idle state.") } }
        val empty = Screen("p", 1080, 2400, emptyList(), "none")
        val aug = ShellAugmentedDevice(FakeDev(empty), { failing }, {})
        repeat(6) { aug.screen() }
        assertEquals(3, n)
    }

    // ---------- end-to-end simulator ----------
    private fun show(r: SimRun) = r.logs.filterNot { it.startsWith("LLM raw") }.joinToString("\n") + "\nRESULT: ${r.result}\nWORLD: " + r.world.log.joinToString(" ")
    private fun pass(sc: Scenario): SimRun { val r = SimRunner.run(sc); assertTrue(show(r), r.result.success); assertTrue(show(r), sc.done(r.world)); return r }

    @Test fun simHiddenTreeReadThroughUiAutomator() {
        val r = pass(Scenarios3.hiddenTree)
        assertTrue(show(r), r.logs.any { it.contains("uiautomator") }); assertTrue(show(r), r.logs.any { it.contains("来源=uia") })
    }
    @Test fun simWithoutShizukuUsesAccessibility() {
        val r = pass(Scenarios3.noShellFallback)
        assertFalse(r.world.log.any { it.startsWith("sh:") }); assertFalse(r.logs.any { it.contains("Shizuku 已就绪") })
        assertFalse(show(r), r.stepPrompts.first().getJSONObject(0).getString("content").contains("shell{cmd}"))
    }
    @Test fun simWifiOffViaShellAndVerified() {
        val r = pass(Scenarios3.wifiOff)
        assertTrue(show(r), r.world.log.contains("sh:svc wifi disable")); assertTrue(show(r), r.world.log.contains("sh:settings get global wifi_on"))
        assertTrue(show(r) + r.confirms, r.confirms.isEmpty())
        // system prompt identical on every step (prefix cache) and carries the shell section
        val sys = r.stepPrompts.map { it.getJSONObject(0).getString("content") }.toSet()
        assertEquals(show(r), 1, sys.size); assertTrue(sys.first().contains("shell{cmd}"))
    }
    @Test fun simShellRefusedWithoutShizukuThenUi() { val r = pass(Scenarios3.shellUnavailable); assertFalse(r.world.log.any { it.startsWith("sh:") }) }
    @Test fun simShizukuLostMidTask() { val r = pass(Scenarios3.shizukuLost); assertFalse(show(r), r.world.log.any { it.startsWith("sh:svc") }) }
    @Test fun simUninstallConfirmed() { val r = pass(Scenarios3.uninstall(true)); assertTrue(show(r), r.confirms.any { it.contains("卸载") }) }
    @Test fun simUninstallDeclinedNeverRuns() {
        val r = SimRunner.run(Scenarios3.uninstall(false))
        assertFalse(r.result.success); assertNull(show(r), r.world.flags["uninstalled"]); assertFalse(r.world.log.any { it.contains("pm uninstall") })
    }
    @Test fun simForceStopAndRelaunch() { val r = pass(Scenarios3.forceStop); assertTrue(show(r), r.world.log.contains("sh:am force-stop ${Scenarios3.CHAT}")) }
    @Test fun simShellOutputIsUntrustedData() {
        val r = pass(Scenarios3.outputInjection)
        val last = r.stepPrompts.last().let { it.getJSONObject(it.length() - 1).getString("content") }
        assertTrue(last, last.contains("<<<屏幕内容")); assertNull(r.world.flags["uninstalled"])
    }
    @Test fun simMediaKeyViaShizuku() { pass(Scenarios3.mediaKey) }

    // ---- pt67 (A): never run uiautomator while our accessibility service is enabled/connected ----
    @Test fun augmentedDeviceNeverDumpsWhileA11yActive() = runBlocking {
        val empty = Screen("p", 2800, 1264, emptyList(), "none")
        val before = xmlShell.calls; val logs = ArrayList<String>()
        val s = ShellAugmentedDevice(FakeDev(empty), { xmlShell }, { logs += it }, a11yActive = { true }).screen()
        assertSame(empty, s); assertEquals("no shell command at all", before, xmlShell.calls)
        assertTrue(logs.any { it.contains("不使用 uiautomator") })
        // service off → the dump is still available as before
        assertEquals("uia", ShellAugmentedDevice(FakeDev(empty), { xmlShell }, {}, a11yActive = { false }).screen().source)
    }

    @Test fun guardedShellBlocksUiAutomationCommandsAndLogsCaller() {
        val ran = ArrayList<List<String>>()
        val raw = object : PrivilegedShell { override fun exec(argv: List<String>, timeoutMs: Long): ShellResult { ran += argv; return ShellResult(0, ByteArray(0)) } }
        val old = ShellLog.a11yActive
        try {
            ShellLog.clear(); ShellLog.a11yActive = { true }
            val sh = BackendSelector.guarded(raw)!!
            assertFalse(sh.exec(listOf("uiautomator", "dump", "/sdcard/x.xml")).ok)
            assertFalse(sh.exec(listOf("am", "instrument", "-w", "x")).ok)
            assertTrue(sh.exec(listOf("input", "text", "secret")).ok)
            assertEquals(listOf(listOf("input", "text", "secret")), ran)
            val e = ShellLog.entries()
            assertEquals(3, e.size)
            assertTrue(e[0].result.contains("已拦截"))
            assertFalse("typed text is redacted", e[2].cmd.contains("secret"))
            assertTrue("caller recorded", e.all { it.caller != "?" && it.caller.isNotBlank() })
            assertTrue(e[0].atMs > 0)
            ShellLog.a11yActive = { false }
            assertTrue(sh.exec(listOf("uiautomator", "dump")).ok)
        } finally { ShellLog.a11yActive = old; ShellLog.clear() }
    }
}

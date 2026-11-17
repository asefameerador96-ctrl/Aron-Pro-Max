package com.aktcl.aron.core.system.integrity

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrityGateControllerTest {
    private val day = 24 * 3600_000L

    private class Env(var flags: DevFlags = DevFlags(false, false), val debug: Boolean = false) {
        val dir: File = Files.createTempDirectory("gate").toFile()
        val clock = LabExemptionStoreTest.Clock()
        val store = LabExemptionStore(dir, clock)
        val log = IntegrityEventLog(dir)
        fun controller() = IntegrityGateController({ flags }, store, log, dir, debug, "1.2.3", clock::wallMs)
    }

    @Test fun nothingOnMeansOpen() {
        val e = Env(); val c = e.controller()
        assertFalse(c.check().blocked); assertFalse(c.noteOnset(c.check()))
        assertTrue(e.log.pending().isEmpty())
    }

    @Test fun eitherFlagBlocksAReleaseBuild() {
        val e = Env(); val c = e.controller()
        e.flags = DevFlags(devOptions = true, adb = false); assertTrue(c.check().blocked)
        e.flags = DevFlags(devOptions = false, adb = true); assertTrue(c.check().blocked)
        e.flags = DevFlags(devOptions = true, adb = true); assertEquals(listOf("dev_options", "adb"), c.check().flags.codes)
    }

    @Test fun aDebugBuildIsExemptButStillRecorded() {
        val e = Env(DevFlags(true, true), debug = true); val c = e.controller()
        val s = c.check(); assertFalse(s.blocked); assertEquals(Exemption.DEBUG_BUILD, s.exempt)
        assertTrue(c.noteOnset(s)); assertEquals("debug_build", e.log.pending().single().exempt)
    }

    @Test fun aReleaseBuildIsOnlyExemptWithALiveLabGrant() {
        val e = Env(DevFlags(true, false)); val c = e.controller()
        assertTrue(c.check().blocked)
        e.store.grant(e.clock.wall + day)
        assertFalse(c.check().blocked); assertEquals(Exemption.LAB, c.check().exempt)
        e.clock.advance(2 * day)
        assertTrue(c.check().blocked) // expired: fail closed
    }

    @Test fun oneEventPerOnsetNotPerCheckOrRestart() {
        val e = Env(DevFlags(true, false))
        val c = e.controller()
        repeat(5) { c.noteOnset(c.check()) }
        e.controller().let { restarted -> restarted.noteOnset(restarted.check()) } // a restart while still gated
        assertEquals(1, e.log.pending().size)
        e.flags = DevFlags(false, false); c.noteOnset(c.check()) // switched off
        e.flags = DevFlags(true, false); c.noteOnset(c.check()) // and on again: a new onset
        assertEquals(2, e.log.pending().size)
        val ev = e.log.pending().first()
        assertEquals(listOf("dev_options"), ev.flags); assertEquals("none", ev.exempt); assertEquals("1.2.3", ev.appVersion)
        assertEquals(e.clock.wall, ev.atUtcMs)
    }

    @Test fun aFlagChangeWhileGatedIsANewOnset() {
        val e = Env(DevFlags(true, false)); val c = e.controller()
        c.noteOnset(c.check()); e.flags = DevFlags(true, true); c.noteOnset(c.check())
        assertEquals(listOf(listOf("dev_options"), listOf("dev_options", "adb")), e.log.pending().map { it.flags })
    }

    @Test fun anUnreadableSettingNeverLocksThePhoneOut() {
        val e = Env(); val c = IntegrityGateController({ error("settings unreadable") }, e.store, e.log, e.dir, false, "v", e.clock::wallMs)
        assertFalse(c.check().blocked)
    }

    @Test fun theClockCannotLiftTheGate() {
        val e = Env(DevFlags(true, false)); val c = e.controller()
        e.clock.wall += 400 * day; assertTrue(c.check().blocked)
        e.clock.wall -= 800 * day; assertTrue(c.check().blocked)
    }

    @Test fun theLogSurvivesARestartAndAckDropsWhatWasSent() {
        val e = Env(DevFlags(true, false)); val c = e.controller()
        c.noteOnset(c.check())
        assertEquals(1, IntegrityEventLog(e.dir).pending().size)
        IntegrityEventLog(e.dir).ack(1)
        assertTrue(IntegrityEventLog(e.dir).pending().isEmpty())
    }

    @Test fun theLogIsCappedAndASlashOrNewlineInTheVersionCannotCorruptIt() {
        val dir = Files.createTempDirectory("log").toFile(); val log = IntegrityEventLog(dir)
        repeat(IntegrityEventLog.CAP + 30) { log.record(IntegrityEvent(it.toLong(), listOf("adb"), "none", "v|1\n2")) }
        val p = log.pending()
        assertEquals(IntegrityEventLog.CAP, p.size); assertEquals(30L, p.first().atUtcMs)
        assertEquals("v_1 2", p.first().appVersion)
    }
}

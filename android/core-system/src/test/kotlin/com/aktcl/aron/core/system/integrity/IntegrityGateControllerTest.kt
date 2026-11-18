package com.aktcl.aron.core.system.integrity

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrityGateControllerTest {
    private val day = 24 * 3600_000L

    private class Env(var flags: DevFlags = DevFlags(false, false), val build: Exemption? = null) {
        val dir: File = Files.createTempDirectory("gate").toFile()
        val clock = LabExemptionStoreTest.Clock()
        val store = LabExemptionStore(dir, clock)
        val log = IntegrityEventLog(dir)
        fun controller() = IntegrityGateController({ flags }, store, log, dir, build, "1.2.3", clock::wallMs)
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
        val e = Env(DevFlags(true, true), build = Exemption.DEBUG_BUILD); val c = e.controller()
        val s = c.check(); assertFalse(s.blocked); assertEquals(Exemption.DEBUG_BUILD, s.exempt)
        assertTrue(c.noteOnset(s)); assertEquals("debug_build", e.log.pending().single().exempt)
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
        val e = Env(); val c = IntegrityGateController({ error("settings unreadable") }, e.store, e.log, e.dir, null, "v", e.clock::wallMs)
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
        IntegrityEventLog(e.dir).ack(IntegrityEventLog(e.dir).pending())
        assertTrue(IntegrityEventLog(e.dir).pending().isEmpty())
    }

    @Test fun theLogIsCappedAndASlashOrNewlineInTheVersionCannotCorruptIt() {
        val dir = Files.createTempDirectory("log").toFile(); val log = IntegrityEventLog(dir)
        repeat(IntegrityEventLog.CAP + 30) { log.record(IntegrityEvent(it.toLong(), listOf("adb"), "none", "v|1\n2")) }
        val p = log.pending()
        assertEquals(IntegrityEventLog.CAP, p.size); assertEquals(30L, p.first().atUtcMs)
        assertEquals("v_1 2", p.first().appVersion)
    }

    @Test fun aReleaseBuildIgnoresAStoredLabFileUntilTheServerPieceLands() {
        // Today the file is unauthenticated: honouring it would let anyone with a file editor skip the gate.
        val e = Env(DevFlags(true, false)); val c = e.controller()
        e.store.grant(e.clock.wall + day)
        assertTrue(c.check().blocked)
        assertEquals(Exemption.NONE, c.check().exempt)
    }

    @Test fun theDefaultConstructorDoesNotHonourTheLabFile() {
        val e = Env(DevFlags(true, false))
        e.store.grant(e.clock.wall + day)
        val c = IntegrityGateController({ e.flags }, e.store, e.log, e.dir, null, "v", e.clock::wallMs)
        assertTrue(c.check().blocked)
    }

    @Test fun aBenchmarkBuildIsExemptAndRecordedAsSuch() {
        val e = Env(DevFlags(true, false), build = Exemption.BENCHMARK_BUILD); val c = e.controller()
        assertFalse(c.check().blocked)
        c.noteOnset(c.check()); assertEquals("benchmark_build", e.log.pending().single().exempt)
    }

    @Test fun twoLogInstancesOverOneFolderShareOneLock() {
        val e = Env(); val l1 = IntegrityEventLog(e.dir); val l2 = IntegrityEventLog(e.dir)
        val t = (1..8).map { i -> Thread { repeat(25) { n -> (if (i % 2 == 0) l1 else l2).record(IntegrityEvent((i * 100 + n).toLong(), listOf("adb"), "none", "v")) } } }
        t.forEach { it.start() }; t.forEach { it.join() }
        assertEquals(IntegrityEventLog.CAP, IntegrityEventLog(e.dir).pending().size.coerceAtMost(IntegrityEventLog.CAP)) // 200 written, capped at 200, none torn
        assertTrue(l1.pending().all { it.flags == listOf("adb") })
    }

    @Test fun ackKeepsTheRestWithoutATornFile() {
        val e = Env(); repeat(5) { e.log.record(IntegrityEvent(it.toLong(), listOf("adb"), "none", "v")) }
        e.log.ack(e.log.pending().take(2))
        assertEquals(listOf(2L, 3L, 4L), e.log.pending().map { it.atUtcMs })
        assertFalse(File(e.dir, "integrity-events.tmp").exists())
    }

    /** Review low: with a full log, an event recorded between pending() and ack drops the OLDEST line by the cap; "ack the first n" then removed an event nobody sent. */
    @Test fun ackRemovesWhatWasSentByIdentityEvenWhenTheCapDroppedTheOldestInBetween() {
        val e = Env(); repeat(IntegrityEventLog.CAP) { e.log.record(IntegrityEvent(it.toLong(), listOf("adb"), "none", "v")) }
        val sent = e.log.pending().take(50) // events 0..49 go out
        e.log.record(IntegrityEvent(1000L, listOf("adb"), "none", "v")) // the cap drops event 0 (already in `sent`)
        e.log.ack(sent)
        val left = e.log.pending().map { it.atUtcMs }
        assertEquals("events 1..49 left with the ack; 50.. and the new one were never sent and stay", (50L until IntegrityEventLog.CAP.toLong()).toList() + 1000L, left)
        e.log.ack(sent); assertEquals("acknowledging twice changes nothing", left, e.log.pending().map { it.atUtcMs })
    }

    @Test fun wirelessDebuggingAloneBlocksAndHasItsOwnCode() {
        // `settings put global development_settings_enabled 0` and `adb_enabled 0` from an on-phone adb client leave wireless debugging on.
        val e = Env(DevFlags(devOptions = false, adb = false, adbWifi = true)); val c = e.controller()
        assertTrue(c.check().blocked); assertEquals(listOf("adb_wifi"), c.check().flags.codes)
        c.noteOnset(c.check()); assertEquals(listOf("adb_wifi"), e.log.pending().single().flags)
        e.flags = DevFlags(true, true, true); assertEquals(listOf("dev_options", "adb", "adb_wifi"), c.check().flags.codes)
    }

    @Test fun aTmpFileLeftByAKillNeverLosesPendingEvents() {
        val e = Env(); e.log.record(IntegrityEvent(1, listOf("adb"), "none", "v"))
        // The kill came after the new content was fully written to the tmp file but before it replaced the real one.
        File(e.dir, "integrity-events.txt").delete()
        File(e.dir, "integrity-events.txt.tmp").writeText("1|adb|none|v\n2|dev_options|none|v")
        assertEquals(listOf(1L, 2L), IntegrityEventLog(e.dir).pending().map { it.atUtcMs })
        File(e.dir, "integrity-events.txt.tmp").writeText("") // a torn (empty) tmp is dropped, the real file stays
        assertEquals(listOf(1L, 2L), IntegrityEventLog(e.dir).pending().map { it.atUtcMs })
    }

    @Test fun theOldFileSurvivesAFailedReplaceOfTheNewOne() {
        val e = Env(); e.log.record(IntegrityEvent(1, listOf("adb"), "none", "v"))
        // A directory where the tmp file must go makes the write fail: the old events must be untouched.
        File(e.dir, "integrity-events.txt.tmp").delete()
        val blocker = File(e.dir, "integrity-events.txt.tmp"); blocker.mkdirs()
        e.log.record(IntegrityEvent(2, listOf("adb"), "none", "v")) // fails inside, swallowed
        blocker.delete()
        assertEquals(listOf(1L), e.log.pending().map { it.atUtcMs })
    }
}

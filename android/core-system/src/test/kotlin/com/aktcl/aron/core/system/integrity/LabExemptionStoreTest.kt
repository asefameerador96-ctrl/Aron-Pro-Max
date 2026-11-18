package com.aktcl.aron.core.system.integrity

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabExemptionStoreTest {
    class Clock(var wall: Long = 1_000_000_000_000L, var elapsed: Long = 5_000_000L, var boot: Int = 7) : GateClock {
        override fun wallMs() = wall
        override fun elapsedMs() = elapsed
        override fun bootCount() = boot
        /** Time passing normally. */
        fun advance(ms: Long) { wall += ms; elapsed += ms }
    }

    private val day = 24 * 3600_000L
    private fun dir(): File = Files.createTempDirectory("lab").toFile()

    @Test fun absentMeansNoExemption() {
        assertFalse(LabExemptionStore(dir(), Clock()).isActive())
    }

    @Test fun aGrantIsActiveUntilItExpiresAndThenFailsClosed() {
        val c = Clock(); val s = LabExemptionStore(dir(), c)
        s.grant(c.wall + 3 * day)
        assertTrue(s.isActive())
        c.advance(2 * day); assertTrue(s.isActive())
        c.advance(day + 1); assertFalse(s.isActive())
        assertFalse(s.isActive()) // stays gone
    }

    @Test fun aLongServerGrantIsCappedToSevenDaysOnThePhone() {
        val c = Clock(); val s = LabExemptionStore(dir(), c)
        s.grant(c.wall + 30 * day)
        c.advance(6 * day); assertTrue(s.isActive())
        c.advance(2 * day); assertFalse(s.isActive())
    }

    @Test fun aGrantAlreadyInThePastClearsAndGrantsNothing() {
        val c = Clock(); val s = LabExemptionStore(dir(), c)
        s.grant(c.wall + day); assertTrue(s.isActive())
        s.grant(c.wall - 1); assertFalse(s.isActive())
    }

    @Test fun settingTheWallClockBackDoesNotExtendIt() {
        val c = Clock(); val s = LabExemptionStore(dir(), c)
        s.grant(c.wall + day)
        c.advance(day - 1000); assertTrue(s.isActive()) // raises the high-water mark
        // The rep sets the clock back two days to stretch it; real time keeps passing.
        c.wall -= 2 * day; c.elapsed += 2000
        assertFalse(s.isActive())
        c.wall += 2 * day; assertFalse(s.isActive()) // the record is gone, only an online refresh brings it back
    }

    @Test fun clockBackAfterARebootIsStillCaughtByTheHighWaterMark() {
        val c = Clock(); val d = dir(); val s = LabExemptionStore(d, c)
        s.grant(c.wall + 3 * day)
        c.advance(2 * day); assertTrue(s.isActive())
        c.boot = 8; c.elapsed = 1000; c.wall -= 1 * day // reboot, then the clock goes back a day
        assertFalse(LabExemptionStore(d, c).isActive())
    }

    @Test fun elapsedTimeBoundsAnExemptionInsideOneBoot() {
        val c = Clock(); val s = LabExemptionStore(dir(), c)
        s.grant(c.wall + day)
        // Wall clock frozen (never moved back past the mark) but the phone has been up far longer than the grant allows.
        c.elapsed += 3 * day
        assertFalse(s.isActive())
    }

    @Test fun aCorruptRecordFailsClosed() {
        val d = dir(); File(d, "lab-exemption.txt").writeText("until=abc\nissued=")
        assertFalse(LabExemptionStore(d, Clock()).isActive())
        File(d, "lab-exemption.txt").writeBytes(byteArrayOf(0, -1, 9))
        assertFalse(LabExemptionStore(d, Clock()).isActive())
    }

    @Test fun anUnreadableBootCountDoesNotHideAnExpiry() {
        val c = Clock(boot = -1); val s = LabExemptionStore(dir(), c)
        s.grant(c.wall + day)
        c.advance(day + 1)
        assertFalse(s.isActive())
    }

    @Test fun clearRemovesIt() {
        val c = Clock(); val s = LabExemptionStore(dir(), c)
        s.grant(c.wall + day); s.clear(); assertFalse(s.isActive())
        assertEquals(false, File(dir(), "x").exists())
    }
}

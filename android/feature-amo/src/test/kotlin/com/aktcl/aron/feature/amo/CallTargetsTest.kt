package com.aktcl.aron.feature.amo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Formulas 4.3 of the AMO build contract on a fixture, and the shape of the future `GET /v1/supervisor/call-targets` answer (screenshot 9). */
class CallTargetsTest {
    private fun v(kind: String, date: String, closed: Boolean = true, abandoned: Boolean = false) = CallTargetMath.CountedVisit(kind, date, closed, abandoned)

    @Test fun todaysTargetIsTheMonthsTargetOverWorkingDaysRoundedHalfUpAtLeastOne() {
        assertEquals(4, CallTargetMath.todayTarget(100, 26))   // 3.846 -> 4
        assertEquals(4, CallTargetMath.todayTarget(104, 26))   // exactly 4
        assertEquals(4, CallTargetMath.todayTarget(105, 26))   // 4.04 -> 4
        assertEquals(5, CallTargetMath.todayTarget(117, 26))   // 4.5 rounds up to 5
        assertEquals(1, CallTargetMath.todayTarget(5, 26))     // 0.19 -> at least 1
        assertEquals(0, CallTargetMath.todayTarget(0, 26))     // nothing due when the month's target is 0
        assertNull(CallTargetMath.todayTarget(null, 26))       // unset stays unset
        assertNull(CallTargetMath.todayTarget(100, 0))         // no working days: no target
    }

    @Test fun doneCountsClosedNonAbandonedVisitsByDateAndKind() {
        val visits = listOf(
            v(CallTargetMath.CONTROL, "2026-11-05"), v(CallTargetMath.CONTROL, "2026-11-06"), v(CallTargetMath.JOINT, "2026-11-06"),
            v(CallTargetMath.JOINT, "2026-11-06", abandoned = true), v(CallTargetMath.CONTROL, "2026-11-06", closed = false),
        )
        val t = CallTargetMath.compute(monthCall = 130, monthControl = 80, monthJoint = 20, workingDays = 26, today = "2026-11-06", visits = visits)
        assertEquals(CallTarget(2, 5), t.today)       // two counted calls today against 130 / 26 = 5
        assertEquals(CallTarget(3, 130), t.month)     // three counted calls in the month
        assertEquals(CallTarget(2, 80), t.control)
        assertEquals(CallTarget(1, 20), t.joint)
    }

    @Test fun anUnsetTargetPrintsTheDoneCountOverADashAndASetOneDoneOverTarget() {
        val t = CallTargetMath.compute(monthCall = null, monthControl = 80, monthJoint = null, workingDays = 26, today = "2026-11-06", visits = listOf(v(CallTargetMath.CONTROL, "2026-11-06")))
        // D-AMO-SHELL-11: the target half is the dash; the AMO still sees their own done count while the programme is deferred
        assertEquals("1/—", CallTargetMath.figure(t.today))
        assertEquals("1/—", CallTargetMath.figure(t.month))
        assertEquals("1/80", CallTargetMath.figure(t.control))
        assertEquals("0/—", CallTargetMath.figure(t.joint))
        assertEquals("—", CallTargetMath.figure(null))
    }

    @Test fun theFixtureSourceReturnsNullSoEveryCardPrintsADash() = runBlocking {
        assertNull(UnsetCallTargets.load("2026-11"))
    }

    @Test fun theContractAnswerIsParsedAndAMalformedOneIsNull() {
        val ok = """{"today":{"done":2,"target":5},"month":{"done":40,"target":130},"control":{"done":10,"target":null},"joint":{"done":0}}"""
        assertEquals(CallTargets(CallTarget(2, 5), CallTarget(40, 130), CallTarget(10, null), CallTarget(0, null)), CallTargets.parse(ok))
        assertNull(CallTargets.parse(null)); assertNull(CallTargets.parse("not json")); assertNull(CallTargets.parse("""{"today":{"done":1,"target":2}}"""))
    }
}

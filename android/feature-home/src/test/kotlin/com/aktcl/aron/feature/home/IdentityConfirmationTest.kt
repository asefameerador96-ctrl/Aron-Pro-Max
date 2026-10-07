package com.aktcl.aron.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-SR-072. */
class IdentityConfirmationTest {
    private class Mem : IdentityStore {
        val m = mutableMapOf<String, IdentityAnswer>()
        override fun answerFor(businessDate: String) = m[businessDate]
        override fun save(answer: IdentityAnswer) { m[answer.businessDate] = answer }
    }

    private val a = BoundUser(1, "Rahim", "sr334001"); private val b = BoundUser(2, "Karim", "sr334002")
    private val store = Mem(); private val c = IdentityConfirmation(store)

    @Test fun onePhoneOneUserAsksNothing() =
        assertEquals(IdentityPrompt.NotNeeded(null), c.promptFor("2026-10-07", listOf(a), a, a))

    @Test fun twoBoundUsersAskForTheAssigneeOnTheFirstCaptureOfTheDate() {
        val p = c.promptFor("2026-10-07", listOf(a, b), a, a) as IdentityPrompt.Ask
        assertEquals(a, p.assignee); assertEquals(listOf(b), p.choices)
    }

    @Test fun yesStoresSelfAndDoesNotAskAgainThatDate() {
        c.confirmSelf("2026-10-07")
        assertEquals(IdentityPrompt.NotNeeded(null), c.promptFor("2026-10-07", listOf(a, b), a, a))
        assertTrue(c.promptFor("2026-10-08", listOf(a, b), a, a) is IdentityPrompt.Ask) // a new date asks again
    }

    @Test fun aDifferentAssigneeIsStoredAsActingFor() {
        assertEquals(2L, c.denyAndChoose("2026-10-07", a, b))
        assertEquals(IdentityPrompt.NotNeeded(2L), c.promptFor("2026-10-07", listOf(a, b), a, a))
    }

    @Test fun choosingYourselfAfterNoStoresNoActingFor() {
        assertNull(c.denyAndChoose("2026-10-07", a, a))
        assertEquals(IdentityPrompt.NotNeeded(null), c.promptFor("2026-10-07", listOf(a, b), a, a))
    }

    @Test fun duplicateBindingsOfTheSameUserCountOnce() =
        assertEquals(IdentityPrompt.NotNeeded(null), c.promptFor("2026-10-07", listOf(a, a), a, a))
}

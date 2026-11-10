package com.aktcl.aron.amo

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A form that queues a record keeps its uuid across a swipe-kill (a new [FormDrafts] over the same file) until its save succeeds. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FormDraftsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun theSameTargetKeepsItsUuidAcrossARelaunchUntilTheSaveClearsIt() {
        val first = FormDrafts(app, 7).uuid("task:3:50")
        val afterKill = FormDrafts(app, 7) // the process died: a new instance over the same file
        assertEquals(first, afterKill.uuid("task:3:50"))
        assertNotEquals(first, afterKill.uuid("task:3:51")) // another target is another act
        afterKill.clear("task:3:50")
        assertNotEquals(first, FormDrafts(app, 7).uuid("task:3:50")) // the next task for the same SR and outlet is a new record
    }

    @Test fun usersDoNotShareDrafts() {
        assertNotEquals(FormDrafts(app, 1).uuid("verify:a"), FormDrafts(app, 2).uuid("verify:a"))
    }

    // A4 (D-AMO-OUTLET-06): the uuid of an act is reused after a relaunch only while no request is stored under it
    @Test fun anUnstoredKeyIsReusedAfterARelaunchAndAStoredOneTakesANewUuid() = kotlinx.coroutines.runBlocking {
        val key = "own:new"
        val first = resolveActUuid(FormDrafts(app, 9), key) { false }
        val afterKill = FormDrafts(app, 9)
        assertEquals("the save never committed: the same record is queued", first, resolveActUuid(afterKill, key) { false })
        val second = resolveActUuid(afterKill, key) { it == first } // the first act's request is stored: this is a different act
        assertNotEquals(first, second)
        assertEquals("and the new uuid is the draft from then on", second, FormDrafts(app, 9).uuid(key))
    }

    @Test fun aLookupThatThrowsLeavesTheDraftKeyUntouched() = kotlinx.coroutines.runBlocking {
        val d = FormDrafts(app, 10)
        val first = d.uuid("own:close:5")
        try { resolveActUuid(d, "own:close:5") { error("db closed") }; org.junit.Assert.fail() } catch (e: IllegalStateException) { /* safeSubmit turns this into the save-failed notice */ }
        assertEquals(first, d.uuid("own:close:5"))
    }
}

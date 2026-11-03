package com.aktcl.aron.core.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * tso-manual A1 (SH-17, D-A1-17): each session phase has its own ViewModel store. A logged-out phase on screen clears the signed-in
 * day that ended and the previous login form; a signed-in phase never clears the login form whose call let the user in; the next
 * sign-in, even of the same user, starts from a fresh store; the activity's store only ever holds the holder.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SessionViewModelStoresTest {
    private class Probe : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
            SessionViewModelStores::class.java -> SessionViewModelStores()
            else -> Probe()
        } as T
    }

    private fun ViewModelStoreOwner.probe(key: String): Probe = ViewModelProvider(this, factory)[key, Probe::class.java]

    @Test fun aPhaseIsStableWhileItLastsAndEveryChangeIsANewPhase() {
        val s = SessionViewModelStores()
        assertEquals("1:out", s.phase(null))
        assertEquals("1:out", s.phase(null)) // recomposition asks again
        assertEquals("2:in:42", s.phase("42"))
        assertEquals("2:in:42", s.phase("42"))
        assertEquals("3:out", s.phase(null))
        assertEquals("4:in:42", s.phase("42")) // the same user again: a new phase
        assertEquals("5:in:43", s.phase("43"))
        assertTrue(SessionViewModelStores.isLoggedOut("3:out"))
        assertFalse(SessionViewModelStores.isLoggedOut("4:in:42"))
        assertFalse("a user key that reads 'out' is still signed in", SessionViewModelStores.isLoggedOut("6:in:out"))
    }

    @Test fun aLoggedOutPhaseOnScreenClearsTheDayAndTheOldFormButSigningInNeverClearsTheForm() {
        val s = SessionViewModelStores()
        val out1 = s.phase(null)
        val form1 = s.owner(out1).probe("login")
        s.entered(out1)
        assertSame("one owner per phase", s.owner(out1), s.owner(out1))

        val in2 = s.phase("42")
        val day = s.owner(in2).probe("day")
        s.entered(in2)
        assertFalse("the login form whose call let the user in is never cancelled under it", form1.cleared)
        assertEquals(setOf(out1, in2), s.livePhases)

        val out3 = s.phase(null) // the logout ended the session
        val form3 = s.owner(out3).probe("login")
        assertNotSame("a fresh login form, not the last user's", form1, form3)
        s.entered(out3)
        assertTrue("SH-17: the day's retained state is gone", day.cleared)
        assertTrue(form1.cleared)
        assertFalse("the form on screen stays alive", form3.cleared)
        assertEquals(setOf(out3), s.livePhases)
        assertSame(form3, s.owner(out3).probe("login"))

        val in4 = s.phase("42") // the same user signs in again
        val day2 = s.owner(in4).probe("day")
        assertNotSame("a fresh day", day, day2)
        s.entered(in4)
        assertFalse(day2.cleared)
        assertFalse(form3.cleared)
    }

    @Test fun anotherUsersDayIsClearedWhenANewSignInIsShown() {
        val s = SessionViewModelStores()
        val a = s.phase("42")
        val dayA = s.owner(a).probe("day")
        s.entered(a)
        val b = s.phase("43")
        s.owner(b).probe("day")
        s.entered(b)
        assertTrue(dayA.cleared)
        assertEquals(setOf(b), s.livePhases)
    }

    @Test fun theActivityFinishingClearsEveryPhase() {
        val activityStore = ViewModelStore()
        val activity = object : ViewModelStoreOwner { override val viewModelStore = activityStore }
        val s = ViewModelProvider(activity, factory)[SessionViewModelStores::class.java]
        val out1 = s.phase(null)
        val form = s.owner(out1).probe("login")
        val in2 = s.phase("42")
        val day = s.owner(in2).probe("day")
        activityStore.clear()
        assertTrue(form.cleared)
        assertTrue(day.cleared)
        assertTrue(s.livePhases.isEmpty())
    }
}

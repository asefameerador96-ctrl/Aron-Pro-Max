package com.aktcl.aron.amo

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.repo.AttendanceRefusal
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.attendance.CheckInFirstTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * D-AGATE-19 (lead's follow-up, item 3): the AMO's gate Close and the control call's leave, through their real buttons on a real [AmoDay]: a close that cannot be written keeps the call and shows
 * the error text (no crash, no dead tap), and a close the route-day refuses ends the call on the phone and SAYS so before going back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
class AmoCloseFailureTest {
    @get:Rule val compose = hostComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var f: AmoLockFixture
    private var scenario: ActivityScenario<ComponentActivity>? = null

    @Before fun setUp() { Dispatchers.setMain(MainLooperImmediate.dispatcher); f = AmoLockFixture(app, latePolicy = null) }
    @After fun tearDown() { try { f.close() } finally { Dispatchers.resetMain() } }

    private fun show(content: @Composable () -> Unit) {
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        scenario = f.own(ActivityScenario.launch(ComponentActivity::class.java))
        scenario!!.onActivity { activity ->
            val ctx = AppLocale.wrap(activity, AppLanguage.EN)
            activity.setContent { CompositionLocalProvider(LocalContext provides ctx, androidx.activity.compose.LocalActivityResultRegistryOwner provides activity) { AronTheme(AppLanguage.EN) { content() } } }
        }
        compose.waitForIdle()
    }

    private fun breakTheClose(visitUuid: String) = runBlocking {
        f.db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF"); f.db.openHelper.writableDatabase.execSQL("DELETE FROM visit WHERE client_uuid = '$visitUuid'")
    }

    @Test fun aFailingGateCloseKeepsTheCallAndShowsTheErrorNotACrash() {
        val v = f.controlCall(); f.day.visitSession.restore(v); breakTheClose(v.visitUuid)
        var closed = 0
        show { AmoCheckInGate(f.day, AttendanceRefusal.EARLIER_CALL_OPEN, canCloseCall = true, earlierOutlet = null, onCheckIn = {}, onCallClosed = { closed++ }) }
        compose.onNodeWithTag(CheckInFirstTags.CLOSE_CALL).performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("The call could not be closed").assertExists() }.isSuccess }
        assertEquals("not sent back in silence", 0, closed); assertNotNull("the call is kept", f.day.visitSession.current.value)
    }

    @Test fun aGateCloseTheRouteDayRefusedSaysSoBeforeGoingBack() {
        val v = f.controlCall(); f.day.visitSession.restore(v)
        f.supervisorDaySubmitted()
        var closed = 0
        show { AmoCheckInGate(f.day, AttendanceRefusal.EARLIER_CALL_OPEN, canCloseCall = true, earlierOutlet = null, onCheckIn = {}, onCallClosed = { closed++ }) }
        compose.onNodeWithTag(CheckInFirstTags.CLOSE_CALL).performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("The sales of the day of this call are already submitted. The call is ended on the phone.").assertExists() }.isSuccess }
        assertEquals(0, closed); assertTrue("nothing was written", runBlocking { f.db.captureDao().visitClosesOn(f.date) }.none { it.visitClientUuid == v.visitUuid })
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) { closed == 1 }
        assertNull("the call ended on the phone", f.day.visitSession.current.value)
    }

    @Test fun aFailingLeaveOfTheControlCallKeepsTheCallAndShowsTheError() {
        val v = f.controlCall(); f.day.visitSession.restore(v); breakTheClose(v.visitUuid)
        show { ControlCallHost(f.day, onBack = {}, startWithSale = false) }
        compose.waitForIdle()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("The call could not be closed").assertExists() }.isSuccess }
        assertNotNull("the call is kept", f.day.visitSession.current.value)
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("The call could not be closed").assertDoesNotExist()
    }
}

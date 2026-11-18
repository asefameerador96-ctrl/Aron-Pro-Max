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
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.repo.AttendanceRefusal
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.amo.CorrectionReason
import com.aktcl.aron.feature.amo.CorrectionTags
import com.aktcl.aron.feature.attendance.ProvideWriteGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * D-AGATE-15 (T4): a screen that reads before check-in but also writes refuses the write AT THE BUTTON with the named reason. The real [AmoStockCorrectHost] on a real [AmoDay],
 * its real Save button, under the guard the app gives the screen: nothing is stored or queued and the rep reads WHY (check in first / finish yesterday's call at <outlet>).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
class AmoWriteGuardHostsTest {
    @get:Rule val compose = hostComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var f: AmoLockFixture

    @Before fun setUp() { Dispatchers.setMain(MainLooperImmediate.dispatcher); f = AmoLockFixture(app, latePolicy = null) }
    @After fun tearDown() { try { f.close() } finally { Dispatchers.resetMain() } }

    private fun show(content: @Composable () -> Unit) {
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        // the fixture owns the screen: its close destroys the activity (composition and view models) before the day's work
        // stops and the database closes (CI run 41750277921)
        f.own(ActivityScenario.launch(ComponentActivity::class.java)).onActivity { activity ->
            val ctx = AppLocale.wrap(activity, AppLanguage.EN)
            activity.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(AppLanguage.EN) { content() } } }
        }
        compose.waitForIdle()
    }

    private fun stockRows() = runBlocking { f.db.captureDao().stockOn(f.date) }.size
    private fun queuedStock() = runBlocking { f.db.outboxDao().committedCounts(f.date) }.filter { it.recordType == "stock_movement" }.sumOf { it.count }

    private fun correctAndSave(why: AttendanceRefusal?) {
        f.stockedCatalog()
        val before = stockRows(); val queued = queuedStock()
        show { ProvideWriteGuard(why, "Rahim Store 50001", onCheckIn = {}) { AmoStockCorrectHost(f.day, onDone = {}) } }
        assertTrue(f.work.awaitSettled()); compose.waitForIdle()
        compose.waitUntil(30_000) { runCatching { compose.onNodeWithTag(CorrectionTags.row(105)).assertExists() }.isSuccess }
        compose.onNodeWithTag(CorrectionTags.row(105)).performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(CorrectionTags.TOTAL)) or (androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasTestTag(CorrectionTags.TOTAL))).performTextInput("15")
        compose.onNodeWithTag(CorrectionTags.reason(CorrectionReason.COUNT_ERROR)).performClick()
        compose.onNodeWithTag(CorrectionTags.SAVE).performClick()
        if (why != null) { assertEquals("nothing stored", before, stockRows()); assertEquals("nothing queued", queued, queuedStock()) }
    }

    @Test fun anEarlierCallOpenRefusesTheStockCorrectionAtTheButtonAndNamesTheOutlet() {
        correctAndSave(AttendanceRefusal.EARLIER_CALL_OPEN)
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Finish yesterday's call first").assertExists() }.isSuccess }
        compose.onNodeWithText("Rahim Store 50001", substring = true).assertExists()
        compose.onNodeWithText("Could not save", substring = true).assertDoesNotExist()
    }

    @Test fun noCheckInRefusesTheStockCorrectionAtTheButtonAndOffersTheWayToCheckIn() {
        correctAndSave(AttendanceRefusal.NOT_CHECKED_IN)
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Check in first").assertExists() }.isSuccess }
        compose.onNodeWithText("Go to check-in").assertExists()
    }

    @Test fun anOpenDayStillSavesTheCorrection() {
        correctAndSave(null)
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag(CorrectionTags.SAVED).assertExists() }.isSuccess || stockRows() > 1 }
        compose.onNodeWithText("Check in first").assertDoesNotExist()
    }
}

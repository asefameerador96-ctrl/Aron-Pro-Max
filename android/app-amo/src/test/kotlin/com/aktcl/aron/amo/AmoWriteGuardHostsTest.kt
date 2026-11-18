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

    private fun correctAndSave(why: AttendanceRefusal?, wrapped: Boolean = true, refused: Boolean = why != null, stock: Boolean = true) {
        if (stock) f.stockedCatalog()
        val before = stockRows(); val queued = queuedStock()
        show { if (wrapped) ProvideWriteGuard(why, "Rahim Store 50001", onCheckIn = {}) { AmoStockCorrectHost(f.day, onDone = {}) } else AmoStockCorrectHost(f.day, onDone = {}) }
        assertTrue(f.work.awaitSettled()); compose.waitForIdle()
        compose.waitUntil(30_000) { runCatching { compose.onNodeWithTag(CorrectionTags.row(105)).assertExists() }.isSuccess }
        compose.onNodeWithTag(CorrectionTags.row(105)).performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(CorrectionTags.TOTAL)) or (androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasTestTag(CorrectionTags.TOTAL))).performTextInput("15")
        compose.onNodeWithTag(CorrectionTags.reason(CorrectionReason.COUNT_ERROR)).performClick()
        compose.onNodeWithTag(CorrectionTags.SAVE).performClick()
        if (refused) { assertEquals("nothing stored", before, stockRows()); assertEquals("nothing queued", queued, queuedStock()) }
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

    /** D-AGATE-19: UNWRAPPED (no guard from the app), the host guards ITSELF from the day's own gate: after check-out the Save is refused at the button with the named reason, nothing stored. */
    @Test fun theStockCorrectionGuardsItselfWhenTheAppGivesItNoGuard() {
        f.stockedCatalog() // while the day is open: after the check-out the write layer refuses even this
        runBlocking {
            val u = com.aktcl.aron.core.common.ClientIds.newUuid()
            val fix = com.aktcl.aron.core.database.entity.GeoFixEntity(
                com.aktcl.aron.core.common.ClientIds.newUuid(), u, "attendance_out", "ok", AmoLockFixture.LAT, AmoLockFixture.LNG, 8.0, provider = "fused", fixTime = f.day.iso(f.day.currentMs()), fixElapsedRealtimeMs = 900, fixAgeMs = 10,
                timeToFixMs = 1000, requestPriority = "balanced", isMock = false, reused = false, refreshCount = 0, deviceOwner = true, devOptionsEnabled = false,
                adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false,
            )
            f.day.capture.recordAttendance(com.aktcl.aron.core.database.entity.AttendanceEventEntity(u, f.day.metaProvider.meta(0L).copy(routeId = null), "check_out", fix.clientUuid, null), fix)
            f.day.refreshDayGate()
        }
        correctAndSave(null, wrapped = false, refused = true, stock = false)
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("You have checked out for today").assertExists() }.isSuccess }
        compose.onNodeWithText("Could not save", substring = true).assertDoesNotExist()
    }

    /** ... and on an open day the unwrapped host saves. */
    @Test fun theUnwrappedStockCorrectionSavesOnceCheckedIn() {
        correctAndSave(null, wrapped = false) // the fixture day starts checked in
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag(CorrectionTags.SAVED).assertExists() }.isSuccess || stockRows() > 1 }
        compose.onNodeWithText("Check in first").assertDoesNotExist()
    }

    // ---- D-AGATE-19: the other write screens, each through its REAL button on a real AmoDay, refused at the button with the named reason; nothing is stored or queued

    private fun outboxRows() = runBlocking { f.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM outbox").use { c -> c.moveToFirst(); c.getInt(0) } }

    /** Picks option [index] of the drop-down whose form field has the key [key]. */
    private fun pick(key: String, index: Int = 0) {
        // the route days load off the main thread: open the list until its options are there
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag(com.aktcl.aron.core.ui.aron.AronDropdownTags.option(index)).assertExists() }.isSuccess || run {
                runCatching { compose.onNode(androidx.compose.ui.test.hasTestTag(com.aktcl.aron.core.ui.aron.AronDropdownTags.FIELD) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(com.aktcl.aron.feature.amo.OutletFormTags.field(key)))).performClick() }
                compose.waitForIdle(); false
            }
        }
        compose.onNodeWithTag(com.aktcl.aron.core.ui.aron.AronDropdownTags.option(index)).performClick()
        compose.waitForIdle()
    }

    private fun refusedAtTheButton(why: AttendanceRefusal = AttendanceRefusal.NOT_CHECKED_IN, title: String = "Check in first") {
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText(title).assertExists() }.isSuccess }
        compose.onNodeWithText("The call could not be", substring = true).assertDoesNotExist()
    }

    /** Own outlet requests (Close shop): cluster, retailer, Save, "Yes" to the confirmation: the guard answers before anything is submitted. */
    @Test fun anOwnOutletRequestIsRefusedAtTheSaveButtonBeforeCheckIn() {
        f.zone()
        val rows = outboxRows()
        show { ProvideWriteGuard(AttendanceRefusal.NOT_CHECKED_IN, null, onCheckIn = {}) { AmoCloseHost(f.day) } }
        compose.waitForIdle()
        pick("cluster"); pick("retailer")
        compose.onNodeWithTag(com.aktcl.aron.feature.amo.OutletFormTags.SAVE).performClick()
        compose.onNodeWithText("Yes").performClick()
        refusedAtTheButton()
        assertEquals("nothing queued", rows, outboxRows())
    }

    /** Exceptions: opening a signal and tapping an action is refused with the named reason (an earlier call open names the outlet in its text elsewhere); no review is queued. */
    @Test fun anExceptionActionIsRefusedAtTheButtonBeforeCheckIn() {
        runBlocking {
            f.db.referenceDao().insertSections(listOf(com.aktcl.aron.core.database.entity.BundleSectionEntity("supervisor",
                """{"risk_signals":[{"signal_id":7,"status":"open","business_date":"${f.date}","code":"GEO_MOCK","severity":2,"subject_type":"user","subject_id":"1","score":1.0,"evidence":{}}]}""")))
        }
        val rows = outboxRows()
        show { ProvideWriteGuard(AttendanceRefusal.NOT_CHECKED_IN, null, onCheckIn = {}) { AmoTeamHub(f.day, com.aktcl.aron.feature.amo.AmoReach(0, 0), onBack = {}, startExceptions = true) } }
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Fake location").assertExists() }.isSuccess }
        compose.onNodeWithText("Fake location").performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Mark as reviewed").assertExists() }.isSuccess }
        compose.onNodeWithText("Mark as reviewed").performClick()
        refusedAtTheButton()
        assertEquals("no review queued", rows, outboxRows())
    }

    /** Opens the [AmoDropdown] tagged [tag] and taps its first option. */
    private fun pickFirst(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
        compose.onAllNodes(androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isPopup()))[0].performClick()
        compose.waitForIdle()
    }

    private fun supervisor(json: String) = runBlocking { f.db.referenceDao().insertSections(listOf(com.aktcl.aron.core.database.entity.BundleSectionEntity("supervisor", json))) }

    /** Verification: a pending close request opened from the list is decided at the form's real Save button: refused with the named reason, no decision queued. */
    @Test fun aVerificationDecisionIsRefusedAtTheButtonBeforeCheckIn() {
        f.zone()
        supervisor("""{"pending_outlet_requests":[{"request_uuid":"11111111-1111-4111-8111-111111111111","request_type":"close","outlet_id":50001,"outlet_name":"Rahim Store 50001","requested_by_user_id":999,"status":"pending","requested_at":"${f.date}T04:00:00.000Z"}]}""")
        val rows = outboxRows()
        show { ProvideWriteGuard(AttendanceRefusal.NOT_CHECKED_IN, null, onCheckIn = {}) { AmoVerifyHost(f.day, "11111111-1111-4111-8111-111111111111", onDone = {}, onGone = {}) } }
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag(com.aktcl.aron.feature.amo.OutletFormTags.SAVE).assertExists() }.isSuccess }
        compose.onNodeWithTag(com.aktcl.aron.feature.amo.OutletFormTags.SAVE).performClick()
        refusedAtTheButton()
        assertEquals("no decision queued", rows, outboxRows())
    }

    /** Task assign: route, outlet, type, a note, then the real Assign button: refused with the named reason, no task queued. */
    @Test fun aTaskAssignmentIsRefusedAtTheButtonBeforeCheckIn() {
        f.zone()
        supervisor("""{"team":[{"user_id":42,"role":"SR","full_name":"Karim SR","route_ids":[${f.route}]}]}""")
        runBlocking { f.db.referenceDao().insertSections(listOf(com.aktcl.aron.core.database.entity.BundleSectionEntity("code_lists", """[{"list_key":"task_type","items":[{"code":"visit_outlet","label_en":"Visit outlet","label_bn":"দোকান"}]}]"""))) }
        val rows = outboxRows()
        show { ProvideWriteGuard(AttendanceRefusal.NOT_CHECKED_IN, null, onCheckIn = {}) { AmoTaskHub(f.day, onBack = {}) } }
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag(com.aktcl.aron.feature.amo.TaskTags.FAB).assertExists() }.isSuccess }
        compose.onNodeWithTag(com.aktcl.aron.feature.amo.TaskTags.FAB).performClick()
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag(com.aktcl.aron.feature.amo.TaskFormTags.ROUTE).assertExists() }.isSuccess }
        pickFirst(com.aktcl.aron.feature.amo.TaskFormTags.ROUTE)
        pickFirst(com.aktcl.aron.feature.amo.TaskFormTags.OUTLET)
        pickFirst(com.aktcl.aron.feature.amo.TaskFormTags.TYPE)
        compose.onNode(androidx.compose.ui.test.hasSetTextAction() and (androidx.compose.ui.test.hasTestTag(com.aktcl.aron.feature.amo.TaskFormTags.TEXT) or androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(com.aktcl.aron.feature.amo.TaskFormTags.TEXT)))).performTextInput("Check the shelf")
        compose.onNodeWithTag(com.aktcl.aron.feature.amo.TaskTags.FORM_SAVE).performClick()
        refusedAtTheButton()
        assertEquals("no task queued", rows, outboxRows())
    }

    /** Update base: route, retailer, the photo-and-fix step (a stand-in camera; the fixture's fix is real and not mock), the map step's real Confirm: refused with the named reason, no request queued. */
    @Test fun anUpdateBaseConfirmIsRefusedAtTheButtonBeforeCheckIn() {
        f.zone()
        f.day.photoPipeline = object : com.aktcl.aron.feature.outlet.PhotoPipeline {
            override suspend fun captureAndCompress(photoUuid: String) = com.aktcl.aron.feature.outlet.CapturedPhoto(photoUuid, "/dev/null", 1L)
            override suspend fun discard(photoUuid: String) = Unit
        }
        val rows = outboxRows()
        show { ProvideWriteGuard(AttendanceRefusal.NOT_CHECKED_IN, null, onCheckIn = {}) { AmoUpdateBaseHost(f.day) } }
        compose.waitForIdle()
        pick("route"); pick("retailer")
        compose.onNodeWithText("Update Base").performClick()
        compose.onNodeWithText("Update").performClick()
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag(com.aktcl.aron.feature.amo.UpdateBaseMapTags.CONFIRM).assertExists() }.isSuccess }
        compose.onNodeWithTag(com.aktcl.aron.feature.amo.UpdateBaseMapTags.CONFIRM).performClick()
        refusedAtTheButton()
        assertEquals("no request queued", rows, outboxRows())
    }
}

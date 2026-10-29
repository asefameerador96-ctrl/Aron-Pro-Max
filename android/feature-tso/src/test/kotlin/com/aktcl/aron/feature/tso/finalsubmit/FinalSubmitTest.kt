package com.aktcl.aron.feature.tso.finalsubmit

import com.aktcl.aron.feature.tso.a11yProblems
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private const val D = "2026-10-26"

/** Screenshot 25's zone: AMO routes and SR routes with "Daily", vacant routes "SR Not Set", the BR dummy route not due. */
private val routes = listOf(
    FsRoute(1, "AMO-1", "AMO-Apsis Route", "amo", null, "in_field", false, 0, 0, null, uploadState = FsUploadState.NOT_DUE),
    FsRoute(4, "R-1", "Apsis Route", "sr", "SR - Testing Banani", "sales_submitted", true, 12, 4_391_000, 0, true, "normal", "daily", "SR", FsUploadState.UPLOADED),
    FsRoute(5, "R-2", "Apsis Route 2", "sr", "SR - Testing Banani 2", "in_field", true, 3, 90_000, null, true, "normal", "daily", "SR", FsUploadState.NOT_UPLOADED),
    FsRoute(6, "R-3", "Apsis Route 3", "sr", null, "not_started", false, 0, 0, null, true, "normal", "daily", null, FsUploadState.WEB_ENTRY),
    FsRoute(8, "BR", "BR", "sr", null, "not_started", false, 0, 0, null, true, "dummy_br", "daily", null, FsUploadState.NOT_DUE),
)

private fun preview(already: Boolean = false, warn: List<Long>? = listOf(5), seq: Int = 0, rs: List<FsRoute> = routes) =
    FsPreview(10, D, already, if (already) "2026-10-26T11:00:00Z" else null, if (already) "tso1001" else null, rs, emptyList(), seq, warn)

private class Api(
    var zones: List<Long>? = listOf(10, 11),
    var previews: MutableList<FsPreviewOutcome> = mutableListOf(FsPreviewOutcome.Ok(preview())),
    var submit: MutableList<FsSubmitOutcome> = mutableListOf(FsSubmitOutcome.Ok(FsResult(10, D, "2026-10-26T12:00:00Z", 5, 15, 4_481_000))),
    var options: FsFilterOptions? = null,
) : FinalSubmitApi {
    val submits = mutableListOf<Triple<String, Long, String>>(); var previewCalls = 0
    override suspend fun zones() = zones
    override suspend fun filterOptions() = options
    override suspend fun preview(zoneId: Long, date: String): FsPreviewOutcome { previewCalls++; return if (previews.size > 1) previews.removeAt(0) else previews[0] }
    override suspend fun submit(clientUuid: String, zoneId: Long, date: String): FsSubmitOutcome { submits += Triple(clientUuid, zoneId, date); return if (submit.size > 1) submit.removeAt(0) else submit[0] }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FinalSubmitLogicTest {
    private fun st(api: Api) = FinalSubmitState(api, 7) { D }

    @Test fun clientUuidIsTheSeqNameBasedUuid() {
        val a = FsIds.clientUuid(7, 10, D, 0)
        assertEquals(com.aktcl.aron.feature.tso.StableIds.v4("aron.tso.final-submit:7:10:$D:0"), a)
        assertEquals(a, FsIds.clientUuid(7, 10, D, 0))
        assertNotEquals(a, FsIds.clientUuid(7, 10, D, 1)) // a submit after a reopen is a new one (CR-3)
        assertNotEquals(a, FsIds.clientUuid(8, 10, D, 0)); assertNotEquals(a, FsIds.clientUuid(7, 11, D, 0)); assertNotEquals(a, FsIds.clientUuid(7, 10, "2026-10-25", 0))
    }

    @Test fun everyStableIdIsAValidVersion4Uuid() {
        val ids = listOf(
            FsIds.clientUuid(7, 10, D, 0), FsIds.clientUuid(7, 10, D, 3), com.aktcl.aron.feature.tso.mycall.PlanIds.plan(7, "2026-10-18", 1), com.aktcl.aron.feature.tso.mycall.PlanIds.planOutlet("p", 5),
            com.aktcl.aron.feature.tso.mycall.QueryIds.assessment("p", 1), com.aktcl.aron.feature.tso.mycall.QueryIds.answer("a", 2), com.aktcl.aron.feature.tso.mycall.QueryIds.task("p", 3),
        )
        ids.forEach { assertTrue("not v4: $it", com.aktcl.aron.feature.tso.StableIds.isV4(it)) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun validation() {
        assertTrue(validateFs(10, D, D).isEmpty())
        assertEquals(listOf(FsError.NO_ZONE), validateFs(null, D, D))
        assertEquals(listOf(FsError.DATE_FUTURE), validateFs(10, "2026-10-27", D))
        assertEquals(listOf(FsError.DATE_FORMAT), validateFs(10, "26-10-2026", D))
    }

    @Test fun noConfirmWhenNothingIsDue() = runBlocking {
        val api = Api(previews = mutableListOf(FsPreviewOutcome.Ok(preview(warn = emptyList())))); val s = st(api)
        s.loadZones(); s.pickZone(10); s.getSalesData()
        s.submit() // straight away: no dialog
        assertEquals(1, api.submits.size)
        assertEquals(FsIds.clientUuid(7, 10, D, 0), api.submits[0].first)
        assertTrue(s.ui.value.phase is FsPhase.Done)
    }

    @Test fun theWarningNamesTheRoutesAndNeverBlocks() = runBlocking {
        val api = Api(); val s = st(api)
        s.pickZone(10); s.getSalesData()
        s.submit()
        val warned = s.ui.value.phase as FsPhase.Review
        assertTrue(warned.warning); assertTrue(api.submits.isEmpty())
        assertEquals(listOf(5L), warned.preview.warnRoutes.map { it.routeId })
        s.cancelConfirm(); assertFalse((s.ui.value.phase as FsPhase.Review).warning); assertTrue(api.submits.isEmpty())
        s.submit(); s.confirmSubmit(); s.confirmSubmit() // "Submit anyway", then a double tap
        assertEquals(1, api.submits.size)
        assertTrue(s.ui.value.phase is FsPhase.Done)
    }

    @Test fun anOlderServerWithoutWarnIdsWarnsOnTheNotUploadedRoutes() {
        assertEquals(listOf(5L), preview(warn = null).warnRoutes.map { it.routeId })
        assertEquals(emptyList<Long>(), preview(warn = null, rs = routes.filter { it.routeId != 5L }).warnRoutes.map { it.routeId })
        assertEquals(listOf(4L), preview(warn = listOf(4)).warnRoutes.map { it.routeId }) // the server's list wins
    }

    @Test fun theNextSubmitUsesThePreviewsSubmitSeq() = runBlocking {
        val api = Api(previews = mutableListOf(FsPreviewOutcome.Ok(preview(warn = emptyList(), seq = 2)))); val s = st(api)
        s.pickZone(10); s.getSalesData(); s.submit()
        assertEquals(FsIds.clientUuid(7, 10, D, 2), api.submits.single().first)
    }

    @Test fun a409ReopenedReadsThePreviewAgainAndTheNextSubmitIsNew() = runBlocking {
        val api = Api(
            previews = mutableListOf(FsPreviewOutcome.Ok(preview(warn = emptyList(), seq = 0)), FsPreviewOutcome.Ok(preview(warn = emptyList(), seq = 1))),
            submit = mutableListOf(FsSubmitOutcome.Reopened, FsSubmitOutcome.Ok(FsResult(10, D, "t", 5, 15, 1))),
        )
        val s = st(api)
        s.pickZone(10); s.getSalesData(); s.submit()
        val again = s.ui.value.phase as FsPhase.Review
        assertEquals(2, api.previewCalls)
        assertEquals(FsNotice.REOPENED, again.notice)
        assertEquals(1, again.preview.submitSeq)
        s.submit()
        assertEquals(listOf(FsIds.clientUuid(7, 10, D, 0), FsIds.clientUuid(7, 10, D, 1)), api.submits.map { it.first })
        assertTrue(s.ui.value.phase is FsPhase.Done)
    }

    @Test fun okAfterSuccessClearsTheZoneForTheNextZone() = runBlocking {
        val api = Api(previews = mutableListOf(FsPreviewOutcome.Ok(preview(warn = emptyList()))), options = FinalSubmitCascadeTest.screenshot23); val s = st(api)
        s.loadCascade()
        assertEquals(mapOf(0 to 1L, 1 to 11L, 2 to 21L, 3 to 31L), s.ui.value.picks) // Apsis Wing > Division > Territory > House preselected
        s.pickLevel(4, 41); s.getSalesData(); s.submit()
        assertTrue(s.ui.value.phase is FsPhase.Done)
        s.nextZone() // the success dialog's OK (CR-5)
        assertEquals(FsPhase.Pick, s.ui.value.phase)
        assertNull(s.ui.value.zoneId)
        assertEquals(mapOf(0 to 1L, 1 to 11L, 2 to 21L, 3 to 31L), s.ui.value.picks) // the levels above stay
        assertEquals(listOf(41L, 42L), s.ui.value.options[4]!!.map { it.id }) // the next zone can be picked at once
        s.pickLevel(4, 42); assertEquals(42L, s.ui.value.zoneId)
    }

    @Test fun alreadySubmittedAlertFiresAtGetSalesDataAndNothingIsSubmitted() = runBlocking {
        val api = Api(previews = mutableListOf(FsPreviewOutcome.Ok(preview(already = true)))); val s = st(api)
        s.loadZones(); s.pickZone(10); s.getSalesData()
        assertTrue(s.ui.value.phase is FsPhase.AlreadySubmitted)
        s.submit(); s.confirmSubmit(); assertTrue(api.submits.isEmpty())
        s.dismissAlready(); assertEquals(FsPhase.Pick, s.ui.value.phase)
    }

    @Test fun offlineFailuresAreWordsAndTheRetryKeepsTheSameUuid() = runBlocking {
        val api = Api(previews = mutableListOf(FsPreviewOutcome.Failed(FsFailure.OFFLINE))); val s = st(api)
        s.pickZone(10); s.getSalesData(); assertEquals(FsPhase.Failed(FsFailure.OFFLINE), s.ui.value.phase)
        api.previews = mutableListOf(FsPreviewOutcome.Ok(preview(warn = emptyList())))
        api.submit = mutableListOf(FsSubmitOutcome.Failed(FsFailure.OFFLINE))
        s.retry(); s.getSalesData(); s.submit()
        assertEquals(FsPhase.Failed(FsFailure.OFFLINE), s.ui.value.phase)
        api.submit = mutableListOf(FsSubmitOutcome.Ok(FsResult(10, D, "t", 2, 12, 1)))
        s.retry(); s.getSalesData(); s.submit()
        assertEquals(2, api.submits.size); assertEquals(api.submits[0].first, api.submits[1].first) // a lost answer replays, never a second submit
        assertTrue(s.ui.value.phase is FsPhase.Done)
    }

    @Test fun aDifferentSubmitterGets409AsTheAlreadySubmittedAlert() = runBlocking {
        val api = Api(previews = mutableListOf(FsPreviewOutcome.Ok(preview(warn = emptyList()))), submit = mutableListOf(FsSubmitOutcome.AlreadySubmitted)); val s = st(api)
        s.pickZone(10); s.getSalesData(); s.submit()
        assertTrue(s.ui.value.phase is FsPhase.AlreadySubmitted)
    }

    /** Checker fix (low): a state kept over midnight asks for the new day's sales, not the day the state was made. */
    @Test fun getSalesDataAsksForTodayWhenItIsTapped() = runBlocking {
        var today = "2026-10-26"
        val dates = ArrayList<String>()
        val api = object : FinalSubmitApi by Api() {
            override suspend fun preview(zoneId: Long, date: String): FsPreviewOutcome { dates += date; return FsPreviewOutcome.Failed(FsFailure.OFFLINE) }
        }
        val s = FinalSubmitState(api, 7) { today }
        s.pickZone(10); s.getSalesData()
        today = "2026-10-27" // the TSO comes back to the kept screen after midnight
        s.retry(); s.getSalesData()
        assertEquals(listOf("2026-10-26", "2026-10-27"), dates)
        assertEquals("2026-10-27", s.ui.value.date)
    }

    @Test fun noZoneIsAnErrorAndPreviewIsNotCalled() = runBlocking {
        val api = Api(); val s = st(api); s.getSalesData()
        assertEquals(listOf(FsError.NO_ZONE), s.ui.value.errors); assertEquals(0, api.previewCalls)
    }

    @Test fun kindLabelsAndNotDue() {
        assertEquals(listOf(FsKindLabel.AMO, FsKindLabel.DAILY, FsKindLabel.DAILY, FsKindLabel.DAILY, FsKindLabel.DAILY), routes.map { it.kindLabel })
        assertEquals(FsKindLabel.F3, routes[1].copy(visitKind = "3F").kindLabel); assertEquals(FsKindLabel.F2, routes[1].copy(visitKind = "2f").kindLabel)
        assertNull(routes[1].copy(visitKind = null).kindLabel)
        assertEquals(listOf(1L, 8L), routes.filter { it.notDue }.map { it.routeId })
        assertEquals(listOf(5L), routes.filter { it.notUploaded }.map { it.routeId })
        // before 1.10.0: not planned and no data
        fun old(planned: Boolean?, data: Boolean) = FsRoute(1, "A", "N", "sr", null, "in_field", data, 0, 0, null, planned)
        assertTrue(old(false, false).notDue); assertFalse(old(false, true).notDue); assertFalse(old(true, false).notDue); assertFalse(old(null, false).notDue)
    }

    @Test fun jsonReadsThe110PreviewAndAnOlderOne() {
        val r110 = """{"route_id":5,"route_code":"R-2","route_name":"Apsis Route 2","kind":"sr","ff_user_id":9,"ff_user_name":"SR - Testing Banani 2","state":"in_field","had_data":true,"memo_count":3,"net_mtk":90000,"pending_rows_reported":null,"planned":true,"route_class":"normal","visit_kind":"daily","ff_role":"SR","upload_state":"not_uploaded"}"""
        val p = FsJson.preview("""{"zone_id":10,"business_date":"$D","already_submitted":false,"submitted_at":null,"submitted_by":null,"routes":[$r110],"warnings":["routes_not_sales_submitted"],"submit_seq":2,"warn_route_ids":[5]}""")!!
        val r = p.routes.single()
        assertEquals(FsUploadState.NOT_UPLOADED, r.uploadState); assertEquals("daily", r.visitKind); assertEquals("SR", r.ffRole); assertEquals("normal", r.routeClass)
        assertEquals(2, p.submitSeq); assertEquals(listOf(5L), p.warnRouteIds)
        val old = FsJson.preview("""{"zone_id":10,"business_date":"$D","already_submitted":false,"submitted_at":null,"submitted_by":null,"routes":[{"route_id":1,"route_code":"A","route_name":"N","kind":"sr","ff_user_id":null,"ff_user_name":null,"state":"in_field","had_data":true,"memo_count":3,"net_mtk":5000,"pending_rows_reported":null}],"warnings":[]}""")!!
        assertEquals(0, old.submitSeq); assertNull(old.warnRouteIds); assertNull(old.routes[0].uploadState); assertNull(old.routes[0].ffName)
        assertNull(FsJson.preview("<html>")); assertNull(FsJson.result("{}"))
        assertEquals(2, FsJson.result("""{"zone_id":1,"business_date":"$D","submitted_at":"t","kind":"manual","route_count":2,"memo_count":9,"net_mtk":7}""")!!.routeCount)
    }
}

@RunWith(AndroidJUnit4::class)
// NATIVE graphics: the legacy mode measures text at 1 px per character, which makes the touch-target sweep meaningless
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FinalSubmitScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun show(language: AppLanguage, st: FinalSubmitState) {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { AronTheme(language) { FinalSubmitScreen(st, onBack = {}) } } }
        compose.waitForIdle()
    }

    private fun pickZone41() {
        compose.onNodeWithTag(FsTags.level(4)).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithText("Banani - Test").performClick(); compose.waitForIdle()
    }

    @Test fun theFiveLevelsWithSingleOptionsPreselectedAndGetEnabledOnlyWithTheZone() {
        val s = FinalSubmitState(Api(options = FinalSubmitCascadeTest.screenshot23), 7) { D }
        show(AppLanguage.EN, s)
        listOf("Wing", "Division", "Territory", "House", "Zone").forEach { compose.onNodeWithText(it).assertExists() }
        listOf("Apsis Wing", "Apsis Division", "Apsis Territory", "Apsis House").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Select Zone").assertExists() // two zones: the TSO chooses
        compose.onNodeWithTag(FsTags.GET).assertIsNotEnabled()
        pickZone41()
        compose.onNodeWithTag(FsTags.GET).assertIsEnabled()
        org.junit.Assert.assertEquals("accessibility: " + compose.a11yProblems(), emptyList<String>(), compose.a11yProblems())
    }

    @Test fun routesShowTitleKindFfAndTheNotUploadedChipThenWarnThenSuccessThenNextZone() {
        val api = Api(options = FinalSubmitCascadeTest.screenshot23); val s = FinalSubmitState(api, 7) { D }
        show(AppLanguage.EN, s)
        pickZone41()
        compose.onNodeWithTag(FsTags.GET).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithText("Sales Date:").assertExists(); compose.onNodeWithText(D).assertExists()
        compose.onNodeWithText("AMO-Apsis Route AMO").assertExists()
        compose.onNodeWithText("Apsis Route Daily").assertExists()
        compose.onNodeWithTag(FsTags.ff(1)).assertExists(); compose.onNodeWithText("FF: SR - Testing Banani").assertExists()
        compose.onNodeWithTag(FsTags.notUploaded(5)).performScrollTo().assertExists(); compose.onNodeWithTag(FsTags.notUploaded(4)).assertDoesNotExist()
        compose.onNodeWithText("Not uploaded").assertExists()
        compose.onNodeWithText("4,391", substring = true).assertDoesNotExist() // the memo and net line is dropped (screenshot 25)
        compose.onNodeWithTag(FsTags.SUBMIT).performClick(); compose.waitForIdle()
        assertTrue("the warning comes first", api.submits.isEmpty())
        compose.onNodeWithText("Routes not uploaded").assertExists()
        compose.onNodeWithText("• Apsis Route 2 Daily", substring = true).assertExists() // the dialog names the route
        compose.onNodeWithText("Submit anyway").performClick(); compose.waitForIdle()
        assertEquals(1, api.submits.size)
        compose.onNodeWithText("Success").assertExists(); compose.onNodeWithText("Final Submit Done Successfully...").assertExists()
        compose.onNodeWithTag(FsTags.SUBMIT).assertIsNotEnabled() // greyed behind the dialog (screenshot 26)
        compose.onNodeWithText("OK").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Select Zone").assertExists() // back to the pickers, the zone cleared (CR-5)
        compose.onNodeWithText("Apsis House").assertExists()
        compose.onNodeWithTag(FsTags.GET).assertIsNotEnabled()
    }

    @Test fun banglaAlertIsTheVerbatimBanglaMessage() {
        val s = FinalSubmitState(Api(previews = mutableListOf(FsPreviewOutcome.Ok(preview(true)))), 7) { D }
        show(AppLanguage.BN, s)
        compose.onNodeWithTag(FsTags.FLAT_ZONE).performClick(); compose.waitForIdle() // filter options unreadable: the flat zone list
        compose.onNodeWithText("জোন ১০").performClick(); compose.waitForIdle()
        compose.onNodeWithTag(FsTags.GET).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithText("আপনি ইতিমধ্যেই আজকের জন্য 'FINAL SUBMIT' জমা দিয়েছেন!").assertExists()
    }

    private fun showNotDue(lang: AppLanguage, word: String) {
        val s = FinalSubmitState(Api(options = FinalSubmitCascadeTest.screenshot23), 7) { D }
        show(lang, s)
        runBlocking { s.pickLevel(4, 41); s.getSalesData() }; compose.waitForIdle()
        compose.onNodeWithTag(FsTags.notDue(8)).performScrollTo().assertExists()
        assertEquals(2, compose.onAllNodesWithText(word, useUnmergedTree = true).fetchSemanticsNodes().size) // the AMO route 1 and the BR route 8
        compose.onNodeWithTag(FsTags.notDue(4)).assertDoesNotExist(); compose.onNodeWithTag(FsTags.notDue(5)).assertDoesNotExist()
    }

    @Test fun notDueRouteCardIsLabelledInEnglish() = showNotDue(AppLanguage.EN, "not due")

    @Test fun notDueRouteCardIsLabelledInBangla() = showNotDue(AppLanguage.BN, "আজ নির্ধারিত নয়")

    @Test fun aReopenedZoneSaysSoOverTheRoutes() {
        val api = Api(
            previews = mutableListOf(FsPreviewOutcome.Ok(preview(warn = emptyList())), FsPreviewOutcome.Ok(preview(warn = emptyList(), seq = 1))),
            submit = mutableListOf(FsSubmitOutcome.Reopened, FsSubmitOutcome.Ok(FsResult(10, D, "t", 5, 15, 1))), options = FinalSubmitCascadeTest.screenshot23,
        )
        val s = FinalSubmitState(api, 7) { D }
        show(AppLanguage.EN, s)
        runBlocking { s.pickLevel(4, 41); s.getSalesData(); s.submit() }; compose.waitForIdle()
        compose.onNodeWithTag(FsTags.NOTICE).assertExists()
        compose.onNodeWithTag(FsTags.SUBMIT).assertIsEnabled()
    }
}

/**
 * Checker fix (low): the system back on the pickers (also with a failure line on them) leaves through the same back as the
 * on-screen arrow, so FinalSubmitFlow starts a fresh state next time (no old picks or failure banner, today's date).
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FinalSubmitBackTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun theSystemBackOnThePickersLeavesThroughTheSameBack() {
        var backs = 0
        val s = FinalSubmitState(Api(previews = mutableListOf(FsPreviewOutcome.Failed(FsFailure.OFFLINE))), 7) { D }
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { AronTheme(AppLanguage.EN) { FinalSubmitScreen(s, onBack = { backs++ }) } } }
        compose.waitForIdle()
        runBlocking { s.pickZone(10); s.getSalesData() }; compose.waitForIdle()
        compose.onNodeWithTag(FsTags.FAILED).assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()
        assertEquals("the system back left through onBack", 1, backs)
        compose.onNodeWithContentDescription("Back").performClick(); compose.waitForIdle()
        assertEquals("the on-screen back does the same", 2, backs)
    }

    @Test fun theSystemBackOnTheRoutesReturnsToThePickers() {
        var backs = 0
        val s = FinalSubmitState(Api(previews = mutableListOf(FsPreviewOutcome.Ok(preview(warn = emptyList())))), 7) { D }
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { AronTheme(AppLanguage.EN) { FinalSubmitScreen(s, onBack = { backs++ }) } } }
        compose.waitForIdle()
        runBlocking { s.pickZone(10); s.getSalesData() }; compose.waitForIdle()
        assertTrue(s.ui.value.phase is FsPhase.Review)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()
        assertEquals(FsPhase.Pick, s.ui.value.phase); assertEquals(0, backs)
    }
}

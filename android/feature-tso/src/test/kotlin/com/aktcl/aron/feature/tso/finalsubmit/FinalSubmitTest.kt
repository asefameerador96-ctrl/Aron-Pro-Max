package com.aktcl.aron.feature.tso.finalsubmit

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private val routes = listOf(
    FsRoute(1, "DHK-1", "Route One", "sr", "Rahim", "sales_submitted", true, 12, 4_391_000, 0),
    FsRoute(2, "DHK-2", "Route Two", "amo", null, "not_started", false, 0, 0, null),
)
private fun preview(already: Boolean = false) = FsPreview(10, "2026-10-17", already, if (already) "2026-10-17T11:00:00Z" else null, if (already) "tso1001" else null, routes, emptyList())

private class Api(var zones: List<Long>? = listOf(10, 11), var preview: FsPreviewOutcome = FsPreviewOutcome.Ok(preview()), var submit: FsSubmitOutcome = FsSubmitOutcome.Ok(FsResult(10, "2026-10-17", "2026-10-17T12:00:00Z", 2, 12, 4_391_000))) : FinalSubmitApi {
    val submits = mutableListOf<Triple<String, Long, String>>(); var previews = 0
    override suspend fun zones() = zones
    override suspend fun preview(zoneId: Long, date: String): FsPreviewOutcome { previews++; return preview }
    override suspend fun submit(clientUuid: String, zoneId: Long, date: String): FsSubmitOutcome { submits += Triple(clientUuid, zoneId, date); return submit }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FinalSubmitLogicTest {
    private fun st(api: Api) = FinalSubmitState(api, 7) { "2026-10-17" }

    @Test fun clientUuidIsStablePerPlannerZoneAndDate() {
        val a = FsIds.clientUuid(7, 10, "2026-10-17")
        assertEquals(a, FsIds.clientUuid(7, 10, "2026-10-17"))
        assertNotEquals(a, FsIds.clientUuid(8, 10, "2026-10-17")); assertNotEquals(a, FsIds.clientUuid(7, 11, "2026-10-17")); assertNotEquals(a, FsIds.clientUuid(7, 10, "2026-10-16"))
    }

    @Test fun validation() {
        assertTrue(validateFs(10, "2026-10-17", "2026-10-17").isEmpty()); assertTrue(validateFs(10, "2026-10-01", "2026-10-17").isEmpty())
        assertEquals(listOf(FsError.NO_ZONE), validateFs(null, "2026-10-17", "2026-10-17"))
        assertEquals(listOf(FsError.DATE_FUTURE), validateFs(10, "2026-10-18", "2026-10-17"))
        assertEquals(listOf(FsError.DATE_FORMAT), validateFs(10, "17-10-2026", "2026-10-17"))
    }

    @Test fun alreadySubmittedAlertFiresAtGetSalesDataAndNothingIsSubmitted() = runBlocking {
        val api = Api(preview = FsPreviewOutcome.Ok(preview(already = true))); val s = st(api)
        s.loadZones(); s.pickZone(10); s.getSalesData()
        assertTrue(s.ui.value.phase is FsPhase.AlreadySubmitted)
        s.confirmSubmit(); assertTrue(api.submits.isEmpty())
        s.dismissAlready(); assertEquals(FsPhase.Pick, s.ui.value.phase)
    }

    @Test fun submitNeedsTheExplicitConfirmAndHappensOnce() = runBlocking {
        val api = Api(); val s = st(api)
        s.loadZones(); s.pickZone(10); s.getSalesData()
        val review = s.ui.value.phase as FsPhase.Review
        assertEquals(listOf("Rahim", null), review.preview.routes.map { it.ffName })
        s.confirmSubmit(); assertTrue("no confirm asked yet", api.submits.isEmpty())
        s.askConfirm(); s.cancelConfirm(); s.confirmSubmit(); assertTrue(api.submits.isEmpty())
        s.askConfirm(); s.confirmSubmit(); s.confirmSubmit()
        assertEquals(1, api.submits.size); assertEquals(FsIds.clientUuid(7, 10, "2026-10-17"), api.submits[0].first)
        assertTrue(s.ui.value.phase is FsPhase.Done)
    }

    @Test fun offlinePreviewAndSubmitFailuresAreWordsNotCrashesAndTheSubmitRetriesWithTheSameUuid() = runBlocking {
        val api = Api(preview = FsPreviewOutcome.Failed(FsFailure.OFFLINE)); val s = st(api)
        s.pickZone(10); s.getSalesData(); assertEquals(FsPhase.Failed(FsFailure.OFFLINE), s.ui.value.phase)
        api.preview = FsPreviewOutcome.Ok(preview()); s.retry(); s.getSalesData(); s.askConfirm()
        api.submit = FsSubmitOutcome.Failed(FsFailure.OFFLINE); s.confirmSubmit()
        assertEquals(FsPhase.Failed(FsFailure.OFFLINE), s.ui.value.phase)
        api.submit = FsSubmitOutcome.Ok(FsResult(10, "2026-10-17", "t", 2, 12, 1)); s.retry(); s.getSalesData(); s.askConfirm(); s.confirmSubmit()
        assertEquals(2, api.submits.size); assertEquals(api.submits[0].first, api.submits[1].first)
        assertTrue(s.ui.value.phase is FsPhase.Done)
    }

    @Test fun aDifferentSubmitterGets409AsTheAlreadySubmittedAlert() = runBlocking {
        val api = Api(submit = FsSubmitOutcome.AlreadySubmitted); val s = st(api)
        s.pickZone(10); s.getSalesData(); s.askConfirm(); s.confirmSubmit()
        assertTrue(s.ui.value.phase is FsPhase.AlreadySubmitted)
    }

    @Test fun noZoneIsAnErrorAndPreviewIsNotCalled() = runBlocking {
        val api = Api(); val s = st(api); s.getSalesData()
        assertEquals(listOf(FsError.NO_ZONE), s.ui.value.errors); assertEquals(0, api.previews)
    }

    @Test fun doneIsFinal() = runBlocking {
        val api = Api(); val s = st(api); s.pickZone(10); s.getSalesData(); s.askConfirm(); s.confirmSubmit()
        s.pickZone(11); s.setDate("2026-10-16"); s.getSalesData()
        assertTrue(s.ui.value.phase is FsPhase.Done); assertEquals(1, api.previews)
    }

    @Test fun jsonReadsThePreviewAndTheResult() {
        val p = FsJson.preview("""{"zone_id":10,"business_date":"2026-10-17","already_submitted":false,"submitted_at":null,"submitted_by":null,"routes":[{"route_id":1,"route_code":"A","route_name":"N","kind":"sr","ff_user_id":null,"ff_user_name":null,"state":"in_field","had_data":true,"memo_count":3,"net_mtk":5000,"pending_rows_reported":null}],"warnings":["routes_not_logged_in"]}""")!!
        assertNull(p.routes[0].ffName); assertEquals(5000L, p.routes[0].netMtk); assertEquals(listOf("routes_not_logged_in"), p.warnings)
        assertNull(FsJson.preview("<html>")); assertNull(FsJson.result("{}"))
        assertEquals(2, FsJson.result("""{"zone_id":1,"business_date":"2026-10-17","submitted_at":"t","kind":"manual","route_count":2,"memo_count":9,"net_mtk":7}""")!!.routeCount)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FinalSubmitScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun show(language: AppLanguage, st: FinalSubmitState) {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { AronTheme(language) { FinalSubmitScreen(st, onBack = {}) } } }
        compose.waitForIdle()
    }

    @Test fun bangla_alertIsTheVerbatimBanglaMessage() {
        val s = FinalSubmitState(Api(preview = FsPreviewOutcome.Ok(preview(true))), 7) { "2026-10-17" }
        show(AppLanguage.BN, s)
        compose.onNodeWithTag(FsTags.zone(10)).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithTag(FsTags.GET).performClick(); compose.waitForIdle()
        compose.onNodeWithText("আপনি ইতিমধ্যেই আজকের জন্য 'FINAL SUBMIT' জমা দিয়েছেন!").assertExists()
    }

    @Test fun routesShowTheFfNameOrSrNotSetThenConfirmThenSuccess() {
        val api = Api(); val s = FinalSubmitState(api, 7) { "2026-10-17" }
        show(AppLanguage.EN, s)
        compose.onNodeWithTag(FsTags.zone(10)).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithTag(FsTags.GET).performClick(); compose.waitForIdle()
        compose.onNodeWithText("FF: Rahim").assertExists(); compose.onNodeWithText("FF: SR Not Set").performScrollTo().assertExists()
        compose.onNodeWithText("Sales Date: 2026-10-17").assertExists()
        compose.onNodeWithTag(FsTags.SUBMIT).performClick(); compose.waitForIdle()
        assertTrue("a confirm dialog comes first", api.submits.isEmpty())
        compose.onNodeWithText("Submit the sales of 2026-10-17 for 2 routes? This cannot be undone.").assertExists()
    }
}

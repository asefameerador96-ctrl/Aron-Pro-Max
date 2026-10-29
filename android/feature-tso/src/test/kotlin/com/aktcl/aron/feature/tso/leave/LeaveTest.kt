package com.aktcl.aron.feature.tso.leave

import com.aktcl.aron.feature.tso.a11yProblems
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

private fun item(uuid: String, from: String, days: Int = 2, status: LeaveStatus = LeaveStatus.PENDING, sent: Boolean = true, type: String = "casual", reason: String = "family", refusal: String? = null) =
    LeaveItem(uuid, type, from, leaveToDate(from, days), days, reason, status, sent, refusal)

private class FakeSource(
    var cached: LeaveSource.Cached? = null, var unsent: List<LeaveItem> = emptyList(), var fresh: LeaveSource.Cached? = null, var throwOnRefresh: Boolean = false,
    var types: List<LeaveType> = emptyList(), var limits: LeaveWindow = LeaveWindow(),
) : LeaveSource {
    override suspend fun cached() = cached
    override suspend fun unsent() = unsent
    override suspend fun refresh(): LeaveSource.Cached? = if (throwOnRefresh) error("boom") else fresh
    override suspend fun leaveTypes() = types
    override suspend fun window() = limits
}

class LeaveModelTest {
    @Test fun toDateIsFromPlusDaysMinusOneInCalendarDays() {
        assertEquals("2026-10-05", leaveToDate("2026-10-05", 1))
        assertEquals("2026-10-07", leaveToDate("2026-10-05", 3))
        assertEquals("2026-11-01", leaveToDate("2026-10-30", 3)) // month end
        // screenshot 19's "2 Days" shown as one date: the build counts the days, Friday 2026-10-30 included (D-TSO-MAN-33)
        assertEquals("2026-02-26", leaveToDate("2026-02-25", 2))
        assertEquals("2026-10-31", leaveToDate("2026-10-29", 3))
    }

    @Test fun oldestFirstAndStableAsScreenshot19() {
        val l = orderLeave(listOf(item("b", "2026-10-01"), item("c", "2026-10-09"), item("a", "2026-10-01"), item("d", "2026-02-25")))
        assertEquals(listOf("d", "a", "b", "c"), l.map { it.leaveUuid })
    }

    @Test fun serverRowWinsOverTheSameUnsentUuid() {
        val server = listOf(item("a", "2026-10-01", status = LeaveStatus.APPROVED))
        val local = listOf(item("a", "2026-10-01", sent = false), item("b", "2026-10-03", sent = false))
        val m = mergeLeave(server, local)
        assertEquals(listOf("a", "b"), m.map { it.leaveUuid })
        assertTrue(m.first { it.leaveUuid == "a" }.sent)
        assertEquals(LeaveStatus.APPROVED, m.first { it.leaveUuid == "a" }.status)
    }

    @Test fun unknownStatusIsPendingNeverApproved() = assertEquals(LeaveStatus.PENDING, LeaveStatus.of("whatever"))

    @Test fun refusalCodesMapToTheirReasonWords() {
        assertEquals(LeaveRefusal.DATE_WINDOW, LeaveRefusal.of("business_date_out_of_window"))
        assertEquals(LeaveRefusal.DUPLICATE, LeaveRefusal.of("payload_conflict"))
        assertEquals(LeaveRefusal.NOT_ALLOWED, LeaveRefusal.of("scope_out_of_reach"))
        assertEquals(LeaveRefusal.INVALID, LeaveRefusal.of("schema_invalid"))
        assertEquals(LeaveRefusal.OTHER, LeaveRefusal.of("server_error"))
        assertEquals(LeaveRefusal.OTHER, LeaveRefusal.of(null))
    }

    /** Checker fix (low): the server's answer as the outbox holds it: rejected is "Not accepted", quarantined is "Held for review". */
    @Test fun aQuarantinedApplicationIsHeldForReviewNotNotSentYet() {
        val queued = item("q", "2026-10-21", sent = false)
        assertEquals(queued, queued.answered(null, null))
        val held = queued.answered(LeaveSyncAnswer.HELD, "scope_out_of_reach")
        assertTrue(held.held); assertEquals("scope_out_of_reach", held.refusalCode)
        val refused = queued.answered(LeaveSyncAnswer.REFUSED, null)
        assertFalse(refused.held); assertEquals("server_error", refused.refusalCode)
    }

    @Test fun stateShowsCachedThenFresh() = runBlocking {
        val src = FakeSource(cached = LeaveSource.Cached(listOf(item("a", "2026-10-01")), "09:00"), fresh = LeaveSource.Cached(listOf(item("a", "2026-10-01", status = LeaveStatus.APPROVED)), "10:30"))
        val s = LeaveListState(src); s.load()
        val ui = s.ui.value as LeaveListUi.Content
        assertEquals("10:30", ui.asOf); assertEquals(LeaveStatus.APPROVED, ui.items.single().status); assertEquals(false, ui.offlineCached)
    }

    @Test fun offlineKeepsTheCachedListAndSaysSo() = runBlocking {
        val s = LeaveListState(FakeSource(cached = LeaveSource.Cached(listOf(item("a", "2026-10-01")), "09:00"), fresh = null)); s.load()
        val ui = s.ui.value as LeaveListUi.Content
        assertEquals("09:00", ui.asOf); assertTrue(ui.offlineCached)
    }

    @Test fun refreshThatThrowsNeverCrashesAndNothingAtAllIsAnError() = runBlocking {
        val s = LeaveListState(FakeSource(throwOnRefresh = true)); s.load()
        assertEquals(LeaveListUi.Error, s.ui.value)
    }

    @Test fun unsentApplicationShowsEvenWhenNeverOnline() = runBlocking {
        val s = LeaveListState(FakeSource(unsent = listOf(item("a", "2026-10-01", sent = false)))); s.load()
        val ui = s.ui.value as LeaveListUi.Content
        assertEquals(false, ui.items.single().sent)
    }

    @Test fun emptyServerAndNothingLocalIsEmptyNotError() = runBlocking {
        val s = LeaveListState(FakeSource(fresh = LeaveSource.Cached(emptyList(), "10:00"))); s.load()
        assertEquals(LeaveListUi.Empty, s.ui.value)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LeaveScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(language: AppLanguage, src: LeaveSource, onApply: (() -> Unit)? = null): Context {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) { AronTheme(language) { LeaveListScreen(LeaveListState(src), onBack = {}, onApply = onApply) } }
        }
        compose.waitForIdle()
        return context
    }

    private val rows = LeaveSource.Cached(
        listOf(item("a", "2026-10-05", days = 3), item("b", "2026-09-01", days = 1, status = LeaveStatus.REJECTED, type = "sick")), "10:42",
    )

    private fun spoken(text: String) = SemanticsMatcher("content description $text") { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true }

    @Test fun englishCardsHaveTheScreenshotLayout() {
        show(AppLanguage.EN, FakeSource(fresh = rows, unsent = listOf(item("c", "2026-10-20", status = LeaveStatus.PENDING, sent = false))), onApply = {})
        // oldest first: b (Sep) before a (Oct)
        compose.onNodeWithTag(LeaveTags.card("b")).assertExists()
        compose.onNodeWithText("Rejected by DMO").assertExists()
        compose.onNodeWithText("2026-09-01 to 2026-09-01").assertExists()
        compose.onNodeWithTag(LeaveTags.days("b")).assert(spoken("1 Day"))
        compose.onNodeWithTag(LeaveTags.LIST).performScrollToNode(hasTestTag(LeaveTags.card("a")))
        compose.onNodeWithText("2026-10-05 to 2026-10-07").assertExists()
        compose.onNodeWithTag(LeaveTags.days("a")).assert(spoken("3 Days"))
        compose.onNodeWithTag(LeaveTags.badge("a")).assertExists()
        assertTrue(compose.onAllNodesWithText("Reason: family", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        // no leave type on the card (screenshot 19)
        compose.onNodeWithText("Casual").assertDoesNotExist(); compose.onNodeWithText("Sick").assertDoesNotExist()
        compose.onNodeWithTag(LeaveTags.LIST).performScrollToNode(hasTestTag(LeaveTags.card("c")))
        compose.onNodeWithTag(LeaveTags.unsent("c")).assertExists()
        compose.onNodeWithText("As of 10:42").assertExists()
        compose.onNodeWithTag(LeaveTags.APPLY).assertExists()
        compose.onNodeWithText("Apply For Leave").assertExists()
        org.junit.Assert.assertEquals("accessibility: " + compose.a11yProblems(), emptyList<String>(), compose.a11yProblems())
    }

    @Test fun theListIsInAscendingOrderOnScreen() {
        show(AppLanguage.EN, FakeSource(fresh = rows))
        val order = listOf("b", "a").map { compose.onNodeWithTag(LeaveTags.card(it)).fetchSemanticsNode().positionInRoot.y }
        assertTrue("oldest card first: $order", order[0] < order[1])
    }

    @Test fun aRefusedApplicationSaysNotAcceptedWithTheReason() {
        show(AppLanguage.EN, FakeSource(fresh = LeaveSource.Cached(emptyList(), "10:00"), unsent = listOf(item("r", "2026-10-21", sent = false, refusal = "business_date_out_of_window"))))
        compose.onNodeWithTag(LeaveTags.refused("r")).assertExists()
        compose.onNodeWithText("Not accepted: the date is outside the allowed window").assertExists()
        compose.onNodeWithText("Not accepted").assertExists() // the pill
        compose.onNodeWithTag(LeaveTags.unsent("r")).assertDoesNotExist()
    }

    @Test fun aHeldApplicationSaysHeldForReviewWithTheReason() {
        val held = item("h", "2026-10-21", sent = false).answered(LeaveSyncAnswer.HELD, "scope_out_of_reach")
        show(AppLanguage.EN, FakeSource(fresh = LeaveSource.Cached(emptyList(), "10:00"), unsent = listOf(held)))
        compose.onNodeWithTag(LeaveTags.held("h")).assertExists()
        compose.onNodeWithText("Held for review: you cannot apply for this date").assertExists()
        compose.onNodeWithText("Held for review").assertExists() // the pill
        compose.onNodeWithTag(LeaveTags.unsent("h")).assertDoesNotExist(); compose.onNodeWithTag(LeaveTags.refused("h")).assertDoesNotExist()
    }

    @Test fun banglaUsesBengaliDigitsAndBanglaBadge() {
        show(AppLanguage.BN, FakeSource(fresh = rows))
        compose.onNodeWithText("ডিএমও অনুমোদনের অপেক্ষায়").assertExists()
        compose.onNodeWithText("২০২৬-১০-০৫ থেকে ২০২৬-১০-০৭").assertExists()
        compose.onNodeWithTag(LeaveTags.days("a")).assert(spoken("৩ দিন"))
        compose.onNodeWithText("১০:৪২ পর্যন্ত").assertExists()
    }

    @Test fun emptyAndErrorStatesRender() {
        show(AppLanguage.EN, FakeSource(fresh = LeaveSource.Cached(emptyList(), "10:00")), onApply = {})
        compose.onNodeWithTag(LeaveTags.EMPTY).assertIsDisplayed()
        org.junit.Assert.assertEquals("accessibility: " + compose.a11yProblems(), emptyList<String>(), compose.a11yProblems())
    }

    @Test fun offlineCachedShowsTheBanner() {
        show(AppLanguage.EN, FakeSource(cached = rows))
        compose.onNodeWithTag(LeaveTags.OFFLINE).assertIsDisplayed()
    }

    @Test fun neverOnlineAndNothingCachedShowsTheErrorState() {
        show(AppLanguage.EN, FakeSource())
        compose.onNodeWithTag(LeaveTags.ERROR).assertIsDisplayed()
    }
}

/** Screenshots 20 to 22: the form's empty states, the bundle's leave types, the days field, Apply under the form. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LeaveApplyScreenTest {
    @get:Rule val compose = createComposeRule()
    private class Q : LeaveQueue {
        val sent = mutableListOf<Pair<String, LeavePayload>>()
        override suspend fun submit(uuid: String, payload: LeavePayload): Boolean { sent += uuid to payload; return true }
    }

    private fun show(language: AppLanguage, st: LeaveApplyState) {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { AronTheme(language) { LeaveApplyScreen(st, onBack = {}) } } }
        compose.waitForIdle()
    }

    @Test fun theFormStartsEmptyAsScreenshot20() {
        val st = LeaveApplyState(Q(), { "2026-10-25" })
        show(AppLanguage.EN, st)
        compose.onNodeWithText("Leave Apply").assertExists()
        compose.onNodeWithText("Leave Type").assertExists(); compose.onNodeWithText("Select leave Type").assertExists()
        compose.onNodeWithText("Date").assertExists(); compose.onNodeWithText("Select Date").assertExists()
        compose.onNodeWithText("Number of days").assertExists(); compose.onNodeWithText("0").assertExists()
        assertTrue(compose.onAllNodesWithText("Reason").fetchSemanticsNodes().isNotEmpty()) // the label and the placeholder
        compose.onNodeWithTag(LeaveApplyTags.SEND).assertExists()
        assertEquals(LeaveForm(), st.form.value) // no type, no date, 0 days: nothing preselected
        org.junit.Assert.assertEquals("accessibility: " + compose.a11yProblems(), emptyList<String>(), compose.a11yProblems())
    }

    @Test fun theTypesComeFromTheBundleAndAChoiceFillsTheField() {
        val types = listOf(LeaveType("casual", "Casual", "নৈমিত্তিক", 1), LeaveType("sick", "Sick", null, 2), LeaveType("earn", "Earn", null, 3), LeaveType("maternity", "Maternity", null, 4))
        val st = LeaveApplyState(Q(), { "2026-10-25" }, types = { types })
        show(AppLanguage.EN, st)
        compose.onNodeWithTag(LeaveApplyTags.TYPE).performClick(); compose.waitForIdle()
        compose.onNodeWithText("Maternity").assertExists() // a type only the bundle knows
        compose.onNodeWithText("Casual").performClick(); compose.waitForIdle()
        assertEquals("casual", st.form.value.type)
        compose.onNodeWithText("Casual").assertExists()
    }

    @Test fun typedDaysReplaceTheZeroAndShowTheLastDay() {
        val st = LeaveApplyState(Q(), { "2026-10-25" })
        show(AppLanguage.EN, st)
        st.edit { it.copy(fromDate = "2026-10-26") }
        compose.onNodeWithTag(LeaveApplyTags.DAYS).performTextReplacement("02"); compose.waitForIdle()
        assertEquals(2, st.form.value.days)
        compose.onNodeWithText("Last day: October 27, 2026", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("October 26, 2026", useUnmergedTree = true).assertExists() // the picked date in the long form (screenshot 22)
    }

    @Test fun applyWithEmptyFieldsShowsEachFieldsMessageAndQueuesNothing() = runBlocking {
        val q = Q(); val st = LeaveApplyState(q, { "2026-10-25" })
        show(AppLanguage.EN, st)
        compose.onNodeWithTag(LeaveApplyTags.SEND).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithTag(LeaveApplyTags.error(LeaveError.TYPE)).assertExists()
        compose.onNodeWithTag(LeaveApplyTags.error(LeaveError.DATE_MISSING)).assertExists()
        compose.onNodeWithText("Days must be between 1 and 365", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Write a reason", useUnmergedTree = true).assertExists()
        assertTrue(q.sent.isEmpty())
    }
}

/** After Apply the flow goes back to the list with a toast, and the new row shows there as not sent yet (D-TSO-MAN-33). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LeaveFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun applyQueuesOnceThenReturnsToTheListWithAToast() {
        val queued = mutableListOf<LeavePayload>()
        val src = FakeSource(fresh = LeaveSource.Cached(emptyList(), "10:00"))
        val queue = LeaveQueue { uuid, p -> queued += p; src.unsent = listOf(LeaveItem(uuid, p.leaveTypeCode, p.fromDate, leaveToDate(p.fromDate, p.days), p.days, p.reason, LeaveStatus.PENDING, sent = false)); true }
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { AronTheme(AppLanguage.EN) { LeaveFlow(src, queue, "tso1001", { "2026-10-25" }, onBack = {}) } } }
        compose.waitForIdle()
        compose.onNodeWithTag(LeaveTags.APPLY).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(LeaveApplyTags.TYPE).performClick(); compose.waitForIdle()
        compose.onNodeWithText("Casual").performClick(); compose.waitForIdle()
        compose.onNodeWithTag(LeaveApplyTags.DAYS).performTextReplacement("1")
        compose.onNodeWithTag(LeaveApplyTags.REASON).performTextReplacement("Family Programme"); compose.waitForIdle()
        // the date from the calendar: it opens on the business date's month (October 2026); pick the 26th, then OK
        compose.onNodeWithTag(LeaveApplyTags.DATE).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNode(hasText("October 26, 2026", substring = true) and hasClickAction()).performClick(); compose.waitForIdle() // the day cell reads its full date
        compose.onNodeWithText("OK").performClick(); compose.waitForIdle()
        compose.onNodeWithText("October 26, 2026", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(LeaveApplyTags.SEND).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithTag(LeaveTags.APPLY).assertExists() // back on the list
        assertEquals(listOf(LeavePayload("casual", "2026-10-26", 1, "Family Programme")), queued)
        assertEquals("Leave application saved. It is sent when the phone is online.", ShadowToast.getTextOfLatestToast())
        compose.onNodeWithText("Not sent yet").assertExists()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LeaveJsonTest {
    private val page = """{"items":[{"leave_uuid":"u1","user_id":7,"leave_type_code":"sick","from_date":"2026-10-05","days":2,"to_date":"2026-10-06","reason":"fever","status":"approved","decided_by_user_id":3,"decided_at":"2026-10-04T10:00:00Z","extra":1},{"leave_uuid":"bad"}],"next_cursor":null}"""

    @Test fun parsesContractPageAndDropsBadRows() {
        val p = LeaveJson.parsePage(page)!!
        assertEquals(1, p.items.size)
        assertEquals(LeaveStatus.APPROVED, p.items[0].status); assertEquals(2, p.items[0].days); assertEquals(null, p.nextCursor)
    }

    @Test fun garbageIsNullNotACrash() { assertEquals(null, LeaveJson.parsePage("<html>captive portal</html>")) }

    @Test fun cacheRoundTripsAndNeverStoresUnsent() {
        val a = item("a", "2026-10-01", status = LeaveStatus.REJECTED); val b = item("b", "2026-10-02", sent = false)
        val back = LeaveJson.parsePage(LeaveJson.toCache(listOf(a, b)))!!
        assertEquals(listOf(a), back.items)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LeaveCheckerTest {
    private fun row(extra: String) = """{"items":[{"leave_uuid":"u1","leave_type_code":"sick","from_date":"2026-10-05","days":2,"to_date":"2026-10-06",$extra}],"next_cursor":null}"""

    @Test fun nullReasonIsDroppedNotShownAsTextNull() {
        val p = LeaveJson.parsePage(row(""""reason":null,"status":"approved"""")) !!
        assertTrue(p.items.none { it.reason == "null" })
    }

    @Test fun unknownStatusRowIsNotShownAsPending() {
        val p = LeaveJson.parsePage(row(""""reason":"x","status":"cancelled"""")) !!
        assertTrue(p.items.isEmpty())
    }

    @Test fun duplicateUuidsAreMergedOnce() {
        val a = item("a", "2026-10-01")
        val merged = mergeLeave(listOf(a, a), listOf(item("b", "2026-10-02", sent = false), item("b", "2026-10-02", sent = false)))
        assertEquals(merged.map { it.leaveUuid }.distinct(), merged.map { it.leaveUuid })
    }

    @Test fun staleCacheIsNotLabelledFreshWhileRefreshRuns() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<LeaveSource.Cached?>()
        val src = object : LeaveSource {
            override suspend fun cached() = LeaveSource.Cached(listOf(item("a", "2026-10-01")), "09:00")
            override suspend fun unsent() = emptyList<LeaveItem>()
            override suspend fun refresh() = gate.await()
        }
        val st = LeaveListState(src)
        val job = kotlinx.coroutines.GlobalScope.launch { st.load() }
        kotlinx.coroutines.delay(300)
        val u = st.ui.value as LeaveListUi.Content
        gate.complete(null); job.join()
        assertTrue("cached list must say it is not yet confirmed", u.refreshing || u.offlineCached)
    }
}

class LeaveApplyStateTest {
    private val today = "2026-10-17"
    private class Q(var fail: Boolean = false) : LeaveQueue {
        val sent = mutableListOf<Pair<String, LeavePayload>>()
        override suspend fun submit(uuid: String, payload: LeavePayload): Boolean { if (fail) error("db"); sent += uuid to payload; return true }
    }

    private fun ok(q: Q) = LeaveApplyState(q, { today }, newUuid = run { var n = 0; { "uuid-${n++}" } })

    @Test fun validation() {
        fun v(f: LeaveForm) = validateLeave(f, today)
        assertTrue(v(LeaveForm("casual", today, 1, "x")).isEmpty())
        assertEquals(listOf(LeaveError.TYPE), v(LeaveForm("holiday", today, 1, "x")))
        assertEquals(listOf(LeaveError.DATE_FORMAT), v(LeaveForm("casual", "17/10/2026", 1, "x")))
        assertEquals(listOf(LeaveError.DATE_PAST), v(LeaveForm("casual", "2026-09-01", 1, "x")))
        assertTrue(v(LeaveForm("sick", "2026-09-16", 1, "x")).isEmpty()) // exactly 31 days back is allowed
        assertEquals(listOf(LeaveError.DATE_FAR), v(LeaveForm("casual", "2027-11-01", 1, "x")))
        assertEquals(listOf(LeaveError.DAYS), v(LeaveForm("casual", today, 0, "x")))
        assertEquals(listOf(LeaveError.DAYS), v(LeaveForm("casual", today, 366, "x")))
        assertEquals(listOf(LeaveError.REASON_EMPTY), v(LeaveForm("casual", today, 1, "   ")))
        assertEquals(listOf(LeaveError.REASON_LONG), v(LeaveForm("casual", today, 1, "ক".repeat(501))))
        assertTrue(v(LeaveForm("casual", today, 1, "ক".repeat(500))).isEmpty())
        // nothing chosen: no type, no date (screenshot 20), and the config window
        assertEquals(listOf(LeaveError.TYPE, LeaveError.DATE_MISSING, LeaveError.DAYS, LeaveError.REASON_EMPTY), v(LeaveForm()))
        assertEquals(listOf(LeaveError.TYPE), validateLeave(LeaveForm("earn", today, 1, "x"), today, listOf("casual", "sick")))
        assertEquals(listOf(LeaveError.DATE_PAST), validateLeave(LeaveForm("casual", "2026-10-07", 1, "x"), today, window = LeaveWindow(pastDays = 7)))
        assertTrue(validateLeave(LeaveForm("casual", "2026-10-10", 1, "x"), today, window = LeaveWindow(pastDays = 7)).isEmpty())
        assertEquals(listOf(LeaveError.DATE_FAR), validateLeave(LeaveForm("casual", "2026-10-25", 1, "x"), today, window = LeaveWindow(aheadDays = 7)))
    }

    @Test fun invalidFormQueuesNothing() = runBlocking {
        val q = Q(); val s = ok(q); s.submit()
        assertTrue(s.result.value is LeaveApplyResult.Invalid); assertTrue(q.sent.isEmpty())
    }

    @Test fun validFormQueuesOnceWithATrimmedReasonAndTheSameUuidOnRetry() = runBlocking {
        val q = Q(fail = true); val s = ok(q)
        s.edit { it.copy(type = "sick", fromDate = today, days = 3, reason = "  fever  ") }
        s.submit(); assertEquals(LeaveApplyResult.Failed, s.result.value)
        q.fail = false
        s.submit(); s.submit() // a double tap after success changes nothing
        assertEquals(1, q.sent.size)
        assertEquals("uuid-0", q.sent[0].first)
        assertEquals(LeavePayload("sick", today, 3, "fever"), q.sent[0].second)
        assertEquals(LeaveApplyResult.Queued, s.result.value)
    }

    @Test fun newFormGetsANewUuid() = runBlocking {
        val q = Q(); val s = ok(q)
        s.edit { it.copy(type = "casual", fromDate = today, days = 1, reason = "a") }; s.submit(); s.startNew()
        s.edit { it.copy(type = "casual", fromDate = today, days = 1, reason = "b") }; s.submit()
        assertEquals(listOf("uuid-0", "uuid-1"), q.sent.map { it.first })
    }

    @Test fun editAfterQueuedIsIgnored() = runBlocking {
        val q = Q(); val s = ok(q); s.edit { it.copy(type = "casual", fromDate = today, days = 1, reason = "a") }; s.submit()
        s.edit { it.copy(reason = "changed") }
        assertEquals("a", s.form.value.reason); assertEquals(LeaveApplyResult.Queued, s.result.value)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LeaveApplyOptionsTest {
    @Test fun daysTextKeepsDigitsDropsLeadingZerosAndEmptyIsZero() {
        assertEquals(0, daysFromText("")); assertEquals(0, daysFromText("0")); assertEquals(1, daysFromText("01"))
        assertEquals(12, daysFromText("1a2")); assertEquals(365, daysFromText("365")); assertEquals(123, daysFromText("12345"))
        // a Bangla keyboard types Bengali digits (checker fix): they count as their Latin digits
        assertEquals(3, daysFromText("৩")); assertEquals(12, daysFromText("০১২")); assertEquals(10, daysFromText("১0"))
    }

    @Test fun leaveTypesComeFromTheCodeListInSortOrder() {
        val lists = """[{"list_key":"task_type","items":[{"code":"x_y","label_en":"X"}]},
          {"list_key":"leave_type","items":[{"code":"sick","label_en":"Sick","label_bn":null,"sort":2},{"code":"casual","label_en":"Casual","label_bn":"নৈমিত্তিক","sort":1},{"code":"Bad Code","label_en":"bad","sort":3},{"code":"earn","label_en":"","sort":4}]}]"""
        val t = LeaveTypes.parse(lists)
        assertEquals(listOf("casual", "sick"), t.map { it.code })
        assertEquals("নৈমিত্তিক", t[0].label(bangla = true)); assertEquals("Sick", t[1].label(bangla = true))
        assertTrue(LeaveTypes.parse(null).isEmpty()); assertTrue(LeaveTypes.parse("{}").isEmpty())
    }

    @Test fun theFormOffersTheBundleTypesAndChecksAgainstThem() = runBlocking {
        val sent = mutableListOf<LeavePayload>()
        val st = LeaveApplyState({ _, p -> sent += p; true }, { "2026-10-25" }, types = { listOf(LeaveType("maternity", "Maternity", null, 1)) }, window = { LeaveWindow(0, 30) })
        assertEquals(LeaveTypes.DEFAULT, st.options.value) // before the bundle answers: the manual's three
        st.load()
        assertEquals(listOf("maternity"), st.options.value.map { it.code }); assertEquals(LeaveWindow(0, 30), st.limits.value)
        st.edit { it.copy(type = "casual", fromDate = "2026-10-25", days = 1, reason = "x") }; st.submit()
        assertEquals(LeaveApplyResult.Invalid(listOf(LeaveError.TYPE)), st.result.value)
        st.edit { it.copy(type = "maternity") }; st.submit()
        assertEquals(listOf(LeavePayload("maternity", "2026-10-25", 1, "x")), sent)
    }

    @Test fun anEmptyOrFailingBundleKeepsTheDefaults() = runBlocking {
        val st = LeaveApplyState({ _, _ -> true }, { "2026-10-25" }, types = { error("db") }, window = { error("db") })
        st.load()
        assertEquals(LeaveTypes.DEFAULT, st.options.value); assertEquals(LeaveWindow(), st.limits.value)
    }
}

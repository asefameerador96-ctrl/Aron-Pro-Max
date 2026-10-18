package com.aktcl.aron.feature.tso.leave

import com.aktcl.aron.feature.tso.a11yProblems
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private fun item(uuid: String, from: String, days: Int = 2, status: LeaveStatus = LeaveStatus.PENDING, sent: Boolean = true, type: String = "casual") =
    LeaveItem(uuid, type, from, leaveToDate(from, days), days, "family", status, sent)

private class FakeSource(
    var cached: LeaveSource.Cached? = null, var unsent: List<LeaveItem> = emptyList(), var fresh: LeaveSource.Cached? = null, var throwOnRefresh: Boolean = false,
) : LeaveSource {
    override suspend fun cached() = cached
    override suspend fun unsent() = unsent
    override suspend fun refresh(): LeaveSource.Cached? = if (throwOnRefresh) error("boom") else fresh
}

class LeaveModelTest {
    @Test fun toDateIsFromPlusDaysMinusOne() {
        assertEquals("2026-10-05", leaveToDate("2026-10-05", 1))
        assertEquals("2026-10-07", leaveToDate("2026-10-05", 3))
        assertEquals("2026-11-01", leaveToDate("2026-10-30", 3)) // month end
    }

    @Test fun newestFirstAndStable() {
        val l = orderLeave(listOf(item("b", "2026-10-01"), item("a", "2026-10-01"), item("c", "2026-10-09")))
        assertEquals(listOf("c", "a", "b"), l.map { it.leaveUuid })
    }

    @Test fun serverRowWinsOverTheSameUnsentUuid() {
        val server = listOf(item("a", "2026-10-01", status = LeaveStatus.APPROVED))
        val local = listOf(item("a", "2026-10-01", sent = false), item("b", "2026-10-03", sent = false))
        val m = mergeLeave(server, local)
        assertEquals(listOf("b", "a"), m.map { it.leaveUuid })
        assertTrue(m.first { it.leaveUuid == "a" }.sent)
        assertEquals(LeaveStatus.APPROVED, m.first { it.leaveUuid == "a" }.status)
    }

    @Test fun unknownStatusIsPendingNeverApproved() = assertEquals(LeaveStatus.PENDING, LeaveStatus.of("whatever"))

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

    private fun show(language: AppLanguage, src: LeaveSource): Context {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) { AronTheme(language) { LeaveListScreen(LeaveListState(src), onBack = {}) } }
        }
        compose.waitForIdle()
        return context
    }

    private val rows = LeaveSource.Cached(
        listOf(item("a", "2026-10-05", days = 3), item("b", "2026-09-01", days = 1, status = LeaveStatus.REJECTED, type = "sick")), "10:42",
    )

    @Test fun englishCardsNameTheApproverRole() {
        show(AppLanguage.EN, FakeSource(fresh = rows, unsent = listOf(item("c", "2026-10-20", status = LeaveStatus.APPROVED, sent = false))))
        compose.onNodeWithTag(LeaveTags.card("a")).assertExists()
        compose.onNodeWithText("DMO approval pending").assertExists()
        compose.onNodeWithTag(LeaveTags.LIST).performScrollToNode(hasTestTag(LeaveTags.card("b")))
        compose.onNodeWithText("Rejected by DMO").assertExists()
        compose.onNodeWithText("2026-10-05 to 2026-10-07  ·  3 Days").assertExists()
        compose.onNodeWithTag(LeaveTags.LIST).performScrollToNode(hasTestTag(LeaveTags.card("c")))
        compose.onNodeWithTag(LeaveTags.unsent("c")).assertExists()
        compose.onNodeWithText("As of 10:42").assertExists()
        org.junit.Assert.assertEquals("accessibility: " + compose.a11yProblems(), emptyList<String>(), compose.a11yProblems())
    }

    @Test fun banglaUsesBengaliDigitsAndBanglaBadge() {
        show(AppLanguage.BN, FakeSource(fresh = rows))
        compose.onNodeWithText("ডিএমও অনুমোদনের অপেক্ষায়").assertExists()
        compose.onNodeWithText("২০২৬-১০-০৫ থেকে ২০২৬-১০-০৭  ·  ৩ দিন").assertExists()
        compose.onNodeWithText("১০:৪২ পর্যন্ত").assertExists()
    }

    @Test fun emptyAndErrorStatesRender() {
        show(AppLanguage.EN, FakeSource(fresh = LeaveSource.Cached(emptyList(), "10:00")))
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
    }

    @Test fun invalidFormQueuesNothing() = runBlocking {
        val q = Q(); val s = ok(q); s.submit()
        assertTrue(s.result.value is LeaveApplyResult.Invalid); assertTrue(q.sent.isEmpty())
    }

    @Test fun validFormQueuesOnceWithATrimmedReasonAndTheSameUuidOnRetry() = runBlocking {
        val q = Q(fail = true); val s = ok(q)
        s.edit { it.copy(type = "sick", days = 3, reason = "  fever  ") }
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
        s.edit { it.copy(reason = "a") }; s.submit(); s.startNew()
        s.edit { it.copy(reason = "b") }; s.submit()
        assertEquals(listOf("uuid-0", "uuid-1"), q.sent.map { it.first })
    }

    @Test fun editAfterQueuedIsIgnored() = runBlocking {
        val q = Q(); val s = ok(q); s.edit { it.copy(reason = "a") }; s.submit()
        s.edit { it.copy(reason = "changed") }
        assertEquals("a", s.form.value.reason); assertEquals(LeaveApplyResult.Queued, s.result.value)
    }
}

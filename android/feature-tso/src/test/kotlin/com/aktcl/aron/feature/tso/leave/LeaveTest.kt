package com.aktcl.aron.feature.tso.leave

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
        compose.onNodeWithText("2026-10-05 to 2026-10-07  ·  3 days").assertExists()
        compose.onNodeWithTag(LeaveTags.LIST).performScrollToNode(hasTestTag(LeaveTags.card("c")))
        compose.onNodeWithTag(LeaveTags.unsent("c")).assertExists()
        compose.onNodeWithText("As of 10:42").assertExists()
    }

    @Test fun banglaUsesBengaliDigitsAndBanglaBadge() {
        show(AppLanguage.BN, FakeSource(fresh = rows))
        compose.onNodeWithText("DMO অনুমোদনের অপেক্ষায়").assertExists()
        compose.onNodeWithText("২০২৬-১০-০৫ থেকে ২০২৬-১০-০৭  ·  ৩ দিন").assertExists()
        compose.onNodeWithText("১০:৪২ পর্যন্ত").assertExists()
    }

    @Test fun emptyAndErrorStatesRender() {
        show(AppLanguage.EN, FakeSource(fresh = LeaveSource.Cached(emptyList(), "10:00")))
        compose.onNodeWithTag(LeaveTags.EMPTY).assertIsDisplayed()
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

package com.aktcl.aron.tso

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.network.AccessTokenSource
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.feature.tso.ChannelMode
import com.aktcl.aron.feature.tso.LoginSubmitTab
import com.aktcl.aron.feature.tso.TsoDashCard
import com.aktcl.aron.feature.tso.TsoFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The TSO Dashboard's reads (build contract s5 A1, D-TSO-MAN-09): every card is a POST to `/v1/dashboards/national/<card>` with the
 * business date as the only filter (no scope ids: the scope is the token's), `/v1/dashboards/summary` is never called, every card
 * has its own cache key, a kept copy serves offline and a refusal never serves a copy. Reads only when asked (no timer in TsoApi or
 * the model).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TsoApiTest {
    @get:Rule val tmp = TemporaryFolder()

    private data class Seen(val method: String, val path: String, val query: String?, val body: String)

    // the card reads run in parallel on OkHttp threads: a plain list can lose a request (a flaky count), so a thread-safe one
    private val seen = java.util.concurrent.CopyOnWriteArrayList<Seen>()
    /** What the fake server answers next: status code by path (default 200). */
    private val status = mutableMapOf<String, Int>()
    private var transportDown = false
    /** Requests held until released: a request whose "path?query" contains the key waits on its latch (a slow 2G read). */
    private val holds = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()

    private fun answer(path: String): String = when {
        path.endsWith("/sales") -> """{"date":"2026-10-29","till_time":true,"as_of":"2026-10-29T08:30:00Z","last_updated_at":null,"working_days":25,"cards":[{"category_code":"cigarette","base_unit":"stick","total_sales":40,"daily_target":"1680.000","sales_so_far":40,"remaining":"1640.000","achievement_pct_bp":238,"target_routes":5,"monthly_target_qty":42000}]}"""
        path.endsWith("/geo-fencing") -> """{"date":"2026-10-29","till_time":true,"as_of":"2026-10-29T08:30:00Z","last_updated_at":null,"total_calls":4,"geo_validated":4,"geo_pct_bp":10000}"""
        path.endsWith("/channel-std") || path.endsWith("/channel-calls") -> """{"date":"2026-10-29","till_time":true,"as_of":"2026-10-29T08:30:00Z","last_updated_at":null,"channels":[{"channel":"Astha","value":40,"share_pct_bp":10000}],"total":40,"unit":"stick","unclassified":0}"""
        path.endsWith("/cpr") && !path.endsWith("brand-cpr") -> """{"date":"2026-10-29","till_time":true,"as_of":"2026-10-29T08:30:00Z","last_updated_at":null,"target_outlets":119,"successful_calls":3,"cpr_pct_bp":252}"""
        path.endsWith("/brand-cpr") -> """{"date":"2026-10-29","till_time":true,"as_of":"2026-10-29T08:30:00Z","last_updated_at":null,"target_outlets":119,"successful_calls":3,"denominator":"successful_calls","brands":[]}"""
        path.endsWith("/login-submit") -> """{"date":"2026-10-29","till_time":true,"as_of":"2026-10-29T08:30:00Z","last_updated_at":null,"target_routes":5,"successful_login":2,"login_pct_bp":4000,"submitted":1,"submit_pct_bp":5000,"submit_of_target_pct_bp":2000}"""
        path.endsWith("/segment-share") -> """{"date":"2026-10-29","till_time":true,"as_of":"2026-10-29T08:30:00Z","last_updated_at":null,"total_value_mtk":1000,"families":[],"segments":[]}"""
        path.endsWith("/login-submit/users") -> """{"date":"2026-10-29","till_time":true,"as_of":"2026-10-29T08:30:00Z","last_updated_at":null,"tab":"not_logged_in","route_count":0,"truncated":false,"items":[]}"""
        else -> "{}"
    }

    private fun client(): AronApiClient {
        val base = OkHttpClient.Builder().addInterceptor { ch ->
            val r = ch.request()
            if (transportDown) throw java.io.IOException("offline")
            val body = r.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            seen += Seen(r.method, r.url.encodedPath, r.url.query, body)
            holds.entries.firstOrNull { (k, _) -> "${r.url.encodedPath}?${r.url.query}".contains(k) }?.value?.await(10, java.util.concurrent.TimeUnit.SECONDS)
            val code = status[r.url.encodedPath] ?: 200
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("x").header("X-Aron-Api", "1")
                .body((if (code == 200) answer(r.url.encodedPath) else """{"type":"about:blank","title":"x","status":$code,"code":"ERR_FORBIDDEN"}""").toResponseBody()).build()
        }.build()
        return AronApiClient(ApiOrigin.parse("https://api.example/"), base, ClientIdentity("1.0+1") { "dev-1" }, object : AccessTokenSource {
            override fun currentAccessToken(grant: Grant) = "t"
            override suspend fun refreshAfterUnauthorized(grant: Grant, rejectedToken: String?, code: com.aktcl.aron.contract.ProblemCode?) = false
        })
    }

    /** A model whose reads run in the test's scope (the day's scope in the app). */
    private fun CoroutineScope.model(api: TsoApi, prefs: android.content.SharedPreferences? = null, now: () -> Long = { 0L }, day: () -> String = { "2026-10-29" }) =
        TsoDashboardModel(api, day, prefs, this, nowMs = now)

    private suspend fun until(what: String, cond: () -> Boolean) = withTimeout(10_000) { while (!cond()) delay(5) }.also { assertTrue(what, cond()) }

    @Test fun everyCardIsANationalPostWithOnlyTheDateAndNeverTheSummary() = runBlocking {
        val m = model(TsoApi(client(), tmp.newFolder("c")))
        m.load()
        assertEquals(
            TsoDashCard.entries.map { "/v1/dashboards/national/${it.path}" }.toSet(),
            seen.map { it.path }.toSet(),
        )
        assertEquals(TsoDashCard.entries.size, seen.size)
        assertTrue(seen.all { it.method == "POST" })
        assertTrue(seen.all { it.body == """{"date":"2026-10-29"}""" })
        // the client never sends scope ids (constraint 4)
        assertFalse(seen.any { it.body.contains("_ids") || it.query?.contains("node_id") == true || it.query?.contains("zone") == true })
        assertFalse(seen.any { it.path.startsWith("/v1/dashboards/summary") || it.path == "/v1/dashboards/login-submit" })
        val s = m.state!!
        assertEquals(40L, s.sales.value!!.rows.single().totalSales)
        assertEquals(42_000L, s.sales.value!!.rows.single().monthlyTarget)
        assertEquals(5L, s.login.value!!.targetRoutes)
        assertFalse(s.anyKept)
    }

    @Test fun theUsersTabIsAQueryParameter() = runBlocking {
        val m = model(TsoApi(client(), tmp.newFolder("c")))
        m.loadUsers(LoginSubmitTab.NOT_UPLOADED)
        val call = seen.single()
        assertEquals("/v1/dashboards/national/login-submit/users", call.path)
        assertEquals("tab=not_uploaded", call.query)
        assertEquals("POST", call.method)
        assertNotNull(m.users[LoginSubmitTab.NOT_UPLOADED]?.value)
    }

    @Test fun offlineServesTheKeptCopyPerCardAndDay() = runBlocking {
        val dir = tmp.newFolder("c")
        model(TsoApi(client(), dir)).load()
        transportDown = true
        val m = model(TsoApi(client(), dir))
        m.load()
        val s = m.state!!
        assertTrue(s.anyKept)
        assertTrue(s.sales.kept && s.sales.value != null)
        assertEquals(TsoFailure.OFFLINE, s.cpr.failure)
        assertEquals(3L, s.cpr.value!!.successfulCalls)
        // another day has no copy
        val other = TsoDashboardModel(TsoApi(client(), dir), { "2026-10-30" }, null, this)
        other.load()
        assertNull(other.state!!.sales.value)
        assertEquals(TsoFailure.OFFLINE, other.state!!.sales.failure)
    }

    @Test fun aRefusalNeverServesAnOldCopy() = runBlocking {
        val dir = tmp.newFolder("c")
        model(TsoApi(client(), dir)).load()
        status["/v1/dashboards/national/brand-cpr"] = 403
        val m = model(TsoApi(client(), dir))
        m.load()
        assertNull(m.state!!.brand.value)
        assertEquals(TsoFailure.REFUSED, m.state!!.brand.failure)
        // and the copy is gone: offline next time there is nothing to show for that card
        status.clear(); transportDown = true
        val again = model(TsoApi(client(), dir))
        again.load()
        assertNull(again.state!!.brand.value)
        assertNotNull(again.state!!.sales.value)
    }

    @Test fun theChannelChoiceIsKeptInTheUsersUiPreferences() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = TsoDashboardModel.prefs(ctx, 42)
        val a = model(TsoApi(client(), tmp.newFolder("c")), prefs)
        assertEquals(ChannelMode.STD, a.channelMode)
        a.toggleChannel()
        assertEquals(ChannelMode.CALLS, model(TsoApi(client(), tmp.newFolder("d")), TsoDashboardModel.prefs(ctx, 42)).channelMode)
        assertEquals(ChannelMode.STD, model(TsoApi(client(), tmp.newFolder("e")), TsoDashboardModel.prefs(ctx, 43)).channelMode)
        // the file logout wipes (SH-17)
        assertTrue(java.io.File(ctx.applicationInfo.dataDir, "shared_prefs/aron_ui_42.xml").let { prefs.edit().commit(); it.exists() })
    }

    @Test fun nothingReadsWithoutBeingAsked() = runBlocking {
        TsoApi(client(), tmp.newFolder("c"))
        model(TsoApi(client(), tmp.newFolder("d")))
        assertTrue("constructing the api or the model reads nothing", seen.isEmpty())
    }

    // ---- tso-manual A1 fix round: one read per key, joined; a screen or tab that leaves never strands the next read --------------

    @Test fun aTabSwitchDuringASlowReadStillReadsTheNewTabAndTheFirstAnswerLands() = runBlocking {
        val slow = java.util.concurrent.CountDownLatch(1)
        holds["tab=not_logged_in"] = slow
        try {
            val m = model(TsoApi(client(), tmp.newFolder("c")))
            val firstTab = launch { m.loadUsers(LoginSubmitTab.NOT_LOGGED_IN) } // LaunchedEffect(usersTab) of Not Logged In
            until("the first tab's read reached the server") { seen.any { it.query == "tab=not_logged_in" } }
            assertTrue(m.usersRefreshing)
            // the tap on Not Uploaded: the old effect is cancelled, the new one reads its tab
            firstTab.cancel()
            m.usersTab = LoginSubmitTab.NOT_UPLOADED
            m.loadUsers(LoginSubmitTab.NOT_UPLOADED)
            assertNotNull("the new tab has its rows", m.users[LoginSubmitTab.NOT_UPLOADED]?.value)
            assertFalse("and no spinner", m.usersRefreshing)
            // the slow answer was not thrown away: it lands when the server answers
            slow.countDown()
            until("the first tab's answer lands") { m.users[LoginSubmitTab.NOT_LOGGED_IN] != null }
            m.usersTab = LoginSubmitTab.NOT_LOGGED_IN
            assertFalse(m.usersRefreshing)
            assertEquals(1, seen.count { it.query == "tab=not_logged_in" })
        } finally {
            slow.countDown()
        }
    }

    @Test fun aSecondPullWhileTheCardsReadWaitsForThatReadAndNeverDoublesIt() = runBlocking {
        val slow = java.util.concurrent.CountDownLatch(1)
        holds["/national/sales?"] = slow
        try {
            val m = model(TsoApi(client(), tmp.newFolder("c")))
            val first = launch { m.load() }
            until("the sales read reached the server") { seen.any { it.path.endsWith("/sales") } }
            assertTrue(m.refreshing)
            val second = async { m.load(); m.state } // the second pull
            yield()
            assertTrue("still reading: the second pull waits", second.isActive)
            slow.countDown()
            first.join()
            assertNotNull("the second pull returns with the figures", second.await()?.sales?.value)
            assertFalse(m.refreshing)
            assertEquals("one read of each card", TsoDashCard.entries.size, seen.size)
        } finally {
            slow.countDown()
        }
    }

    @Test fun theDashboardShownAgainWithinAMinuteKeepsItsReadAndAPullAlwaysReads() = runBlocking {
        var now = 1_000_000L
        var day = "2026-10-29"
        val m = model(TsoApi(client(), tmp.newFolder("c")), now = { now }, day = { day })
        val cards = TsoDashCard.entries.size
        m.show()
        assertEquals(cards, seen.size)
        now += 30_000L
        m.show() // back from Login & Bikroy Joma Status within the minute: no read
        assertEquals(cards, seen.size)
        now += 31_000L
        m.show() // a minute later: read again
        assertEquals(2 * cards, seen.size)
        m.load() // pull-to-refresh always reads
        assertEquals(3 * cards, seen.size)
        day = "2026-10-30"
        m.show() // a new business day reads
        assertEquals(4 * cards, seen.size)
        assertTrue(seen.takeLast(cards).all { it.body == """{"date":"2026-10-30"}""" })
    }
}

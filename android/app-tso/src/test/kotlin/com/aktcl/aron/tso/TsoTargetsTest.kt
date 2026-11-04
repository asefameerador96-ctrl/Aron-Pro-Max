package com.aktcl.aron.tso

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.network.AccessTokenSource
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.feature.tso.TsoFailure
import com.aktcl.aron.feature.tso.TsoNav
import com.aktcl.aron.feature.tso.TsoScreen
import com.aktcl.aron.feature.tso.targets.TargetNodeKey
import com.aktcl.aron.feature.tso.targets.TargetTab
import com.aktcl.aron.rules.Bands
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Target Status reads (build contract s5 A1, D-TSO-MAN-39, 40): Status is `GET /v1/dashboards/targets?month&product_level=category&
 * children=zone`, Details `level&node_id&product_level=variant` (the top node without them), each answer kept per month and node, a
 * refusal never serves a copy, no read without a screen or a pull (no timer), and the client never sends a scope list.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TsoTargetsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String?>>()
    private var transportDown = false
    private var status = 200
    /** Requests held until released: a request whose query contains the key waits on its latch (a slow 2G read). */
    private val holds = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()

    private val answer = """{"month":"2026-10","as_of":"2026-10-25T09:35:00Z","day":"2026-10-25","tilldate_basis":"calendar_days","last_updated_at":null,
        "node":{"type":"territory","id":3,"name":"Apsis"},"elapsed_days":25,"period_days":31,
        "rows":[{"product_level":"category","product_id":1,"name":"Cigarette","unit":"stick","target_qty":"40999.000","achieved_qty":"1030.000","tilldate_target_qty":"33064.000","remaining_qty":"39969.000","category_code":"cigarette","target_qty_base":40999,"achieved_qty_base":1030,"tilldate_target_qty_base":33064,"remaining_qty_base":39969,"remaining_tilldate_qty_base":32034,"achievement_pct_bp":251,"tilldate_achievement_pct_bp":312}],
        "children":[{"node":{"type":"zone","id":9,"name":"Banani - Test"},"elapsed_days":25,"period_days":31,"rows":[]}]}"""

    private fun client(): AronApiClient {
        val base = OkHttpClient.Builder().addInterceptor { ch ->
            val r = ch.request()
            if (transportDown) throw java.io.IOException("offline")
            seen += r.url.encodedPath to r.url.query
            holds.entries.firstOrNull { (k, _) -> r.url.query.orEmpty().contains(k) }?.value?.await(10, java.util.concurrent.TimeUnit.SECONDS)
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(status).message("x").header("X-Aron-Api", "1")
                .body((if (status == 200) answer else """{"type":"about:blank","title":"x","status":$status,"code":"ERR_OUT_OF_SCOPE"}""").toResponseBody()).build()
        }.build()
        return AronApiClient(ApiOrigin.parse("https://api.example/"), base, ClientIdentity("1.0+1") { "dev-1" }, object : AccessTokenSource {
            override fun currentAccessToken(grant: Grant) = "t"
            override suspend fun refreshAfterUnauthorized(grant: Grant, rejectedToken: String?, code: com.aktcl.aron.contract.ProblemCode?) = false
        })
    }

    private var now = 1_000_000L
    private var day = "2026-10-25"
    /** A model whose reads run in the test's scope (the day's scope in the app). */
    private fun CoroutineScope.model(dir: java.io.File = tmp.newFolder("c"), bands: Bands = Bands.DEFAULT) = TsoTargetsModel(TsoApi(client(), dir), { day }, { now }, this, { bands })

    @Test fun statusIsOneGetWithTheMonthCategoriesAndZonesOnly() = runBlocking {
        val m = model(bands = Bands(30, 70))
        assertTrue("no read without a call (no timer)", seen.isEmpty())
        m.showStatus()
        assertEquals(listOf("/v1/dashboards/targets" to "month=2026-10&product_level=category&children=zone"), seen)
        assertEquals("Apsis", m.status!!.value!!.node.ref.name)
        assertEquals(listOf(3L, 9L), m.status!!.value!!.cards.map { it.ref.id })
        assertEquals(Bands(30, 70), m.bands)
    }

    @Test fun detailsReadTheTopNodeWithoutLevelAndAZoneByItsId() = runBlocking {
        val m = model()
        m.showDetails(TargetNodeKey.Top)
        m.showDetails(TargetNodeKey("zone", 9))
        assertEquals(
            listOf(
                "/v1/dashboards/targets" to "month=2026-10&product_level=variant",
                "/v1/dashboards/targets" to "month=2026-10&level=zone&node_id=9&product_level=variant",
            ),
            seen,
        )
        assertEquals("Apsis", m.titleOf(TargetNodeKey.Top))
    }

    @Test fun aScreenShownAgainWithinAMinuteKeepsItsReadAndANewMonthReadsAgain() = runBlocking {
        val m = model()
        m.showStatus(); m.showStatus()
        assertEquals(1, seen.size)
        now += 61_000L
        m.showStatus()
        assertEquals(2, seen.size)
        m.loadStatus() // pull-to-refresh always reads
        assertEquals(3, seen.size)
        day = "2026-11-01"
        m.showStatus()
        assertEquals("month=2026-11&product_level=category&children=zone", seen.last().second)
    }

    @Test fun eachAnswerIsKeptPerMonthAndNodeAndARefusalDropsIt() = runBlocking {
        val dir = tmp.newFolder("c")
        model(dir).showStatus()
        model(dir).showDetails(TargetNodeKey("zone", 9))
        transportDown = true
        val offline = model(dir)
        offline.showStatus(); offline.showDetails(TargetNodeKey("zone", 9)); offline.showDetails(TargetNodeKey.Top)
        assertTrue(offline.status!!.kept)
        assertEquals("Apsis", offline.status!!.value!!.node.ref.name)
        assertTrue(offline.details["zone:9"]!!.kept)
        assertNull("no copy of the top node's details", offline.details["top"]!!.value)
        assertEquals(TsoFailure.OFFLINE, offline.details["top"]!!.failure)
        // another month has no copy
        day = "2026-11-01"
        val nextMonth = model(dir); nextMonth.showStatus()
        assertNull(nextMonth.status!!.value)
        // a refusal drops the copy
        day = "2026-10-25"; transportDown = false; status = 403
        val refused = model(dir); refused.showStatus()
        assertEquals(TsoFailure.REFUSED, refused.status!!.failure)
        status = 200; transportDown = true
        val after = model(dir); after.showStatus()
        assertNull("the refused answer's copy is gone", after.status!!.value)
    }

    @Test fun detailsOpenWithNoFilterOnTheSharedTab() = runBlocking {
        val m = model()
        val opened = mutableListOf<Pair<TsoScreen, String?>>()
        val nav = object : TsoNav { override fun open(screen: TsoScreen, arg: String?) { opened += screen to arg }; override fun back() {}; override fun home() {} }
        m.tab = TargetTab.TILL_DATE
        m.filter = "lighter"
        m.openDetails(TargetNodeKey("zone", 9), nav)
        assertEquals(listOf(TsoScreen.TARGET_DETAILS to "zone:9"), opened)
        assertNull(m.filter)
        assertEquals(TargetTab.TILL_DATE, m.tab)
    }

    // ---- tso-manual A1 fix round: one read per node, joined; opening another node never strands its spinner ----------------------

    @Test fun openingAnotherNodeWhileOneStillReadsReadsItAndEndsItsSpinner() = runBlocking {
        val slow = java.util.concurrent.CountDownLatch(1)
        holds["node_id=9"] = slow
        try {
            val m = model()
            val zone = TargetNodeKey("zone", 9)
            val zoneScreen = launch { m.showDetails(zone) } // Details of zone 9 on 2G
            withTimeout(10_000) { while (seen.none { it.second.orEmpty().contains("node_id=9") }) delay(5) }
            assertTrue(m.detailsRefreshing(zone))
            // back, then "Details ->" of the territory: zone 9's effect is cancelled, the top node reads
            zoneScreen.cancel()
            m.showDetails(TargetNodeKey.Top)
            assertEquals("Apsis", m.details["top"]?.value?.node?.ref?.name)
            assertTrue("the top node's spinner ends", !m.detailsRefreshing(TargetNodeKey.Top))
            // zone 9's answer still lands
            slow.countDown()
            withTimeout(10_000) { while (m.details["zone:9"] == null) delay(5) }
            assertTrue(!m.detailsRefreshing(zone))
            assertEquals(1, seen.count { it.second.orEmpty().contains("node_id=9") })
        } finally {
            slow.countDown()
        }
    }

    @Test fun aSecondPullWhileStatusReadsWaitsForThatRead() = runBlocking {
        val slow = java.util.concurrent.CountDownLatch(1)
        holds["children=zone"] = slow
        try {
            val m = model()
            val first = launch { m.loadStatus() }
            withTimeout(10_000) { while (seen.isEmpty()) delay(5) }
            assertTrue(m.statusRefreshing)
            val second = async { m.loadStatus(); m.status }
            yield()
            assertTrue("the second pull waits", second.isActive)
            slow.countDown()
            first.join()
            assertEquals("Apsis", second.await()?.value?.node?.ref?.name)
            assertEquals("one read", 1, seen.size)
            assertTrue(!m.statusRefreshing)
        } finally {
            slow.countDown()
        }
    }
}

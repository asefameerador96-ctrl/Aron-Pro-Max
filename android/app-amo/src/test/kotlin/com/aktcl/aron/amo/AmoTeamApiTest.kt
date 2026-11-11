package com.aktcl.aron.amo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.network.AccessTokenSource
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import com.aktcl.aron.core.network.Grant
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The AMO team reads of lane A3 (constraint 4, D-AMO-MAN-09, 11): every read is a GET whose only scope words are ids the server itself
 * listed (one route, one SR), never a list of zones; the zone reads carry no id at all (the token's reach decides); a refusal never
 * serves an old copy; offline serves the kept copy of the day.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AmoTeamApiTest {
    private data class Seen(val method: String, val path: String, val query: String?)
    private val seen = java.util.concurrent.CopyOnWriteArrayList<Seen>()
    private var status = 200
    private var down = false

    private fun api(userId: Long = 42): AmoApi {
        val http = OkHttpClient.Builder().addInterceptor { ch ->
            val r = ch.request()
            if (down) throw java.io.IOException("offline")
            seen += Seen(r.method, r.url.encodedPath, r.url.query)
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(status).message("x").header("X-Aron-Api", "1")
                .body((if (status == 200) """{"items":[],"kpis":{}}""" else """{"type":"about:blank","title":"x","status":$status,"code":"ERR_FORBIDDEN"}""").toResponseBody()).build()
        }.build()
        val client = AronApiClient(ApiOrigin.parse("https://api.example/"), http, ClientIdentity("1.0+1") { "dev-1" }, object : AccessTokenSource {
            override fun currentAccessToken(grant: Grant) = "t"
            override suspend fun refreshAfterUnauthorized(grant: Grant, rejectedToken: String?, code: com.aktcl.aron.contract.ProblemCode?) = false
        })
        return AmoApi(client, ApplicationProvider.getApplicationContext<Context>(), userId)
    }

    @Test fun zoneReadsCarryNoScopeIdAndRouteReadsOneIdTheServerListed() = runBlocking {
        val a = api()
        a.teamMembers("2026-11-06"); a.brandTargets("2026-11", null); a.brandTargets("2026-11", 11L); a.teamJourney(7, 11, "2026-11-06"); a.liveDashboard("2026-11-06", null); a.liveDashboardRoute("2026-11-06", 11L)
        assertTrue(seen.all { it.method == "GET" })
        val q = seen.associate { (it.path + "?" + it.query) to it }
        assertEquals("business_date=2026-11-06", seen.first { it.path == "/v1/team/members" }.query)
        assertEquals("month=2026-11&product_level=brand", seen.first { it.path == "/v1/dashboards/targets" }.query)
        assertTrue(q.keys.contains("/v1/dashboards/targets?month=2026-11&product_level=brand&level=route&node_id=11"))
        assertTrue(q.keys.contains("/v1/team/journey?user_id=7&route_id=11&business_date=2026-11-06"))
        assertEquals("business_date=2026-11-06", seen.first { it.path == "/v1/dashboards/login-submit" && it.query?.contains("level") != true }.query)
        // never a list of zones or scope ids
        assertFalse(seen.any { it.query.orEmpty().contains("zone_ids") || it.query.orEmpty().contains("scope") || it.query.orEmpty().contains(",") })
    }

    @Test fun refusalNeverServesAnOldCopyAndOfflineServesTheKeptOne() = runBlocking {
        val a = api()
        assertTrue(a.teamMembers("2026-11-06") is AmoFetch.Fresh)
        down = true
        assertTrue(a.teamMembers("2026-11-06") is AmoFetch.Cached)
        assertTrue(a.teamMembers("2026-11-07") is AmoFetch.Unavailable)
        down = false; status = 403
        assertTrue(a.teamMembers("2026-11-06") is AmoFetch.Gone)
        down = true
        assertTrue("a refusal removed the copy", a.teamMembers("2026-11-06") is AmoFetch.Unavailable)
    }

    /** Contract 1.11.2: a route read the server refuses is a refusal in both of its forms (403 outside the reach, 400 without node_id / level not offered), never an offline copy; the zone read is unchanged. */
    @Test fun aRouteTargetReadRefusedWith403Or400IsARefusalAndTheZoneReadKeepsItsMeaning() = runBlocking {
        val a = api()
        assertTrue(a.brandTargets("2026-11", 11L) is AmoFetch.Fresh)
        assertTrue(a.brandTargets("2026-11", null) is AmoFetch.Fresh)
        for (code in listOf(403, 400)) {
            status = code
            assertTrue("route $code is a refusal", a.brandTargets("2026-11", 11L) is AmoFetch.Gone)
            down = true
            assertTrue("route $code left no old copy", a.brandTargets("2026-11", 11L) is AmoFetch.Unavailable)
            down = false; status = 200
            assertTrue(a.brandTargets("2026-11", 11L) is AmoFetch.Fresh)
        }
        status = 400
        assertFalse("a 400 on the zone read stays what it was (unreadable, not a refusal)", a.brandTargets("2026-11", null) is AmoFetch.Gone)
    }
}

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

    /** Contract 1.11.2, lead ruling 2026-11-11 06:00 UTC: a route read tells its two refusals apart (403 outside the reach = Gone for that route; 400 = the server has no route level), never an old copy. */
    @Test fun aRouteTargetReadTellsA403FromA400AndKeepsNoOldCopy() = runBlocking {
        val a = api()
        assertTrue(a.routeTargets("2026-11", 11L).fetch is AmoFetch.Fresh)
        assertTrue(seen.any { it.path == "/v1/dashboards/targets" && it.query == "month=2026-11&product_level=brand&level=route&node_id=11" })
        status = 403
        val r403 = a.routeTargets("2026-11", 11L)
        assertTrue(r403.fetch is AmoFetch.Gone); assertFalse(r403.notOffered)
        down = true; assertTrue("403 left no old copy", a.routeTargets("2026-11", 11L).fetch is AmoFetch.Unavailable); down = false
        status = 200; assertTrue(a.routeTargets("2026-11", 11L).fetch is AmoFetch.Fresh)
        status = 400
        val r400 = a.routeTargets("2026-11", 11L)
        assertTrue(r400.notOffered); assertFalse("a 400 is not an access refusal", r400.fetch is AmoFetch.Gone)
        down = true; assertTrue("400 left no old copy", a.routeTargets("2026-11", 11L).fetch is AmoFetch.Unavailable); down = false
        // offline is neither: it is just unavailable, and the zone read keeps its meaning
        status = 200; assertTrue(a.routeTargets("2026-11", 11L).fetch is AmoFetch.Fresh)
        down = true; val off = a.routeTargets("2026-11", 11L); assertFalse(off.notOffered); assertTrue(off.fetch is AmoFetch.Cached); down = false
        status = 400; assertFalse(a.brandTargets("2026-11", null) is AmoFetch.Gone)
    }

    /** D-AMO-CALLS-31 (3): only 403 is "not yours"; 404 and 501 (no route level) are route detail unavailable, like 400, and leave no old copy. */
    @Test fun aRoute404Or501IsRouteDetailUnavailableNotOutOfReach() = runBlocking {
        val a = api()
        assertTrue(a.routeTargets("2026-11", 11L).fetch is AmoFetch.Fresh)
        for (code in listOf(404, 501)) {
            status = code
            val r = a.routeTargets("2026-11", 11L)
            assertTrue("$code is not offered", r.notOffered); assertFalse("$code is not an out-of-reach refusal", r.fetch is AmoFetch.Gone)
            down = true; assertTrue("$code left no old copy", a.routeTargets("2026-11", 11L).fetch is AmoFetch.Unavailable); down = false
            status = 200; assertTrue(a.routeTargets("2026-11", 11L).fetch is AmoFetch.Fresh)
        }
        status = 403
        val r403 = a.routeTargets("2026-11", 11L)
        assertTrue(r403.fetch is AmoFetch.Gone); assertFalse(r403.notOffered)
    }

    /** D-AMO-CALLS-32/33: forgetting drops exactly the named route copies; other routes and other months stay. */
    @Test fun forgettingRouteTargetsDropsTheirKeptCopiesAndOnlyThose() = runBlocking {
        val a = api()
        for (id in listOf(11L, 12L, 13L)) assertTrue(a.routeTargets("2026-11", id).fetch is AmoFetch.Fresh)
        assertTrue(a.routeTargets("2026-10", 11L).fetch is AmoFetch.Fresh)
        a.forgetRouteTargets("2026-11", listOf(11L, 12L))
        down = true
        assertTrue(a.routeTargets("2026-11", 11L).fetch is AmoFetch.Unavailable); assertTrue(a.routeTargets("2026-11", 12L).fetch is AmoFetch.Unavailable)
        assertTrue("another route's copy survives", a.routeTargets("2026-11", 13L).fetch is AmoFetch.Cached)
        assertTrue("another month's copy survives", a.routeTargets("2026-10", 11L).fetch is AmoFetch.Cached)
        down = false
    }

    /** D-AMO-CALLS-33 (6): the function the screens call purges EVERY member route (25 here, only 20 are ever asked) when the zone is refused, and nothing else. */
    @Test fun aRefusedZonePurgesTheKeptCopiesOfEveryMemberRouteThroughTheScreensEntryPoint() = runBlocking {
        val a = api()
        val members = (1L..25L).toList()
        for (id in members) assertTrue(a.routeTargets("2026-11", id).fetch is AmoFetch.Fresh)
        assertTrue(a.routeTargets("2026-11", 99L).fetch is AmoFetch.Fresh) // not a member of this AMO's list
        status = 403
        val published = mutableListOf<TargetNodes>()
        loadTargetNodesFromApi(a, "2026-11", members, { null }) { published += it }
        assertTrue("the zone refusal is published", published.last().refused)
        status = 200; down = true
        for (id in members) assertTrue("member route $id has no old copy", a.routeTargets("2026-11", id).fetch is AmoFetch.Unavailable)
        assertTrue("a route outside the list keeps its copy", a.routeTargets("2026-11", 99L).fetch is AmoFetch.Cached)
        down = false
    }
}

package com.aktcl.aron.tso

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.network.AccessTokenSource
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.core.sync.SyncScheduler
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * tso-manual X4b fix round: every online read of [TsoReadModels] asks its contract path with the query as the URL's query. The
 * reads used to pass `/v1/x?a=b` as the client's contract path, which OkHttp keeps as an encoded path, writing `?` as `%3F`: the
 * server was asked `/v1/routes%3Flimit=500` (no such route), so the TSO screens' online reads never reached their endpoint and
 * fell back to the kept copy or the offline line. Found by TsoAssignTaskOfflineTest's online case in this round.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class TsoReadModelsUrlTest {
    private val user = 7L
    private lateinit var databases: UserDatabases
    private val seen = mutableListOf<Pair<String, String?>>()

    /** The answer of every read: an empty first page, or a page with a cursor while [pages] says more follow. */
    private var pages = 1

    private val api: AronApiClient = run {
        val base = OkHttpClient.Builder().addInterceptor { ch ->
            val r = ch.request()
            seen += r.url.encodedPath to r.url.encodedQuery
            val next = if (seen.size < pages) "\"c ${seen.size}\"" else "null"
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("ok").header("X-Aron-Api", "1")
                .body("""{"items":[],"next_cursor":$next}""".toResponseBody()).build()
        }.build()
        AronApiClient(ApiOrigin.parse("https://api.example/"), base, ClientIdentity("1.0+1") { "dev-1" }, object : AccessTokenSource {
            override fun currentAccessToken(grant: Grant) = "t"
            override suspend fun refreshAfterUnauthorized(grant: Grant, rejectedToken: String?, code: com.aktcl.aron.contract.ProblemCode?) = false
        })
    }

    private val env = object : TsoEnv {
        override val client: AronApiClient get() = api
        override fun nowMs() = 1_760_680_000_000L
        override fun elapsedRealtimeMs() = 5_000L
        override fun bootCount() = 3
        override fun clockOffsetMs(): Long? = 0L
        override fun businessDate() = "2026-10-17"
    }
    private val scheduler = object : SyncScheduler { override fun requestSync(userId: Long, trigger: SyncTrigger) = Unit }

    @Before fun open() { databases = UserDatabases(ApplicationProvider.getApplicationContext<Application>()) { null } }
    @After fun close() { databases.close(user) }

    private fun models() = TsoReadModels(env, databases, TsoOutbox(env, databases, scheduler) { false })

    @Test fun everyReadAsksItsPathWithItsQuery() = runBlocking {
        val m = models()
        val plan = m.plan(user)
        m.leave(user).refresh()
        m.feedback(user).refresh()
        m.zoneNames(user, bangla = false)
        plan.plans("2026-10-17")
        plan.outlets(9) // no bundle outlets on this phone: the online read
        m.periphery(user, plan).nearby(23.79, 90.4, 500, 41)
        m.periphery(user, plan).team(41, "2026-10-17")
        assertEquals(
            listOf(
                "/v1/leave" to "limit=100",
                "/v1/feedback" to "limit=100",
                "/v1/admin/geo/zone" to "limit=500",
                "/v1/visit-plans" to "from=2026-10-17&to=2026-10-17&planner_user_id=7",
                "/v1/outlets" to "route_id=9&limit=500",
                "/v1/outlets/nearby" to "lat=23.79&lng=90.4&radius_m=500&zone_id=41",
                "/v1/team/locations" to "business_date=2026-10-17&zone_id=41",
            ),
            seen,
        )
    }

    @Test fun aPagedReadSendsTheCursorInTheQuery() = runBlocking {
        pages = 2
        models().plan(user).routes()
        assertEquals(listOf("/v1/routes" to "limit=500", "/v1/routes" to "limit=500&cursor=c+1"), seen)
    }
}

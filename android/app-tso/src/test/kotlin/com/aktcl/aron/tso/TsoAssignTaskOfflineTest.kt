package com.aktcl.aron.tso

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.RecordType
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.database.entity.BundleSectionEntity
import com.aktcl.aron.core.database.entity.RouteEntity
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.core.network.AccessTokenSource
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.core.sync.SyncScheduler
import com.aktcl.aron.feature.tso.mycall.AssignTaskState
import com.aktcl.aron.feature.tso.mycall.PlanJson
import com.aktcl.aron.feature.tso.mycall.PlanRoute
import com.aktcl.aron.feature.tso.mycall.RouteSr
import com.aktcl.aron.feature.tso.mycall.TaskResult
import com.aktcl.aron.feature.tso.mycall.TaskType
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * tso-manual X4b (R-W-12 (9)): Assign Task works offline. The TSO bundle's route entries name the route's SR (`sr_user_id`,
 * `sr_name`, R-W-06 (4)); the sync engine keeps them in the route's raw section (`route.<id>`). With no network at all, the
 * plan source reads the routes and their SR from the stored bundle (no request), Assign Task names the SR, and the task record
 * queued on the phone carries that SR as its assignee. A bundle without the members (an older server) still tries the online
 * read first, as before. Fix round (D-X4b-08): an online read younger than 30 minutes wins over the bundle (a mid-day SR
 * change shows); a route whose snapshot lacks the members is not carried, so it is named by the online read, or offline by the
 * last read kept, and is vacant only when neither names it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class TsoAssignTaskOfflineTest {
    private val user = 7L
    private val today = "2026-10-17"
    private lateinit var databases: UserDatabases
    private var networkCalls = 0
    private val now = 1_760_680_000_000L

    /** The `GET /v1/routes` answer while the phone is online; null: offline (no network at all). */
    private var onlineRoutes: String? = null

    private val env = object : TsoEnv {
        override val client: AronApiClient get() { networkCalls++; return onlineRoutes?.let { client(it) } ?: error("offline: no network on this phone") }
        override fun nowMs() = now
        override fun elapsedRealtimeMs() = 5_000L
        override fun bootCount() = 3
        override fun clockOffsetMs(): Long? = 0L
        override fun businessDate() = today
    }
    private val scheduler = object : SyncScheduler { override fun requestSync(userId: Long, trigger: SyncTrigger) = Unit }

    @Before fun open() { databases = UserDatabases(ApplicationProvider.getApplicationContext<Application>()) { null } }
    @After fun close() { databases.close(user) }

    private fun models() = TsoReadModels(env, databases, TsoOutbox(env, databases, scheduler) { false })

    private val paths = mutableListOf<String>()

    private fun client(routes: String): AronApiClient {
        val base = OkHttpClient.Builder().addInterceptor { ch ->
            val r = ch.request()
            paths += r.url.encodedPath + (r.url.encodedQuery?.let { "?$it" } ?: "") // the query in the URL's query, never %3F in the path
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("ok").header("X-Aron-Api", "1").body(routes.toResponseBody()).build()
        }.build()
        return AronApiClient(ApiOrigin.parse("https://api.example/"), base, ClientIdentity("1.0+1") { "dev-1" }, object : AccessTokenSource {
            override fun currentAccessToken(grant: Grant) = "t"
            override suspend fun refreshAfterUnauthorized(grant: Grant, rejectedToken: String?, code: com.aktcl.aron.contract.ProblemCode?) = false
        })
    }

    private fun planRoute(id: Long, sr: RouteSr?) = PlanRoute(id, "R$id", "Route $id", 41, null, sr)

    /** An online read kept on the phone [ageMs] ago (as TsoReadModels keeps it: body, "as of" and the time it was read). */
    private fun kept(ageMs: Long, vararg routes: PlanRoute) = runBlocking {
        val dao = databases.of(user).referenceDao()
        dao.putMeta(SyncMetaEntity("tso.cache.routes", PlanJson.routesToCache(routes.toList())))
        dao.putMeta(SyncMetaEntity("tso.cache.routes.asof", "10:00"))
        dao.putMeta(SyncMetaEntity("tso.cache.routes.ts", (now - ageMs).toString()))
    }

    private val karim = RouteSr(77, "Karim Mia")
    private val minute = 60_000L

    private fun route(id: Long) = RouteEntity(
        routeId = id, code = "R$id", name = "Route $id", displayLabel = null, zoneId = 41, kind = "sr", visitKind = null, visitDaysMask = 127,
        sequenceNo = id.toInt(), status = "active", assignmentKind = "primary", actingForUserId = null, plannedToday = true, targetOutlets = 3,
        routeSnapshotVersion = 1, businessDate = today, bundleVersion = "$today:1",
    )

    /** The stored bundle as the sync engine leaves it: the route rows, and each snapshot's other members raw in `route.<id>`. */
    private fun seed(sections: Map<Long, String>) = runBlocking {
        val dao = databases.of(user).referenceDao()
        dao.insertRoutes(sections.keys.map { route(it) })
        dao.insertSections(sections.map { (id, json) -> BundleSectionEntity("route.$id", json) })
    }

    private val types = listOf(TaskType("irregular_visit", null, "Irregular Visit", 1))

    @Test fun assignTaskNamesTheRouteSrFromTheBundleWithNoNetwork() = runBlocking {
        seed(
            mapOf(
                1L to """{"route_id":1,"assignment_kind":"primary","planned_today":true,"sr_user_id":42,"sr_name":"Rahim Uddin"}""",
                2L to """{"route_id":2,"assignment_kind":"primary","planned_today":true,"sr_user_id":null,"sr_name":null}""",
            ),
        )
        val routes = checkNotNull(models().plan(user).routes())
        assertEquals("no online read: the bundle names the SR", 0, networkCalls)
        assertEquals(listOf(1L, 2L), routes.map { it.id })
        val named = routes.first { it.id == 1L }
        assertEquals(RouteSr(42, "Rahim Uddin"), named.sr)

        val task = AssignTaskState(types, named, 501, models().taskQueue(user), { today })
        assertEquals("Rahim Uddin", task.srName)
        assertTrue(task.ui.value.result is TaskResult.Editing)
        task.pickType("irregular_visit"); task.submit()
        assertEquals(TaskResult.Assigned, task.ui.value.result)
        val row = databases.of(user).outboxDao().nextPending(100).single { it.recordType == RecordType.TASK.wire }
        val payload = JSONObject(row.payloadJson).getJSONObject("payload")
        assertEquals(42L, payload.getLong("assignee_user_id")); assertEquals(501L, payload.getLong("outlet_id"))
        assertEquals(0, networkCalls)

        // a vacant route on the bundle date: "SR Not Set", and nothing can be queued for it
        val vacant = routes.first { it.id == 2L }
        assertNull(vacant.sr)
        val none = AssignTaskState(types, vacant, 502, models().taskQueue(user), { today })
        assertEquals(TaskResult.NoSr, none.ui.value.result)
        none.submit()
        assertEquals(1, databases.of(user).outboxDao().nextPending(100).count { it.recordType == RecordType.TASK.wire })
    }

    @Test fun anOlderBundleWithoutTheMembersTriesTheOnlineReadThenKeepsTheBundleRoutes() = runBlocking {
        seed(mapOf(1L to """{"route_id":1,"assignment_kind":"primary","planned_today":true}"""))
        val routes = checkNotNull(models().plan(user).routes())
        assertTrue("the online routes read is the fallback when the bundle names no SR", networkCalls > 0)
        assertEquals(listOf(1L), routes.map { it.id })
        assertNull(routes.single().sr)
    }

    // ---- fix round (D-X4b-08): which source names the SR

    @Test fun anOnlineReadYoungerThan30MinutesWinsOverTheBundle() = runBlocking {
        seed(mapOf(1L to """{"route_id":1,"sr_user_id":42,"sr_name":"Rahim Uddin"}"""))
        kept(5 * minute, planRoute(1, karim)) // read online 5 minutes ago: the SR was changed after the morning's bundle
        val routes = checkNotNull(models().plan(user).routes())
        assertEquals(0, networkCalls)
        assertEquals(karim, routes.single().sr)
        // Assign Task queues the task to the SR the newer read names
        val task = AssignTaskState(types, routes.single(), 501, models().taskQueue(user), { today })
        task.pickType("irregular_visit"); task.submit()
        val row = databases.of(user).outboxDao().nextPending(100).single { it.recordType == RecordType.TASK.wire }
        assertEquals(77L, JSONObject(row.payloadJson).getJSONObject("payload").getLong("assignee_user_id"))
    }

    @Test fun anOlderKeptReadDoesNotOverruleTheBundle() = runBlocking {
        seed(mapOf(1L to """{"route_id":1,"sr_user_id":42,"sr_name":"Rahim Uddin"}"""))
        kept(40 * minute, planRoute(1, karim))
        val routes = checkNotNull(models().plan(user).routes())
        assertEquals("the bundle carries every route: no request", 0, networkCalls)
        assertEquals(RouteSr(42, "Rahim Uddin"), routes.single().sr)
    }

    @Test fun aRouteNotCarriedIsNamedByTheOnlineRead() = runBlocking {
        seed(
            mapOf(
                1L to """{"route_id":1,"sr_user_id":42,"sr_name":"Rahim Uddin"}""",
                2L to """{"route_id":2,"planned_today":true}""", // a delta from a mixed version: no members for this route
            ),
        )
        onlineRoutes = PlanJson.routesToCache(listOf(planRoute(1, RouteSr(42, "Rahim Uddin")), planRoute(2, karim)))
        val routes = checkNotNull(models().plan(user).routes())
        assertEquals(listOf("/v1/routes?limit=500"), paths)
        assertEquals(listOf(RouteSr(42, "Rahim Uddin"), karim), routes.map { it.sr })
        // kept 30 minutes: the next screen reads no more
        onlineRoutes = null; networkCalls = 0
        assertEquals(karim, checkNotNull(models().plan(user).routes()).single { it.id == 2L }.sr)
        assertEquals(0, networkCalls)
    }

    @Test fun offlineARouteNotCarriedIsNamedByTheLastReadKeptElseItIsVacant() = runBlocking {
        seed(
            mapOf(
                1L to """{"route_id":1,"sr_user_id":42,"sr_name":"Rahim Uddin"}""",
                2L to """{"route_id":2,"planned_today":true}""",
                3L to """{"route_id":3,"planned_today":true}""",
            ),
        )
        // offline, nothing kept: the carried route keeps its SR, the two others are vacant
        checkNotNull(models().plan(user).routes()).let { r ->
            assertTrue("the online read was tried for the routes not carried", networkCalls > 0)
            assertEquals(listOf(RouteSr(42, "Rahim Uddin"), null, null), r.map { it.sr })
        }
        // a read kept from yesterday names route 2 (and an older SR for route 1, which the bundle carries)
        kept(20 * 60 * minute, planRoute(1, RouteSr(5, "Old SR")), planRoute(2, karim))
        val routes = checkNotNull(models().plan(user).routes())
        assertEquals(listOf(1L, 2L, 3L), routes.map { it.id })
        assertEquals(listOf(RouteSr(42, "Rahim Uddin"), karim, null), routes.map { it.sr })
        // Assign Task: route 2 is named offline; route 3 is still "SR Not Set" and queues nothing
        val named = AssignTaskState(types, routes[1], 601, models().taskQueue(user), { today })
        named.pickType("irregular_visit"); named.submit()
        assertEquals(TaskResult.Assigned, named.ui.value.result)
        assertEquals(TaskResult.NoSr, AssignTaskState(types, routes[2], 602, models().taskQueue(user), { today }).ui.value.result)
        val rows = databases.of(user).outboxDao().nextPending(100).filter { it.recordType == RecordType.TASK.wire }
        assertEquals(listOf(77L), rows.map { JSONObject(it.payloadJson).getJSONObject("payload").getLong("assignee_user_id") })
    }
}

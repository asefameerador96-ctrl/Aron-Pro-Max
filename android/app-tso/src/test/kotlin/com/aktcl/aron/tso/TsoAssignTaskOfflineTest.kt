package com.aktcl.aron.tso

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.RecordType
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.database.entity.BundleSectionEntity
import com.aktcl.aron.core.database.entity.RouteEntity
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.sync.SyncScheduler
import com.aktcl.aron.feature.tso.mycall.AssignTaskState
import com.aktcl.aron.feature.tso.mycall.RouteSr
import com.aktcl.aron.feature.tso.mycall.TaskResult
import com.aktcl.aron.feature.tso.mycall.TaskType
import kotlinx.coroutines.runBlocking
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
 * read first, as before.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class TsoAssignTaskOfflineTest {
    private val user = 7L
    private val today = "2026-10-17"
    private lateinit var databases: UserDatabases
    private var networkCalls = 0

    private val env = object : TsoEnv {
        override val client: AronApiClient get() { networkCalls++; error("offline: no network on this phone") }
        override fun nowMs() = 1_760_680_000_000L
        override fun elapsedRealtimeMs() = 5_000L
        override fun bootCount() = 3
        override fun clockOffsetMs(): Long? = 0L
        override fun businessDate() = today
    }
    private val scheduler = object : SyncScheduler { override fun requestSync(userId: Long, trigger: SyncTrigger) = Unit }

    @Before fun open() { databases = UserDatabases(ApplicationProvider.getApplicationContext<Application>()) { null } }
    @After fun close() { databases.close(user) }

    private fun models() = TsoReadModels(env, databases, TsoOutbox(env, databases, scheduler) { false })

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
}

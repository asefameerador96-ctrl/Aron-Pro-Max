package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.contract.Role
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/** F-SYS-086: a route assignment created or ended today marks the route-day tile dirty (route_day_agg) in its transaction. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssignmentDirtyKeyTest {
    private lateinit var env: AdminEnv
    @BeforeAll fun setUp() { env = AdminEnv() }
    @AfterAll fun tearDown() = env.close()

    private val admin get() = env.token("admin1001", Role.ADMIN)
    private fun zone(c: String) = env.sql("SELECT id FROM app.zone WHERE code = '$c'")!!.toLong()
    private fun dirty(route: Long, reason: String = "route_assignment") =
        env.count("SELECT count(*) FROM app.dirty_key WHERE kind = 'route_day_agg' AND subject_id = $route AND business_date = DATE '${env.today}' AND reason = '$reason'")

    @Test
    fun aNewOrEndedAssignmentOfTodayMarksTheRouteDay() = env.app {
        val z = zone("Z-MIR")
        val u = (sendA1(HttpMethod.Post, "/admin/users", admin, """{"username":"dksr0001","full_name":"Dk SR","role":"SR","locale":"en","home_zone_id":$z}""").objA1()["user"] as JsonObject).lng("id")
        val r = sendA1(HttpMethod.Post, "/admin/routes", admin, """{"code":"DKR1","name":"Dk route","zone_id":$z,"kind":"sr","visit_kind":"daily","visit_days_mask":127}""").objA1().lng("id")
        // Only a day that has a route_day row is marked; future days are built fresh.
        val future = sendA1(HttpMethod.Post, "/admin/route-assignments", admin, """{"route_id":$r,"user_id":$u,"kind":"cover","valid_from":"${env.today.plusDays(2)}","valid_to":"${env.today.plusDays(3)}"}""")
        assertEquals(HttpStatusCode.Created, future.status, future.bodyAsText())
        assertEquals(0L, env.count("SELECT count(*) FROM app.dirty_key WHERE kind = 'route_day_agg' AND subject_id = $r"))

        env.db.fresh.db.jdbi.useHandle<Exception> { it.execute("INSERT INTO app.route_day (route_id, business_date, planned) VALUES ($r, DATE '${env.today}', true)") }
        val a = sendA1(HttpMethod.Post, "/admin/route-assignments", admin, """{"route_id":$r,"user_id":$u,"kind":"primary","valid_from":"${env.today}"}""")
        assertEquals(HttpStatusCode.Created, a.status, a.bodyAsText())
        assertEquals(1L, dirty(r), "create marks today's route-day")

        env.db.fresh.db.jdbi.useHandle<Exception> { it.execute("DELETE FROM app.dirty_key WHERE kind = 'route_day_agg' AND subject_id = $r") }
        val end = sendA1(HttpMethod.Post, "/admin/route-assignments/${a.objA1().lng("id")}/end", admin, """{"valid_to":"${env.today.plusDays(1)}","reason":"handover to the next SR"}""")
        assertEquals(HttpStatusCode.OK, end.status, end.bodyAsText())
        assertEquals(1L, dirty(r), "end marks today's route-day")
    }
}

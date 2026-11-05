package com.aktcl.aron.backend.masterdata

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** T1 checker probes of the replay-first fix (ReplayFirstTest). The visit-plan ones are expected to FAIL on the current fix. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReplayFirstGapsTest {
    private lateinit var env: BackendAdminEnv
    @BeforeAll fun setUp() { env = BackendAdminEnv() }
    @AfterAll fun tearDown() = env.close()
    private fun uuid() = UUID.randomUUID().toString()

    /** A second plan uuid merged into an existing plan of the same route/date has no visit_plan row, so its replay is not replay-first. */
    @Test
    fun mergedPlanUuidReplayAfterAnOutletLeavesTheReach() = env.app {
        val o = env.outletIds("MIR-SR-D", 2)
        val route = env.routeIds.getValue("MIR-SR-D")
        val first = uuid(); val second = uuid()
        assertEquals(HttpStatusCode.Created, sendA3(HttpMethod.Post, "/v1/visit-plans", env.tok("tso1001"), """{"plan_uuid":"$first","plan_date":"2028-05-05","route_id":$route,"outlet_ids":[${o[0]}]}""").status)
        val body2 = """{"plan_uuid":"$second","plan_date":"2028-05-05","route_id":$route,"outlet_ids":[${o[1]}]}"""
        val firstAnswer = sendA3(HttpMethod.Post, "/v1/visit-plans", env.tok("tso1001"), body2)
        assertEquals(HttpStatusCode.Created, firstAnswer.status)
        val far = env.scalar("SELECT z.id FROM app.zone z WHERE z.id NOT IN (SELECT home_zone_id FROM app.app_user WHERE username = 'tso1001') ORDER BY z.id DESC LIMIT 1")!!
        val homeZone = env.scalar("SELECT zone_id FROM app.outlet WHERE id = ${o[1]}")!!
        env.exec("UPDATE app.outlet SET zone_id = $far WHERE id = ${o[1]}")
        try {
            val replay = sendA3(HttpMethod.Post, "/v1/visit-plans", env.tok("tso1001"), body2)
            assertEquals(HttpStatusCode.OK, replay.status, "late replay of the merged uuid must answer the original result")
            assertEquals(first, replay.objA3().strA3("plan_uuid"))
        } finally {
            env.exec("UPDATE app.outlet SET zone_id = $homeZone WHERE id = ${o[1]}")
        }
    }

    /** An outlet re-routed (route rebalancing) after the plan was saved: the replay-first guard compares the outlet's CURRENT route. */
    @Test
    fun planReplayAfterAnOutletIsReRouted() = env.app {
        val o = env.outletIds("MIR-SR-D", 2)
        val route = env.routeIds.getValue("MIR-SR-D")
        val other = env.scalar("SELECT id FROM app.route WHERE id <> $route ORDER BY id LIMIT 1")!!
        val plan = uuid()
        val body = """{"plan_uuid":"$plan","plan_date":"2028-05-06","route_id":$route,"outlet_ids":[${o.joinToString(",")}]}"""
        assertEquals(HttpStatusCode.Created, sendA3(HttpMethod.Post, "/v1/visit-plans", env.tok("tso1001"), body).status)
        env.exec("UPDATE app.outlet SET route_id = $other WHERE id = ${o[0]}")
        try {
            val replay = sendA3(HttpMethod.Post, "/v1/visit-plans", env.tok("tso1001"), body)
            assertEquals(HttpStatusCode.OK, replay.status, "late replay after an outlet moved route must answer the original result, not 400/409")
        } finally {
            env.exec("UPDATE app.outlet SET route_id = $route WHERE id = ${o[0]}")
        }
    }

    /** Another user replaying a stored uuid gets a bare 409 that does not reveal the stored row. Expected to PASS. */
    @Test
    fun anotherUsersReplayDoesNotLeak() = env.app {
        val id = uuid()
        val fb = """{"feedback_uuid":"$id","category_code":"other","title":"SecretTitle","description":"SecretBody"}"""
        val created = sendA3(HttpMethod.Post, "/v1/feedback", env.tok("tso1001"), fb)
        if (created.status == HttpStatusCode.Created) {
            val r = sendA3(HttpMethod.Post, "/v1/feedback", env.tok("tso2001"), fb)
            assertEquals(HttpStatusCode.Conflict, r.status)
            assertFalse(r.bodyAsText().contains("Secret"))
        }
        val lid = uuid()
        // tso-manual B3: inside cfg.leave.allow_ahead_days (365) of the API's system clock (was 2028-06-01).
        val from = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Dhaka")).plusDays(200) // wall-clock-ok: the leave window is relative to the API's system clock
        val lv = """{"leave_uuid":"$lid","leave_type_code":"casual","from_date":"$from","days":1,"reason":"SecretReason"}"""
        assertEquals(HttpStatusCode.Created, sendA3(HttpMethod.Post, "/v1/leave", env.tok("tso1001"), lv).status)
        val r2 = sendA3(HttpMethod.Post, "/v1/leave", env.tok("tso2001"), lv)
        assertEquals(HttpStatusCode.Conflict, r2.status)
        assertFalse(r2.bodyAsText().contains("Secret"))
    }
}

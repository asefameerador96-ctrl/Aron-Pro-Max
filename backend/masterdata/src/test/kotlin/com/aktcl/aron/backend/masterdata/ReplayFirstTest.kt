package com.aktcl.aron.backend.masterdata

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Idempotency (CLAUDE.md rule 2): a retried POST of a stored client uuid answers the stored result, even after a rule
 * it passed has changed (category retired, a new overlapping leave, an outlet moved out of reach). New content under
 * the same uuid stays a 409, and a fresh request still meets every rule.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReplayFirstTest {
    private lateinit var env: BackendAdminEnv
    @BeforeAll fun setUp() { env = BackendAdminEnv() }
    @AfterAll fun tearDown() = env.close()
    private fun uuid() = UUID.randomUUID().toString()

    // tso-manual B3 (D-TSO-MAN-33): overlapping leave is refused only with cfg.leave.block_overlap (default false), read
    // by LeaveRules on the request's own handle, so the switch is a cfg_value row (closed again after the test).
    private fun overlapBlock(on: Boolean) = if (on) {
        env.exec(
            "INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) " +
                "SELECT 'cfg.leave.block_overlap', 'global', 0, 'true'::jsonb, TIMESTAMPTZ '2026-01-01T00:00:00Z', max(config_version), 'test: overlap block on' FROM app.cfg_version",
        )
    } else {
        env.exec("UPDATE app.cfg_value SET effective_to = now() WHERE key = 'cfg.leave.block_overlap' AND scope_type = 'global' AND effective_to IS NULL")
    }

    @Test
    fun feedbackReplayAfterTheCategoryIsRetired() = env.app {
        env.exec("INSERT INTO app.code_list_item (list_key, code, label_en, sort, valid_from) VALUES ('feedback_category', 'night_check', 'Night check', 99, DATE '2001-01-01')")
        val id = uuid()
        val body = """{"feedback_uuid":"$id","category_code":"night_check","title":"Printer","description":"Paper jams"}"""
        assertEquals(HttpStatusCode.Created, sendA3(HttpMethod.Post, "/v1/feedback", env.tok("tso1001"), body).status)
        env.exec("UPDATE app.code_list_item SET valid_to = DATE '2001-01-02' WHERE list_key = 'feedback_category' AND code = 'night_check'")
        val replay = sendA3(HttpMethod.Post, "/v1/feedback", env.tok("tso1001"), body)
        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals(id, replay.objA3().strA3("feedback_uuid"))
        assertEquals(1, env.count("SELECT count(*) FROM app.feedback WHERE client_uuid = '$id'"))
        // The same uuid with other content is still a conflict; a new uuid with the retired code is refused.
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Post, "/v1/feedback", env.tok("tso1001"), body.replace("Paper jams", "Other")).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/feedback", env.tok("tso1001"), body.replace(id, uuid())).status)
    }

    @Test
    fun leaveReplayAfterAnOverlapAppears() = env.app {
        val id = uuid()
        // tso-manual B3: inside cfg.leave.allow_ahead_days (365) of the API's system clock (was 2028-03-01).
        val from = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Dhaka")).plusDays(120) // wall-clock-ok: the leave window is relative to the API's system clock
        val body = """{"leave_uuid":"$id","leave_type_code":"casual","from_date":"$from","days":3,"reason":"Family"}"""
        assertEquals(HttpStatusCode.Created, sendA3(HttpMethod.Post, "/v1/leave", env.tok("tso1001"), body).status)
        // Another pending application over the same days lands later (another phone, a migration, an admin fix).
        env.exec(
            "INSERT INTO app.leave_application (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, leave_type_code, from_date, days, reason) " +
                "SELECT u2, u2, current_date, user_id, now(), config_version, leave_type_code, from_date, days, 'Other' FROM app.leave_application, (SELECT gen_random_uuid() AS u2) g WHERE client_uuid = '$id'",
        )
        val replay = sendA3(HttpMethod.Post, "/v1/leave", env.tok("tso1001"), body)
        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals(id, replay.objA3().strA3("leave_uuid"))
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Post, "/v1/leave", env.tok("tso1001"), body.replace("Family", "Changed")).status)
        overlapBlock(true)
        try {
            assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Post, "/v1/leave", env.tok("tso1001"), body.replace(id, uuid())).status)   // a fresh one still meets the overlap rule
        } finally { overlapBlock(false) }
    }

    @Test
    fun visitPlanReplayAfterAnOutletLeavesTheReach() = env.app {
        val o = env.outletIds("MIR-SR-D", 2)
        val plan = uuid()
        val route = env.routeIds.getValue("MIR-SR-D")
        val body = """{"plan_uuid":"$plan","plan_date":"2028-04-04","route_id":$route,"outlet_ids":[${o.joinToString(",")}]}"""
        assertEquals(HttpStatusCode.Created, sendA3(HttpMethod.Post, "/v1/visit-plans", env.tok("tso1001"), body).status)
        val far = env.scalar("SELECT z.id FROM app.zone z WHERE z.id NOT IN (SELECT home_zone_id FROM app.app_user WHERE username = 'tso1001') ORDER BY z.id DESC LIMIT 1")!!
        val homeZone = env.scalar("SELECT zone_id FROM app.outlet WHERE id = ${o[0]}")!!
        env.exec("UPDATE app.outlet SET zone_id = $far WHERE id = ${o[0]}")
        try {
            val replay = sendA3(HttpMethod.Post, "/v1/visit-plans", env.tok("tso1001"), body)
            assertEquals(HttpStatusCode.OK, replay.status)
            assertEquals(plan, replay.objA3().strA3("plan_uuid"))
            assertEquals(2, env.count("SELECT count(*) FROM app.visit_plan_outlet WHERE plan_client_uuid = '$plan' AND voided_at IS NULL"))
            // A new plan naming that outlet meets the reach rule (403, as an unknown outlet).
            assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Post, "/v1/visit-plans", env.tok("tso1001"), body.replace(plan, uuid()).replace("2028-04-04", "2028-04-05")).status)
        } finally {
            env.exec("UPDATE app.outlet SET zone_id = $homeZone WHERE id = ${o[0]}")
        }
    }
}

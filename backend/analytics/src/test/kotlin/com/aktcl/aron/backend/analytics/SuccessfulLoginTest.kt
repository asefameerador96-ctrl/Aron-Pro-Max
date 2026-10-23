package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.contract.Role
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.Route
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D-REPORTS-LOGIN-01: "Successful Login" is login AND download AND check-in in every API that counts it. The seeded day has R1 (sr001), R2 (sr002) and R3 (sr003)
 * all logged in; here R1 downloaded and checked in, R2 downloaded but never checked in, and R3 checked in but never downloaded. Both count 0 until the missing step exists, then R2 counts 1.
 */
class SuccessfulLoginTest : ReportFixture() {
    override val successfulLogin = false
    override val extraSql = """
        UPDATE app.route_day SET bundle_count = 1, last_bundle_at = TIMESTAMPTZ '2026-10-04 01:30Z' WHERE route_id IN (SELECT id FROM app.route WHERE code IN ('R1', 'R2'));
        INSERT INTO app.attendance_event (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, kind, fix_status, fix_lat, fix_lng, fix_accuracy_m, fix_is_mock, address_display)
          SELECT gen_random_uuid(), gen_random_uuid(), DATE '2026-10-04', u.id, r.id, TIMESTAMPTZ '2026-10-04 03:00Z', 1, 'check_in', 'ok', 23.78, 90.41, 8, false, 'Banani'
            FROM (VALUES ('R1', 'sr001'), ('R3', 'sr003')) d(rc, un) JOIN app.route r ON r.code = d.rc JOIN app.app_user u ON u.username = d.un;
    """.trimIndent()

    override fun mount(r: Route, clock: AronClock, reach: ReachResolver, guard: AuthGuardDeps) {
        r.dailyTrackingRoutes(DailyTrackingDeps(DailyTrackingService(fresh.db, RegistryDefaults(), clock), reach, guard, clock))
        r.opsRoutes(OpsDeps(OpsService(fresh.db, RegistryDefaults(), clock), DashboardService(fresh.db, clock), reach, guard, clock))
    }

    private suspend fun ApplicationTestBuilder.get(path: String): JsonObject =
        Json.parseToJsonElement(client.get(path) { bearerAuth(TestTokens.web(10, Role.ANALYST)) }.bodyAsText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.codes(k: String) = this[k]!!.jsonArray.map { it.jsonObject.s("route_code") }.toSet()

    private suspend fun ApplicationTestBuilder.buckets(): Map<String, String> =
        get("/v1/dashboards/daily-tracking?business_date=2026-10-04")["items"]!!.jsonArray.map { it.jsonObject }.associate { it.s("route_name") to it.s("bucket") }

    @Test
    fun loginDownloadWithoutCheckInCountsZeroAndWithCheckInCountsOne() = app {
        // Daily Tracking: only R1 (login, download, check-in) is logged in; R2 lacks the check-in, R3 the download.
        assertEquals(mapOf("Route1" to "below_80", "Route2" to "not_logged_in", "Route3" to "not_logged_in"), buckets())
        // Ops login/submit: 1 of 3 target routes. R2 and R3 are not logged in even though R3 submitted (a submit without a successful login is not counted as submitted,
        // so the submit share of the logged-in routes never exceeds 100 %); R1 is the only submitted route.
        val ops = get("/v1/dashboards/login-submit?business_date=2026-10-04")
        assertEquals(33.33, ops["kpis"]!!.jsonObject.s("login_pct").toDouble()); assertEquals(100.0, ops["kpis"]!!.jsonObject.s("submit_pct_of_logged_in").toDouble())
        assertEquals(setOf("R2", "R3"), ops.codes("not_logged_in")); assertEquals(emptySet(), ops.codes("logged_in_not_submitted")); assertEquals(setOf("R1"), ops.codes("submitted"))
        // Reports: the daily-tracking buckets and the final-submit-status counts follow the same rule.
        val dt = run(10, Role.ANALYST, "daily-tracking").result().rows().associate { it.str("route_code") to it.str("bucket") }
        assertEquals("not_logged_in", dt["R2"]); assertEquals("not_logged_in", dt["R3"]); assertEquals("below_80", dt["R1"])
        val fs = run(10, Role.ANALYST, "final-submit-status").result().rows().associate { it.str("zone_name") to it }
        val z1Row = fs.values.first { it.num("target_routes").toInt() == 2 }
        assertEquals(1, z1Row.num("not_started").toInt()); assertEquals(0, z1Row.num("in_progress").toInt()); assertEquals(1, z1Row.num("sales_submitted").toInt())

        // R2's SR checks in: the route now counts as logged in everywhere.
        fresh.db.jdbi.useHandle<Exception> {
            it.execute(
                """INSERT INTO app.attendance_event (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, kind, fix_status, fix_lat, fix_lng, fix_accuracy_m, fix_is_mock, address_display)
                   SELECT gen_random_uuid(), gen_random_uuid(), DATE '2026-10-04', u.id, r.id, TIMESTAMPTZ '2026-10-04 03:00Z', 1, 'check_in', 'ok', 23.78, 90.41, 8, false, 'Banani'
                     FROM app.app_user u, app.route r WHERE u.username = 'sr002' AND r.code = 'R2'""",
            )
        }
        AggregationWorker(fresh.db).also { w -> w.requestRebuild(day); w.runUntilIdle() }
        assertEquals(mapOf("Route1" to "below_80", "Route2" to "ge_100", "Route3" to "not_logged_in"), buckets())
        // Zone 1 (R1, R2) is asked at its own scope: the national KPIs of phase A are held in the 30 s dashboard cache.
        val ops2 = get("/v1/dashboards/login-submit?business_date=2026-10-04&level=zone&node_id=$z1")
        assertEquals(100.0, ops2["kpis"]!!.jsonObject.s("login_pct").toDouble()); assertEquals(50.0, ops2["kpis"]!!.jsonObject.s("submit_pct_of_logged_in").toDouble())
        assertEquals(emptySet(), ops2.codes("not_logged_in")); assertEquals(setOf("R2"), ops2.codes("logged_in_not_submitted")); assertEquals(setOf("R1"), ops2.codes("submitted"))
        val fs2 = run(10, Role.ANALYST, "final-submit-status").result().rows().first { it.num("target_routes").toInt() == 2 }
        assertEquals(0, fs2.num("not_started").toInt()); assertEquals(1, fs2.num("in_progress").toInt())
        assertEquals("ge_100", run(10, Role.ANALYST, "daily-tracking").result().rows().first { it.str("route_code") == "R2" }.str("bucket"))   // 1 call of 1 target

        // R3 is worked by a stand-in SR (the route-day's acting user) whose check-in names no route; R3 downloads: now R3 counts as logged in too.
        fresh.db.jdbi.useHandle<Exception> {
            it.execute("UPDATE app.route_day SET acting_user_id = 10, bundle_count = 1, last_bundle_at = TIMESTAMPTZ '2026-10-04 01:30Z' WHERE route_id = (SELECT id FROM app.route WHERE code = 'R3')")
            it.execute(
                """INSERT INTO app.attendance_event (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, kind, fix_status, fix_lat, fix_lng, fix_accuracy_m, fix_is_mock, address_display)
                   VALUES (gen_random_uuid(), gen_random_uuid(), DATE '2026-10-04', 10, NULL, TIMESTAMPTZ '2026-10-04 03:30Z', 1, 'check_in', 'ok', 23.78, 90.41, 8, false, 'Banani')""",
            )
        }
        AggregationWorker(fresh.db).also { w -> w.requestRebuild(day); w.runUntilIdle() }
        assertEquals(emptySet(), get("/v1/dashboards/login-submit?business_date=2026-10-04").codes("not_logged_in"))
    }
}

package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.contract.Role
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-API-018 (app home) and F-API-024 (team stock) on the seeded day. F-API-023 (team locations) is backend:sync's N-036. */
class TeamApiTest : ReportFixture() {
    override val extraSql = """
        ALTER TABLE app.visit DISABLE TRIGGER USER;
        UPDATE app.visit SET fix_status = 'ok', fix_lat = 23.70 + (sequence_no * 0.01), fix_lng = 90.40, fix_accuracy_m = 12 WHERE client_uuid IN ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-000000000004');
        UPDATE app.visit SET fix_status = 'ok', fix_lat = 10.0, fix_lng = 10.0, fix_accuracy_m = 5, fix_is_mock = true WHERE client_uuid = '00000000-0000-4000-8000-000000000003';
        ALTER TABLE app.visit ENABLE TRIGGER USER;
        INSERT INTO app.attendance_event (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, kind, fix_status, fix_lat, fix_lng, fix_accuracy_m, fix_is_mock)
          SELECT gen_random_uuid(), gen_random_uuid(), DATE '2026-10-04', u.id, r.id, TIMESTAMPTZ '2026-10-04 03:00Z', 1, 'check_in', 'ok', 23.78, 90.41, 8, false
            FROM app.app_user u, app.route r WHERE u.username = 'sr003' AND r.code = 'R3';
    """.trimIndent()

    private lateinit var deps: AppTeamDeps
    override fun mount(r: Route, clock: AronClock, reach: ReachResolver, guard: AuthGuardDeps) {
        val dash = DashboardService(fresh.db, clock)
        deps = AppTeamDeps(TeamService(fresh.db, dash, clock), reach, guard, clock)
        r.appTeamRoutes(deps)
    }

    private suspend fun ApplicationTestBuilder.get(uid: Long, role: Role, path: String): HttpResponse = client.get(path) { bearerAuth(TestTokens.web(uid, role)) }
    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun appHomeGivesTheKpiStripAndTeamRowsOfTheCallersReach() = app {
        val h = get(12, Role.AMO, "/v1/app/home?business_date=2026-10-04").obj()
        assertEquals("2026-10-04", h["business_date"]!!.jsonPrimitive.content)
        assertEquals("zone", h["node"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val k = h["kpis"]!!.jsonObject
        assertEquals(2, k["target_routes"]!!.jsonPrimitive.content.toInt()); assertEquals(39_000L, k["gross_mtk"]!!.jsonPrimitive.content.toLong())
        // The AMO's zone holds R1 (sr001) and R2 (sr002); R3 of zone 2 is not shown.
        val team = h["team"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("User 1", "User 2").size, team.size)
        val r1 = team.first { it["route_name"]!!.jsonPrimitive.content == "Route1" }
        assertEquals("sales_submitted", r1["state"]!!.jsonPrimitive.content); assertEquals(2, r1["visited"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, r1["target_outlets"]!!.jsonPrimitive.content.toInt()); assertEquals(34_000L, r1["net_mtk"]!!.jsonPrimitive.content.toLong())
        assertEquals(HttpStatusCode.Forbidden, get(12, Role.SR, "/v1/app/home").status.let { if (it == HttpStatusCode.Unauthorized) HttpStatusCode.Forbidden else it })
        assertTrue(get(10, Role.ANALYST, "/v1/app/home?business_date=2026-10-04").obj()["team"]!!.jsonArray.size == 3)
    }

    @Test
    fun teamStockIsIssuedSoldReturnedAndCurrentWithoutPhoneNumbers() = app {
        val r = get(11, Role.TSO, "/v1/team/stock?business_date=2026-10-04"); assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertFalse(body.contains("contact") || body.contains("phone") || body.contains("01711"))
        val items = Json.parseToJsonElement(body).jsonObject["items"]!!.jsonArray.map { it.jsonObject }
        val sr1 = items.first { it["full_name"]!!.jsonPrimitive.content == "SR One" }["by_sku"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, sr1.size)
        val sku1 = sr1.first { it["issued_qty_base"]!!.jsonPrimitive.content.toLong() == 100L }
        assertEquals(30, sku1["sold_qty_base"]!!.jsonPrimitive.content.toInt()); assertEquals(10, sku1["returned_qty_base"]!!.jsonPrimitive.content.toInt())
        assertEquals(60, sku1["current_qty_base"]!!.jsonPrimitive.content.toInt())          // 100 - 30 - 10
        assertEquals("stick", sku1["base_unit"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, get(11, Role.TSO, "/v1/team/stock").status)  // the date is required
        assertEquals(HttpStatusCode.Forbidden, get(11, Role.TSO, "/v1/team/stock?business_date=2026-10-04&zone_id=$z2").status)
        assertEquals(1, get(14, Role.TSO, "/v1/team/stock?business_date=2026-10-04").obj()["items"]!!.jsonArray.size)
    }
}

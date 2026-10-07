package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.contract.Role
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.Route
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-API-015: buckets by route (hand-computed from the seeded day plus one not-logged-in and one excused route) and the take-action note. */
class DailyTrackingTest : ReportFixture() {
    override val extraSql = """
        INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'R4', 'Route4', id, 'sr', 'daily', 127 FROM app.zone WHERE code = 'Z1';
        INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'R5', 'Route5', id, 'sr', 'daily', 127 FROM app.zone WHERE code = 'Z1';
        INSERT INTO app.route_day (route_id, business_date, planned, state, target_outlets) SELECT id, DATE '2026-10-04', true, 'not_started', 2 FROM app.route WHERE code IN ('R4', 'R5');
        INSERT INTO app.day_exception (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, reason_code, route_ids, from_date, to_date, status)
          SELECT gen_random_uuid(), gen_random_uuid(), DATE '2026-10-04', u.id, TIMESTAMPTZ '2026-10-04 03:00Z', 1, 'sick_leave', ARRAY[r.id], DATE '2026-10-04', DATE '2026-10-04', 'approved'
            FROM app.app_user u, app.route r WHERE u.username = 'sr001' AND r.code = 'R5';
    """.trimIndent()

    override fun mount(r: Route, clock: AronClock, reach: ReachResolver, guard: AuthGuardDeps) {
        r.dailyTrackingRoutes(DailyTrackingDeps(DailyTrackingService(fresh.db, RegistryDefaults(), clock), reach, guard, clock))
    }

    private suspend fun ApplicationTestBuilder.get(uid: Long, role: Role, path: String): HttpResponse = client.get(path) { bearerAuth(TestTokens.web(uid, role)) }
    private suspend fun ApplicationTestBuilder.act(uid: Long, role: Role, json: String): HttpResponse =
        client.post("/v1/dashboards/daily-tracking/actions") { bearerAuth(TestTokens.web(uid, role)); contentType(ContentType.Application.Json); setBody(json) }
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private suspend fun routeId(code: String) = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT id FROM app.route WHERE code = '$code'").mapTo(Long::class.java).one() }

    @Test
    fun bucketsFollowCallProductivityExceptionAndLogin() = app {
        val o = Json.parseToJsonElement(get(10, Role.ANALYST, "/v1/dashboards/daily-tracking?business_date=2026-10-04").bodyAsText()).jsonObject
        val items = o["items"]!!.jsonArray.map { it.jsonObject }.associateBy { it.s("route_name") }
        assertEquals(5, items.size)
        assertEquals("below_80", items.getValue("Route1").s("bucket"))      // 2 calls / 3 targets = 66.67 %
        assertEquals("ge_100", items.getValue("Route2").s("bucket")); assertEquals("ge_100", items.getValue("Route3").s("bucket"))
        assertEquals("not_logged_in", items.getValue("Route4").s("bucket")); assertEquals("exception", items.getValue("Route5").s("bucket"))
        val r1 = items.getValue("Route1")
        assertEquals("User 1".let { "SR One" }, r1.s("user_name")); assertEquals(2, r1.s("successful_calls").toInt()); assertEquals(34_000, r1.s("net_mtk").toLong())
        assertEquals("sales_submitted", r1.s("state")); assertEquals(34_000L, r1["by_category"]!!.jsonArray.single().jsonObject.s("net_mtk").toLong())
        assertNull(r1["tilldate_target_achievement_pct"]?.jsonPrimitive?.takeIf { it.content != "null" })
        // Scope: zone 2's TSO sees only route 3; zone 1's TSO may not ask for zone 2; node narrowing works for national.
        assertEquals(listOf("Route3"), Json.parseToJsonElement(get(14, Role.TSO, "/v1/dashboards/daily-tracking?business_date=2026-10-04").bodyAsText()).jsonObject["items"]!!.jsonArray.map { it.jsonObject.s("route_name") })
        assertEquals(HttpStatusCode.Forbidden, get(11, Role.TSO, "/v1/dashboards/daily-tracking?business_date=2026-10-04&level=zone&node_id=$z2").status)
        assertEquals(1, Json.parseToJsonElement(get(10, Role.ANALYST, "/v1/dashboards/daily-tracking?business_date=2026-10-04&level=zone&node_id=$z2").bodyAsText()).jsonObject["items"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.BadRequest, get(10, Role.ANALYST, "/v1/dashboards/daily-tracking").status)
        assertEquals(HttpStatusCode.Forbidden, get(12, Role.AMO, "/v1/dashboards/daily-tracking?business_date=2026-10-04").status)
    }

    @Test
    fun pagingFollowsTheCursorWithoutRepeats() = app {
        val seen = mutableListOf<String>(); var cursor: String? = null
        do {
            val o = Json.parseToJsonElement(get(10, Role.ANALYST, "/v1/dashboards/daily-tracking?business_date=2026-10-04&limit=2" + (cursor?.let { "&cursor=$it" } ?: "")).bodyAsText()).jsonObject
            seen += o["items"]!!.jsonArray.map { it.jsonObject.s("route_name") }
            cursor = o["next_cursor"]!!.jsonPrimitive.takeIf { it.content != "null" }?.content
        } while (cursor != null)
        assertEquals(5, seen.size); assertEquals(5, seen.toSet().size)
        assertEquals(HttpStatusCode.BadRequest, get(10, Role.ANALYST, "/v1/dashboards/daily-tracking?business_date=2026-10-04&cursor=zzz").status)
    }

    @Test
    fun takeActionStoresAnIdempotentNoteAfterTheCutoffAndNamesTheNudgedUsers() = app {
        val r4 = routeId("R4")
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from) VALUES (11, 'zone', $z1, '2026-01-01'), (12, 'zone', $z1, '2026-01-01'), (14, 'zone', $z2, '2026-01-01')")
        }
        val uuid = "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10"
        val body = """{"action_uuid":"$uuid","route_id":$r4,"business_date":"2026-10-04","note":"Why not logged in?"}"""
        val r = act(11, Role.TSO, body); assertEquals(HttpStatusCode.Created, r.status)
        val a = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("Why not logged in?", a.s("note")); assertEquals(11, a.s("created_by_user_id").toInt())
        assertEquals(setOf(11L, 12L), a["notified_user_ids"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() }.toSet())   // the zone's TSO and AMO, not zone 2's TSO
        // Replay changes nothing: same answer, one stored row, one outbox event.
        val again = Json.parseToJsonElement(act(11, Role.TSO, body).bodyAsText()).jsonObject
        assertEquals(a.s("created_at"), again.s("created_at"))
        assertEquals(1, fresh.db.jdbi.withHandle<Int, Exception> { it.createQuery("SELECT count(*) FROM app.audit_log WHERE entity = 'tracking_action'").mapTo(Int::class.java).one() })
        assertEquals(1, fresh.db.jdbi.withHandle<Int, Exception> { it.createQuery("SELECT count(*) FROM app.domain_event WHERE event_type = 'tracking_action.created'").mapTo(Int::class.java).one() })
        // The same uuid with another note is a conflict; another user's uuid reuse too.
        assertEquals(HttpStatusCode.Conflict, act(11, Role.TSO, body.replace("Why not", "Where is")).status)
        // Too early (business date of the future: the cutoff 17:00 Dhaka of that date has not come), outside reach, wrong role, bad body.
        val early = act(11, Role.TSO, """{"action_uuid":"7f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10","route_id":$r4,"business_date":"2026-10-05","note":"Too early"}""")
        assertEquals(HttpStatusCode.Conflict, early.status); assertTrue(early.bodyAsText().contains("ERR_REQUEST_STATE"))
        assertEquals(HttpStatusCode.Forbidden, act(14, Role.TSO, """{"action_uuid":"8f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10","route_id":$r4,"business_date":"2026-10-04","note":"Not mine"}""").status)
        assertEquals(HttpStatusCode.Forbidden, act(12, Role.AMO, body.replace(uuid, "9f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10")).status)
        assertEquals(HttpStatusCode.BadRequest, act(11, Role.TSO, """{"action_uuid":"$uuid","route_id":$r4,"business_date":"2026-10-04","note":"x","scope":[1]}""").status)
        assertNotNull(a["notified_user_ids"])
    }
}

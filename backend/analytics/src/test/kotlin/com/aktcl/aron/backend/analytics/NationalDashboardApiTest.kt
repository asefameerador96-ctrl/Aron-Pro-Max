package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The national dashboard over HTTP (contract 1.4.0): paths, methods, statuses, the contract's required fields and the scope
 * rules of docs/34 s1. Users: 10 ANALYST (national), 11 TSO (zone 1), 14 TSO (zone 2), 15 a BRAND-scoped user (BrandOne only),
 * 12 AMO (no dashboard). The figures behind the cards are in NationalDashboardTest and NationalMapsTest.
 */
class NationalDashboardApiTest : ReportFixture() {
    private fun res(n: String) = javaClass.classLoader.getResourceAsStream(n)!!.bufferedReader().readText()
    override val extraSql: String by lazy { listOf("dashboard_summaries_test.sql", "national_day_extra.sql", "national_map_extra.sql").joinToString("\n") { res(it) } }

    override fun mount(r: Route, clock: AronClock, reach: ReachResolver, guard: AuthGuardDeps) {
        val brandOne = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT id FROM app.product_node WHERE level = 'brand' AND code = 'B1'").mapTo(Long::class.java).one() }
        reaches[15] = Reach(15, Role.TOP, day, true, emptySet(), emptySet(), false, emptyList())
        val resolver = ReachResolver { uid, role, sv, d -> if (uid == 15L) reaches.getValue(15) else reach.reach(uid, role, sv, d) }
        r.nationalDashboardRoutes(NationalDeps(NationalDashboardService(fresh.db, testClock), resolver, guard, BrandScopeResolver { uid, _ -> if (uid == 15L) setOf(brandOne) else null }, testClock))
    }

    private suspend fun ApplicationTestBuilder.post(uid: Long, role: Role, path: String, json: String = """{"date":"2026-10-04"}"""): HttpResponse =
        client.post("/v1$path") { bearerAuth(TestTokens.web(uid, role)); contentType(ContentType.Application.Json); setBody(json) }

    private suspend fun ApplicationTestBuilder.get(uid: Long, role: Role, path: String): HttpResponse = client.get("/v1$path") { bearerAuth(TestTokens.web(uid, role)) }
    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private val pinsBody = """{"filter":{"date":"2026-10-04"}}"""
    private val common = listOf("date", "till_time", "as_of", "last_updated_at")

    @Test
    fun everyCardAnswersOverHttpWithTheContractsRequiredFields() = app {
        val cards = mapOf(
            "/dashboards/national/sales" to listOf("working_days", "cards"),
            "/dashboards/national/cpr" to listOf("target_outlets", "successful_calls", "cpr_pct"),
            "/dashboards/national/channel-calls" to listOf("channels", "total", "unclassified"),
            "/dashboards/national/final-submit" to listOf("total_zones", "service_zones", "final_submitted", "remaining", "submit_pct"),
            "/dashboards/national/login-submit" to listOf("target_routes", "successful_login", "login_pct", "submitted", "submit_pct"),
            "/dashboards/national/channel-std" to listOf("channels", "total", "unit"),
            "/dashboards/national/geo-fencing" to listOf("total_calls", "geo_validated", "geo_pct"),
            "/dashboards/national/ff-geo/pins" to listOf("pins", "counts", "routes_without_position"),
        )
        for ((path, fields) in cards) {
            val r = post(10, Role.ANALYST, path, if (path.endsWith("/pins")) pinsBody else """{"date":"2026-10-04"}"""); assertEquals(HttpStatusCode.OK, r.status, path)
            val o = r.obj(); for (k in common + fields) assertTrue(k in o, "$path lacks $k")
            assertEquals("2026-10-04", o["date"]!!.jsonPrimitive.content)
        }
        val bd = post(10, Role.ANALYST, "/dashboards/national/breakdown", """{"filter":{"date":"2026-10-04"},"measure":"memo","dimension":"brand"}""").obj()
        assertEquals(listOf("BrandOne", "BrandTwo"), bd["items"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals(HttpStatusCode.OK, get(10, Role.ANALYST, "/dashboards/national/filter-options").status)
        assertEquals(HttpStatusCode.OK, get(10, Role.ANALYST, "/dashboards/national/freshness").status)
    }

    @Test
    fun mapPagesAnswerAndAZoneCallerCannotOpenAnotherZonesRoute() = app {
        val r1 = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT id FROM app.route WHERE code = 'R1'").mapTo(Long::class.java).one() }
        val o2 = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT id FROM app.outlet WHERE code = 'O2'").mapTo(Long::class.java).one() }
        for (p in listOf("/dashboards/national/ff-geo/routes/$r1?date=2026-10-04", "/dashboards/route-map/$r1?date=2026-10-04", "/dashboards/sale-position/$o2?date=2026-10-04")) {
            assertEquals(HttpStatusCode.OK, get(11, Role.TSO, p).status, p)
            assertEquals(HttpStatusCode.NotFound, get(14, Role.TSO, p).status, p)          // zone 2's TSO: the same answer as for a route that does not exist
        }
        assertEquals(HttpStatusCode.NotFound, get(10, Role.ANALYST, "/dashboards/route-map/987654?date=2026-10-04").status)
        assertEquals(HttpStatusCode.BadRequest, get(10, Role.ANALYST, "/dashboards/route-map/abc").status)
        assertEquals(HttpStatusCode.BadRequest, get(10, Role.ANALYST, "/dashboards/route-map/$r1?date=yesterday").status)
        val m = get(10, Role.ANALYST, "/dashboards/route-map/$r1?date=2026-10-04").obj()
        for (k in common + listOf("route", "territory", "zone", "outlets", "path", "target_vs_achievement", "cpr", "time_card")) assertTrue(k in m, "route map lacks $k")
    }

    @Test
    fun theZoneCallersFiguresAreCutToItsReachAndTheClientCannotWidenThem() = app {
        fun sold(o: JsonObject) = o["cards"]!!.jsonArray.map { it.jsonObject }.single { it["category_code"]!!.jsonPrimitive.content == "cigarette" }["total_sales"]!!.jsonPrimitive.content.toLong()
        assertEquals(81, sold(post(10, Role.ANALYST, "/dashboards/national/sales").obj()))           // all five: 75 + the 5-stick repeat + the edit's 1 stick more
        assertEquals(51, sold(post(11, Role.TSO, "/dashboards/national/sales").obj()))               // zone 1 only
        assertEquals(51, sold(post(11, Role.TSO, "/dashboards/national/sales", """{"date":"2026-10-04","zone_ids":[$z1,$z2]}""").obj()))   // asking for both is cut to the reach
        assertEquals(0, sold(post(11, Role.TSO, "/dashboards/national/sales", """{"date":"2026-10-04","zone_ids":[$z2]}""").obj()))
    }

    @Test
    fun fieldRolesAnonymousAndBadBodiesAreRefused() = app {
        assertEquals(HttpStatusCode.Forbidden, post(12, Role.AMO, "/dashboards/national/sales").status)
        assertEquals(HttpStatusCode.Forbidden, get(12, Role.AMO, "/dashboards/national/filter-options").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/dashboards/national/sales") { contentType(ContentType.Application.Json); setBody("{}") }.status)
        assertEquals(HttpStatusCode.BadRequest, post(10, Role.ANALYST, "/dashboards/national/sales", """{"date":"not-a-date"}""").status)
        assertEquals(HttpStatusCode.BadRequest, post(10, Role.ANALYST, "/dashboards/national/sales", """{"date":"2026-10-04","surprise":1}""").status)
        assertEquals(HttpStatusCode.BadRequest, post(10, Role.ANALYST, "/dashboards/national/breakdown", """{"measure":"memo","dimension":"outlet"}""").status)
    }

    @Test
    fun aBrandUserSeesTheProductCardsOfItsBrandsOnlyAndNothingElse() = app {
        val s = post(15, Role.TOP, "/dashboards/national/sales").obj()
        assertEquals(65, s["cards"]!!.jsonArray.map { it.jsonObject }.single { it["category_code"]!!.jsonPrimitive.content == "cigarette" }["total_sales"]!!.jsonPrimitive.content.toLong())
        val bd = post(15, Role.TOP, "/dashboards/national/breakdown", """{"filter":{"date":"2026-10-04"},"measure":"std","dimension":"brand"}""").obj()
        assertEquals(listOf("B1"), bd["items"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content })
        for (p in listOf("cpr", "final-submit", "login-submit", "geo-fencing", "ff-geo/pins")) assertEquals(HttpStatusCode.Forbidden, post(15, Role.TOP, "/dashboards/national/$p", if (p.endsWith("/pins")) pinsBody else """{"date":"2026-10-04"}""").status, p)
        assertEquals(HttpStatusCode.Forbidden, get(15, Role.TOP, "/dashboards/route-map/1").status)
        assertFalse(post(15, Role.TOP, "/dashboards/national/channel-std").bodyAsText().contains("BrandTwo"))
    }
}

package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachNode
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.contract.Role
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * tso_dashboard_day.sql aggregated by the real worker (every figure through the SQL the cards and the summary read), with the
 * callers of the TSO dashboard tests. A fresh database per test: several tests change the day.
 * Callers: 31 ANALYST (national), 32 TSO territory "Apsis" (Z1 + Z2; the screenshots' TSO), 33 TSO zone "Banani - Test" (Z1),
 * 34 TSO zone "Gulshan - Test" (Z2), 35 TSO holding only routes R1 and R2 (route-keyed), 36 TSO holding house "House A" (Z1),
 * 37 TSO zone "Exact Zone" (ZX), 38 BRAND user (Maxim), 39 AMO (zone Z1; no dashboard).
 */
abstract class TsoDayFixture {
    protected lateinit var fresh: FreshDb
    protected val day: LocalDate = LocalDate.parse("2026-10-25")
    protected val now: Instant = Instant.parse("2026-10-25T06:00:00Z")   // 12:00 Dhaka, the day in progress
    protected val clock = AronClock { now }
    protected val ids = mutableMapOf<String, Long>()
    protected val reaches = mutableMapOf<Long, Reach>()
    protected val all = NationalDashboardFilter(date = "2026-10-25")

    @BeforeEach
    fun fixtureSetUp() {
        fresh = FreshDb.create()
        fresh.dataSource.connection.use { c -> c.createStatement().use { it.execute(javaClass.classLoader.getResourceAsStream("tso_dashboard_day.sql")!!.bufferedReader().readText()) } }
        rebuild()
        fresh.db.jdbi.useHandle<Exception> { h ->
            for (t in listOf("zone", "territory", "house", "route")) {
                h.createQuery("SELECT code, id FROM app.$t").map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().forEach { (code, id) -> ids[code] = id }
            }
            h.createQuery("SELECT code, id FROM app.product_node WHERE level = 'brand'").map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().forEach { (code, id) -> ids[code] = id }
        }
        val z1 = id("Z1"); val z2 = id("Z2")
        reaches[31] = Reach(31, Role.ANALYST, day, true, emptySet(), emptySet(), false, emptyList())
        reaches[32] = Reach(32, Role.TSO, day, false, setOf(z1, z2), emptySet(), false, listOf(ReachNode("territory", id("T1"), "T1", "Apsis")))
        reaches[33] = Reach(33, Role.TSO, day, false, setOf(z1), emptySet(), false, listOf(ReachNode("zone", z1)))
        reaches[34] = Reach(34, Role.TSO, day, false, setOf(z2), emptySet(), false, listOf(ReachNode("zone", z2)))
        reaches[35] = Reach(35, Role.TSO, day, false, emptySet(), setOf(id("R1"), id("R2")), false, listOf(ReachNode("route", id("R1"), "R1", "Apsis Route"), ReachNode("route", id("R2"), "R2", "Apsis Route 2")))
        reaches[36] = Reach(36, Role.TSO, day, false, setOf(z1), emptySet(), false, listOf(ReachNode("house", id("H1"), "H1", "House A")))
        reaches[37] = Reach(37, Role.TSO, day, false, setOf(id("ZX")), emptySet(), false, listOf(ReachNode("zone", id("ZX"))))
        reaches[38] = Reach(38, Role.BRAND, day, true, emptySet(), emptySet(), false, emptyList())
        reaches[39] = Reach(39, Role.AMO, day, false, setOf(z1), emptySet(), false, listOf(ReachNode("zone", z1)))
    }

    @AfterEach
    fun fixtureTearDown() = fresh.close()

    protected fun id(code: String): Long = ids.getValue(code)

    /** Dimensions and every route-day of [day] again, as the worker does after a change. */
    protected fun rebuild() {
        fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }
        AggregationWorker(fresh.db, clock).also { w -> w.requestRebuild(day); w.runUntilIdle() }
    }

    protected fun sql(s: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(s) }
    protected fun long(s: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery(s).mapTo(Long::class.java).one() }

    protected fun config(overrides: Map<String, String> = emptyMap()) = object : ServerConfig {
        private val d = RegistryDefaults()
        override fun value(key: String): JsonElement = overrides[key]?.let(::JsonPrimitive) ?: if (key == "cfg.api.rl.user_per_min") JsonPrimitive(5000) else d.value(key)
        override fun configVersion() = 1L
    }

    protected fun national(cfg: Map<String, String> = emptyMap()) = NationalDashboardService(fresh.db, clock, config(cfg))

    /** A new summary service per read: its 5 s cache runs on the fixed clock. */
    protected fun summary(uid: Long): DashboardSummary = DashboardService(fresh.db, clock).summary(reaches.getValue(uid), null, null, day, day)

    protected fun caller(uid: Long): NationalCaller = NationalCaller(reaches.getValue(uid), if (uid == 38L) setOf(id("B-MAX")) else null)

    /** A Double percentage with two decimals as basis points (exact decimal, no float arithmetic). */
    protected fun bp(v: Double?): Long? = v?.let { BigDecimal(it.toString()).movePointRight(2).longValueExact() }

    // ---------------------------------------------------------------- HTTP harness: the national routes behind the real guard

    protected fun http(cfg: Map<String, String> = emptyMap(), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val config = config(cfg)
        val reach = ReachResolver { uid, _, _, _ -> reaches.getValue(uid) }
        application {
            installAronPlatform(PlatformContext(config = RegistryDefaults(), generation = { "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10" }))
            routing {
                route("/v1") {
                    nationalDashboardRoutes(NationalDeps(NationalDashboardService(fresh.db, clock, config), reach, TestTokens.guard(config), BrandScopeResolver { uid, _ -> if (uid == 38L) setOf(id("B-MAX")) else null }, clock))
                }
            }
        }
        block()
    }

    protected suspend fun ApplicationTestBuilder.post(uid: Long, path: String, json: String = """{"date":"2026-10-25"}"""): HttpResponse =
        client.post("/v1$path") { bearerAuth(TestTokens.web(uid, reaches.getValue(uid).role)); contentType(ContentType.Application.Json); setBody(json) }

    protected suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
}

/**
 * D-TSO-MAN-09 (CR-7, s5 B2 "parity fixture day"): on one day holding AMO, manual and dummy routes, free and DRP lines, an
 * abandoned visit, an excused route and a zone-scoped holiday, the summary (`/v1/dashboards/summary`, the web portal) and the
 * national cards the TSO app reads (the `/v1/dashboards/national` operations) print the same Sales, CPR, Login and Geo figures for every kind
 * of caller; the Not Logged In / Not Uploaded lists hold target - successful_login and successful_login - submitted routes.
 */
class TsoDashboardParityTest : TsoDayFixture() {

    private fun assertParity(uid: Long) {
        val c = caller(uid)
        val nat = national()
        val s = summary(uid)
        val sales = nat.sales(c, all)
        val qty = s.by_category.associate { it.category_code to it.qty_base }
        for (card in sales.cards) assertEquals(card.total_sales, qty[card.category_code] ?: 0L, "$uid sales ${card.category_code}")
        val cpr = nat.cpr(c, all)
        assertEquals(cpr.target_outlets to cpr.successful_calls, s.kpis.target_outlets to s.kpis.successful_calls, "$uid CPR")
        assertEquals(cpr.cpr_pct_bp?.toLong(), bp(s.kpis.strike_rate_pct), "$uid CPR %")
        val ls = nat.loginSubmit(c, all)
        assertEquals(Triple(ls.target_routes, ls.successful_login, ls.submitted), Triple(s.kpis.target_routes, s.kpis.logged_in_routes, s.kpis.sales_submitted_routes), "$uid login")
        assertEquals(ls.login_pct_bp?.toLong(), bp(s.kpis.login_pct), "$uid login %")
        assertEquals(ls.submit_pct_bp?.toLong(), bp(s.kpis.submit_pct_of_logged_in), "$uid submit % of logged in")
        val geo = nat.geoFencing(c, all)
        assertEquals(geo.geo_pct_bp?.toLong(), bp(s.kpis.geo_valid_pct), "$uid geo")
        val fs = nat.finalSubmit(c, all)
        assertEquals(fs.service_zones to fs.final_submitted, s.kpis.zones_with_target_routes to s.kpis.zones_final_submitted, "$uid final submit")
        // The lists behind the Login / Submit card hold exactly the card's differences, each route once.
        val notLogged = nat.loginSubmitUsers(c, "not_logged_in", all)
        val notUploaded = nat.loginSubmitUsers(c, "not_uploaded", all)
        assertEquals(ls.target_routes - ls.successful_login, notLogged.route_count, "$uid Not Logged In")
        assertEquals(ls.successful_login - ls.submitted, notUploaded.route_count, "$uid Not Uploaded")
        assertEquals(notLogged.route_count, notLogged.items.map { it.route_id }.distinct().size)
        assertEquals(notUploaded.route_count, notUploaded.items.map { it.route_id }.distinct().size)
    }

    @Test
    fun theFixtureHoldsEveryTrapTheParityMustSurvive() {
        // Without the national route set, the free lines and the completed-call rule the two would differ on this day.
        assertEquals(8L, long("SELECT sum(target_routes) FROM dw.agg_daily_zone WHERE business_date = '2026-10-25' AND zone_id IN (SELECT id FROM app.zone WHERE code IN ('Z1','Z2'))"),
            "the zone rollup counts the AMO, manual and dummy routes as target routes (the excused one not)")
        assertEquals(30L, long("SELECT sum(free_qty_base) FROM dw.agg_daily_route_sku WHERE business_date = '2026-10-25'"), "a promo and a DRP line of 20 + 10 sticks")
        assertEquals(9L, long("SELECT sum(visits) FROM dw.agg_daily_route WHERE business_date = '2026-10-25' AND route_id IN (SELECT id FROM app.route WHERE code <> 'RX')"))
        assertEquals(4L, long("SELECT sum(geo_valid_visits) FROM dw.agg_daily_route WHERE business_date = '2026-10-25' AND route_id IN (SELECT id FROM app.route WHERE code <> 'RX')"))
        assertEquals(1L, long("SELECT count(*) FROM app.visit WHERE business_date = '2026-10-25' AND outcome_code = 'abandoned'"))
        assertEquals(1L, long("SELECT count(*) FROM dw.agg_daily_route WHERE business_date = '2026-10-25' AND exception_approved"))
        assertEquals(24, national().sales(caller(33), all).working_days, "the zone holiday of Banani - Test counts at its zone")
        assertEquals(25, national().sales(caller(32), all).working_days, "and not at its territory")
    }

    @Test
    fun theScreenshotTsoSeesTheSameFiguresOnBothReads() {
        val s = summary(32)
        // Screenshots 6 and 7: Sales 40 / 2991, CPR 119 / 3, Login 5 / 2, Bikroy Joma 1, Geo 4 of 4.
        assertEquals(mapOf("cigarette" to 40L, "bidi" to 2991L), s.by_category.associate { it.category_code to it.qty_base })
        assertEquals(listOf(119, 3), listOf(s.kpis.target_outlets, s.kpis.successful_calls))
        assertEquals(listOf(5, 2, 1), listOf(s.kpis.target_routes, s.kpis.logged_in_routes, s.kpis.sales_submitted_routes))
        assertEquals(100.0, s.kpis.geo_valid_pct)
        assertEquals("2.000", s.by_category.single { it.category_code == "cigarette" }.qty_entered, "40 sticks are 2 packets of 20: sold only, the 30 free sticks left out")
        assertParity(32)
    }

    @Test
    fun everyKindOfCallerSeesTheSameFiguresOnBothReads() {
        for (uid in listOf(31L, 32L, 33L, 34L, 35L, 36L, 37L)) assertParity(uid)
        // The route-only TSO (R1, R2) reads its routes: 2 target routes, both logged in, 1 submitted, 70 target outlets, 3 calls.
        val routes = summary(35)
        assertEquals(listOf(2, 2, 1, 70, 3), listOf(routes.kpis.target_routes, routes.kpis.logged_in_routes, routes.kpis.sales_submitted_routes, routes.kpis.target_outlets, routes.kpis.successful_calls))
        assertEquals(3, national().cpr(caller(35), all).successful_calls)
        assertEquals(listOf("Banani - Test"), national().filterOptions(caller(35)).zones.map { it.name }, "the route-only TSO's filter tree is its routes' zone")
    }

    @Test
    fun theSummaryChildrenAddUpToTheNode() {
        val s = summary(32)
        assertEquals(s.kpis.target_routes, s.children.sumOf { it.kpis.target_routes })
        assertEquals(s.kpis.successful_calls, s.children.sumOf { it.kpis.successful_calls })
        assertEquals(s.kpis.zones_with_target_routes, s.children.sumOf { it.kpis.zones_with_target_routes ?: 0 })
        val z1 = summary(33)
        assertEquals(setOf("R1", "R2", "R3", "R4", "R5", "R6").map(::id).toSet(), z1.children.map { it.node.id }.toSet())
        assertEquals(listOf(1, 1, 1, 0, 0, 0), listOf("R1", "R2", "R3", "R4", "R5", "R6").map { r -> z1.children.single { it.node.id == id(r) }.kpis.target_routes },
            "a route child is a target route only in the national route set")
        assertNotEquals(0L, z1.children.single { it.node.id == id("R4") }.kpis.gross_mtk, "the AMO route still shows its sales")
    }

    @Test
    fun theTsoRoleGetsEveryNationalCardTheAppReadsOverHttp() = http {
        val paths = listOf("sales", "cpr", "channel-std", "channel-calls", "brand-cpr", "login-submit", "segment-share", "geo-fencing", "final-submit")
        for (uid in listOf(32L, 35L, 36L)) {
            for (p in paths) assertEquals(200, post(uid, "/dashboards/national/$p").status.value, "$uid $p")
            assertEquals(200, post(uid, "/dashboards/national/login-submit/users?tab=not_logged_in").status.value, "$uid users")
            assertEquals(200, client.get("/v1/dashboards/national/filter-options") { bearerAuth(TestTokens.web(uid, Role.TSO)) }.status.value, "$uid filter-options")
        }
        assertEquals(403, post(39, "/dashboards/national/sales").status.value, "an AMO has no dashboard")
        assertTrue(post(35, "/dashboards/national/login-submit").obj().containsKey("submit_of_target_pct_bp"))
    }
}

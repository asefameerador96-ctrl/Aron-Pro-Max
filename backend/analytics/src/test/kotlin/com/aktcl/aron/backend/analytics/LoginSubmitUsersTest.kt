package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachNode
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Not Logged In / Not Uploaded lists (D-TSO-MAN-12, screenshot 8, contract 1.10.0 `queryNationalLoginSubmitUsers`) on
 * tso_dashboard_day.sql through the SQL: rows are (route, user) over the card's target routes; per route the route-day SR (acting
 * SR first, vacant = "SR Not Set") then every active AMO whose scope covers the route's zone on the day, by username, following the
 * route's state; Dep Name is the zone name; at most 2000 rows with `truncated`, `route_count` counting every route.
 */
class LoginSubmitUsersTest : TsoDayFixture() {
    private data class Row(val route: String, val dep: String, val username: String?, val display: String?, val role: String)

    private fun rows(uid: Long, tab: String): Pair<NationalLoginSubmitUsers, List<Row>> {
        val u = national().loginSubmitUsers(caller(uid), tab, all)
        return u to u.items.map { Row(it.route_name, it.dep_name, it.username, it.display_name, it.role) }
    }

    @Test
    fun notLoggedInFansOutEachRouteToItsSrAndTheAmosOfItsZoneInOrder() {
        val (u, r) = rows(32, "not_logged_in")
        assertEquals("not_logged_in", u.tab); assertEquals(3, u.route_count); assertFalse(u.truncated)
        val z1 = "Banani - Test"; val z2 = "Gulshan - Test"
        assertEquals(listOf(
            Row("Apsis Route 3", z1, "sr334003", "SR - Testing Banani 3", "SR"),
            Row("Apsis Route 3", z1, "amo6334", "AMO-6334", "AMO"), Row("Apsis Route 3", z1, "amo63342", "AMO-63342", "AMO"), Row("Apsis Route 3", z1, "amo7000", "AMO-7000", "AMO"),
            Row("Route 8", z2, "sr334008", "SR Route 8", "SR"), Row("Route 8", z2, "amo7000", "AMO-7000", "AMO"), Row("Route 8", z2, "amo9004", "AMO-9004", "AMO"),
            Row("Route 9", z2, null, null, "SR"), Row("Route 9", z2, "amo7000", "AMO-7000", "AMO"), Row("Route 9", z2, "amo9004", "AMO-9004", "AMO"),
        ), r)
        val vacant = u.items.single { it.route_name == "Route 9" && it.role == "SR" }
        assertEquals(null, vacant.user_id, "a vacant route: one SR row without a user (\"SR Not Set\")")
        assertEquals(id("Z2"), vacant.zone_id); assertEquals(id("R9"), vacant.route_id); assertEquals("R9", vacant.route_code)
        // Never listed: a disabled AMO, a scope that ended or starts later, a TSO's scope; and no excused, AMO, manual or dummy route.
        assertTrue(u.items.none { it.username in setOf("amo9001", "amo9002", "amo9003", "tso9100") })
        assertTrue(u.items.none { it.route_name in setOf("Excused Route", "AMO Route", "Manual Route", "Dummy Route") })
    }

    @Test
    fun notUploadedListsTheStandInSrAndTheAmosWhateverTheirOwnLogin() {
        val (u, r) = rows(32, "not_uploaded")
        assertEquals(1, u.route_count)
        val z1 = "Banani - Test"
        assertEquals(listOf(
            Row("Apsis Route 2", z1, "srcover1", "SR Cover", "SR"),
            Row("Apsis Route 2", z1, "amo6334", "AMO-6334", "AMO"), Row("Apsis Route 2", z1, "amo63342", "AMO-63342", "AMO"), Row("Apsis Route 2", z1, "amo7000", "AMO-7000", "AMO"),
        ), r, "the acting SR of the route-day; amo6334 logged in on its own AMO route and is still listed (screenshot 8: in both tabs)")
        // The card's differences: target 5 - successful login 2 = 3 routes; successful login 2 - submitted 1 = 1 route.
        val ls = national().loginSubmit(caller(32), all)
        assertEquals(ls.target_routes - ls.successful_login, national().loginSubmitUsers(caller(32), "not_logged_in", all).route_count)
        assertEquals(ls.successful_login - ls.submitted, u.route_count)
    }

    @Test
    fun aRouteOnlyAndAHouseTsoReadTheirOwnRoutes() {
        assertEquals(listOf("Apsis Route 2"), rows(35, "not_uploaded").second.map { it.route }.distinct(), "R1 submitted, R2 did not")
        assertEquals(0, rows(35, "not_logged_in").first.route_count)
        assertEquals(listOf("Apsis Route 3"), rows(36, "not_logged_in").second.map { it.route }.distinct(), "house A holds Banani - Test only")
    }

    @Test
    fun theTabIsRequiredAndCheckedAndABrandUserIsRefused() {
        for (tab in listOf(null, "logged_in", "")) {
            val p = assertFailsWith<ApiProblem> { national().loginSubmitUsers(caller(32), tab, all) }
            assertEquals(ProblemCode.ERR_VALIDATION, p.code, "$tab"); assertEquals("query.tab", p.errors.single().pointer)
        }
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { national().loginSubmitUsers(caller(38), "not_logged_in", all) }.code)
    }

    @Test
    fun aLongerTabIsCutAt2000RowsAndStillCountsEveryRoute() {
        // 20 vacant routes in "Trunc Zone" covered by 99 AMOs (20 x 100 = 2000 rows) and one vacant route in "Trunc Zone 2" (the 2001st).
        sql("""
            INSERT INTO app.zone (code, name, territory_id) SELECT d.c, d.n, t.id FROM (VALUES ('ZT', 'Trunc Zone'), ('ZT2', 'Trunc Zone 2')) d(c, n), app.territory t WHERE t.code = 'T9';
            INSERT INTO app.cluster (zone_id, name) SELECT id, 'C ' || code FROM app.zone WHERE code IN ('ZT', 'ZT2');
            INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask)
              SELECT 'T' || lpad(n::text, 2, '0'), 'T-Route ' || lpad(n::text, 2, '0'), z.id, 'sr', 'daily', 127 FROM generate_series(1, 20) n, app.zone z WHERE z.code = 'ZT';
            INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'TZZ', 'ZZ Route', id, 'sr', 'daily', 127 FROM app.zone WHERE code = 'ZT2';
            INSERT INTO app.route_day (route_id, business_date, planned, state, target_outlets)
              SELECT r.id, DATE '2026-10-25', true, 'not_started', 1 FROM app.route r JOIN app.zone z ON z.id = r.zone_id WHERE z.code IN ('ZT', 'ZT2');
            INSERT INTO app.app_user (username, full_name, role, must_change_password) SELECT 'amot' || lpad(n::text, 3, '0'), 'AMO T' || n, 'AMO', false FROM generate_series(1, 99) n;
            INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from)
              SELECT u.id, 'zone', z.id, DATE '2026-09-01' FROM app.app_user u, app.zone z WHERE u.username LIKE 'amot%' AND z.code = 'ZT';
        """.trimIndent())
        rebuild()
        val zt = long("SELECT id FROM app.zone WHERE code = 'ZT'"); val zt2 = long("SELECT id FROM app.zone WHERE code = 'ZT2'")
        reaches[40] = Reach(40, Role.TSO, day, false, setOf(zt, zt2), emptySet(), false, listOf(ReachNode("zone", zt), ReachNode("zone", zt2)))
        reaches[41] = Reach(41, Role.TSO, day, false, setOf(zt), emptySet(), false, listOf(ReachNode("zone", zt)))
        val cut = national().loginSubmitUsers(caller(40), "not_logged_in", all)
        assertTrue(cut.truncated); assertEquals(2000, cut.items.size); assertEquals(21, cut.route_count, "route_count counts the cut route too")
        assertEquals("T-Route 20" to "amot099", cut.items.last().route_name to cut.items.last().username, "the first 2000 rows in order")
        assertTrue(cut.items.none { it.route_name == "ZZ Route" })
        val whole = national().loginSubmitUsers(caller(41), "not_logged_in", all)
        assertFalse(whole.truncated); assertEquals(2000, whole.items.size); assertEquals(20, whole.route_count)
        assertEquals(null, whole.items.first().username); assertEquals("amot001", whole.items[1].username)
    }

    @Test
    fun overHttpTheScopeComesFromTheTokenAndTheClientCannotWidenIt() = http {
        val path = "/dashboards/national/login-submit/users"
        val own = post(34, "$path?tab=not_logged_in").obj()
        assertEquals(setOf("Route 8", "Route 9"), own["items"]!!.jsonArray.map { it.jsonObject["route_name"]!!.jsonPrimitive.content }.toSet())
        assertEquals("2", own["route_count"]!!.jsonPrimitive.content); assertEquals("false", own["truncated"]!!.jsonPrimitive.content)
        val vacant = own["items"]!!.jsonArray.map { it.jsonObject }.single { it["route_name"]!!.jsonPrimitive.content == "Route 9" && it["role"]!!.jsonPrimitive.content == "SR" }
        for (k in listOf("user_id", "username", "display_name")) assertEquals(JsonNull, vacant[k], "$k is an explicit null")
        val widened = post(34, "$path?tab=not_logged_in", """{"date":"2026-10-25","zone_ids":[${id("Z1")},${id("Z2")}]}""").obj()
        assertEquals(own["items"], widened["items"], "naming Banani - Test is cut to the reach")
        val routes = post(35, "$path?tab=not_uploaded").obj()
        assertEquals(setOf(id("R2").toString()), routes["items"]!!.jsonArray.map { it.jsonObject["route_id"]!!.jsonPrimitive.content }.toSet())
        assertEquals(400, post(32, path).status.value, "tab is required")
        assertEquals(400, post(32, "$path?tab=everyone").status.value)
        assertEquals(403, post(38, "$path?tab=not_logged_in").status.value, "BRAND")
        assertEquals(403, post(39, "$path?tab=not_logged_in").status.value, "AMO")
        val t = post(32, "$path?tab=not_uploaded").obj()
        for (k in listOf("date", "till_time", "as_of", "last_updated_at", "tab", "route_count", "truncated", "items")) assertTrue(k in t, k)
    }
}

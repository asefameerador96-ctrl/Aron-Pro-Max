package com.aktcl.aron.backend.analytics

import com.aktcl.aron.contract.Role
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.statement.bodyAsText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A report's geography selector and its SKU and route placement are as they were on the report's dates (D-REPORTS-NODEASWAS-01): after Z2
 * moves to a new territory, route-std for the old territory on the fixture date still lists both zones' routes with the same figures, and the
 * new territory has no members on that date (403).
 */
class AsWasReportScopeTest : ReportFixture() {
    /** The status first, so a refusal fails readably instead of as a missing field. */
    private suspend fun io.ktor.client.statement.HttpResponse.okResult(): kotlinx.serialization.json.JsonObject { assertEquals(HttpStatusCode.OK, status, "the report answered ${status}"); return result() }

    @BeforeEach fun noProbeCache() { AsWasSql.probeTtlMs = 0 }
    @AfterEach fun probeCache() { AsWasSql.probeTtlMs = 20_000 }

    private fun exec(sql: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(sql); Aggregator.refreshDimensions(it); DashboardSummaries.refreshDimensions(it) }
    private fun id(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery(sql).mapTo(Long::class.java).one() }

    @Test
    fun anOldTerritorySelectorAndRouteStdReadTheDayAsItWas() = app {
        val t1 = id("SELECT id FROM app.territory WHERE code = 'T1'")
        val sel = body(""","geo":{"territory":[$t1]}""")
        val keys = listOf("route-std", "route-memo")
        val before = keys.associateWith { run(10, Role.ANALYST, it, sel).okResult() }
        assertEquals(4, before.getValue("route-std").rows().size); assertEquals(63_000, before.getValue("route-std").total("gross_mtk"))
        exec("INSERT INTO app.wing (code, name) VALUES ('W2','Wing2')")
        exec("INSERT INTO app.division (code, name, wing_id) VALUES ('D2','Div2', (SELECT id FROM app.wing WHERE code = 'W2'))")
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('T2','Terr2', (SELECT id FROM app.division WHERE code = 'D2'))")
        exec("UPDATE app.zone SET territory_id = (SELECT id FROM app.territory WHERE code = 'T2') WHERE code = 'Z2'")
        val t2 = id("SELECT id FROM app.territory WHERE code = 'T2'")
        for (key in keys) {
            val after = run(10, Role.ANALYST, key, sel).okResult()
            assertEquals(before.getValue(key).rows(), after.rows(), "$key: the old territory still holds both zones' routes and figures on that date")
            assertEquals(63_000, after.total("gross_mtk"), key)
        }
        val gone = run(10, Role.ANALYST, "route-std", body(""","geo":{"territory":[$t2]}"""))
        assertEquals(HttpStatusCode.OK, gone.status, "the new territory is real and inside the reach but had no zone on the report's date: 200, no rows (D-LEAD-REPORT-SELECTOR-01)")
        assertEquals(0, gone.okResult().rows().size)
        assertEquals(HttpStatusCode.Forbidden, run(10, Role.ANALYST, "route-std", body(""","geo":{"territory":[999999]}""")).status, "an id that is no territory is refused")
        // A TSO whose own zone is in T2 today: T2 is in their reach, it had no zone on the date: 200, no rows. A TSO of another zone: refused, with the answer of an unknown id.
        val joined = run(14, Role.TSO, "route-std", body(""","geo":{"territory":[$t2]}"""))
        assertEquals(HttpStatusCode.OK, joined.status, "in the reach today, empty for the period")
        assertEquals(0, joined.okResult().rows().size)
        val outOfReach = run(11, Role.TSO, "route-std", body(""","geo":{"territory":[$t2]}"""))
        val unknown = run(11, Role.TSO, "route-std", body(""","geo":{"territory":[999999]}"""))
        assertEquals(HttpStatusCode.Forbidden, outOfReach.status); assertEquals(HttpStatusCode.Forbidden, unknown.status)
        assertEquals(unknown.problemBody(), outOfReach.problemBody(), "a territory out of reach and an unknown id answer alike")
        // The reach still bounds it: a TSO of zone 1 gets the old territory cut to the zone they reach (the zone that left is not held against them)...
        val tso = run(11, Role.TSO, "route-std", sel)
        assertEquals(HttpStatusCode.OK, tso.status)
        assertEquals(run(11, Role.TSO, "route-std").okResult().rows(), tso.okResult().rows())
        assertTrue(tso.okResult().rows().size < before.getValue("route-std").rows().size)
        // ...and a territory that holds, today, a zone outside the reach is refused.
        assertEquals(HttpStatusCode.Forbidden, run(14, Role.TSO, "route-std", sel).status, "zone 2's TSO is not in the old territory any more")
    }

    @Test
    fun aRouteMovedToAnotherZoneKeepsItsOldDayInRouteStdForTheOldZone() = app {
        val sel = body(""","geo":{"zone":[$z1]}""")
        val before = run(10, Role.ANALYST, "route-std", sel).okResult()
        val z2Before = run(10, Role.ANALYST, "route-std", body(""","geo":{"zone":[$z2]}""")).okResult()
        assertTrue(before.rows().any { it.str("route_code") == "R2" } && before.total("gross_mtk") > 0)
        exec("UPDATE app.route SET zone_id = (SELECT id FROM app.zone WHERE code = 'Z2') WHERE code = 'R2'")
        assertEquals(before.rows(), run(10, Role.ANALYST, "route-std", sel).okResult().rows(), "Z1 lost the moved route's old day")
        assertEquals(z2Before.rows(), run(10, Role.ANALYST, "route-std", body(""","geo":{"zone":[$z2]}""")).okResult().rows(), "Z2 gained it")
    }

    @Test
    fun aRouteSelectorUsesTheZonesTheRouteHadOnTheDatesAndRefusesOnScopeOnly() = app {
        val r3 = id("SELECT id FROM app.route WHERE code = 'R3'")
        val sel = body(""","geo":{"route":[$r3]}""")
        val before = run(14, Role.TSO, "route-std", sel)
        assertEquals(HttpStatusCode.OK, before.status, "R3 is zone 2's route")
        assertEquals(setOf("R3"), before.okResult().rows().map { it.str("route_code") }.toSet())
        // Out of the caller's reach: refused, with the very answer of an unknown id (no existence leak).
        val outOfReach = run(11, Role.TSO, "route-std", sel)
        val unknown = run(11, Role.TSO, "route-std", body(""","geo":{"route":[999999]}"""))
        assertEquals(HttpStatusCode.Forbidden, outOfReach.status)
        assertEquals(HttpStatusCode.Forbidden, unknown.status)
        assertEquals(unknown.problemBody(), outOfReach.problemBody(), "a route out of reach and an unknown id answer alike")
        exec("UPDATE app.route SET zone_id = $z1 WHERE code = 'R3'")   // moved to zone 1 today
        // The old day is still zone 2's: its TSO keeps the route for that date.
        assertEquals(before.okResult().rows(), run(14, Role.TSO, "route-std", sel).okResult().rows())
        // Zone 1's TSO now reaches the route (it is in their zone today) but it was not in their zone during the period: 200, no rows, not 403.
        val joined = run(11, Role.TSO, "route-std", sel)
        assertEquals(HttpStatusCode.OK, joined.status)
        assertEquals(0, joined.okResult().rows().size, "in reach, not in the selected zones during the period")
        // The empty answer is empty for the reports that read route-less rows too (coalesce(route_id, -1)): an outlet with no route in zone 1 is not R3's.
        exec("UPDATE app.outlet SET route_id = NULL WHERE code = 'O1'")
        assertEquals(0, run(10, Role.ANALYST, "retailer-list", body(""","geo":{"zone":[$z1],"route":[$r3]}""")).okResult().rows().size, "no route-less outlet in the empty answer")
        assertEquals(0, run(11, Role.TSO, "retailer-list", sel).okResult().rows().size)
        // A caller with the whole reach selecting zone 1 together with that route: 200, no rows (the route was zone 2's then).
        val nat = run(10, Role.ANALYST, "route-std", body(""","geo":{"zone":[$z1],"route":[$r3]}"""))
        assertEquals(HttpStatusCode.OK, nat.status)
        assertEquals(0, nat.okResult().rows().size)
        assertEquals(setOf("R3"), run(10, Role.ANALYST, "route-std", sel).okResult().rows().map { it.str("route_code") }.toSet())
        // Zone 2's TSO keeps its route and loses nothing else; a route from another territory or an unknown id still refuses the lot.
        assertEquals(HttpStatusCode.Forbidden, run(10, Role.ANALYST, "route-std", body(""","geo":{"route":[$r3,999999]}""")).status, "an unknown id refuses the lot")
        // A route with no history row at all (a bulk load with triggers off) is judged on the zone it has now, not refused for everyone.
        exec("DELETE FROM app.route_zone_history WHERE route_id = $r3")
        assertEquals(setOf("R3"), run(10, Role.ANALYST, "route-std", sel).okResult().rows().map { it.str("route_code") }.toSet(), "judged on the zone it has now: selected, rows read")
        assertEquals(HttpStatusCode.OK, run(11, Role.TSO, "route-std", sel).status, "R3 is zone 1's today")
        assertEquals(HttpStatusCode.Forbidden, run(14, Role.TSO, "route-std", sel).status, "and not zone 2's")
    }

    /** The refusal as a client sees it, minus what differs per request. */
    private suspend fun io.ktor.client.statement.HttpResponse.problemBody(): Pair<String?, String?> =
        kotlinx.serialization.json.Json.parseToJsonElement(this.bodyAsText()).let { it.jsonObject["code"]?.jsonPrimitive?.content to it.jsonObject["detail"]?.jsonPrimitive?.content }

    private fun onDate(date: String, extra: String) = """{"period":{"date":"$date"},"output":{"format":"json","page_size":500}$extra}"""

    @Test
    fun severalSelectedNodesAreCheckedEachByItself() = app {
        val t1 = id("SELECT id FROM app.territory WHERE code = 'T1'")
        exec("INSERT INTO app.wing (code, name) VALUES ('W2','Wing2')")
        exec("INSERT INTO app.division (code, name, wing_id) VALUES ('D2','Div2', (SELECT id FROM app.wing WHERE code = 'W2'))")
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('TX','TerrX', (SELECT id FROM app.division WHERE code = 'D2'))")
        val tx = id("SELECT id FROM app.territory WHERE code = 'TX'")
        exec("UPDATE app.zone SET territory_id = $tx WHERE code = 'Z2'")
        exec("UPDATE app.zone SET territory_id = $t1 WHERE code = 'Z2'")   // Z2 goes to TX and back: TX holds no zone today, and held one only during the move
        // Zone 1's TSO: T1 holds zone 2 again today (not theirs); a territory that holds nothing of theirs, with or without a valid first id, is refused like an unknown id.
        assertEquals(HttpStatusCode.Forbidden, run(11, Role.TSO, "route-std", body(""","geo":{"territory":[$t1]}""")).status)
        assertEquals(HttpStatusCode.Forbidden, run(11, Role.TSO, "route-std", body(""","geo":{"territory":[$tx]}""")).status)
        assertEquals(HttpStatusCode.Forbidden, run(11, Role.TSO, "route-std", body(""","geo":{"territory":[$t1,$tx]}""")).status)
        assertEquals(HttpStatusCode.Forbidden, run(11, Role.TSO, "route-std", body(""","geo":{"territory":[$t1,999999]}""")).status)
        // Each selected node by itself: zone 1 is theirs, zone 2 is not, so asking for both is refused (and zone 1 alone is fine).
        assertEquals(HttpStatusCode.OK, run(11, Role.TSO, "route-std", body(""","geo":{"zone":[$z1]}""")).status)
        assertEquals(HttpStatusCode.Forbidden, run(11, Role.TSO, "route-std", body(""","geo":{"zone":[$z1,$z2]}""")).status)
    }

    @Test
    fun aNodeThatNeverHeldTheZoneOnTheDateIsRefusedAfterTwoMoves() = app {
        // Z2: T1 until 2026-10-05, T2 from then until today, T3 today. On 2026-10-05 T3 had no member (the zone was in T2).
        exec("INSERT INTO app.wing (code, name) VALUES ('W2','Wing2')")
        exec("INSERT INTO app.division (code, name, wing_id) VALUES ('D2','Div2', (SELECT id FROM app.wing WHERE code = 'W2'))")
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('T2','Terr2', (SELECT id FROM app.division WHERE code = 'D2'))")
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('T3','Terr3', (SELECT id FROM app.division WHERE code = 'D2'))")
        val t2 = id("SELECT id FROM app.territory WHERE code = 'T2'"); val t3 = id("SELECT id FROM app.territory WHERE code = 'T3'")
        exec("UPDATE app.zone SET territory_id = $t2 WHERE code = 'Z2'")
        exec("UPDATE app.zone SET territory_id = $t3 WHERE code = 'Z2'")
        // Two moves on one day collapse into one period, so the middle period is written the way the history would have it had the moves been days apart.
        exec("UPDATE app.zone_history SET valid_to = DATE '2026-10-05' WHERE zone_id = $z2 AND valid_to IS NOT NULL")
        exec("INSERT INTO app.zone_history (zone_id, territory_id, house_id, status, valid_from, valid_to) SELECT zone_id, $t2, house_id, status, DATE '2026-10-05', valid_from FROM app.zone_history WHERE zone_id = $z2 AND territory_id = $t3")
        // T2 and its division existed from 10-05 too (the chain is the overlap of the zone, territory and division periods).
        exec("UPDATE app.territory_history SET valid_from = DATE '2026-10-05' WHERE territory_id = $t2")
        exec("UPDATE app.division_history SET valid_from = DATE '2026-10-05' WHERE division_id = (SELECT division_id FROM app.territory WHERE id = $t2)")
        assertEquals(HttpStatusCode.OK, run(10, Role.ANALYST, "route-std", onDate("2026-10-05", ""","geo":{"territory":[$t2]}""")).status, "T2 held the zone on 10-05")
        assertEquals(0, run(10, Role.ANALYST, "route-std", onDate("2026-10-05", ""","geo":{"territory":[$t3]}""")).okResult().rows().size, "T3 had no member on 10-05: 200, no rows")
    }

    @Test
    fun aParentAndAChildSelectorTogetherAreAsWasToo() = app {
        // Z2: T1 until 10-05, T2 (under W2) from 10-05 until 10-20, back in T1 from then on. On 10-05 W1 held only Z1 while T1 holds both zones today.
        exec("INSERT INTO app.wing (code, name) VALUES ('W2','Wing2')")
        exec("INSERT INTO app.division (code, name, wing_id) VALUES ('D2','Div2', (SELECT id FROM app.wing WHERE code = 'W2'))")
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('T2','Terr2', (SELECT id FROM app.division WHERE code = 'D2'))")
        val t1 = id("SELECT id FROM app.territory WHERE code = 'T1'"); val t2 = id("SELECT id FROM app.territory WHERE code = 'T2'"); val w1 = id("SELECT id FROM app.wing WHERE code = 'W1'")
        exec("UPDATE app.zone SET territory_id = $t2 WHERE code = 'Z2'")
        exec("UPDATE app.zone SET territory_id = $t1 WHERE code = 'Z2'")
        exec("DELETE FROM app.zone_history WHERE zone_id = $z2")
        exec("INSERT INTO app.zone_history (zone_id, territory_id, house_id, status, valid_from, valid_to) SELECT $z2, $t1, house_id, status, NULL, DATE '2026-10-05' FROM app.zone WHERE id = $z2")
        exec("INSERT INTO app.zone_history (zone_id, territory_id, house_id, status, valid_from, valid_to) SELECT $z2, $t2, house_id, status, DATE '2026-10-05', DATE '2026-10-20' FROM app.zone WHERE id = $z2")
        exec("INSERT INTO app.zone_history (zone_id, territory_id, house_id, status, valid_from, valid_to) SELECT $z2, $t1, house_id, status, DATE '2026-10-20', NULL FROM app.zone WHERE id = $z2")
        exec("UPDATE app.territory_history SET valid_from = DATE '2026-10-05' WHERE territory_id = $t2")
        exec("UPDATE app.division_history SET valid_from = DATE '2026-10-05' WHERE division_id = (SELECT division_id FROM app.territory WHERE id = $t2)")
        val both = run(10, Role.ANALYST, "route-std", onDate("2026-10-05", ""","geo":{"wing":[$w1],"territory":[$t1]}"""))
        assertEquals(HttpStatusCode.OK, both.status, "a redundant parent selector must not turn the child's 200 into a 403")
        assertEquals(run(10, Role.ANALYST, "route-std", onDate("2026-10-05", ""","geo":{"territory":[$t1]}""")).okResult().rows(), both.okResult().rows())
        // The child selector is checked against the zones the parent left: T2 sat under W2 on that date, so wing W1 with territory T2 selects nothing.
        assertEquals(0, run(10, Role.ANALYST, "route-std", onDate("2026-10-05", ""","geo":{"wing":[$w1],"territory":[$t2]}""")).okResult().rows().size)
    }
}

package com.aktcl.aron.backend.analytics

import com.aktcl.aron.contract.Role
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A report's geography selector and its SKU and route placement are as they were on the report's dates (D-REPORTS-NODEASWAS-01): after Z2
 * moves to a new territory, route-std for the old territory on the fixture date still lists both zones' routes with the same figures, and the
 * new territory has no members on that date (403).
 */
class AsWasReportScopeTest : ReportFixture() {
    @BeforeEach fun noProbeCache() { AsWasSql.probeTtlMs = 0 }
    @AfterEach fun probeCache() { AsWasSql.probeTtlMs = 20_000 }

    private fun exec(sql: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(sql); Aggregator.refreshDimensions(it); DashboardSummaries.refreshDimensions(it) }
    private fun id(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery(sql).mapTo(Long::class.java).one() }

    @Test
    fun anOldTerritorySelectorAndRouteStdReadTheDayAsItWas() = app {
        val t1 = id("SELECT id FROM app.territory WHERE code = 'T1'")
        val sel = body(""","geo":{"territory":[$t1]}""")
        val keys = listOf("route-std", "route-memo")
        val before = keys.associateWith { run(10, Role.ANALYST, it, sel).result() }
        assertEquals(4, before.getValue("route-std").rows().size); assertEquals(63_000, before.getValue("route-std").total("gross_mtk"))
        exec("INSERT INTO app.wing (code, name) VALUES ('W2','Wing2')")
        exec("INSERT INTO app.division (code, name, wing_id) VALUES ('D2','Div2', (SELECT id FROM app.wing WHERE code = 'W2'))")
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('T2','Terr2', (SELECT id FROM app.division WHERE code = 'D2'))")
        exec("UPDATE app.zone SET territory_id = (SELECT id FROM app.territory WHERE code = 'T2') WHERE code = 'Z2'")
        val t2 = id("SELECT id FROM app.territory WHERE code = 'T2'")
        for (key in keys) {
            val after = run(10, Role.ANALYST, key, sel).result()
            assertEquals(before.getValue(key).rows(), after.rows(), "$key: the old territory still holds both zones' routes and figures on that date")
            assertEquals(63_000, after.total("gross_mtk"), key)
        }
        val gone = run(10, Role.ANALYST, "route-std", body(""","geo":{"territory":[$t2]}"""))
        assertEquals(HttpStatusCode.Forbidden, gone.status, "the new territory had no zone on the report's date")
        // The reach still bounds it: a TSO of zone 1 gets the old territory cut to the zone they reach (the zone that left is not held against them)...
        val tso = run(11, Role.TSO, "route-std", sel)
        assertEquals(HttpStatusCode.OK, tso.status)
        assertEquals(run(11, Role.TSO, "route-std").result().rows(), tso.result().rows())
        assertTrue(tso.result().rows().size < before.getValue("route-std").rows().size)
        // ...and a territory that holds, today, a zone outside the reach is refused.
        assertEquals(HttpStatusCode.Forbidden, run(14, Role.TSO, "route-std", sel).status, "zone 2's TSO is not in the old territory any more")
    }

    @Test
    fun aRouteMovedToAnotherZoneKeepsItsOldDayInRouteStdForTheOldZone() = app {
        val sel = body(""","geo":{"zone":[$z1]}""")
        val before = run(10, Role.ANALYST, "route-std", sel).result()
        val z2Before = run(10, Role.ANALYST, "route-std", body(""","geo":{"zone":[$z2]}""")).result()
        assertTrue(before.rows().any { it.str("route_code") == "R2" } && before.total("gross_mtk") > 0)
        exec("UPDATE app.route SET zone_id = (SELECT id FROM app.zone WHERE code = 'Z2') WHERE code = 'R2'")
        assertEquals(before.rows(), run(10, Role.ANALYST, "route-std", sel).result().rows(), "Z1 lost the moved route's old day")
        assertEquals(z2Before.rows(), run(10, Role.ANALYST, "route-std", body(""","geo":{"zone":[$z2]}""")).result().rows(), "Z2 gained it")
    }

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
        assertEquals(HttpStatusCode.Forbidden, run(10, Role.ANALYST, "route-std", onDate("2026-10-05", ""","geo":{"territory":[$t3]}""")).status, "T3 had no member on 10-05")
    }
}

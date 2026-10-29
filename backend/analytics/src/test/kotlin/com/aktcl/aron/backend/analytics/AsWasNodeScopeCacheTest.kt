package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachNode
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** D-REPORTS-NODEASWAS-09: the response cache of one shared DashboardService never serves one caller's chain decision (an empty node) to another with the same zones. */
class AsWasNodeScopeCacheTest {
    private val day = LocalDate.parse("2026-10-04")
    private lateinit var fresh: FreshDb
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private var t1 = 0L; private var z1 = 0L; private var z2 = 0L

    private fun resource(n: String) = checkNotNull(javaClass.classLoader.getResourceAsStream(n)).bufferedReader().readText()

    @BeforeEach
    fun setUp() {
        AsWasSql.probeTtlMs = 0
        fresh = FreshDb.create()
        fresh.dataSource.connection.use { c ->
            c.createStatement().use { it.execute(resource("seed_day.sql")) }
            c.createStatement().use { it.execute(resource("national_day_extra.sql")) }
            for (u in listOf("a1", "a2", "a4", "a5")) c.createStatement().use {
                it.execute("INSERT INTO app.domain_event (event_type, aggregate_type, aggregate_id, business_date, source_client_uuid) VALUES ('memo.created','memo','x','2026-10-04','00000000-0000-4000-8000-0000000000$u')")
            }
        }
        val w = AggregationWorker(fresh.db, AronClock { now })
        fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }
        w.requestRebuild(day); w.runUntilIdle()
        z1 = id("SELECT id FROM app.zone WHERE code = 'Z1'"); z2 = id("SELECT id FROM app.zone WHERE code = 'Z2'")
        t1 = id("SELECT id FROM app.territory WHERE code = 'T1'")
    }

    @AfterEach
    fun tearDown() { AsWasSql.probeTtlMs = 20_000; fresh.close() }

    private fun id(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery(sql).mapTo(Long::class.java).one() }
    private fun exec(sql: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(sql); Aggregator.refreshDimensions(it); DashboardSummaries.refreshDimensions(it) }

    @Test
    fun sharedServiceSummaryCacheDoesNotGrantAnEmptyNodeToATsoWithTheSameZones() {
        val d1 = id("SELECT division_id FROM app.territory WHERE id = $t1")
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('TEMPTY','Empty territory', $d1)")
        val tEmpty = id("SELECT id FROM app.territory WHERE code = 'TEMPTY'")
        val dmo = Reach(5, Role.DMO, day, false, setOf(z1, z2), emptySet(), false, listOf(ReachNode("division", d1)))
        val tso = Reach(6, Role.TSO, day, false, setOf(z1, z2), emptySet(), false, listOf(ReachNode("territory", t1)))
        val svc = DashboardService(fresh.db, AronClock { now })   // ONE service, as in production (Wiring.kt)
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { DashboardService(fresh.db, AronClock { now }).summary(tso, "territory", tEmpty, day, day) }.code, "baseline: TSO refused")
        assertEquals(0L, svc.summary(dmo, "territory", tEmpty, day, day).kpis.gross_mtk, "DMO: 200 through the chain")
        val leaked = runCatching { svc.summary(tso, "territory", tEmpty, day, day) }
        assertEquals(ProblemCode.ERR_FORBIDDEN, (leaked.exceptionOrNull() as? ApiProblem)?.code, "TSO must still be refused on a shared service (cache key must include topNodes)")
    }

    @Test
    fun sharedServiceGeoCacheDoesNotGrantAnEmptyNodeToATsoWithTheSameZones() {
        val d1 = id("SELECT division_id FROM app.territory WHERE id = $t1")
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('TEMPTY','Empty territory', $d1)")
        val tEmpty = id("SELECT id FROM app.territory WHERE code = 'TEMPTY'")
        val dmo = Reach(5, Role.DMO, day, false, setOf(z1, z2), emptySet(), false, listOf(ReachNode("division", d1)))
        val tso = Reach(6, Role.TSO, day, false, setOf(z1, z2), emptySet(), false, listOf(ReachNode("territory", t1)))
        val svc = DashboardService(fresh.db, AronClock { now })
        assertEquals(0, svc.geoValidation(dmo, "territory", tEmpty, day, day).visits, "DMO: 200 through the chain")
        val leaked = runCatching { svc.geoValidation(tso, "territory", tEmpty, day, day) }
        assertEquals(ProblemCode.ERR_FORBIDDEN, (leaked.exceptionOrNull() as? ApiProblem)?.code, "TSO must still be refused on a shared service")
    }

    @Test
    fun anEmptyTodayNodeWhoseWindowZonesLeftTheReachIsRefusedEvenWhenTheChainTies() {
        // T1 loses both zones today to a territory of ANOTHER division; T1 stays under D1. A DMO of D1 today reaches neither zone.
        val d1 = id("SELECT division_id FROM app.territory WHERE id = $t1"); val w1 = id("SELECT wing_id FROM app.division WHERE id = $d1")
        exec("INSERT INTO app.division (code, name, wing_id) VALUES ('D9','Div9', $w1)")
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('T9','Terr9', (SELECT id FROM app.division WHERE code = 'D9'))")
        val t9 = id("SELECT id FROM app.territory WHERE code = 'T9'")
        exec("UPDATE app.zone SET territory_id = $t9 WHERE id IN ($z1, $z2)")
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('T8','Terr8', $d1)")
        exec("INSERT INTO app.zone (code, name, territory_id) VALUES ('Z8','Zone8', (SELECT id FROM app.territory WHERE code = 'T8'))")
        val z8 = id("SELECT id FROM app.zone WHERE code = 'Z8'")
        val dmo = Reach(5, Role.DMO, day, false, setOf(z8), emptySet(), false, listOf(ReachNode("division", d1)))
        val r = runCatching { DashboardService(fresh.db, AronClock { now }).summary(dmo, "territory", t1, day, day) }
        assertEquals(ProblemCode.ERR_FORBIDDEN, (r.exceptionOrNull() as? ApiProblem)?.code, "window zones all out of reach: 403, the chain is not consulted")
    }
}

package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.FreshDb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-dimension, column-aware probe (`AsWasSql.probe`, D-REPORTS-ASWAS-02) from a CLEAN baseline: `seed_day.sql` only creates
 * masters (every history period is the one open period of its key), so geography, product tree and outlet class all answer "plain".
 * Each step then edits one thing and says which dimensions left the plain path. A dimension may only flip for an edit of a column
 * its timeline reads; an edit of any other column closes a history period too but must leave the dimension plain.
 */
class AsWasProbeTest {
    private val day = LocalDate.parse("2026-10-04")   // the fixture's own date, before every edit made here
    private lateinit var fresh: FreshDb

    @BeforeEach
    fun setUp() {
        AsWasSql.probeTtlMs = 0   // edit and probe at once
        fresh = FreshDb.create()
        fresh.dataSource.connection.use { c -> c.createStatement().use { it.execute(checkNotNull(javaClass.classLoader.getResourceAsStream("seed_day.sql")).bufferedReader().readText()) } }
    }

    @AfterEach
    fun tearDown() { AsWasSql.probeTtlMs = 20_000; fresh.close() }

    private fun exec(sql: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(sql) }

    /** "geo/product/outlet" as t (plain) or f (timeline). */
    private fun probe(): String = fresh.db.jdbi.withHandle<String, Exception> { h ->
        AsWasSql.probe(h, day).let { listOf(it.geo, it.product, it.outlet).joinToString("/") { b -> if (b) "t" else "f" } }
    }

    @Test
    fun everyDimensionStartsPlainAndOnlyAnEditOfAColumnItsTimelineReadsLeavesThePlainPath() {
        assertEquals("t/t/t", probe(), "a seed with no edits: nothing closed")

        exec("UPDATE app.route SET status = 'inactive' WHERE code = 'R3'")   // closes a route_history period; no timeline reads route status
        assertEquals("t/t/t", probe(), "a route status edit stays plain")
        exec("UPDATE app.outlet SET price_type = 'cc' WHERE code = 'O5'")    // closes an outlet period; no timeline reads price_type
        assertEquals("t/t/t", probe(), "an outlet price_type edit stays plain")
        exec("UPDATE app.sku SET report_unit = 'box' WHERE code = 'SKU2'")   // closes a sku period; no timeline reads report_unit
        assertEquals("t/t/t", probe(), "a SKU report_unit edit stays plain")

        exec("UPDATE app.route SET zone_id = (SELECT id FROM app.zone WHERE code = 'Z2') WHERE code = 'R2'")
        assertEquals("f/t/t", probe(), "a route zone move flips geography only")

        exec("UPDATE app.outlet SET channel = 'MT' WHERE code = 'O1'")
        assertEquals("f/t/f", probe(), "an outlet channel move flips the outlet dimension")

        exec("UPDATE app.product_node SET parent_id = (SELECT id FROM app.product_node WHERE code = 'B2') WHERE code = 'V1'")
        assertEquals("f/f/f", probe(), "a variant move flips the product dimension")
    }

    @Test
    fun anOutletZoneMoveAloneFlipsOnlyTheOutletDimension() {
        assertEquals("t/t/t", probe())
        exec(
            "UPDATE app.outlet SET zone_id = (SELECT id FROM app.zone WHERE code = 'Z2'), cluster_id = (SELECT id FROM app.cluster WHERE zone_id = (SELECT id FROM app.zone WHERE code = 'Z2')) WHERE code = 'O1'",
        )
        assertEquals("t/t/f", probe(), "an outlet zone move flips the outlet dimension and nothing else")
    }

    @Test
    fun aZoneMovedToAnotherTerritoryFlipsGeographyOnly() {
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('T9','Terr9', (SELECT id FROM app.division))")
        assertEquals("t/t/t", probe(), "a new territory closes no period")
        exec("UPDATE app.zone SET territory_id = (SELECT id FROM app.territory WHERE code = 'T9') WHERE code = 'Z1'")
        assertEquals("f/t/t", probe())
    }

    @Test
    fun probesOfOneKeyThatOverlapRunOneQuery() {
        val queries = java.util.concurrent.atomic.AtomicInteger()
        fresh.db.jdbi.setSqlLogger(object : org.jdbi.v3.core.statement.SqlLogger {
            override fun logAfterExecution(context: org.jdbi.v3.core.statement.StatementContext) { if (context.renderedSql.contains("NOT EXISTS")) queries.incrementAndGet() }
        })
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        AsWasSql.probeHook = { entered.countDown(); release.await() }
        try {
            var a = ""; var b = ""
            val t1 = Thread { a = probe() }.also { it.start() }
            entered.await()                                   // the first probe is running
            val joined = java.util.concurrent.CountDownLatch(1)
            AsWasSql.probeJoinHook = { joined.countDown() }
            val t2 = Thread { b = probe() }.also { it.start() }
            assertTrue(joined.await(20, java.util.concurrent.TimeUnit.SECONDS), "the second probe joined the running one")
            release.countDown(); t1.join(20_000); t2.join(20_000)
            assertEquals("t/t/t", a); assertEquals(a, b)
            assertEquals(1, queries.get(), "the overlapping probe took the running one's answer instead of scanning the history tables again")
        } finally { AsWasSql.probeHook = null; AsWasSql.probeJoinHook = null }
    }
}

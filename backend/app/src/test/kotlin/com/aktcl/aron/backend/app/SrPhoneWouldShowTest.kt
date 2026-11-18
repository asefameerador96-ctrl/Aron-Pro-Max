package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.analytics.AggregationWorker
import com.aktcl.aron.backend.analytics.Aggregator
import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlReachResolver
import com.aktcl.aron.backend.masterdata.SqlRoutePlanner
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.sync.BundleService
import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * D of the lead's no-clock design (D-TKPI-12): what the PHONE WOULD SHOW, not what the server sends. The server figure (achievement, KPI volume and memo counts, Star STD,
 * campaign STD and memo) is read from the accepted memos through `through_date`; a small model of the phone adds and subtracts its local memos by the ORDERING rule B
 * of `docs/requests/sr-programmes-phone-rule-b.md` (no timestamp is read anywhere): it ADDS a local memo iff it was not acknowledged before the request R of this bundle (or
 * is dated after `through_date`), SUBTRACTS a memo acknowledged before R that it has since voided or superseded when that void or edit was not acknowledged before R, and
 * sends no bundle request while an upload batch is in flight. At every interleaving the sum must be exactly the true total of the live local memos, never less, never more.
 *
 * Interleavings covered: two offline days synced then the bundle pulled before the commit of batch 2, after the commit before the projection, after the projection before the
 * rebuild, after the rebuild, with a dead aggregation key; an edit with identical lines; a void followed by a re-sale; an aggregation rebuild in flight (uncommitted, and
 * running in another thread) during the bundle read; and a memo committed WHILE the bundle is read (one repeatable-read snapshot).
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class SrPhoneWouldShowTest {
    private lateinit var fresh: FreshDb
    private lateinit var service: BundleService
    private val clock = AronClock { Instant.parse("2027-01-03T02:00:00Z") }
    private val day = LocalDate.parse("2027-01-03")
    private var sr = 0L; private var route = 0L; private var outlet = 0L
    private var skuC = 0L; private var skuM = 0L; private var brandC = 0L
    private lateinit var fx: SrMemoFixture

    private fun q(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun x(sql: String) = fresh.db.jdbi.useHandle<Exception> { h -> h.execute(sql) }
    private val principal get() = AronPrincipal(sr, "sr1001", Role.SR, 0, "api", null, null, "sr", emptyList(), false, emptyList(), "j", clock.now().plusSeconds(600))

    @BeforeEach
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c -> seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } } }
        val config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        val geo = GeoRepository(fresh.db, clock)
        service = BundleService(fresh.db, config, SqlRoutePlanner(fresh.db, geo, config), clock, reach = SqlReachResolver(fresh.db, geo, clock))
        sr = q("SELECT id FROM app.app_user WHERE username = 'sr1001'")
        val r = service.bundle(principal, day, "1.0.0+1").full.routes.first()
        route = r.route_id; outlet = r.outlets.first().outlet_id
        skuC = q("SELECT id FROM app.sku WHERE category_code = 'cigarette' ORDER BY id LIMIT 1"); skuM = q("SELECT id FROM app.sku WHERE category_code = 'match' ORDER BY id LIMIT 1")
        brandC = q("SELECT v.parent_id FROM app.sku s JOIN app.product_node v ON v.id = s.variant_id WHERE s.id = $skuC")
        fx = SrMemoFixture({ x(it) }, sr)
        // a route with a target, a Star programme and a campaign at the outlet for SKU C, so every merged figure is on the wire
        x("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ('00000000-0000-4000-8000-00000000c101', decode(repeat('cd', 32), 'hex'), 1, $sr)")
        val b = "'00000000-0000-4000-8000-00000000c101'"
        x("INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid) VALUES ($route, $skuC, DATE '2027-01-01', 5000, $b), ($route, $skuM, DATE '2027-01-01', 5000, $b)")
        val star = q("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('superstar', 'MBLS-P', 'Star', DATE '2026-12-01', DATE '2027-01-31', 'active') RETURNING id")
        x("INSERT INTO app.route_programme_target (programme_id, route_id, month, target_qty, batch_uuid) VALUES ($star, $route, DATE '2027-01-01', 20.000, $b)")
        val camp = q("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('campaign', 'SSL-P', 'Campaign', DATE '2026-12-01', DATE '2027-01-31', 'active') RETURNING id")
        x("INSERT INTO app.outlet_campaign_target (programme_id, month, outlet_id, std_target, memo_target, batch_uuid) VALUES ($camp, DATE '2027-01-01', $outlet, 1000, 50, $b)")
        x("INSERT INTO app.outlet_campaign_sku_target (programme_id, month, outlet_id, sku_id, qty_base, batch_uuid) VALUES ($camp, DATE '2027-01-01', $outlet, $skuC, 1000, $b)")
    }

    @AfterEach fun tearDown() = fresh.close()

    // ---------------------------------------------------------------- the figures, as the phone merges them

    /** The eight merged figures: achievement qty and memos of SKU C, brand qty and memos, BLM match volume, Star STD (milli), campaign STD and memos. */
    private data class Fig(val achQty: Long = 0, val achMemos: Long = 0, val brandQty: Long = 0, val brandMemos: Long = 0, val matchQty: Long = 0, val starMilli: Long = 0, val campStd: Long = 0, val campMemos: Long = 0) {
        operator fun plus(o: Fig) = Fig(achQty + o.achQty, achMemos + o.achMemos, brandQty + o.brandQty, brandMemos + o.brandMemos, matchQty + o.matchQty, starMilli + o.starMilli, campStd + o.campStd, campMemos + o.campMemos)
        operator fun minus(o: Fig) = Fig(achQty - o.achQty, achMemos - o.achMemos, brandQty - o.brandQty, brandMemos - o.brandMemos, matchQty - o.matchQty, starMilli - o.starMilli, campStd - o.campStd, campMemos - o.campMemos)
    }

    private fun JsonObject.l(k: String) = this[k]!!.jsonPrimitive.content.toLong()

    /** Reads the figures off a bundle (the server's side). */
    private fun serverFigures(): Pair<Fig, String> {
        val r = service.bundle(principal, day, "1.0.0+1").full.routes.single { it.route_id == route }
        val a = r.achievement_mtd.map { it.jsonObject }.singleOrNull { it.l("product_id") == skuC }
        val k = r.kpi?.jsonObject
        val brand = k?.get("brands")?.jsonArray?.map { it.jsonObject }?.singleOrNull { it.l("brand_id") == brandC }
        val match = k?.get("categories")?.jsonArray?.map { it.jsonObject }?.singleOrNull { it["category_code"]!!.jsonPrimitive.content == "match" }
        val star = r.star?.jsonObject?.get("std")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { BigDecimal(it).movePointRight(3).toLong() } ?: 0L
        val c = r.campaigns.orEmpty().map { it.jsonObject }.singleOrNull { it.l("outlet_id") == outlet }
        val through = r.calendar?.through_date ?: "2027-01-02"
        assertEquals("2027-01-02", through, "the figures are cut at through_date = the day before the bundle date")
        return Fig(
            a?.l("achieved_qty_base") ?: 0, a?.l("memo_count") ?: 0, brand?.l("volume_achieved") ?: 0, brand?.l("memo_achieved") ?: 0, match?.l("volume_achieved") ?: 0, star,
            c?.get("std")?.jsonObject?.l("achieved") ?: 0, c?.get("memo")?.jsonObject?.l("achieved") ?: 0,
        ) to through
    }

    // ---------------------------------------------------------------- the phone model (rule B, no clock)

    /** A local memo: [qC] sticks of the cigarette SKU and [qM] of the match SKU, dated [date]. */
    private inner class LocalMemo(val id: String, val date: String, val qC: Int, val qM: Int) {
        var acked = false          // the server's `accepted` or `duplicate` for the memo has reached the phone
        var removed = false        // voided or superseded on the phone
        var removalAcked = false   // that void or edit record has been acknowledged
        var committed = false      // server side: the memo is committed (the phone cannot see this)
        val fig get() = Fig(qC.toLong(), if (qC > 0) 1 else 0, qC.toLong(), if (qC > 0) 1 else 0, qM.toLong(), qM * 1000L, qC.toLong(), if (qC > 0) 1 else 0)
        val uuid get() = id
    }

    private inner class Phone {
        val memos = ArrayList<LocalMemo>()
        var inFlight = 0   // upload batches sent and not yet answered

        fun sell(id: String, date: String, qC: Int, qM: Int = 0) = LocalMemo(id, date, qC, qM).also { memos += it }
        /** The true total: every live memo of the month, wherever it is. */
        fun truth(): Fig = memos.filter { !it.removed }.fold(Fig()) { t, m -> t + m.fig }

        /** The phone's own request moment R is the state of these flags at the moment it sends the request; rule 4: no bundle request while a batch is in flight. */
        fun requestBundle(through: String = "2027-01-02"): Fig {
            check(inFlight == 0) { "rule 4: the phone sends no bundle request while an upload batch is in flight" }
            val ackedAtR = memos.filter { it.acked }.toSet(); val removalAckedAtR = memos.filter { it.removalAcked }.toSet()
            val (server, th) = serverFigures()
            assertEquals(through, th)
            var shown = server
            for (m in memos) {
                if (!m.removed && (m !in ackedAtR || m.date > through)) shown += m.fig                                 // rule 2
                if (m.removed && m in ackedAtR && m !in removalAckedAtR && m.date <= through) shown -= m.fig       // rule 3
            }
            return shown
        }
    }

    // ---------------------------------------------------------------- server side: commit then acknowledge

    private fun commit(m: LocalMemo, emit: Boolean = false, supersedes: LocalMemo? = null) {
        val lines = listOfNotNull(if (m.qC > 0) SrMemoFixture.L(skuC, m.qC) else null, if (m.qM > 0) SrMemoFixture.L(skuM, m.qM) else null)
        fx.memo(m.uuid, route, outlet, m.date, lines, "now()", supersedes = supersedes?.uuid, emit = emit); m.committed = true
    }
    private fun commitVoid(m: LocalMemo, emit: Boolean = false) = fx.void(m.uuid, m.date, emit)

    private fun uid(n: Int) = "00000000-0000-4000-8000-00000000%04x".format(0x1000 + n)
    private fun worker() = AggregationWorker(fresh.db, AronClock { Instant.now() })

    private fun assertShowsTruth(p: Phone, what: String) = assertEquals(p.truth(), p.requestBundle(), "the phone shows exactly the true total: $what")

    // ---------------------------------------------------------------- tests

    @Test
    fun twoOfflineDaysSyncedThenTheBundleAtEveryPointOfTheProjectionAndRebuild() {
        val p = Phone()
        val a = p.sell(uid(1), "2027-01-01", 100, 10); val b = p.sell(uid(2), "2027-01-02", 70, 5); val t = p.sell(uid(3), "2027-01-03", 33)   // t: today, local only
        assertShowsTruth(p, "nothing synced: every memo is a local addition")
        // batch 1 (day D-2) committed, then acknowledged
        commit(a, emit = true); a.acked = true
        assertShowsTruth(p, "batch 1 acknowledged, batch 2 (D-1) not yet uploaded: server holds A, phone adds B and today's memo")
        // batch 2 in flight: the phone must not ask; committed but the ack lost: still in flight
        p.inFlight = 1
        assertFailsWith<IllegalStateException> { p.requestBundle() }
        commit(b, emit = true)
        assertFailsWith<IllegalStateException> { p.requestBundle() }
        // rule 4 is needed: a bundle requested NOW (committed, ack lost) would count B twice
        p.inFlight = 0
        val doubled = p.requestBundle()
        assertEquals(p.truth() + b.fig, doubled, "without the single-flight rule the committed-but-unacknowledged batch is counted twice")
        // the phone retries the batch (idempotent): `duplicate`, then asks
        b.acked = true
        assertShowsTruth(p, "batch 2 committed and acknowledged, projection not run yet")
        val w = worker()
        w.project()
        assertShowsTruth(p, "after the projection, before the rebuild (the aggregate may lag: the figure does not read it)")
        w.drain()
        assertShowsTruth(p, "after the rebuild")
        // a dead aggregation key for the memos' day (a rebuild that keeps failing): the dashboards lag, the phone figure does not
        x("INSERT INTO app.dirty_key (kind, subject_id, business_date, first_dirtied_at, last_dirtied_at, reason, dead_at) VALUES ('route_day_agg', $route, DATE '2027-01-02', TIMESTAMPTZ '2027-01-03T00:00:00Z', TIMESTAMPTZ '2027-01-03T00:00:01Z', 'memo', now()) ON CONFLICT DO NOTHING")
        assertShowsTruth(p, "with a dead aggregation key for D-1")
        // today's memo uploads later: it stays a local addition until acknowledged, and after its ack the server still never holds today
        commit(t); t.acked = true
        assertShowsTruth(p, "today's memo acknowledged: still not in a server figure (cut at through_date), still added by the phone")
    }

    @Test
    fun theFigureNeverDependsOnTheAggregateOrItsTimestamps() {
        val p = Phone()
        val a = p.sell(uid(1), "2027-01-01", 100, 10); val b = p.sell(uid(2), "2027-01-02", 70)
        commit(a, emit = true); commit(b, emit = true); a.acked = true; b.acked = true
        val before = serverFigures().first
        // an aggregate row with wrong numbers and a future updated_at (what the lagging projector or a clock fight could leave) changes nothing
        x("INSERT INTO dw.agg_daily_route_sku (business_date, route_id, sku_id, sold_qty_base, memo_count, updated_at) VALUES (DATE '2027-01-02', $route, $skuC, 99999, 99, TIMESTAMPTZ '2030-01-01T00:00:00Z') ON CONFLICT (business_date, route_id, sku_id) DO UPDATE SET sold_qty_base = 99999, memo_count = 99, updated_at = TIMESTAMPTZ '2030-01-01T00:00:00Z'")
        assertEquals(before, serverFigures().first)
        assertShowsTruth(p, "the aggregate is ignored")
    }

    @Test
    fun anEditWithIdenticalLines() {
        val p = Phone()
        val x1 = p.sell(uid(1), "2027-01-02", 80, 4); commit(x1); x1.acked = true
        assertShowsTruth(p, "X synced")
        // the phone edits X: X is superseded locally, Y (identical lines) is the new live memo; the edit is not uploaded yet
        x1.removed = true; val y = p.sell(uid(2), "2027-01-02", 80, 4)
        assertShowsTruth(p, "edit not uploaded: server still holds X, phone adds Y and subtracts X, the same lines give the same total")
        // the edit batch commits: X superseded, Y active, then the ack arrives
        commit(y, supersedes = x1); y.acked = true; x1.removalAcked = true
        assertShowsTruth(p, "edit committed and acknowledged")
        val w = worker(); w.project(); w.drain()
        assertShowsTruth(p, "after projection and rebuild")
        assertEquals(1L, serverFigures().first.achMemos, "the superseded memo is out of the memo count, the edit is in: one memo, not two")
    }

    @Test
    fun aVoidFollowedByAReSale() {
        val p = Phone()
        val x1 = p.sell(uid(1), "2027-01-02", 50, 0); commit(x1); x1.acked = true
        val other = p.sell(uid(9), "2027-01-01", 20, 0); commit(other); other.acked = true
        assertShowsTruth(p, "two memos synced")
        x1.removed = true; val z = p.sell(uid(2), "2027-01-02", 65, 0)     // void X, sell Z (same outlet), nothing uploaded
        assertShowsTruth(p, "void and re-sale not uploaded: phone subtracts X (server holds it) and adds Z")
        commitVoid(x1); x1.removalAcked = true                              // the void batch commits and is acknowledged; Z not uploaded yet
        assertShowsTruth(p, "void acknowledged, re-sale not uploaded: the server no longer holds X, the phone adds Z and does not subtract X again")
        commit(z); z.acked = true
        assertShowsTruth(p, "re-sale acknowledged")
        val w = worker(); w.project(); w.drain()
        assertShowsTruth(p, "after projection and rebuild")
    }

    @Test
    fun aRebuildInFlightDuringTheBundleReadChangesNothing() {
        val p = Phone()
        val a = p.sell(uid(1), "2027-01-01", 100, 10); val b = p.sell(uid(2), "2027-01-02", 70, 5)
        commit(a, emit = true); commit(b, emit = true); a.acked = true; b.acked = true
        val w = worker(); w.project(); w.drain()
        // (1) a rebuild transaction open and uncommitted: it has deleted the aggregate rows of the route (what DELETE-then-INSERT does for a while)
        fresh.dataSource.connection.use { c ->
            c.autoCommit = false
            c.createStatement().use { s ->
                s.execute("DELETE FROM dw.agg_daily_route_sku WHERE route_id = $route"); s.execute("DELETE FROM dw.agg_daily_route_brand WHERE route_id = $route")
            }
            assertShowsTruth(p, "a rebuild is open (aggregate rows deleted, not committed)")
            c.rollback()
        }
        // (2) the real rebuild running in another thread while bundles are read
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val t = Thread { while (!stop.get()) { runCatching { worker().also { w2 -> w2.requestRebuild(LocalDate.parse("2027-01-02")); w2.requestRebuild(LocalDate.parse("2027-01-01")); w2.runUntilIdle() } } } }
        t.start()
        try { repeat(15) { assertShowsTruth(p, "bundle #$it while the aggregation rebuilds in another thread") } } finally { stop.set(true); t.join(30_000) }
    }

    @Test
    fun aMemoCommittedWhileTheBundleIsReadIsInEveryFigureOrInNone() {
        // The campaign figure and the achievement are separate reads of the same memos; one repeatable-read snapshot makes them agree at all times. A committer inserts
        // memos of SKU C at the campaign outlet (this route) while bundles are built: for every bundle the campaign STD equals the achievement of SKU C and the brand volume.
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val n = java.util.concurrent.atomic.AtomicInteger(0)
        val t = Thread { while (!stop.get() && n.get() < 400) { fx.memo(uid(100 + n.getAndIncrement()), route, outlet, "2027-01-02", listOf(SrMemoFixture.L(skuC, 1))) } }
        t.start()
        var builds = 0; var moved = 0; var last = -1L
        try {
            while (t.isAlive && builds < 60) {
                val (f, _) = serverFigures(); builds++
                assertEquals(f.achQty, f.campStd, "build $builds: campaign STD and achievement of SKU C come from one snapshot")
                assertEquals(f.achQty, f.brandQty, "build $builds: brand volume and achievement come from one snapshot")
                assertEquals(f.achMemos, f.campMemos, "build $builds: memo counts agree")
                if (f.achQty != last) { moved++; last = f.achQty }
            }
        } finally { stop.set(true); t.join(30_000) }
        note("builds=$builds, distinct figures seen=$moved, memos committed=${n.get()}")
        assertTrue(builds >= 5, "the test read several bundles while memos were committing")
    }

    @Test
    fun onceTheWorkerHasCaughtUpTheDirectFigureEqualsTheDashboardAggregate() {
        val p = Phone()
        val a = p.sell(uid(1), "2027-01-01", 100, 10); val b = p.sell(uid(2), "2027-01-02", 70, 5); val v = p.sell(uid(3), "2027-01-02", 40, 0)
        commit(a, emit = true); commit(b, emit = true); commit(v, emit = true); commitVoid(v, emit = true)
        p.sell(uid(4), "2027-01-03", 999)   // today: in no server figure
        val w = worker(); w.runUntilIdle(); x("SELECT 1"); fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }
        w.requestRebuild(LocalDate.parse("2027-01-01")); w.requestRebuild(LocalDate.parse("2027-01-02")); w.runUntilIdle()
        val (f, _) = serverFigures()
        fun agg(sql: String) = q(sql)
        assertEquals(agg("SELECT coalesce(sum(sold_qty_base), 0) FROM dw.agg_daily_route_sku WHERE route_id = $route AND sku_id = $skuC AND business_date BETWEEN DATE '2027-01-01' AND DATE '2027-01-02'"), f.achQty, "SKU sum = dw.agg_daily_route_sku")
        assertEquals(agg("SELECT coalesce(sum(memo_count), 0) FROM dw.agg_daily_route_sku WHERE route_id = $route AND sku_id = $skuC AND business_date BETWEEN DATE '2027-01-01' AND DATE '2027-01-02'"), f.achMemos, "SKU memo count = dw.agg_daily_route_sku")
        assertEquals(agg("SELECT coalesce(sum(sold_qty_base), 0) FROM dw.agg_daily_route_brand WHERE route_id = $route AND brand_id = $brandC AND business_date BETWEEN DATE '2027-01-01' AND DATE '2027-01-02'"), f.brandQty, "brand volume = dw.agg_daily_route_brand")
        assertEquals(agg("SELECT coalesce(sum(memo_count), 0) FROM dw.agg_daily_route_brand WHERE route_id = $route AND brand_id = $brandC AND business_date BETWEEN DATE '2027-01-01' AND DATE '2027-01-02'"), f.brandMemos, "brand memo count = dw.agg_daily_route_brand")
        assertEquals(170L, f.achQty); assertEquals(2L, f.achMemos)
        // WHERE THEY DIFFER (by construction, not run here): the dashboard aggregate takes the AS-WAS brand of a SKU on the memo's date (dw.v_dim_product_history), the bundle takes the CURRENT
        // tree for the target and the figure alike. They differ only for a SKU whose brand changed inside the month. The bundle side of that rule:
        val otherBrandVariant = q("SELECT v.id FROM app.product_node v JOIN app.product_node b ON b.id = v.parent_id WHERE v.level = 'variant' AND b.id <> $brandC AND EXISTS (SELECT 1 FROM app.sku s WHERE s.variant_id = v.id AND s.category_code = 'cigarette') ORDER BY v.id LIMIT 1")
        val newBrand = q("SELECT parent_id FROM app.product_node WHERE id = $otherBrandVariant")
        x("UPDATE app.sku SET variant_id = $otherBrandVariant WHERE id = $skuC")
        val r = service.bundle(principal, day, "1.0.0+1").full.routes.single { it.route_id == route }
        val brands = r.kpi!!.jsonObject["brands"]!!.jsonArray.map { it.jsonObject }
        assertEquals(170L, brands.single { it.l("brand_id") == newBrand }.l("volume_achieved"), "the SKU's volume follows the current tree to its new brand")
        assertTrue(brands.none { it.l("brand_id") == brandC && it.l("volume_achieved") > 0 }, "and is no longer in the old brand: target and figure always use one hierarchy")
        assertEquals(170L, r.achievement_mtd.map { it.jsonObject }.single { it.l("product_id") == skuC }.l("achieved_qty_base"), "the SKU figure itself does not move")
    }

    private fun note(s: String) = println("NOTE SrPhoneWouldShowTest: $s")
}

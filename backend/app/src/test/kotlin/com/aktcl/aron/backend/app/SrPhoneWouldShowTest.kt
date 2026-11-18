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
import com.aktcl.aron.backend.sync.SrFigureProbe
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
import kotlin.test.assertNotEquals
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
    @Volatile private var nowAt: Instant = Instant.parse("2027-01-03T02:00:00Z")
    private val clock = AronClock { nowAt }
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
    private fun serverFigures(date: LocalDate = day, routeId: Long = route, outletId: Long = outlet): Pair<Fig, String> = figuresOf(service.bundle(principal, date, "1.0.0+1"), date, routeId, outletId)

    private fun figuresOf(res: BundleService.Result, date: LocalDate, routeId: Long = route, outletId: Long = outlet): Pair<Fig, String> {
        val r = res.full.routes.single { it.route_id == routeId }
        val a = r.achievement_mtd.map { it.jsonObject }.singleOrNull { it.l("product_id") == skuC }
        val k = r.kpi?.jsonObject
        val brand = k?.get("brands")?.jsonArray?.map { it.jsonObject }?.singleOrNull { it.l("brand_id") == brandC }
        val match = k?.get("categories")?.jsonArray?.map { it.jsonObject }?.singleOrNull { it["category_code"]!!.jsonPrimitive.content == "match" }
        val star = r.star?.jsonObject?.get("std")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { BigDecimal(it).movePointRight(3).toLong() } ?: 0L
        val c = r.campaigns.orEmpty().map { it.jsonObject }.singleOrNull { it.l("outlet_id") == outletId }
        val through = date.minusDays(1).toString()
        r.calendar?.let { assertEquals(through, it.through_date, "the figures are cut at through_date = the day before the bundle date") }
        return Fig(
            a?.l("achieved_qty_base") ?: 0, a?.l("memo_count") ?: 0, brand?.l("volume_achieved") ?: 0, brand?.l("memo_achieved") ?: 0, match?.l("volume_achieved") ?: 0, star,
            c?.get("std")?.jsonObject?.l("achieved") ?: 0, c?.get("memo")?.jsonObject?.l("achieved") ?: 0,
        ) to through
    }

    // ---------------------------------------------------------------- the phone model: EXACT BY IDENTITY (D-TKPI-14), no acknowledgements, resolutions, quarantine states, clocks or single flight

    /** A local memo: [qC] sticks of the cigarette SKU and [qM] of the match SKU (sale lines), [qFree] free sticks of the cigarette SKU, dated [date], on [routeId] at [outletId]. */
    private inner class IdMemo(val id: String, val date: String, val qC: Int, val qM: Int = 0, val memoNo: String? = null, val routeId: Long = route, val outletId: Long = outlet, val qFree: Int = 0) {
        var removed = false          // voided or superseded on the phone
        var confirmedIn = false      // an earlier answer said IN and the phone has not touched the memo since: not probed again
        var droppedByServer = false  // the server said VOIDED: the phone drops it
        val fig get() = Fig(
            qC.toLong(), if (qC > 0 || qFree > 0) 1 else 0, qC.toLong(), if (qC > 0) 1 else 0, qM.toLong(), qM * 1000L, qC.toLong(), if (qC > 0) 1 else 0,
        )
    }

    private inner class IdPhone {
        val memos = ArrayList<IdMemo>()
        fun sell(id: String, date: String, qC: Int, qM: Int = 0, memoNo: String? = null, routeId: Long = route, outletId: Long = outlet, qFree: Int = 0) = IdMemo(id, date, qC, qM, memoNo, routeId, outletId, qFree).also { memos += it }
        /** The phone-intent total of a route and month: its live memos, minus those the server says are voided. */
        fun truth(routeId: Long = route, month: String = "2027-01"): Fig = memos.filter { !it.removed && !it.droppedByServer && it.routeId == routeId && it.date.startsWith(month) }.fold(Fig()) { t, m -> t + m.fig }

        /** One call of /figures (the service-level twin of the endpoint) and the merge by identity; returns what the phone shows for [routeId]. */
        fun request(date: LocalDate = day, routeId: Long = route, outletId: Long = outlet): Fig {
            val through = date.minusDays(1).toString(); val month = date.toString().substring(0, 7)
            val inMonth = memos.filter { it.date.startsWith(month) && !it.droppedByServer }
            val probes = inMonth.filter { (!it.confirmedIn || it.removed) && !(it.removed && it.date > through) }
                .map { SrFigureProbe.Probe(java.util.UUID.fromString(it.id), it.memoNo, LocalDate.parse(it.date), it.removed) }
            val res = service.bundle(principal, date, "1.0.0+1", probe = probes)
            val ans = res.probe!!
            var shown = figuresOf(res, date, routeId, outletId).first
            for (m in inMonth) {
                val u = java.util.UUID.fromString(m.id)
                if (m.confirmedIn && !m.removed) { continue }          // confirmed IN earlier and untouched: stays IN, not probed, nothing to do
                val voided = u in ans.voided; val inFig = u in ans.inFigure
                val mine = m.routeId == routeId
                if (!m.removed) {
                    if (voided) m.droppedByServer = true
                    else if (m.date > through) { if (mine) shown += m.fig }
                    else if (inFig) m.confirmedIn = true
                    else if (mine) shown += m.fig
                } else if (m.date <= through && inFig && mine) {
                    val lines = ans.removedLines.getValue(u)
                    val c = lines.filter { it.skuId == skuC && it.kind == "sale" }.sumOf { it.qtyBase }; val mm = lines.filter { it.skuId == skuM && it.kind == "sale" }.sumOf { it.qtyBase }
                    shown -= Fig(c, if (lines.any { it.skuId == skuC }) 1 else 0, c, if (c > 0) 1 else 0, mm, mm * 1000, c, if (c > 0) 1 else 0)
                }
            }
            return shown
        }
    }

    private fun uid(n: Int) = "00000000-0000-4000-8000-00000000%04x".format(0x1000 + n)
    private fun worker() = AggregationWorker(fresh.db, AronClock { Instant.now() })

    private fun commitId(m: IdMemo, supersedes: IdMemo? = null, lines: List<SrMemoFixture.L>? = null, manualBy: Long? = null, emit: Boolean = false) {
        val l = lines ?: listOfNotNull(
            if (m.qC > 0) SrMemoFixture.L(skuC, m.qC) else null, if (m.qM > 0) SrMemoFixture.L(skuM, m.qM) else null, if (m.qFree > 0) SrMemoFixture.L(skuC, m.qFree, "promo_free") else null,
        )
        fx.memo(m.id, m.routeId, m.outletId, m.date, l, "now()", supersedes = supersedes?.id, memoNo = m.memoNo, manualBy = manualBy, emit = emit)
    }
    private fun assertIdTruth(p: IdPhone, what: String, date: LocalDate = day, routeId: Long = route, outletId: Long = outlet) {
        val shown = p.request(date, routeId, outletId)
        assertEquals(p.truth(routeId, date.toString().substring(0, 7)), shown, "by identity the phone shows exactly the true total: $what")
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun twoOfflineDaysSyncedThenTheBundleAtEveryPointOfTheProjectionAndRebuild() {
        val p = IdPhone()
        val a = p.sell(uid(1), "2027-01-01", 100, 10); val b = p.sell(uid(2), "2027-01-02", 70, 5); val t = p.sell(uid(3), "2027-01-03", 33)
        assertIdTruth(p, "nothing synced: every memo is NOT IN and added")
        commitId(a, emit = true)
        assertIdTruth(p, "batch 1 committed: A is IN, B and today's are added")
        // batch 2 committed but the phone never heard (lost response): it simply asks, no acknowledgement and no single flight involved
        commitId(b, emit = true)
        assertIdTruth(p, "batch 2 committed, response lost, projection not run")
        val w = worker(); w.project()
        assertIdTruth(p, "after the projection, before the rebuild")
        w.drain()
        assertIdTruth(p, "after the rebuild")
        x("INSERT INTO app.dirty_key (kind, subject_id, business_date, first_dirtied_at, last_dirtied_at, reason, dead_at) VALUES ('route_day_agg', $route, DATE '2027-01-02', TIMESTAMPTZ '2027-01-03T00:00:00Z', TIMESTAMPTZ '2027-01-03T00:00:01Z', 'memo', now()) ON CONFLICT DO NOTHING")
        assertIdTruth(p, "with a dead aggregation key for D-1")
        commitId(t)
        assertIdTruth(p, "today's memo committed: still cut by through_date, still added by the phone")
    }

    @Test
    fun theFigureNeverDependsOnTheAggregateOrItsTimestamps() {
        val p = IdPhone()
        val a = p.sell(uid(1), "2027-01-01", 100, 10); val b = p.sell(uid(2), "2027-01-02", 70)
        commitId(a, emit = true); commitId(b, emit = true)
        val before = serverFigures().first
        x("INSERT INTO dw.agg_daily_route_sku (business_date, route_id, sku_id, sold_qty_base, memo_count, updated_at) VALUES (DATE '2027-01-02', $route, $skuC, 99999, 99, TIMESTAMPTZ '2030-01-01T00:00:00Z') ON CONFLICT (business_date, route_id, sku_id) DO UPDATE SET sold_qty_base = 99999, memo_count = 99, updated_at = TIMESTAMPTZ '2030-01-01T00:00:00Z'")
        assertEquals(before, serverFigures().first)
        assertIdTruth(p, "the aggregate is ignored")
    }

    @Test
    fun anEditWithIdenticalLines() {
        val p = IdPhone()
        val x1 = p.sell(uid(1), "2027-01-02", 80, 4); commitId(x1)
        assertIdTruth(p, "X synced")
        x1.removed = true; val y = p.sell(uid(2), "2027-01-02", 80, 4)
        assertIdTruth(p, "edit not uploaded: Y NOT IN (added), X IN (subtracted with its stored lines)")
        commitId(y, supersedes = x1)
        assertIdTruth(p, "edit committed: X VOIDED, Y IN, nothing added or subtracted")
        val w = worker(); w.project(); w.drain()
        assertIdTruth(p, "after projection and rebuild")
        assertEquals(1L, serverFigures().first.achMemos, "the superseded memo is out of the memo count, the edit is in: one memo, not two")
    }

    @Test
    fun aVoidFollowedByAReSale() {
        val p = IdPhone()
        val x1 = p.sell(uid(1), "2027-01-02", 50, 0); commitId(x1)
        val other = p.sell(uid(9), "2027-01-01", 20, 0); commitId(other)
        assertIdTruth(p, "two memos synced")
        x1.removed = true; val z = p.sell(uid(2), "2027-01-02", 65, 0)
        assertIdTruth(p, "void and re-sale not uploaded: X is IN (subtracted), Z NOT IN (added)")
        fx.void(x1.id, x1.date)
        assertIdTruth(p, "void committed, re-sale not uploaded: X VOIDED (nothing subtracted again), Z added")
        commitId(z)
        assertIdTruth(p, "re-sale committed")
        val w = worker(); w.project(); w.drain()
        assertIdTruth(p, "after projection and rebuild")
    }

    @Test
    fun aRebuildInFlightDuringTheBundleReadChangesNothing() {
        val p = IdPhone()
        val a = p.sell(uid(1), "2027-01-01", 100, 10); val b = p.sell(uid(2), "2027-01-02", 70, 5)
        commitId(a, emit = true); commitId(b, emit = true)
        val w = worker(); w.project(); w.drain()
        fresh.dataSource.connection.use { c ->
            c.autoCommit = false
            c.createStatement().use { s -> s.execute("DELETE FROM dw.agg_daily_route_sku WHERE route_id = $route"); s.execute("DELETE FROM dw.agg_daily_route_brand WHERE route_id = $route") }
            assertIdTruth(p, "a rebuild is open (aggregate rows deleted, not committed)")
            c.rollback()
        }
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val t = Thread { while (!stop.get()) { runCatching { worker().also { w2 -> w2.requestRebuild(LocalDate.parse("2027-01-02")); w2.requestRebuild(LocalDate.parse("2027-01-01")); w2.runUntilIdle() } } } }
        t.start()
        try { repeat(15) { assertIdTruth(p, "bundle #$it while the aggregation rebuilds in another thread") } } finally { stop.set(true); t.join(30_000) }
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
        val p = IdPhone()
        val a = p.sell(uid(1), "2027-01-01", 100, 10); val b = p.sell(uid(2), "2027-01-02", 70, 5); val v = p.sell(uid(3), "2027-01-02", 40, 0)
        commitId(a, emit = true); commitId(b, emit = true); commitId(v, emit = true); fx.void(v.id, v.date, emit = true)
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


    @Test
    fun aFreeOnlyMemoCountsInTheSkuMemoCountButNotInVolumeBrandOrCampaignCounts() {
        val p = IdPhone()
        val free = p.sell(uid(1), "2027-01-02", 0, qFree = 5); val sale = p.sell(uid(2), "2027-01-02", 40)
        assertIdTruth(p, "both NOT IN: the phone adds the free-only memo to the SKU memo count only")
        commitId(free)
        assertIdTruth(p, "free-only memo committed")
        val s1 = serverFigures().first
        assertEquals(1L, s1.achMemos, "the SKU memo count counts any line of the SKU (free included)"); assertEquals(0L, s1.achQty); assertEquals(0L, s1.brandMemos); assertEquals(0L, s1.campMemos)
        commitId(sale)
        assertIdTruth(p, "sale memo committed too")
        val s2 = serverFigures().first
        assertEquals(2L, s2.achMemos); assertEquals(1L, s2.brandMemos, "brand and campaign memo counts need a sale line"); assertEquals(1L, s2.campMemos); assertEquals(40L, s2.achQty)
    }

    @Test
    fun aBundleOnTheFirstOfTheMonthHasNoServerFigureAndLastMonthIsNeverAdded() {
        val b = "'00000000-0000-4000-8000-00000000c101'"
        x("UPDATE app.programme SET active_to = DATE '2027-02-28' WHERE code IN ('MBLS-P', 'SSL-P')")
        x("INSERT INTO app.route_programme_target (programme_id, route_id, month, target_qty, batch_uuid) SELECT id, $route, DATE '2027-02-01', 20.000, $b FROM app.programme WHERE code = 'MBLS-P'")
        x("INSERT INTO app.outlet_campaign_target (programme_id, month, outlet_id, std_target, memo_target, batch_uuid) SELECT id, DATE '2027-02-01', $outlet, 1000, 50, $b FROM app.programme WHERE code = 'SSL-P'")
        x("INSERT INTO app.outlet_campaign_sku_target (programme_id, month, outlet_id, sku_id, qty_base, batch_uuid) SELECT id, DATE '2027-02-01', $outlet, $skuC, 1000, $b FROM app.programme WHERE code = 'SSL-P'")
        val p = IdPhone()
        val jan = p.sell(uid(1), "2027-01-31", 60, 5); commitId(jan)
        val feb = p.sell(uid(2), "2027-02-01", 25, 3)
        val first = LocalDate.parse("2027-02-01")
        nowAt = Instant.parse("2027-02-01T02:00:00Z")
        assertEquals(Fig(), serverFigures(first).first, "on the 1st the window is empty: no server figure, last month's memos are not in it")
        assertIdTruth(p, "the phone adds today's memo and nothing of January", first)
        commitId(feb)
        nowAt = Instant.parse("2027-02-02T02:00:00Z")
        assertIdTruth(p, "the next day the server holds the 1st's memo (IN) and the phone does not add it", LocalDate.parse("2027-02-02"))
    }

    @Test
    fun aBundleBuiltAheadForTomorrowHoldsTodaysCommittedMemosAndStillGivesTheTrueTotal() {
        val tomorrow = day.plusDays(1)
        val p = IdPhone()
        val y = p.sell(uid(1), "2027-01-02", 50, 3); val t = p.sell(uid(2), "2027-01-03", 70, 2)
        commitId(y)
        assertIdTruth(p, "today's memo is not committed yet: through_date of the prefetched bundle is today, NOT IN, added", tomorrow)
        val v0 = service.bundle(principal, tomorrow, "1.0.0+1").version
        commitId(t)
        assertIdTruth(p, "today's memo is committed: IN the prefetched figure, not added", tomorrow)
        assertNotEquals(v0, service.bundle(principal, tomorrow, "1.0.0+1").version, "the version of a bundle built ahead moves with today's commits (admitted churn)")
        assertIdTruth(p, "a bundle for today never holds today's memos: the phone adds them", day)
    }

    @Test
    fun anEditDatedTodayOfYesterdaysMemo() {
        val p = IdPhone()
        val x1 = p.sell(uid(1), "2027-01-02", 80, 4); commitId(x1)
        x1.removed = true; val y = p.sell(uid(2), "2027-01-03", 80, 4)
        assertIdTruth(p, "edit not uploaded: X IN (subtracted), Y added (after through_date)")
        commitId(y, supersedes = x1)
        assertIdTruth(p, "edit committed: X VOIDED, Y cut by through_date and added")
        val dayNext = day.plusDays(1)
        assertIdTruth(p, "tomorrow the server holds Y (IN) and the phone does not add it", dayNext)
    }

    @Test
    fun anotherRoutesMemoAtTheCampaignOutletIsInTheOutletFigureAndTheRouteFigureIsStillOnlyOwn() {
        val other = q("INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no) SELECT 'T-OTHER', 'Other route', 'Daily', zone_id, 'sr', 'daily', 127, 99 FROM app.route WHERE id = $route RETURNING id")
        val otherSr = q("SELECT id FROM app.app_user WHERE id <> $sr ORDER BY id LIMIT 1")
        SrMemoFixture({ x(it) }, otherSr, 500).memo(uid(50), other, outlet, "2027-01-02", listOf(SrMemoFixture.L(skuC, 30)), "now()")
        val p = IdPhone()
        val a = p.sell(uid(1), "2027-01-02", 60); commitId(a)
        val shownBefore = p.request()
        val f = serverFigures().first
        assertEquals(60L, f.achQty, "the route's own figures hold this route's memos only"); assertEquals(60L, f.brandQty)
        assertEquals(90L, f.campStd, "the campaign figure is the OUTLET's: this route's 60 plus the other route's committed 30"); assertEquals(2L, f.campMemos)
        assertEquals(f, shownBefore, "the phone adds nothing: its memo is IN")
        p.sell(uid(2), "2027-01-02", 10)
        val shown = p.request()
        assertEquals(70L, shown.achQty); assertEquals(100L, shown.campStd, "outlet memos committed before the read + this phone's own local memo"); assertEquals(3L, shown.campMemos)
    }

    @Test
    fun anSrWithTwoRoutesGetsEachRoutesOwnFiguresAndMergesEachSeparately() {
        val (r2, o2) = secondRoute()
        val p = IdPhone()
        val a = p.sell(uid(1), "2027-01-02", 40, 2); commitId(a)
        val b = p.sell(uid(2), "2027-01-02", 25, 0, routeId = r2, outletId = o2); commitId(b)
        p.sell(uid(3), "2027-01-02", 15, 0, routeId = r2, outletId = o2)
        assertIdTruth(p, "route 1: its own memo only", day, route, outlet)
        assertIdTruth(p, "route 2: its committed memo from the server, its local memo added", day, r2, o2)
        assertEquals(40L, serverFigures(day, route, outlet).first.achQty, "route 1's figure never holds route 2's memos")
        assertEquals(25L, serverFigures(day, r2, o2).first.achQty)
    }

    /** A second route of the SR (a cover) with an outlet, a Star target and a campaign at that outlet, so every merged figure exists for it too. */
    private fun secondRoute(): Pair<Long, Long> {
        val r2 = q("INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no) SELECT 'T-R2', 'Second route', 'Daily', zone_id, 'sr', 'daily', 127, 98 FROM app.route WHERE id = $route RETURNING id")
        val o2 = q("INSERT INTO app.outlet (code, name, owner_name, route_id, cluster_id, zone_id, channel, outlet_kind) SELECT 'T-R2-1', 'R2 outlet', 'Owner', $r2, cluster_id, zone_id, channel, outlet_kind FROM app.outlet WHERE route_id = $route ORDER BY id LIMIT 1 RETURNING id")
        x("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from) VALUES ($r2, $sr, 'cover', DATE '2026-01-01')")
        val b = "'00000000-0000-4000-8000-00000000c101'"
        x("INSERT INTO app.route_programme_target (programme_id, route_id, month, target_qty, batch_uuid) SELECT id, $r2, DATE '2027-01-01', 8.000, $b FROM app.programme WHERE code = 'MBLS-P'")
        x("INSERT INTO app.outlet_campaign_target (programme_id, month, outlet_id, std_target, memo_target, batch_uuid) SELECT id, DATE '2027-01-01', $o2, 500, 20, $b FROM app.programme WHERE code = 'SSL-P'")
        x("INSERT INTO app.outlet_campaign_sku_target (programme_id, month, outlet_id, sku_id, qty_base, batch_uuid) SELECT id, DATE '2027-01-01', $o2, $skuC, 500, $b FROM app.programme WHERE code = 'SSL-P'")
        return r2 to o2
    }

    @Test
    fun idHeldVoidReleasedIsNotSubtractedTwice() {
        val p = IdPhone()
        val m = p.sell(uid(1), "2027-01-02", 80, 4); val other = p.sell(uid(2), "2027-01-01", 20); commitId(m); commitId(other)
        assertIdTruth(p, "both confirmed IN")
        m.removed = true                                   // the SR voids M; the void is held for review (device gate): the server still holds M
        assertIdTruth(p, "void held: M is IN, the phone subtracts the lines the server holds")
        fx.void(m.id, m.date)                              // the reviewer accepts the void: M is gone on the server; the phone has not heard
        assertIdTruth(p, "void released before the phone hears: M is VOIDED on the server, nothing is subtracted a second time")
    }

    @Test
    fun idHeldEditReleasedIsNeitherDoubleCountedNorLost() {
        val p = IdPhone()
        val m = p.sell(uid(1), "2027-01-02", 80, 4); commitId(m)
        assertIdTruth(p, "M IN")
        m.removed = true; val e = p.sell(uid(2), "2027-01-03", 90, 4)       // the edit E is dated today and is held
        assertIdTruth(p, "edit held: M still IN (subtracted), E not stored (added)")
        commitId(e, supersedes = m)                                           // the reviewer releases the edit: M superseded, E stored (today: not in a figure)
        assertIdTruth(p, "edit released before the phone hears")
        val m2 = p.sell(uid(3), "2027-01-02", 50); commitId(m2)
        m2.confirmedIn = false; assertIdTruth(p, "another memo of the day")
        m2.removed = true; val e2 = p.sell(uid(4), "2027-01-02", 55); assertIdTruth(p, "same-day edit held")
        commitId(e2, supersedes = m2); assertIdTruth(p, "same-day edit released: M2 VOIDED, E2 IN, nothing added")
    }

    @Test
    fun idAZeroPriceLineRefusedForGoodNeverMakesTheWholeMemoAddedOnEveryBundle() {
        val p = IdPhone()
        val m = p.sell(uid(1), "2027-01-02", 20, 10)                         // the phone sent two lines: SKU C 20, SKU M 10 (priced zero)
        commitId(m, lines = listOf(SrMemoFixture.L(skuC, 20)))               // the server stored the memo with line 1 only; line 2 was refused (price_invalid)
        val shown = p.request(); val serverOnly = Fig(20, 1, 20, 1, 0, 0, 20, 1)
        assertEquals(serverOnly, shown, "the figure holds the stored lines and the phone adds nothing: server-authoritative")
        assertEquals(serverOnly, p.request(), "and again on the next bundle: nothing is added on every bundle")
        val res = service.bundle(principal, day, "1.0.0+1", probe = listOf(SrFigureProbe.Probe(java.util.UUID.fromString(m.id), null, LocalDate.parse(m.date))))
        assertEquals(1, res.probe!!.inFigure[java.util.UUID.fromString(m.id)], "the answer carries the stored line count (1 of the 2 the phone sent), so the phone can show the difference")
    }

    @Test
    fun idAHeldMemoOfTodayReleasedBeforeThePhoneHearsIsExactAndAHeldMemoOfYesterdayToo() {
        val p = IdPhone()
        val today = p.sell(uid(1), "2027-01-03", 50, 2); val yesterday = p.sell(uid(2), "2027-01-02", 30)
        assertIdTruth(p, "both held (not stored): the phone adds both")
        commitId(today); commitId(yesterday)                                  // the reviewer releases both
        assertIdTruth(p, "released before the phone hears: today's is cut by through_date and added, yesterday's is IN and not added")
        assertIdTruth(p, "and again")
    }

    @Test
    fun idAnAdminDataVoidOfTodaysMemosIsNotAdded() {
        val p = IdPhone()
        val a = p.sell(uid(1), "2027-01-03", 60, 2); val b = p.sell(uid(2), "2027-01-03", 40); val old = p.sell(uid(3), "2027-01-02", 25)
        commitId(a); commitId(b); commitId(old)
        assertIdTruth(p, "everything stored")
        fx.void(a.id, a.date); fx.void(b.id, b.date)                          // the office voids the route-day's memos of today
        assertIdTruth(p, "the phone no longer adds today's voided memos (VOIDED), the old one stays IN")
        fx.void(old.id, old.date)
        assertEquals(Fig(), p.request(), "an old memo (confirmed IN earlier) voided by the office: the figure no longer holds it, the phone adds nothing: the office's truth, nothing counted")
    }

    @Test
    fun idAPaperTwinOfAMemoThePhoneHasNotUploadedIsNotCountedTwice() {
        val p = IdPhone()
        val office = q("SELECT id FROM app.app_user WHERE id <> $sr ORDER BY id LIMIT 1")
        val twin = IdMemo(uid(90), "2027-01-02", 300, 0, "sr1001-270102-0007")
        commitId(twin, manualBy = office)                                     // the office keyed the printed memo: stored under ITS uuid
        val phoneCopy = p.sell(uid(1), "2027-01-02", 300, 0, "sr1001-270102-0007")   // the revived phone has its own copy, another uuid, not uploaded
        val shown = p.request()
        assertEquals(Fig(300, 1, 300, 1, 0, 0, 300, 1), shown, "the paper memo is in the figure once and the phone's copy is answered IN through the memo number: 300, not 600")
        assertTrue(phoneCopy.confirmedIn)
        // and the office voids the paper memo: the phone's copy follows
        fx.void(twin.id, twin.date)
        phoneCopy.confirmedIn = false
        assertEquals(Fig(), p.request(), "the paper memo voided: the phone's copy is VOIDED through the twin and not added")
    }

    @Test
    fun idAUuidThatIsNotTheCallersOwnAnswersNotInAndRevealsNothing() {
        val other = q("SELECT id FROM app.app_user WHERE id <> $sr ORDER BY id LIMIT 1")
        SrMemoFixture({ x(it) }, other, 700).memo(uid(60), route, outlet, "2027-01-02", listOf(SrMemoFixture.L(skuC, 33)))
        val res = service.bundle(principal, day, "1.0.0+1", probe = listOf(SrFigureProbe.Probe(java.util.UUID.fromString(uid(60)), null, LocalDate.parse("2027-01-02"))))
        assertTrue(res.probe!!.inFigure.isEmpty() && res.probe!!.voided.isEmpty(), "another user's memo: no answer at all")
    }

    @Test
    fun idAMemoCommittedWhileTheBundleIsReadIsAnsweredInExactlyWhenTheFigureHoldsIt() {
        val ids = (0 until 300).map { uid(2000 + it) }
        val stop = java.util.concurrent.atomic.AtomicBoolean(false); val n = java.util.concurrent.atomic.AtomicInteger(0)
        val t = Thread { while (!stop.get() && n.get() < ids.size) { fx.memo(ids[n.getAndIncrement()], route, outlet, "2027-01-02", listOf(SrMemoFixture.L(skuC, 1))) } }
        t.start()
        var builds = 0
        try {
            while (t.isAlive && builds < 40) {
                val res = service.bundle(principal, day, "1.0.0+1", probe = ids.map { SrFigureProbe.Probe(java.util.UUID.fromString(it), null, LocalDate.parse("2027-01-02")) })
                val f = figuresOf(res, day).first; builds++
                assertEquals(res.probe!!.inFigure.size.toLong(), f.achQty, "build $builds: the memos answered IN are exactly the memos the figure holds (one snapshot)")
                assertEquals(f.achQty, f.campStd)
            }
        } finally { stop.set(true); t.join(30_000) }
        assertTrue(builds >= 3)
    }


    private fun note(s: String) = println("NOTE SrPhoneWouldShowTest: $s")
}

package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlReachResolver
import com.aktcl.aron.backend.masterdata.SqlRoutePlanner
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.sync.BundleService
import com.aktcl.aron.backend.sync.SrMemoFigures
import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonArray
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test

/**
 * MEASUREMENT, not a regression test: runs only with `ARON_SCALE_MEASURE=1` (it builds 500 routes x 28 days x 60 memos x 4 lines = 840,000 memos and 3.36 M lines
 * in a scratch database, a few minutes and about 1.5 GB). It prints the plan and the time of the direct memo read per route (warm, then after a PostgreSQL restart),
 * and the end-to-end bundle build and size for an SR route with 70 outlets x 20 SKUs. The numbers are copied into docs/status/sr-targets-server.md (E).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SrBundleScaleMeasureTest {
    private val clock = AronClock { Instant.parse("2027-01-29T02:00:00Z") }
    private val day = LocalDate.parse("2027-01-29")   // through_date = 2027-01-28: 28 closed days of the month

    private fun out(s: String) = println("SCALE $s")

    @Test
    fun measure() {
        Assumptions.assumeTrue(System.getenv("ARON_SCALE_MEASURE") == "1", "set ARON_SCALE_MEASURE=1 to run the fleet-size measurement")
        val fresh = FreshDb.create()
        try {
            val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
            fresh.dataSource.connection.use { c -> seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } } }
            fun q(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
            fun x(sql: String) = fresh.db.jdbi.useHandle<Exception> { h -> h.execute(sql) }
            val config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
            val geo = GeoRepository(fresh.db, clock)
            val service = BundleService(fresh.db, config, SqlRoutePlanner(fresh.db, geo, config), clock, reach = SqlReachResolver(fresh.db, geo, clock))
            val sr = q("SELECT id FROM app.app_user WHERE username = 'sr1001'")
            val principal = AronPrincipal(sr, "sr1001", Role.SR, 0, "api", null, null, "sr", emptyList(), false, emptyList(), "j", clock.now().plusSeconds(600))
            val route = service.bundle(principal, day, "1.0.0+1").full.routes.first().route_id
            val skuCount = q("SELECT count(*) FROM app.sku"); out("skus in the catalogue: $skuCount")

            // 500 routes (the SR's included) with 70 outlets each
            val t0 = System.nanoTime()
            x("INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no) SELECT 'SCALE-' || g, 'Scale route ' || g, 'Daily', zone_id, 'sr', 'daily', 127, 1000 + g FROM app.route r, generate_series(1, 499) g WHERE r.id = $route")
            x("""INSERT INTO app.outlet (code, name, owner_name, route_id, cluster_id, zone_id, channel, outlet_kind)
                 SELECT 'SC-' || r.id || '-' || g, 'Scale outlet', 'Owner', r.id, o.cluster_id, o.zone_id, o.channel, o.outlet_kind
                   FROM app.route r CROSS JOIN generate_series(1, 70) g CROSS JOIN LATERAL (SELECT * FROM app.outlet WHERE route_id = $route ORDER BY id LIMIT 1) o
                  WHERE r.code LIKE 'SCALE-%'""")
            val own = q("SELECT count(*) FROM app.outlet WHERE route_id = $route")
            if (own < 70) x("""INSERT INTO app.outlet (code, name, owner_name, route_id, cluster_id, zone_id, channel, outlet_kind)
                 SELECT 'SC-OWN-' || g, 'Scale outlet', 'Owner', $route, o.cluster_id, o.zone_id, o.channel, o.outlet_kind
                   FROM generate_series(1, ${70 - own}) g CROSS JOIN LATERAL (SELECT * FROM app.outlet WHERE route_id = $route ORDER BY id LIMIT 1) o""")
            out("routes=${q("SELECT count(*) FROM app.route")}, outlets=${q("SELECT count(*) FROM app.outlet")} (${"%.1f".format((System.nanoTime() - t0) / 1e9)} s)")

            // memos in INGEST order (day, k, route): one route's rows are scattered over the heap, like production
            val t1 = System.nanoTime()
            x("CREATE UNLOGGED TABLE public.scale_stage AS SELECT gen_random_uuid() AS id, d, k, r.id AS route_id FROM generate_series(1, 28) d, generate_series(1, 60) k, app.route r WHERE r.id IN (SELECT route_id FROM app.outlet GROUP BY route_id HAVING count(*) >= 70) ORDER BY d, k, r.id")
            x("ALTER TABLE public.scale_stage ADD COLUMN gross bigint")
            x("UPDATE public.scale_stage SET gross = (SELECT sum(((k + j) % 20 + 1) * 100) FROM generate_series(1, 4) j)")
            out("stage rows=${q("SELECT count(*) FROM public.scale_stage")} (${"%.1f".format((System.nanoTime() - t1) / 1e9)} s)")
            x("""INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_client_uuid, outlet_id, memo_no, memo_kind, committed_at,
                                      price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, round_adj_mtk, net_mtk, paid_mtk, due_mtk, is_credit,
                                      line_count, discount_line_count, qc_line_count, status, received_at)
                 SELECT id, id, DATE '2027-01-01' + (d - 1), $sr, route_id, TIMESTAMPTZ '2027-01-01 10:00:00+06' + (d - 1) * interval '1 day', 0, gen_random_uuid(), ro.outlets[(k - 1) % 70 + 1],
                        'r' || lpad(route_id::text, 5, '0') || '-' || to_char(DATE '2027-01-01' + (d - 1), 'YYMMDD') || '-' || lpad(k::text, 3, '0'), 'sale',
                        TIMESTAMPTZ '2027-01-01 10:00:00+06' + (d - 1) * interval '1 day', DATE '2027-01-01' + (d - 1), 'outlet', gross, 0, 0, 0, 0, gross, gross, 0, false, 4, 0, 0, 'active',
                        TIMESTAMPTZ '2027-01-01 10:00:00+06' + (d - 1) * interval '1 day' + interval '1 hour'
                   FROM public.scale_stage st JOIN (SELECT route_id, array_agg(id ORDER BY id) AS outlets FROM app.outlet GROUP BY route_id) ro USING (route_id) ORDER BY d, k, route_id""")
            out("memos=${q("SELECT count(*) FROM app.memo")} (${"%.1f".format((System.nanoTime() - t1) / 1e9)} s)")
            x("""INSERT INTO app.memo_line (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, memo_client_uuid, line_no, sku_id, line_kind, qty_entered, unit_entered,
                                           pack_factor, qty_base, price_type, price_valid_from, base_price_mtk, price_per_qty, gross_mtk)
                 SELECT gen_random_uuid(), gen_random_uuid(), DATE '2027-01-01' + (d - 1), $sr, route_id, TIMESTAMPTZ '2027-01-01 10:00:00+06' + (d - 1) * interval '1 day', 0, id, j,
                        sk.a[(route_id + k + j * 7) % $skuCount + 1], 'sale', (k + j) % 20 + 1, 'stick', 1, (k + j) % 20 + 1, 'outlet', DATE '2027-01-01', 100, 1, ((k + j) % 20 + 1) * 100
                   FROM public.scale_stage CROSS JOIN generate_series(1, 4) j CROSS JOIN (SELECT array_agg(id ORDER BY id) AS a FROM app.sku) sk ORDER BY d, k, route_id, j""")
            out("lines=${q("SELECT count(*) FROM app.memo_line")} (${"%.1f".format((System.nanoTime() - t1) / 1e9)} s)")
            x("DROP TABLE public.scale_stage"); fresh.dataSource.connection.use { c -> c.createStatement().use { it.execute("ANALYZE") } }
            out("heap size app.memo+memo_line: ${q("SELECT (pg_total_relation_size('app.memo') + pg_total_relation_size('app.memo_line')) / 1048576")} MB")

            // the SR's real work: targets, a Star, KPI and 70 campaign outlets x 20 SKUs
            val batch = "'00000000-0000-4000-8000-00000000c201'"
            x("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ($batch, decode(repeat('ce', 32), 'hex'), 1, $sr)")
            x("INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid) SELECT $route, id, DATE '2027-01-01', 5000, $batch FROM app.sku")
            val star = q("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('superstar', 'MBLS-X', 'Star', DATE '2026-12-01', DATE '2027-01-31', 'active') RETURNING id")
            x("INSERT INTO app.route_programme_target (programme_id, route_id, month, target_qty, batch_uuid) VALUES ($star, $route, DATE '2027-01-01', 20.000, $batch)")
            val camp = q("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('campaign', 'SSL-X', 'Campaign', DATE '2026-12-01', DATE '2027-01-31', 'active') RETURNING id")
            x("INSERT INTO app.outlet_campaign_target (programme_id, month, outlet_id, std_target, memo_target, batch_uuid) SELECT $camp, DATE '2027-01-01', id, 5000, 20, $batch FROM app.outlet WHERE route_id = $route")
            x("INSERT INTO app.outlet_campaign_sku_target (programme_id, month, outlet_id, sku_id, qty_base, batch_uuid) SELECT $camp, DATE '2027-01-01', o.id, s.id, 500, $batch FROM app.outlet o CROSS JOIN (SELECT id FROM app.sku ORDER BY id LIMIT 20) s WHERE o.route_id = $route")

            val from = YearMonth.from(day).atDay(1); val through = day.minusDays(1)
            fun ms(f: () -> Unit): Double { val s = System.nanoTime(); f(); return (System.nanoTime() - s) / 1e6 }
            fun stats(l: List<Double>) = l.sorted().let { "median ${"%.1f".format(it[it.size / 2])} ms, p95 ${"%.1f".format(it[(it.size * 95 / 100).coerceAtMost(it.size - 1)])} ms, max ${"%.1f".format(it.last())} ms" }
            fun explain(label: String) {
                val plan = fresh.db.jdbi.withHandle<List<String>, Exception> { h ->
                    h.createQuery("EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT m.route_id, m.client_uuid, ml.sku_id, coalesce(sum(ml.qty_base) FILTER (WHERE ml.line_kind = 'sale'), 0), count(*) FILTER (WHERE ml.line_kind = 'sale'), max(m.received_at) FROM app.memo m JOIN app.memo_line ml ON ml.memo_client_uuid = m.client_uuid AND ml.business_date = m.business_date WHERE m.route_id = ANY(ARRAY[$route]) AND m.business_date BETWEEN DATE '$from' AND DATE '$through' AND ${SrMemoFigures.ACTIVE_MEMO} AND ml.voided_at IS NULL GROUP BY m.route_id, m.client_uuid, ml.sku_id")
                        .mapTo(String::class.java).list()
                }
                out("PLAN ($label), the route's month read:"); plan.forEach { out("  $it") }
            }

            // WARM: the same read after a few runs
            repeat(3) { fresh.db.jdbi.withHandle<Any, Exception> { h -> SrMemoFigures.forRoutes(h, listOf(route), from, through) } }
            explain("warm")
            val warm = (1..30).map { ms { fresh.db.jdbi.withHandle<Any, Exception> { h -> SrMemoFigures.forRoutes(h, listOf(route), from, through) } } }
            val figures = fresh.db.jdbi.withHandle<Map<Long, SrMemoFigures.Figures>, Exception> { h -> SrMemoFigures.forRoutes(h, listOf(route), from, through) }
            out("route read WARM (one route, month to date, ${figures[route]?.lines?.size} (memo, SKU) rows, ${figures[route]?.lines?.map { it.memo }?.toSet()?.size} memos): ${stats(warm)}")
            val warm3 = (1..30).map { ms { fresh.db.jdbi.withHandle<Any, Exception> { h -> SrMemoFigures.forRoutes(h, listOf(route, route + 1, route + 2), from, through) } } }
            out("route read WARM (three routes in one query, a cover SR): ${stats(warm3)}")
            val campWarm = (1..30).map { ms { fresh.db.jdbi.withHandle<Any, Exception> { h ->
                val outlets = h.createQuery("SELECT id FROM app.outlet WHERE route_id = $route").mapTo(Long::class.java).list()
                val skus = h.createQuery("SELECT id FROM app.sku ORDER BY id LIMIT 20").mapTo(Long::class.java).list()
                SrMemoFigures.forOutletPairs(h, outlets.flatMap { o -> skus.map { o to it } }, from, through)
            } } }
            out("campaign read WARM (70 outlets x 20 SKUs): ${stats(campWarm)}")
            fun size(label: String, raw: ByteArray) { val gz = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(raw) } }.size(); out("SIZE $label: raw ${raw.size} bytes, gzip $gz bytes") }
            // END TO END build (everything the route snapshot reads, in the one repeatable-read snapshot), warm
            val build = (1..15).map { ms { service.bundle(principal, day, "1.0.0+1") } }
            out("END-TO-END bundle build WARM (SR with one route: 70 outlets x 20 campaign SKUs, targets for the whole catalogue, KPI, Star): ${stats(build)}")
            val res = service.bundle(principal, day, "1.0.0+1")
            val r = res.full.routes.single { it.route_id == route }
            val json = kotlinx.serialization.json.Json { encodeDefaults = false }
            size("campaigns (${r.campaigns?.size} outlets)", JsonArray(r.campaigns.orEmpty()).toString().toByteArray())
            size("achievement_mtd (${r.achievement_mtd.size} rows)", JsonArray(r.achievement_mtd).toString().toByteArray())
            size("targets (${r.targets.size} rows)", JsonArray(r.targets).toString().toByteArray())
            size("kpi", (r.kpi?.toString() ?: "").toByteArray()); size("star", (r.star?.toString() ?: "").toByteArray())
            size("whole bundle (wire form)", json.encodeToString(com.aktcl.aron.backend.sync.Bundle.serializer(), res.bundle).toByteArray())

            // COLD: restart PostgreSQL (empties shared_buffers; the OS page cache is dropped when the container lets us), then the same read and the end-to-end build
            runCatching { ProcessBuilder("sh", "-c", "sync; echo 3 > /proc/sys/vm/drop_caches").redirectErrorStream(true).start().also { it.waitFor() } }
            val restart = ProcessBuilder("pg_ctlcluster", "16", "main", "restart").redirectErrorStream(true).start(); restart.waitFor()
            out("postgres restarted (exit ${restart.exitValue()}); OS cache dropped: ${runCatching { File("/proc/sys/vm/drop_caches").canWrite() }.getOrDefault(false)}")
            Thread.sleep(2000)
            val coldRead = ms { fresh.db.jdbi.withHandle<Any, Exception> { h -> SrMemoFigures.forRoutes(h, listOf(route), from, through) } }
            out("route read COLD (first read after the restart): ${"%.1f".format(coldRead)} ms")
            explain("after the first cold read, now warm")
            ProcessBuilder("pg_ctlcluster", "16", "main", "restart").redirectErrorStream(true).start().waitFor(); Thread.sleep(2000)
            val coldBuild = ms { service.bundle(principal, day, "1.0.0+1") }
            out("END-TO-END bundle build COLD (first build after a restart): ${"%.1f".format(coldBuild)} ms")
        } finally { fresh.close() }
    }
}

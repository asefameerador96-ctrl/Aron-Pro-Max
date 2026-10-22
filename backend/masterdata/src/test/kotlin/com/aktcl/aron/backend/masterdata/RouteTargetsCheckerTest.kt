package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.rules.BusinessDate
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.YearMonth
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T1 checker (docs/29) for D-TARGET-MIN-01, PUT/GET /v1/admin/route-targets: tries to break totals, duplicates, idempotency,
 * concurrency, all-or-none, boundaries, the Dhaka month boundary, the role matrix and the GET cursor.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RouteTargetsCheckerTest {
    private lateinit var env: BackendAdminEnv

    @BeforeAll fun setUp() {
        env = BackendAdminEnv()
        // 100 extra selling routes so the volume tests can build 20 000 distinct (route, SKU, month) keys.
        env.exec(
            "INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'CHKR-' || lpad(g::text, 3, '0'), 'Checker route ' || g, z.id, 'sr', 'daily', 127 " +
                "FROM app.zone z, generate_series(1, 100) g WHERE z.code = 'Z-MIR'",
        )
    }
    @AfterAll fun tearDown() = env.close()

    // wall-clock-ok: the env app judges "past month" against the Dhaka month of the system clock
    private val thisMonth: YearMonth = YearMonth.from(BusinessDate.of(System.currentTimeMillis()).toJavaLocalDate())
    private fun m(offset: Long): YearMonth = thisMonth.plusMonths(offset)
    private val reason = "Checker upload of the monthly target sheet"
    private val path = "/v1/admin/route-targets"

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun rowRaw(route: String, sku: String, month: String, qty: String) = """{"route_code":$route,"sku_code":$sku,"month":$month,"qty_base":$qty}"""
    private fun row(route: String, sku: String, month: Any, qty: Long) = rowRaw(q(route), q(sku), q(month.toString()), qty.toString())
    private fun body(rows: List<String>, dry: Boolean, u: UUID = UUID.randomUUID(), reason: String? = this.reason) =
        """{"batch_uuid":"$u","rows":[${rows.joinToString(",")}],"dry_run":$dry${if (reason != null) ""","change_reason":${q(reason)}""" else ""}}"""

    private fun sellingRoutes(): List<String> = env.db.jdbi.withHandle<List<String>, Exception> { h ->
        h.createQuery("SELECT code FROM app.route WHERE status = 'active' AND kind = 'sr' ORDER BY code").mapTo(String::class.java).list()
    }
    private fun skus(): List<String> = env.db.jdbi.withHandle<List<String>, Exception> { h ->
        h.createQuery("SELECT code FROM app.sku WHERE status = 'active' ORDER BY code").mapTo(String::class.java).list()
    }
    private fun sumMonth(ym: YearMonth): Long = env.scalar("SELECT COALESCE(sum(qty_base), 0) FROM app.route_monthly_target WHERE month = date '$ym-01'")!!.toLong()
    private fun countMonth(ym: YearMonth): Int = env.count("SELECT count(*) FROM app.route_monthly_target WHERE month = date '$ym-01'")
    private fun batchRows(u: UUID) = env.count("SELECT count(*) FROM app.route_target_batch WHERE batch_uuid = '$u'")
    private fun audits(u: UUID) = env.count("SELECT count(*) FROM app.audit_log WHERE entity = 'route_target' AND entity_id = '$u'")

    private suspend fun ApplicationTestBuilder.put(b: String, user: String? = "admin1001"): HttpResponse = sendA3(HttpMethod.Put, path, user?.let { env.tok(it) }, b)

    private suspend fun HttpResponse.errorTriples() = objA3().getValue("errors").jsonArray.map { it.jsonObject }.map { Triple(it.strA3("row").toInt(), it.strA3("column"), it.strA3("code")) }

    /** An app with only the route-target routes and an injected clock. */
    private fun clockApp(clock: AronClock, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val cfg = env.config()
        application {
            installAronPlatform(PlatformContext(AronClock.SYSTEM, cfg, { "00000000-0000-0000-0000-000000000000" }))
            val guard = TestTokens.guard(cfg)
            routing { route("/v1") { routeTargetRoutes(RouteTargetDeps(env.db, guard, clock)) } }
        }
        block()
    }

    // ---------------------------------------------------------------- 1. totals

    @Test
    fun c1_previewTotalMatchesAnOracleOverRandomMixedBatches() = env.app {
        val routes = sellingRoutes().take(6)
        val skus = skus().take(4)
        val f1 = m(30); val f2 = m(31)
        // Existing keys so `replaces` is exercised.
        val pre = listOf(Triple(routes[0], skus[0], f1) to 5L, Triple(routes[1], skus[0], f1) to 7L, Triple(routes[2], skus[1], f2) to 9L)
        assertEquals(HttpStatusCode.OK, put(body(pre.map { row(it.first.first, it.first.second, it.first.third, it.second) }, false)).status)
        val existing = pre.map { it.first.let { k -> Triple(k.first, k.second, k.third.toString()) } }.toSet()
        for (seed in 1..6) {
            val rnd = Random(seed)
            val routePool = routes + listOf("NOPE", "MIR-AMO-1", " ${routes[0]} ")
            val skuPool = skus + listOf("NOSKU", "\t${skus[0]}")
            val monthPool = listOf(f1.toString(), f2.toString(), thisMonth.minusMonths(1).toString(), "2026-13", " $f1")
            val qtyPool = listOf("-1", "1000000001", "1.5", "\"3\"", "null")
            val rows = ArrayList<String>(); var total = 0L; var replaces = 0
            val seen = HashSet<Triple<String, String, String>>(); val errRows = HashSet<Int>()
            repeat(300) { i ->
                val r = routePool.random(rnd); val s = skuPool.random(rnd); val mo = monthPool.random(rnd)
                val qtyOk = rnd.nextInt(10) != 0
                val qv = if (qtyOk) rnd.nextLong(0, 1_000_001).toString() else qtyPool.random(rnd)
                rows += rowRaw(q(r), q(s), q(mo), qv)
                val rt = r.trim(); val st = s.trim(); val mt = mo
                val ok = rt in routes && st in skus && (mt == f1.toString() || mt == f2.toString()) && qtyOk
                if (!ok) errRows += i + 1
                else if (!seen.add(Triple(rt, st, mt))) errRows += i + 1
                else { total += qv.toLong(); if (Triple(rt, st, mt) in existing) replaces++ }
            }
            val r = put(body(rows, true, reason = null))
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            val o = r.objA3()
            assertEquals(total.toString(), o.strA3("total_qty_base"), "seed $seed total")
            assertEquals(replaces.toString(), o.strA3("replaces"), "seed $seed replaces")
            assertEquals(errRows, r.errorTriples().map { it.first }.toSet(), "seed $seed error rows")
        }
    }

    @Test
    fun c1_commitTableSumEqualsBatchSumAndReplaceAccountingIsExact() = env.app {
        val routes = sellingRoutes().take(5); val skus = skus().take(3)
        val f = m(32)
        val rnd = Random(7)
        val all = routes.flatMap { r -> skus.map { s -> Triple(r, s, rnd.nextLong(0, 1_000_000_000)) } }
        val a = all.take(6); val fresh = all.drop(6)
        val ua = UUID.randomUUID()
        assertEquals(HttpStatusCode.OK, put(body(a.map { row(it.first, it.second, f, it.third) }, false, ua)).status)
        assertEquals(a.sumOf { it.third }, sumMonth(f))
        // Batch B: 3 rows replace existing keys with new quantities, the rest are fresh.
        val replacedKeys = a.take(3)
        val bRows = replacedKeys.map { Triple(it.first, it.second, it.third / 2 + 1) } + fresh
        val ub = UUID.randomUUID()
        val pv = put(body(bRows.map { row(it.first, it.second, f, it.third) }, true, ub, null)).objA3()
        assertEquals("3", pv.strA3("replaces")); assertEquals(bRows.sumOf { it.third }.toString(), pv.strA3("total_qty_base"))
        assertEquals(HttpStatusCode.OK, put(body(bRows.map { row(it.first, it.second, f, it.third) }, false, ub)).status)
        assertEquals(a.drop(3).sumOf { it.third } + bRows.sumOf { it.third }, sumMonth(f))
        assertEquals(all.size, countMonth(f))
        val after = env.scalar("SELECT after::text FROM app.audit_log WHERE entity = 'route_target' AND entity_id = '$ub'")!!
        assertTrue(after.contains("\"replaced_rows\": 3") || after.contains("\"replaced_rows\":3"), after)
        assertTrue(after.contains("${replacedKeys.sumOf { it.third }}"), "replaced_total_qty_base: $after")
        assertTrue(after.contains("${bRows.sumOf { it.third }}"), "total_qty_base: $after")
    }

    // ---------------------------------------------------------------- 2. duplicates

    @Test
    fun c2_duplicateKeyWithOtherSpellingRefusesTheWholeCommit() = env.app {
        val f = m(35)
        val variants = listOf(
            listOf(row("MIR-SR-D", "MaxR-10S", f, 5), row(" MIR-SR-D ", "MaxR-10S", f, 6)),
            listOf(row("MIR-SR-D", "MaxR-10S", f, 5), row("MIR-SR-D", " MaxR-10S\t", f, 6)),
            listOf(row("MIR-SR-D", "MaxR-10S", f, 5), row("MIR-SR-3F", "MaxR-10S", f, 1), row("MIR-SR-D", "MaxR-10S", f, 5)),
        )
        for (v in variants) {
            val u = UUID.randomUUID()
            val r = put(body(v, false, u))
            assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
            assertTrue(r.bodyAsText().contains("duplicate_row"), r.bodyAsText())
            assertEquals(0, batchRows(u)); assertEquals(0, countMonth(f), "no partial write"); assertEquals(0, audits(u))
        }
        // Case differs: a different code, so unknown_route, never a silent merge.
        val r = put(body(listOf(row("MIR-SR-D", "MaxR-10S", f, 5), row("mir-sr-d", "MaxR-10S", f, 6)), true, reason = null))
        assertEquals(listOf(Triple(2, "route", "unknown_route")), r.errorTriples())
    }

    // ---------------------------------------------------------------- 3. idempotency and concurrency

    @Test
    fun c3_replayAnyOrderZeroWritesConflictsOnOtherContentOrUser() = env.app {
        val f = m(36)
        val rows = listOf(row("MIR-SR-D", "MaxR-10S", f, 10), row("MIR-SR-3F", "MaxR-20S", f, 20), row("MIR-SR-2F", "MaxB-10S", f, 30))
        val u = UUID.randomUUID()
        assertEquals(HttpStatusCode.OK, put(body(rows, false, u)).status)
        val stamp = env.scalar("SELECT max(updated_at)::text FROM app.route_monthly_target WHERE batch_uuid = '$u'")
        for (perm in listOf(rows.reversed(), listOf(rows[1], rows[0], rows[2]), rows.map { it.replace("\"MIR-SR-D\"", "\" MIR-SR-D\"") })) {
            val r = put(body(perm, false, u, "A different reason for the same content"))
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText()); assertEquals("true", r.objA3().strA3("replayed")); assertEquals("3", r.objA3().strA3("rows"))
        }
        assertEquals(stamp, env.scalar("SELECT max(updated_at)::text FROM app.route_monthly_target WHERE batch_uuid = '$u'"), "a replay writes nothing")
        assertEquals(1, audits(u))
        // Other content: one quantity changed, a row added, a month changed; another user.
        assertEquals(HttpStatusCode.Conflict, put(body(rows.dropLast(1) + row("MIR-SR-2F", "MaxB-10S", f, 31), false, u)).status)
        assertEquals(HttpStatusCode.Conflict, put(body(rows + row("MIR-SR-D", "MaxB-10S", f, 1), false, u)).status)
        assertEquals(HttpStatusCode.Conflict, put(body(rows.dropLast(1) + row("MIR-SR-2F", "MaxB-10S", f.plusMonths(1), 30), false, u)).status)
        assertEquals(HttpStatusCode.Conflict, put(body(rows, false, u), "superadmin1001").status)
        assertEquals(60, sumMonth(f)); assertEquals(1, audits(u))
    }

    @Test
    fun c3_concurrentSameBatchUuidWritesOnce() = env.app {
        val f = m(37)
        val rows = sellingRoutes().take(5).flatMap { r -> skus().take(3).map { s -> row(r, s, f, 11) } }
        repeat(3) { round ->
            val u = UUID.randomUUID()
            val b = body(rows.map { it.replace("11}", "${11 + round}}") }, false, u)
            val res = coroutineScope { List(8) { async(Dispatchers.IO) { put(b).let { it.status to it.bodyAsText() } } }.awaitAll() }
            assertTrue(res.all { it.first == HttpStatusCode.OK }, res.toString())
            assertEquals(1, res.count { it.second.contains("\"replayed\":false") }, res.toString())
            assertEquals(1, batchRows(u)); assertEquals(1, audits(u))
            assertEquals(rows.size, countMonth(f)); assertEquals(rows.size * (11L + round), sumMonth(f))
        }
    }

    @Test
    fun c3_concurrentDifferentBatchesOnTheSameKeysEndConsistent() = env.app {
        val f = m(38)
        val keys = sellingRoutes().take(20).flatMap { r -> skus().take(3).map { s -> r to s } }
        repeat(5) { round ->
            val ua = UUID.randomUUID(); val ub = UUID.randomUUID()
            val a = body(keys.map { row(it.first, it.second, f, 111) }, false, ua)
            val b = body(keys.reversed().map { row(it.first, it.second, f, 222) }, false, ub)
            val res = withTimeout(60_000) { coroutineScope { listOf(async(Dispatchers.IO) { put(a).status }, async(Dispatchers.IO) { put(b).status }).awaitAll() } }
            assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.OK), res, "round $round")
            val distinct = env.scalar("SELECT string_agg(DISTINCT qty_base::text || '/' || batch_uuid::text, ',') FROM app.route_monthly_target WHERE month = date '$f-01'")!!
            assertEquals(1, distinct.split(',').size, "one batch wins every key, no mix: $distinct")
            assertTrue(distinct == "111/$ua" || distinct == "222/$ub", distinct)
            assertEquals(keys.size, countMonth(f))
        }
    }

    @Test
    fun c3_requestsQueueBehindTheUploadLockAndSameUuidDifferentContentGivesOne409() = env.app {
        val f = m(39)
        val u = UUID.randomUUID()
        val b1 = body(listOf(row("MIR-SR-D", "MaxR-10S", f, 1)), false, u)
        val b2 = body(listOf(row("MIR-SR-D", "MaxR-10S", f, 2)), false, u)
        env.seeded.fresh.dataSource.connection.use { c ->
            c.autoCommit = false
            c.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(hashtextextended('route-target-upload', 0))") }
            val res = withTimeout(60_000) {
                coroutineScope {
                    val d = listOf(async(Dispatchers.IO) { put(b1).status }, async(Dispatchers.IO) { put(b2).status })
                    delay(700)
                    assertFalse(d.any { it.isCompleted }, "both commits wait for the upload lock")
                    c.commit()
                    d.awaitAll()
                }
            }
            assertEquals(setOf(HttpStatusCode.OK, HttpStatusCode.Conflict), res.toSet(), res.toString())
        }
        assertEquals(1, batchRows(u)); assertEquals(1, countMonth(f)); assertEquals(1, audits(u))
    }

    // ---------------------------------------------------------------- 4. all or none

    @Test
    fun c4_oneBadRowAmong500GoodWritesNothing() = env.app {
        val routes = sellingRoutes(); val skus = skus().take(5)
        val good = routes.flatMap { r -> skus.map { s -> row(r, s, m(40), 3) } }.take(500)
        assertEquals(500, good.size)
        for ((i, bad) in listOf(row("MIR-SR-D", "MaxR-10S", m(40), 1_000_000_001), row("NOPE", "MaxR-10S", m(40), 1), row("MIR-SR-D", "MaxR-10S", thisMonth.minusMonths(1), 1)).withIndex()) {
            val u = UUID.randomUUID()
            val rows = good.take(250) + bad + good.drop(250)
            val r = put(body(rows, false, u))
            assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
            assertTrue(r.bodyAsText().contains("body.rows/250/"), "case $i: ${r.bodyAsText()}")
            assertEquals(0, countMonth(m(40))); assertEquals(0, batchRows(u)); assertEquals(0, audits(u))
        }
    }

    @Test
    fun c4_databaseFailureMidCommitLeavesNoBatchNoRowsAndNoAudit() = env.app {
        val f = m(41)
        assertEquals(HttpStatusCode.OK, put(body(listOf(row("MIR-SR-D", "MaxR-10S", f, 5)), false)).status)
        env.exec(
            "CREATE OR REPLACE FUNCTION app.chk_rt_fail() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN " +
                "IF NEW.qty_base = 777777 THEN RAISE EXCEPTION 'forced check' USING ERRCODE = 'check_violation'; END IF; " +
                "IF NEW.qty_base = 888888 THEN RAISE EXCEPTION 'forced internal' USING ERRCODE = 'XX000'; END IF; RETURN NEW; END \$\$",
        )
        env.exec("CREATE TRIGGER chk_rt_fail BEFORE INSERT OR UPDATE ON app.route_monthly_target FOR EACH ROW EXECUTE FUNCTION app.chk_rt_fail()")
        try {
            for ((poison, expect) in listOf(777777L to HttpStatusCode.BadRequest, 888888L to HttpStatusCode.InternalServerError)) {
                val u = UUID.randomUUID()
                // The first row updates the existing key, then the fourth row fails inside the JDBC batch.
                val rows = listOf(row("MIR-SR-D", "MaxR-10S", f, 9), row("MIR-SR-3F", "MaxR-10S", f, 9), row("MIR-SR-2F", "MaxR-10S", f, 9), row("MIR-SR-D", "MaxR-20S", f, poison), row("MIR-SR-3F", "MaxR-20S", f, 9))
                val r = put(body(rows, false, u))
                assertEquals(expect, r.status, r.bodyAsText())
                assertEquals(0, batchRows(u), "no batch record"); assertEquals(0, audits(u))
                assertEquals(1, countMonth(f)); assertEquals(5, sumMonth(f), "the replaced row is untouched")
                // The same uuid can be retried after the failure (nothing was remembered).
            }
        } finally {
            env.exec("DROP TRIGGER IF EXISTS chk_rt_fail ON app.route_monthly_target")
        }
    }

    // ---------------------------------------------------------------- 5. boundaries

    @Test
    fun c5_quantityBoundaries() = env.app {
        val f = m(42)
        for ((qty, ok) in listOf("0" to true, "1000000000" to true, "1" to true)) {
            val r = put(body(listOf(rowRaw(q("MIR-SR-D"), q("MaxR-10S"), q(f.toString()), qty)), true, reason = null))
            assertEquals(if (ok) emptyList() else listOf(Triple(1, "quantity", "invalid_quantity")), r.errorTriples(), "qty $qty")
        }
        val refused = listOf("1000000001", "-1", "1.0", "\"5\"", "null", "1e3", "1E3", "1e999999999", "99999999999999999999999999", "true", "-0", "[]", "{}", "0.0", "10E-1")
        for (qty in refused) {
            val r = put(body(listOf(rowRaw(q("MIR-SR-D"), q("MaxR-10S"), q(f.toString()), qty)), true, reason = null))
            assertEquals(HttpStatusCode.OK, r.status, "qty $qty: ${r.bodyAsText()}")
            assertEquals(listOf(Triple(1, "quantity", "invalid_quantity")), r.errorTriples(), "qty $qty")
        }
        // qty_base missing
        val r = put(body(listOf("""{"route_code":"MIR-SR-D","sku_code":"MaxR-10S","month":"$f"}"""), true, reason = null))
        assertEquals(listOf(Triple(1, "quantity", "invalid_quantity")), r.errorTriples())
        // Commit at the upper bound stores exactly 1e9.
        assertEquals(HttpStatusCode.OK, put(body(listOf(row("MIR-SR-D", "MaxR-10S", f, 1_000_000_000), row("MIR-SR-3F", "MaxR-10S", f, 0)), false)).status)
        assertEquals(1_000_000_000L, sumMonth(f)); assertEquals(2, countMonth(f))
    }

    /** JSON forbids leading zeros; a non-JSON number must not become a quantity. */
    @Test
    fun c5_leadingZeroQuantityIsNotAccepted() = env.app {
        val r = put(body(listOf(rowRaw(q("MIR-SR-D"), q("MaxR-10S"), q(m(42).toString()), "007")), true, reason = null))
        assertTrue(r.status == HttpStatusCode.BadRequest || r.errorTriples() == listOf(Triple(1, "quantity", "invalid_quantity")), "007 was accepted: ${r.status} ${r.bodyAsText()}")
    }

    @Test
    fun c5_monthBoundaries() = env.app {
        val refused = listOf("\"2026-00\"", "\"2026-13\"", "\"26-01\"", "\"2026-1\"", "\"২০২৬-১১\"", "\"２０２６-11\"", "\"2026-11-01\"", "\"2026/11\"", "202611", "null", "\"\"", "\"+2026-11\"")
        for (mo in refused) {
            val r = put(body(listOf(rowRaw(q("MIR-SR-D"), q("MaxR-10S"), mo, "1")), true, reason = null))
            assertEquals(HttpStatusCode.OK, r.status, "month $mo: ${r.bodyAsText()}")
            assertEquals(listOf(Triple(1, "month", "invalid_month")), r.errorTriples(), "month $mo")
        }
    }

    /** Contract RouteTargetMonth is ^[0-9]{4}-(0[1-9]|1[0-2])$; the server trims first. Same for codes (maxLength 40 counts the padding). */
    @Test
    fun c5_paddedMonthIsRefusedAsTheContractPatternSays() = env.app {
        for (mo in listOf(" ${m(43)} ", "${m(43)}\n", "\t${m(43)}")) {
            val r = put(body(listOf(rowRaw(q("MIR-SR-D"), q("MaxR-10S"), q(mo), "1")), true, reason = null))
            assertEquals(listOf(Triple(1, "month", "invalid_month")), r.errorTriples(), "padded month ${q(mo)} accepted: ${r.bodyAsText()}")
        }
    }

    /** Observation (passes, documents behaviour): no upper bound on the month, so a sheet typo like 2206-01 for 2026-01 is accepted as a target 180 years out. */
    @Test
    fun c5_farFutureMonthIsAccepted_observation() = env.app {
        val r = put(body(listOf(row("MIR-SR-D", "MaxR-10S", "9999-12", 1)), true, reason = null))
        assertEquals(emptyList(), r.errorTriples(), r.bodyAsText())
    }

    @Test
    fun c5_codesShapesAndMembers() = env.app {
        val f = m(44).toString()
        val cases = listOf(
            rowRaw(q("A".repeat(41)), q("MaxR-10S"), q(f), "1") to listOf(Triple(1, "route", "unknown_route")),
            rowRaw(q("MIR-SR-D"), q("M".repeat(41)), q(f), "1") to listOf(Triple(1, "sku", "unknown_sku")),
            rowRaw("123", q("MaxR-10S"), q(f), "1") to listOf(Triple(1, "route", "unknown_route")),
            rowRaw("[\"MIR-SR-D\"]", q("MaxR-10S"), q(f), "1") to listOf(Triple(1, "route", "unknown_route")),
            rowRaw("{\"c\":\"MIR-SR-D\"}", "null", q(f), "1") to listOf(Triple(1, "route", "unknown_route"), Triple(1, "sku", "unknown_sku")),
            rowRaw(q("MIR-SR-D' OR '1'='1"), q("MaxR-10S"), q(f), "1") to listOf(Triple(1, "route", "unknown_route")),
            rowRaw(q("MIR-SR-D"), q("x');DROP TABLE app.sku;--"), q(f), "1") to listOf(Triple(1, "sku", "unknown_sku")),
            rowRaw(q("MIR-SR-D "), q("MaxR-10S"), q(f), "1") to emptyList(), // NBSP trims (Kotlin isWhitespace)
            rowRaw(q("MIR​SR-D"), q("MaxR-10S"), q(f), "1") to listOf(Triple(1, "route", "unknown_route")),
        )
        for ((rw, expect) in cases) {
            val r = put(body(listOf(rw), true, reason = null))
            assertEquals(HttpStatusCode.OK, r.status, "$rw: ${r.bodyAsText()}")
            assertEquals(expect, r.errorTriples(), rw)
        }
        assertEquals(1, env.count("SELECT count(*) FROM pg_tables WHERE schemaname = 'app' AND tablename = 'sku'"))
        // Extra members, a non-object row, a string dry_run, a top-level extra member, an uppercase uuid.
        for (b in listOf(
            """{"batch_uuid":"${UUID.randomUUID()}","rows":[{"route_code":"MIR-SR-D","sku_code":"MaxR-10S","month":"$f","qty_base":1,"note":"x"}],"dry_run":true}""",
            """{"batch_uuid":"${UUID.randomUUID()}","rows":["MIR-SR-D"],"dry_run":true}""",
            """{"batch_uuid":"${UUID.randomUUID()}","rows":[${row("MIR-SR-D", "MaxR-10S", f, 1)}],"dry_run":"true"}""",
            """{"batch_uuid":"${UUID.randomUUID()}","rows":[${row("MIR-SR-D", "MaxR-10S", f, 1)}],"dry_run":true,"x":1}""",
            """{"batch_uuid":"${UUID.randomUUID().toString().uppercase()}","rows":[${row("MIR-SR-D", "MaxR-10S", f, 1)}],"dry_run":true}""",
            """{"batch_uuid":"${UUID.randomUUID()}","rows":{"0":${row("MIR-SR-D", "MaxR-10S", f, 1)}},"dry_run":true}""",
            """{"batch_uuid":"${UUID.randomUUID()}","rows":[${row("MIR-SR-D", "MaxR-10S", f, 1)}],"dry_run":true,"dry_run":false}""",
        )) assertEquals(HttpStatusCode.BadRequest, put(b).status, b)
    }

    @Test
    fun c5_20000RowsCommitAnd20001RefusedAndBodyCap() = env.app {
        val routes = sellingRoutes(); val skus = skus().take(3)
        val perMonth = routes.size * skus.size
        val keys = (0 until 20_000).map { i -> Triple(routes[(i / skus.size) % routes.size], skus[i % skus.size], m(200L + i / perMonth)) }
        assertEquals(20_000, keys.toSet().size)
        val rows = keys.map { row(it.first, it.second, it.third, 2) }
        val u = UUID.randomUUID()
        val t0 = System.nanoTime()
        val r = put(body(rows, false, u))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText().take(500))
        println("CHECKER 20000-row commit took $ms ms, body ${body(rows, false, u).length} bytes")
        assertEquals(40_000L, env.scalar("SELECT sum(qty_base) FROM app.route_monthly_target WHERE batch_uuid = '$u'")!!.toLong())
        val r2 = put(body(rows + row("MIR-SR-D", "MaxR-10S", m(199), 1), false))
        assertEquals(HttpStatusCode.BadRequest, r2.status)
        // Body cap: exactly 4 MiB passes the size gate, one byte more is 413.
        val small = body(listOf(row("MIR-SR-D", "MaxR-10S", m(45), 1)), true, reason = null)
        val cap = 4 * 1024 * 1024
        assertEquals(HttpStatusCode.OK, put(small + " ".repeat(cap - small.length)).status)
        val big = put(small + " ".repeat(cap - small.length + 1))
        assertEquals(HttpStatusCode.PayloadTooLarge, big.status, big.bodyAsText())
    }

    // ---------------------------------------------------------------- 6. Dhaka month boundary

    @Test
    fun c6_pastMonthTurnsAtDhakaMidnightAndReplaySurvivesTheTurn() {
        val now = AtomicReference(Instant.parse("2026-10-31T17:59:59Z")) // 2026-10-31 23:59:59 Dhaka
        clockApp({ now.get() }) {
            val oct = "2026-10"; val nov = "2026-11"
            val u = UUID.randomUUID()
            val rows = listOf(row("FAR-SR-1", "MaxR-10S", oct, 70), row("FAR-SR-1", "MaxR-20S", nov, 80))
            assertEquals(emptyList(), put(body(rows, true, reason = null)).errorTriples(), "October is the current Dhaka month at 23:59:59")
            val c = put(body(rows, false, u))
            assertEquals(HttpStatusCode.OK, c.status, c.bodyAsText())
            now.set(Instant.parse("2026-10-31T18:00:00Z")) // 2026-11-01 00:00 Dhaka
            assertEquals(listOf(Triple(1, "month", "past_month")), put(body(rows, true, reason = null)).errorTriples(), "October is past at Dhaka midnight")
            val replay = put(body(rows.reversed(), false, u, "Retry after midnight of the last day"))
            assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText()); assertEquals("true", replay.objA3().strA3("replayed"))
            val fresh = put(body(rows, false))
            assertEquals(HttpStatusCode.BadRequest, fresh.status); assertTrue(fresh.bodyAsText().contains("past_month"))
            // GET defaults to the Dhaka month of the clock: November now.
            val g = sendA3(HttpMethod.Get, path, env.tok("admin1001")).objA3()
            assertTrue(g.itemsA3().isNotEmpty() && g.itemsA3().all { it.strA3("month") == nov }, g.toString())
            assertTrue(g.itemsA3().any { it.strA3("route_code") == "FAR-SR-1" && it.strA3("sku_code") == "MaxR-20S" && it.strA3("qty_base") == "80" }, g.toString())
            now.set(Instant.parse("2026-10-31T17:59:59Z"))
            val g2 = sendA3(HttpMethod.Get, path, env.tok("admin1001")).objA3()
            assertTrue(g2.itemsA3().any { it.strA3("route_code") == "FAR-SR-1" && it.strA3("month") == oct && it.strA3("qty_base") == "70" }, g2.toString())
        }
    }

    // ---------------------------------------------------------------- 7. authorization

    @Test
    fun c7_roleMatrixForCommitAndDryRun() = env.app {
        val f = m(46)
        for (dry in listOf(true, false)) {
            for ((user, writer) in listOf("admin1001" to true, "superadmin1001" to true, "support1001" to false, "tso1001" to false, "dmo1001" to false, "sr1001" to false, "amo1001" to false)) {
                val u = UUID.randomUUID()
                val st = put(body(listOf(row("MIR-SR-D", if (user == "admin1001") "MaxR-10S" else "MaxR-20S", f, 1)), dry, u), user).status
                assertEquals(if (writer) HttpStatusCode.OK else HttpStatusCode.Forbidden, st, "$user dry=$dry")
                if (!writer) assertEquals(0, batchRows(u))
            }
            assertEquals(HttpStatusCode.Unauthorized, put(body(listOf(row("MIR-SR-D", "MaxR-10S", f, 1)), dry), null).status)
        }
        assertEquals(HttpStatusCode.Unauthorized, sendA3(HttpMethod.Put, path, "not-a-jwt", body(listOf(row("MIR-SR-D", "MaxR-10S", f, 1)), true)).status)
        // A forbidden caller is refused before the body is read (no 400 that leaks validation to a TSO).
        assertEquals(HttpStatusCode.Forbidden, put("{not json", "tso1001").status)
        assertEquals(2, countMonth(f))
    }

    // ---------------------------------------------------------------- 8. other: inactive masters, cursor, GET params

    @Test
    fun c8_inactiveOrRetiredRouteAndInactiveSkuAreRefused() = env.app {
        env.exec("INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask, status) SELECT 'CHK-OFF-1', 'Retired', id, 'sr', 'daily', 127, 'inactive' FROM app.zone WHERE code = 'Z-MIR'")
        env.exec(
            "INSERT INTO app.sku (code, variant_id, category_code, name, short_name, base_unit, base_per_pack, entry_unit_default, status) " +
                "SELECT 'CHK-SKU-OFF', variant_id, category_code, 'Checker off', 'ChkOff', base_unit, base_per_pack, entry_unit_default, 'inactive' FROM app.sku WHERE code = 'MaxR-10S'",
        )
        val f = m(47)
        val r = put(body(listOf(row("CHK-OFF-1", "MaxR-10S", f, 1), row("MIR-SR-D", "CHK-SKU-OFF", f, 1)), true, reason = null))
        assertEquals(listOf(Triple(1, "route", "unknown_route"), Triple(2, "sku", "unknown_sku")), r.errorTriples())
        // A route retired after its target was uploaded: the target stays readable; a new upload for it is refused.
        assertEquals(HttpStatusCode.OK, put(body(listOf(row("CHKR-100", "MaxR-10S", f, 4)), false)).status)
        env.exec("UPDATE app.route SET status = 'inactive' WHERE code = 'CHKR-100'")
        try {
            assertEquals(listOf(Triple(1, "route", "unknown_route")), put(body(listOf(row("CHKR-100", "MaxR-10S", f, 5)), true, reason = null)).errorTriples())
            val g = sendA3(HttpMethod.Get, "$path?month=$f", env.tok("support1001")).objA3()
            assertEquals(listOf("CHKR-100"), g.itemsA3().map { it.strA3("route_code") })
        } finally { env.exec("UPDATE app.route SET status = 'active' WHERE code = 'CHKR-100'") }
    }

    @Test
    fun c8_cursorPagingVisitsEveryRowOnceWithMixedCaseAndPunctuation() = env.app {
        val codes = listOf("CHK-a", "CHK-B", "CHK_b", "CHK-1", "chk-z", "CHK-10", "CHK-2", "CHK.X".replace(".", "-"), "CHKa", "CHK-A")
        for (c in codes) env.exec("INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT '$c', 'Cursor $c', id, 'sr', 'daily', 127 FROM app.zone WHERE code = 'Z-MIR'")
        val skuList = listOf("MaxB-10S", "MaxR-10S", "MaxR-20S")
        val f = m(48)
        assertEquals(HttpStatusCode.OK, put(body(codes.flatMap { c -> skuList.map { s -> row(c, s, f, 1) } }, false)).status)
        val expected = codes.flatMap { c -> skuList.map { s -> c to s } }.sortedWith(compareBy({ it.first }, { it.second })).map { "${it.first}|${it.second}" }
        val all = sendA3(HttpMethod.Get, "$path?month=$f&limit=500", env.tok("admin1001")).objA3().itemsA3().map { it.strA3("route_code") + "|" + it.strA3("sku_code") }
        assertEquals(expected, all, "server order equals Kotlin (UTF-16) order under this database's collation")
        for (limit in listOf(1, 2, 7)) {
            val seen = ArrayList<String>(); var cursor: String? = null; var pages = 0
            do {
                val r = sendA3(HttpMethod.Get, "$path?month=$f&limit=$limit" + (cursor?.let { "&cursor=$it" } ?: ""), env.tok("admin1001"))
                assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
                val o = r.objA3()
                seen += o.itemsA3().map { it.strA3("route_code") + "|" + it.strA3("sku_code") }
                cursor = o["next_cursor"]?.takeIf { it.toString() != "null" }?.jsonPrimitive?.content
                pages++
            } while (cursor != null && pages < 100)
            assertEquals(expected, seen, "limit $limit")
        }
        // GET parameters
        for (bad in listOf("month=2026-13", "month=%202026-11", "month=2026-11-01", "limit=0", "limit=501", "cursor=" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("a|b|c".toByteArray())))
            assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Get, "$path?$bad", env.tok("admin1001")).status, bad)
        // A forged cursor value with SQL in it is just a bound string.
        val forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("' OR 1=1 --|x".toByteArray())
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Get, "$path?month=$f&cursor=$forged", env.tok("admin1001")).status)
    }
}

package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachNode
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ScopeVersionLookup
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.contract.Role
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** F-API-017 through HTTP on the seeded day: one query, json / xlsx / print / pdf, logged exports, sanitiser, scope. */
class ReportApiTest {
    private lateinit var fresh: FreshDb
    private val day = java.time.LocalDate.parse("2026-10-04")
    private var z1 = 0L; private var z2 = 0L
    private var syncMax = 10_000; private var exportMax = 200_000
    private val reaches = mutableMapOf<Long, Reach>()
    private val config = object : ServerConfig {
        private val d = RegistryDefaults()
        override fun value(key: String): JsonElement = when (key) {
            "cfg.ops.report_sync_max_rows" -> JsonPrimitive(syncMax); "cfg.ops.report_export_max_rows" -> JsonPrimitive(exportMax)
            "cfg.api.rl.user_per_min" -> JsonPrimitive(5000); else -> d.value(key)
        }
        override fun configVersion() = 1L
    }
    private val queued = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        fresh = FreshDb.create()
        fresh.dataSource.connection.use { c ->
            c.createStatement().use { it.execute(javaClass.classLoader.getResourceAsStream("seed_day.sql")!!.bufferedReader().readText()) }
            c.createStatement().use { it.execute("INSERT INTO app.domain_event (event_type, aggregate_type, aggregate_id, business_date, source_client_uuid) VALUES ('memo.created','memo','x','2026-10-04','00000000-0000-4000-8000-0000000000a1')") }
        }
        // A route name that a spreadsheet would execute, to prove the sanitiser; dimensions pick it up.
        fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.route SET name = '=HYPERLINK(\"http://x\")' WHERE code = 'R3'") }
        AggregationWorker(fresh.db).also { w -> fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }; w.requestRebuild(day); w.runUntilIdle() }
        fresh.db.jdbi.useHandle<Exception> { h ->
            z1 = h.createQuery("SELECT id FROM app.zone WHERE code='Z1'").mapTo(Long::class.java).one(); z2 = h.createQuery("SELECT id FROM app.zone WHERE code='Z2'").mapTo(Long::class.java).one()
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            for ((id, role) in listOf(10 to "ANALYST", 11 to "TSO", 12 to "AMO", 13 to "ADMIN"))
                h.execute("INSERT INTO app.app_user (id, username, full_name, role, must_change_password) OVERRIDING SYSTEM VALUE VALUES ($id, 'u$id', 'User $id', '$role', false)".replace("'u$id'", "'user$id'"))
        }
        reaches[10] = Reach(10, Role.ANALYST, day, true, emptySet(), emptySet(), false, emptyList())
        reaches[11] = Reach(11, Role.TSO, day, false, setOf(z1), emptySet(), false, listOf(ReachNode("zone", z1)))
        reaches[12] = Reach(12, Role.AMO, day, false, setOf(z1), emptySet(), false, listOf(ReachNode("zone", z1)))
        reaches[13] = Reach(13, Role.ADMIN, day, true, emptySet(), emptySet(), false, emptyList())
    }

    @AfterEach
    fun tearDown() = fresh.close()

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val clock = AronClock { Instant.parse("2026-10-04T12:00:00Z") }
        val reach = ReachResolver { uid, _, _, _ -> reaches.getValue(uid) }
        val deps = ReportDeps(
            fresh.db, ReportEngine(fresh.db, config, clock, listOf(RouteMemoReport)), reach,
            AuthGuardDeps(AccessTokenVerifier(TestTokens.keys), ScopeVersionLookup { 1 }, config), clock,
            ExportJobs { key, fmt, _, rows -> queued += "$key/$fmt/$rows"; ExportJob("11111111-1111-4111-8111-111111111111", key, "queued", null, "2026-10-04T12:00:00.000Z") },
        )
        application {
            installAronPlatform(PlatformContext(config = RegistryDefaults(), generation = { "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10" }))
            routing { route("/v1") { reportRoutes(deps) } }
        }
        block()
    }

    private fun body(format: String = "json", extra: String = "", size: Int = 50) =
        """{"period":{"date":"2026-10-04"},"output":{"format":"$format","page_size":$size}$extra}"""

    private suspend fun ApplicationTestBuilder.query(uid: Long, role: Role, json: String, key: String = "route-memo", pii: Boolean = false) =
        client.post("/v1/reports/$key/query") { bearerAuth(TestTokens.web(uid, role, pii = pii)); contentType(ContentType.Application.Json); setBody(json) }

    @Test
    fun jsonRowsAndTotalsEqualTheHandComputedControlTotals() = app {
        val r = query(10, Role.ANALYST, body(extra = ""","date_grouping":"total"""")).also { assertEquals(HttpStatusCode.OK, it.status) }
        val o = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(3, o["total_rows"]!!.jsonPrimitive.content.toInt())
        val rows = o["rows"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(34_000L, 5_000L, 24_000L).sorted(), rows.map { it["gross_mtk"]!!.jsonPrimitive.content.toLong() }.sorted())
        assertEquals(63_000L, o["totals"]!!.jsonObject["gross_mtk"]!!.jsonPrimitive.content.toLong())
        assertEquals(4L, o["totals"]!!.jsonObject["memos"]!!.jsonPrimitive.content.toLong())
        assertEquals("route-memo", o["report_key"]!!.jsonPrimitive.content)
        // Sorting and paging come from the one query.
        val desc = Json.parseToJsonElement(query(10, Role.ANALYST, """{"period":{"date":"2026-10-04"},"output":{"format":"json","page_size":10,"page":1,"sort":[{"col":"gross_mtk","dir":"desc"}]}}""").bodyAsText()).jsonObject
        assertEquals(34_000L, desc["rows"]!!.jsonArray.first().jsonObject["gross_mtk"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun scopeComesFromTheTokenAndSelectorsOnlyNarrow() = app {
        val own = Json.parseToJsonElement(query(11, Role.TSO, body()).bodyAsText()).jsonObject
        assertEquals(2, own["total_rows"]!!.jsonPrimitive.content.toInt())                  // zone 1 only: R1 and R2
        assertEquals(39_000L, own["totals"]!!.jsonObject["gross_mtk"]!!.jsonPrimitive.content.toLong())
        assertEquals(HttpStatusCode.Forbidden, query(11, Role.TSO, body(extra = ""","geo":{"zone":[$z2]}""")).status)
        assertEquals(HttpStatusCode.Forbidden, query(11, Role.TSO, body(extra = ""","geo":{"zone":[$z1,$z2]}""")).status)
        assertEquals(HttpStatusCode.Forbidden, query(11, Role.TSO, body(extra = ""","geo":{"route":[999999]}""")).status)   // unknown looks like outside
        val narrowed = Json.parseToJsonElement(query(10, Role.ANALYST, body(extra = ""","geo":{"zone":[$z2]}""")).bodyAsText()).jsonObject
        assertEquals(1, narrowed["total_rows"]!!.jsonPrimitive.content.toInt())
        // Scope ids sent as body members are not part of the contract: refused, not ignored.
        val stray = query(11, Role.TSO, body(extra = ""","zone_ids":[$z2],"scope":{"zones":[$z2]}"""))
        assertEquals(HttpStatusCode.BadRequest, stray.status); assertTrue(stray.bodyAsText().contains("ERR_VALIDATION"))
        assertEquals(HttpStatusCode.Forbidden, query(12, Role.AMO, body()).status)          // the role gate precedes the body
        assertEquals(HttpStatusCode.NotFound, query(10, Role.ANALYST, body(), key = "no-such-report").status)
        assertEquals(HttpStatusCode.BadRequest, query(10, Role.ANALYST, body(format = "csv")).status)
        val defs = Json.parseToJsonElement(client.get("/v1/reports") { bearerAuth(TestTokens.web(10, Role.ANALYST)) }.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertEquals("route-memo", defs.single().jsonObject["report_key"]!!.jsonPrimitive.content)
        assertEquals(0, Json.parseToJsonElement(client.get("/v1/reports") { bearerAuth(TestTokens.web(12, Role.AMO)) }.bodyAsText()).jsonObject["items"]!!.jsonArray.size)
    }

    @Test
    fun printIsServerRenderedEscapedAndLogged() = app {
        val r = query(10, Role.ANALYST, body("print"))
        assertEquals(HttpStatusCode.OK, r.status); assertTrue(r.contentType()!!.match(ContentType.Text.Html))
        val html = r.bodyAsText()
        assertTrue(html.contains("<table>") && html.contains("Route-wise Memo") && html.contains("Exported by u10"))
        assertTrue(html.contains("=HYPERLINK(&quot;http://x&quot;)"), "route name must be HTML-escaped, not raw")
        assertFalse(html.contains("HYPERLINK(\"http"))
        // Logged with filters, row count and the PII flag; users see their own, an admin sees all.
        val mine = Json.parseToJsonElement(client.get("/v1/report-exports") { bearerAuth(TestTokens.web(10, Role.ANALYST)) }.bodyAsText()).jsonObject["items"]!!.jsonArray.single().jsonObject
        assertEquals("print", mine["format"]!!.jsonPrimitive.content); assertEquals(3, mine["rows"]!!.jsonPrimitive.content.toInt())
        assertEquals("route-memo", mine["report_key"]!!.jsonPrimitive.content); assertFalse(mine["pii_included"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(0, Json.parseToJsonElement(client.get("/v1/report-exports") { bearerAuth(TestTokens.web(11, Role.TSO)) }.bodyAsText()).jsonObject["items"]!!.jsonArray.size)
        assertEquals(1, Json.parseToJsonElement(client.get("/v1/report-exports?user_id=10") { bearerAuth(TestTokens.web(13, Role.ADMIN)) }.bodyAsText()).jsonObject["items"]!!.jsonArray.size)
    }

    @Test
    fun xlsxIsInlineSanitisedLoggedAndAboveTheLimitsAJobOrRefused() = app {
        val r = query(10, Role.ANALYST, body("xlsx"))
        assertEquals(HttpStatusCode.OK, r.status)
        val tmp = java.io.File.createTempFile("rep", ".xlsx").also { it.writeBytes(r.bodyAsBytes()); it.deleteOnExit() }
        val files = java.util.zip.ZipFile(tmp).use { z -> z.entries().asSequence().associate { it.name to z.getInputStream(it).readBytes().decodeToString() } }
        val strings = files.entries.filter { it.key.contains("sharedStrings") || it.key.contains("worksheets/sheet") }.joinToString("\n") { it.value }
        assertTrue(Regex("(?:'|&apos;|&#39;|&#x27;)=HYPERLINK").containsMatchIn(strings), "formula-leading text must be neutralised: " + strings.take(600))
        assertFalse(Regex(">=HYPERLINK").containsMatchIn(strings), "no cell may start with =")
        assertTrue(strings.contains("Route-wise Memo") || files.keys.any { it.contains("workbook") })
        assertEquals(1, Json.parseToJsonElement(client.get("/v1/report-exports") { bearerAuth(TestTokens.web(10, Role.ANALYST)) }.bodyAsText()).jsonObject["items"]!!.jsonArray.size)
        syncMax = 2
        val job = query(10, Role.ANALYST, body("xlsx")); assertEquals(HttpStatusCode.Accepted, job.status); assertEquals(listOf("route-memo/xlsx/3"), queued)
        val pdf = query(10, Role.ANALYST, body("pdf")); assertEquals(HttpStatusCode.Accepted, pdf.status)
        exportMax = 2
        val big = query(10, Role.ANALYST, body("xlsx")); assertEquals(HttpStatusCode.PayloadTooLarge, big.status); assertTrue(big.bodyAsText().contains("ERR_REPORT_TOO_LARGE"))
    }

    @Test
    fun sanitiserNeutralisesEveryFormulaLeader() {
        for (s in listOf("=1+1", "+cmd", "-2+3", "@SUM(A1)", "\tx", "\rx", "  =x")) assertTrue(ReportOutputs.sanitize(s).startsWith("'"), s)
        for (s in listOf("Route 1", "12", "রুট", "a=b", "")) assertEquals(s, ReportOutputs.sanitize(s))
    }

    // ---------------- checker_ tests (independent checker, F-API-017) ----------------

    private object PiiReport : ReportHandler {
        override val definition = definition(
            "route-memo", "Pii probe", "sales", "route",
            listOf(col("route_code", "Route code", "string"), col("secret", "Secret", "integer", pii = true), col("memos", "Memos", "integer")), listOf("period"),
        )
        // secret order is the reverse of route_code order, so sorting by it reveals the masked column's order.
        override fun spec(ctx: ReportContext) = SqlSpec(
            "SELECT g.route_code, (1000 - g.route_id)::bigint AS secret, sum(a.active_memo_count)::bigint AS memos FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id " +
                "WHERE ${ctx.dateClause("a.business_date")} AND ${ctx.zoneClause("a.zone_id")} AND ${ctx.routeClause("a.route_id")} GROUP BY 1, 2",
            totalColumns = listOf("secret", "memos"),
        )
    }

    private fun appWith(handlers: List<ReportHandler>, jobs: ExportJobs = ExportJobs.UNAVAILABLE, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val clock = AronClock { Instant.parse("2026-10-04T12:00:00Z") }
        val reach = ReachResolver { uid, _, _, _ -> reaches.getValue(uid) }
        val deps = ReportDeps(fresh.db, ReportEngine(fresh.db, config, clock, handlers), reach, AuthGuardDeps(AccessTokenVerifier(TestTokens.keys), ScopeVersionLookup { 1 }, config), clock, jobs)
        application {
            installAronPlatform(PlatformContext(config = RegistryDefaults(), generation = { "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10" }))
            routing { route("/v1") { reportRoutes(deps) } }
        }
        block()
    }

    @Test
    fun checker_sortByMaskedPiiColumnMustNotRevealItsOrder() = appWith(listOf(PiiReport)) {
        // Sorting by a column the caller cannot see is refused (400), never answered: the order would leak the masked values.
        val r = query(10, Role.ANALYST, """{"period":{"date":"2026-10-04"},"output":{"format":"json","sort":[{"col":"secret","dir":"asc"}]}}""")
        assertEquals(HttpStatusCode.BadRequest, r.status); assertTrue(r.bodyAsText().contains("ERR_REPORT_INVALID_QUERY"))
    }

    @Test
    fun checker_totalsMustNotCarryAMaskedPiiColumn() = appWith(listOf(PiiReport)) {
        val o = Json.parseToJsonElement(query(10, Role.ANALYST, body()).bodyAsText()).jsonObject
        assertFalse(o["totals"]!!.jsonObject.containsKey("secret"), "totals include the pii column the caller may not see: " + o["totals"])
    }

    @Test
    fun checker_exportLogLimitUpTo500PerContract() = app {
        val r = client.get("/v1/report-exports?limit=300") { bearerAuth(TestTokens.web(10, Role.ANALYST)) }
        assertEquals(HttpStatusCode.OK, r.status, "contract Limit allows 1..500: " + r.bodyAsText())
    }

    @Test
    fun checker_pagingIsStableWhenTheFirstColumnTies() = app {
        fresh.db.jdbi.useHandle<Exception> { it.execute("INSERT INTO dw.agg_daily_route (business_date, route_id, zone_id, active_memo_count, gross_mtk) SELECT d::date, route_id, zone_id, 1, 1000 FROM dw.agg_daily_route, generate_series('2026-08-01'::date, '2026-10-03'::date, '1 day') d WHERE business_date = '2026-10-04'") }
        val seen = mutableListOf<String>(); var total = 0
        for (pg in 1..30) {
            val o = Json.parseToJsonElement(query(10, Role.ANALYST, """{"period":{"from":"2026-08-01","to":"2026-10-04"},"date_grouping":"day","output":{"format":"json","page_size":25,"page":$pg}}""").bodyAsText()).jsonObject
            total = o["total_rows"]!!.jsonPrimitive.content.toInt()
            seen += o["rows"]!!.jsonArray.map { it.jsonObject["route_code"]!!.jsonPrimitive.content + "|" + it.jsonObject["period"]!!.jsonPrimitive.content }
            if (seen.size >= total) break
        }
        assertEquals(total, seen.size); assertEquals(total, seen.toSet().size, "a row appeared on two pages (unstable ORDER BY ties)")
    }

    @Test
    fun checker_pdfWithoutJobsIs503WithRetryAfterAndNothingLogged() = appWith(listOf(RouteMemoReport)) {
        val r = query(10, Role.ANALYST, body("pdf"))
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status); assertEquals("60", r.headers["Retry-After"])
        assertEquals(0, Json.parseToJsonElement(client.get("/v1/report-exports") { bearerAuth(TestTokens.web(10, Role.ANALYST)) }.bodyAsText()).jsonObject["items"]!!.jsonArray.size)
    }
}

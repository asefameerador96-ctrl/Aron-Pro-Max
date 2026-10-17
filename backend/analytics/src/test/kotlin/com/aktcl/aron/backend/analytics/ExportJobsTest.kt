package com.aktcl.aron.backend.analytics

import com.aktcl.aron.contract.Role
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-SYS-064 / F-API-053: the export job (queue, lease, run, link, failure), scope re-derived on the server; F-SYS-058: hourly and daily PII budgets and the export log. */
class ExportJobsTest : ReportFixture() {
    override val configOverrides: Map<String, JsonElement> = mapOf(
        "cfg.ops.report_sync_max_rows" to JsonPrimitive(1),   // any xlsx of more than one row is therefore a job
        "cfg.pii.list_rows_per_hour" to JsonPrimitive(4),
        "cfg.pii.export_rows_per_day" to JsonPrimitive(3),
    )

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.job(uid: Long, role: Role, id: String) =
        client.get("/v1/report-exports/$id") { bearerAuth(TestTokens.web(uid, role)) }

    private fun sheetRows(bytes: ByteArray): Int {
        val f = java.io.File.createTempFile("export", ".xlsx").also { it.writeBytes(bytes) }
        try {
            java.util.zip.ZipFile(f).use { z ->
                val e = z.getEntry("xl/worksheets/sheet1.xml") ?: return -1
                return Regex("<row ").findAll(z.getInputStream(e).readBytes().decodeToString()).count() - 1   // minus the header
            }
        } finally { f.delete() }
    }

    @Test
    fun aBigXlsxRunsAsAJobKeepsTheScreensRowsAndHandsOutAShortLivedLink() = app {
        val screen = run(10, Role.ANALYST, "route-memo").result()
        val n = screen["total_rows"]!!.jsonPrimitive.content.toInt()
        assertTrue(n > 2)
        val r = run(10, Role.ANALYST, "route-memo", body(format = "xlsx"))
        assertEquals(HttpStatusCode.Accepted, r.status)
        val queued = r.result(); assertEquals("queued", queued.str("status")); val id = queued.str("export_id")!!
        assertEquals("queued", Json.parseToJsonElement(job(10, Role.ANALYST, id).bodyAsText()).jsonObject.str("status"))
        assertNull(Json.parseToJsonElement(job(10, Role.ANALYST, id).bodyAsText()).jsonObject.str("download_url"))
        assertEquals(1, exports.runUntilIdle())
        val done = Json.parseToJsonElement(job(10, Role.ANALYST, id).bodyAsText()).jsonObject
        assertEquals("done", done.str("status")); assertEquals(n, done.str("row_count")!!.toInt())
        assertTrue(done.str("download_url")!!.startsWith("https://exports.test/exports/2026-10-04/$id.xlsx"))
        assertEquals(n, sheetRows(storedExports.getValue("exports/2026-10-04/$id.xlsx")))     // the file holds the screen's rows
        // Someone else's export looks like none; an admin sees it. The log row says who, what, how many.
        assertEquals(HttpStatusCode.NotFound, job(11, Role.TSO, id).status)
        assertEquals(HttpStatusCode.OK, job(13, Role.ADMIN, id).status)
        assertEquals(HttpStatusCode.NotFound, job(10, Role.ANALYST, "00000000-0000-4000-8000-000000000000").status)
        val log = Json.parseToJsonElement(client.get("/v1/report-exports") { bearerAuth(TestTokens.web(10, Role.ANALYST)) }.bodyAsText()).jsonObject["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(id), log.map { it.str("export_id") }); assertEquals("xlsx", log.single().str("format")); assertEquals(n, log.single().str("rows")!!.toInt())
    }

    @Test
    fun theJobRunsWithTheRequestersCurrentReachNotWhatTheClientSent() = app {
        // Zone 1's TSO asks for a job: the file holds zone 1 rows only, even though the stored filters carry no scope.
        val all = run(10, Role.ANALYST, "route-memo").result()["total_rows"]!!.jsonPrimitive.content.toInt()
        val id = run(11, Role.TSO, "route-memo", body(format = "xlsx")).result().str("export_id")!!
        exports.runUntilIdle()
        val rows = sheetRows(storedExports.getValue("exports/2026-10-04/$id.xlsx"))
        assertTrue(rows in 1 until all, "zone 1 only: $rows of $all")
    }

    @Test
    fun aFailingStoreMarksTheJobFailedWithAShortReasonAndAFinishedJobIsHistory() = app {
        val failing = ExportJobService(fresh.db, ReportEngine(fresh.db, object : com.aktcl.aron.backend.platform.ServerConfig by com.aktcl.aron.backend.platform.RegistryDefaults() {}, testClock, handlers), ReachResolverOf(reaches), object : ExportStore {
            override fun put(path: String, bytes: ByteArray) = error("disk full")
            override fun readUrl(path: String, expiresAt: java.time.Instant) = ""
        }, testClock)
        val id = run(10, Role.ANALYST, "route-memo", body(format = "xlsx")).result().str("export_id")!!
        assertEquals(1, failing.runUntilIdle())
        val j = Json.parseToJsonElement(job(10, Role.ANALYST, id).bodyAsText()).jsonObject
        assertEquals("failed", j.str("status")); assertEquals("export failed (IllegalStateException)", j.str("error"))   // class only: no raw message; assertNull(j.str("download_url"))
        assertEquals(0, failing.runUntilIdle())   // nothing left to run; a failed job is not retried
    }

    @Test
    fun aJobWhoseWorkerVanishedIsTakenOverAfterTheLease() = app {
        val id = run(10, Role.ANALYST, "route-memo", body(format = "xlsx")).result().str("export_id")!!
        fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.report_export SET status = 'running', claimed_by = 'dead', claimed_at = TIMESTAMPTZ '2026-10-04 11:00Z' WHERE export_id = '$id'") }   // 1 h before the clock, lease is 15 min
        assertEquals(1, exports.runUntilIdle())
        assertEquals("done", Json.parseToJsonElement(job(10, Role.ANALYST, id).bodyAsText()).jsonObject.str("status"))
    }

    @Test
    fun theHourlyPiiBudgetRefusesAReadThatWouldPassItWithoutCountingIt() = app {
        // sr-efficiency has a personal column (full_name); the budget is 4 rows an hour; the seeded day has 3 SRs.
        assertEquals(HttpStatusCode.OK, run(10, Role.ANALYST, "sr-efficiency", pii = true).status)            // 3 of 4
        val second = run(10, Role.ANALYST, "sr-efficiency", pii = true)
        assertEquals(HttpStatusCode.TooManyRequests, second.status); assertNotNull(second.headers["Retry-After"])
        // Masked reads are free and a refused read cost nothing: the remaining 1 still serves a one-row read.
        assertEquals(HttpStatusCode.OK, run(10, Role.ANALYST, "sr-efficiency").status)
        assertEquals(HttpStatusCode.OK, run(11, Role.TSO, "sr-efficiency", pii = true).status)               // another user has their own budget
        assertEquals(3, fresh.db.jdbi.withHandle<Int, Exception> { it.createQuery("SELECT rows_read FROM app.pii_read_budget WHERE user_id = 10").mapTo(Int::class.java).one() })
    }

    @Test
    fun theDailyPiiExportBudgetCountsRowsExportedWithPersonalColumns() = app {
        // 3 personal rows a day; sr-efficiency has 3 rows and a personal column. The first export (a job: more than 2 rows) uses the whole budget.
        assertEquals(HttpStatusCode.Accepted, run(10, Role.ANALYST, "sr-efficiency", body(format = "xlsx"), pii = true).status)
        val second = run(10, Role.ANALYST, "sr-efficiency", body(format = "xlsx"), pii = true)
        assertEquals(HttpStatusCode.TooManyRequests, second.status); assertNotNull(second.headers["Retry-After"])
        // Without the personal columns (no pii claim) the same export is not counted, and another user has their own day.
        assertEquals(HttpStatusCode.Accepted, run(10, Role.ANALYST, "sr-efficiency", body(format = "xlsx")).status)
        assertEquals(HttpStatusCode.Accepted, run(13, Role.ADMIN, "sr-efficiency", body(format = "xlsx"), pii = true).status)
    }
}

/** Checker F-SYS-064 defects: each test fails on the current code and states the expected behaviour. */
class ExportJobsCheckerTest : ReportFixture() {
    override val configOverrides: Map<String, JsonElement> = mapOf("cfg.ops.report_sync_max_rows" to JsonPrimitive(1))

    private fun service(store: ExportStore, clock: com.aktcl.aron.backend.platform.AronClock, id: String) = ExportJobService(
        fresh.db, ReportEngine(fresh.db, object : com.aktcl.aron.backend.platform.ServerConfig by com.aktcl.aron.backend.platform.RegistryDefaults() {}, clock, handlers),
        ReachResolverOf(reaches), store, clock, workerId = id,
    )
    private fun state(id: String) = fresh.db.jdbi.withHandle<Pair<String, String?>, Exception> { h ->
        h.createQuery("SELECT status, error FROM app.report_export WHERE export_id = CAST(:i AS uuid)").bind("i", id).map { rs, _ -> rs.getString(1) to rs.getString(2) }.one()
    }

    @Test
    fun aLateWorkerWhoseLeaseWasSweptDoesNotMarkTheJobFailed() = app {
        val id = run(10, Role.ANALYST, "route-memo", body(format = "xlsx")).result().str("export_id")!!
        // While worker A is writing the file, another worker's sweep puts the lapsed job back in the queue.
        val a = service(object : ExportStore {
            override fun put(path: String, bytes: ByteArray) { fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.report_export SET status = 'queued', claimed_by = NULL, claimed_at = NULL WHERE export_id = '$id'") } }
            override fun readUrl(path: String, expiresAt: java.time.Instant) = ""
        }, testClock, "A")
        a.runNext()
        val (status, err) = state(id)
        assertTrue(status != "failed", "late worker A turned a re-queued job into failed: $status / $err")
    }

    @Test
    fun aLateWorkerFinishingAfterTheTakeoverWorkerDoesNotThrowOrChangeTheJob() = app {
        val id = run(10, Role.ANALYST, "route-memo", body(format = "xlsx")).result().str("export_id")!!
        val later = com.aktcl.aron.backend.platform.AronClock { java.time.Instant.parse("2026-10-04T13:00:00Z") }
        val b = service(exportStore, later, "B")
        val a = service(object : ExportStore {
            override fun put(path: String, bytes: ByteArray) { assertTrue(b.runNext()) }   // B takes over the lapsed lease and finishes first
            override fun readUrl(path: String, expiresAt: java.time.Instant) = ""
        }, testClock, "A")
        val threw = runCatching { a.runNext() }.exceptionOrNull()
        assertNull(threw, "worker A's late finish escaped runNext: $threw")
        assertEquals("done", state(id).first)
    }

    @Test
    fun theFailureReasonDoesNotCarryTheRawExceptionMessage() = app {
        val id = run(10, Role.ANALYST, "route-memo", body(format = "xlsx")).result().str("export_id")!!
        service(object : ExportStore {
            override fun put(path: String, bytes: ByteArray) = throw java.io.IOException("PUT https://acct.blob.core.windows.net/exports/x.xlsx?sig=SECRET failed for +8801711000000")
            override fun readUrl(path: String, expiresAt: java.time.Instant) = ""
        }, testClock, "A").runNext()
        val err = state(id).second.orEmpty()
        assertTrue(!err.contains("SECRET") && !err.contains("8801711000000"), "error served to the user: $err")
    }

    @Test
    fun aJobWithoutExplicitPeriodExportsTheDayTheScreenShowedNotTheDayTheWorkerRuns() = app {
        val q = """{"period":{},"output":{"format":"xlsx","page_size":500}}"""
        val screen = run(10, Role.ANALYST, "route-memo", """{"period":{},"output":{"format":"json","page_size":500}}""").result()["total_rows"]!!.jsonPrimitive.content.toInt()
        val id = run(10, Role.ANALYST, "route-memo", q).result().str("export_id")!!
        val nextDay = com.aktcl.aron.backend.platform.AronClock { java.time.Instant.parse("2026-10-05T12:00:00Z") }
        service(exportStore, nextDay, "A").runNext()
        val path = fresh.db.jdbi.withHandle<String, Exception> { it.createQuery("SELECT blob_path FROM app.report_export WHERE export_id = CAST(:i AS uuid)").bind("i", id).mapTo(String::class.java).one() }
        val f = java.io.File.createTempFile("export", ".xlsx").also { it.writeBytes(storedExports.getValue(path)) }
        val rows = java.util.zip.ZipFile(f).use { z -> Regex("<row ").findAll(z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml")).readBytes().decodeToString()).count() - 1 }
        f.delete()
        assertEquals(screen, rows, "screen showed $screen rows, the job exported $rows")
    }
}

private class ReachResolverOf(val m: Map<Long, com.aktcl.aron.backend.platform.Reach>) : com.aktcl.aron.backend.platform.ReachResolver {
    override fun reach(userId: Long, role: Role, scopeVersion: Long, businessDate: java.time.LocalDate) = m.getValue(userId)
}

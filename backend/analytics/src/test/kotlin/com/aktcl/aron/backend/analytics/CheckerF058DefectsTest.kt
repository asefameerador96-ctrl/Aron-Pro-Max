package com.aktcl.aron.backend.analytics

import com.aktcl.aron.contract.Role
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent T1 checker for F-SYS-058 (PII read budgets and export log). Each test names the defect it pins; the first one is a
 * positive control (the seeded TSO role value of 5000 is found at all), the others fail on lane/backend-reports at 403e6146.
 */
class CheckerF058DefectsTest : ReportFixture() {
    override val configOverrides: Map<String, JsonElement> = mapOf(
        "cfg.ops.report_sync_max_rows" to JsonPrimitive(1),
        "cfg.pii.list_rows_per_hour" to JsonPrimitive(4),
        "cfg.pii.export_rows_per_day" to JsonPrimitive(3),
    )

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.piiRowsOf(uid: Long, role: Role): Int =
        run(uid, role, "sr-efficiency", pii = true).also { assertEquals(HttpStatusCode.OK, it.status) }.result()["total_rows"]!!.jsonPrimitive.content.toInt()

    /** Reads sr-efficiency with personal columns until more than the global 4 rows have been read; returns the statuses. */
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.readPastGlobal(uid: Long, role: Role): List<HttpStatusCode> {
        val n = piiRowsOf(uid, role); assertTrue(n >= 1)
        val out = mutableListOf(HttpStatusCode.OK)
        var read = n
        while (read <= 4) { out += run(uid, role, "sr-efficiency", pii = true).status; read += n }
        return out
    }

    private fun sql(s: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(s) }

    private fun newVersion(): Long = fresh.db.jdbi.withHandle<Long, Exception> { h ->
        h.createQuery(
            "INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT (SELECT max(config_version) + 1 FROM app.cfg_version), 'change', id, 'checker' FROM app.app_user WHERE username = 'aron.system' RETURNING config_version",
        ).mapTo(Long::class.java).one()
    }

    @Test
    fun control_theTsoRoleValueOf5000IsUsed() = app {
        // Global budget is 4; the TSO has the V0030 role value 5000, so reading past 4 rows is fine.
        assertTrue(readPastGlobal(11, Role.TSO).all { it == HttpStatusCode.OK })
    }

    /**
     * DEFECT ReportEngine.kt:243 (hourlyLimit): `superseded_in_version IS NULL AND effective_from <= now`. ConfigService.applyItem closes the
     * current row (effective_to = start of the new value, superseded_in_version = v) the moment a future-dated change is committed, so between
     * the commit and the start date neither row qualifies and the TSO silently drops to the global budget (here 4; in production 2000 instead of 5000).
     */
    @Test
    fun aFutureDatedTsoChangeKeepsTheCurrentRoleValueUntilItStarts() = app {
        val v = newVersion()
        // Exactly what ConfigService.applyItem writes for a TSO value of 6000 scheduled from 2026-11-01 (clock is 2026-10-04 12:00Z).
        sql("UPDATE app.cfg_value SET effective_to = '2026-11-01T00:00:00Z', superseded_in_version = $v WHERE key = 'cfg.pii.list_rows_per_hour' AND scope_type = 'role' AND effective_to IS NULL")
        sql(
            "INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, created_by, reason) " +
                "SELECT 'cfg.pii.list_rows_per_hour', 'role', r.ordinal, '6000'::jsonb, '2026-11-01T00:00:00Z', $v, u.id, 'checker: scheduled raise' " +
                "FROM app.role_def r, app.app_user u WHERE r.role = 'TSO' AND u.username = 'aron.system'",
        )
        assertTrue(readPastGlobal(11, Role.TSO).all { it == HttpStatusCode.OK }, "the TSO's 5000 is still in force until 2026-11-01")
    }

    /**
     * DEFECT ReportEngine.kt:243 (hourlyLimit): effective_to is ignored. A time-boxed role value (a row with effective_to and no supersede, as
     * applyItem writes when the change carries an end) keeps applying after it ended.
     */
    @Test
    fun anExpiredTimeBoxedRoleValueNoLongerApplies() = app {
        val v = newVersion()
        sql(
            "INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, effective_to, config_version, created_by, reason) " +
                "SELECT 'cfg.pii.list_rows_per_hour', 'role', r.ordinal, '50000'::jsonb, '2026-09-01T00:00:00Z', '2026-09-08T00:00:00Z', $v, u.id, 'checker: one-week audit window' " +
                "FROM app.role_def r, app.app_user u WHERE r.role = 'ANALYST' AND u.username = 'aron.system'",
        )
        val statuses = readPastGlobal(10, Role.ANALYST)
        assertEquals(HttpStatusCode.TooManyRequests, statuses.last(), "the 50000 window ended 2026-09-08; the global 4 applies: $statuses")
    }

    /**
     * DEFECT ReportApi.kt:75 + :82/:89/:96 (check then act): checkExportBudget reads the day's sum on a separate read handle with no lock or
     * reservation and the export row is inserted later, so concurrent PII exports each see the same `used` and all pass the daily budget.
     */
    @Test
    fun concurrentPiiExportsCannotPassTheDailyBudgetTogether() = app {
        val statuses = coroutineScope { (1..12).map { async(Dispatchers.IO) { run(10, Role.ANALYST, "sr-efficiency", body(format = "xlsx"), pii = true).status } }.awaitAll() }
        val exported = fresh.db.jdbi.withHandle<Long, Exception> {
            it.createQuery("SELECT coalesce(sum(row_count), 0) FROM app.report_export WHERE user_id = 10 AND pii_included").mapTo(Long::class.java).one()
        }
        assertTrue(exported <= 3, "daily budget 3 rows, exported $exported personal rows; statuses $statuses")
    }

    /** Control: the hourly budget is an atomic conditional upsert, so concurrent revealed reads never pass it together. */
    @Test
    fun control_concurrentPiiReadsCannotPassTheHourlyBudgetTogether() = app {
        val statuses = coroutineScope { (1..12).map { async(Dispatchers.IO) { run(10, Role.ANALYST, "sr-efficiency", pii = true).status } }.awaitAll() }
        val read = fresh.db.jdbi.withHandle<Int, Exception> { it.createQuery("SELECT rows_read FROM app.pii_read_budget WHERE user_id = 10").mapTo(Int::class.java).one() }
        assertTrue(read <= 4, "hourly budget 4, read $read; $statuses")
        assertEquals(1, statuses.count { it == HttpStatusCode.OK })
    }

    /** Same race shown without timing: two requests interleave exactly as ReportApi orders the calls (check, check, record, record). */
    @Test
    fun interleavedCheckAndRecordCannotPassTheDailyBudget() {
        val clock = com.aktcl.aron.backend.platform.AronClock { java.time.Instant.parse("2026-10-04T12:00:00Z") }
        val cfg = object : com.aktcl.aron.backend.platform.ServerConfig {
            private val d = com.aktcl.aron.backend.platform.RegistryDefaults()
            override fun value(key: String): JsonElement = configOverrides[key] ?: d.value(key)
            override fun configVersion() = 1L
        }
        val engine = ReportEngine(fresh.db, cfg, clock, handlers)
        val p = com.aktcl.aron.backend.platform.AronPrincipal(10, "user10", Role.ANALYST, 1, "aron-api", null, null, "web", emptyList(), true, listOf("pwd"), "j", clock.now().plusSeconds(900))
        val q = kotlinx.serialization.json.Json.decodeFromString(ReportQuery.serializer(), body(format = "xlsx"))
        val refused = fresh.db.jdbi.withHandle<Int, Exception> { h ->
            val ctx = engine.context(h, "sr-efficiency", q, reaches.getValue(10), p, day)
            val rows = engine.count(h, "sr-efficiency", ctx)
            var refused = 0
            for (i in 0..1) runCatching { engine.checkExportBudget(h, ctx, rows) }.onFailure { refused++ }
            engine.recordExport(h, java.util.UUID.randomUUID().toString(), "sr-efficiency", "xlsx", ctx, rows, "queued")
            engine.recordExport(h, java.util.UUID.randomUUID().toString(), "sr-efficiency", "xlsx", ctx, rows, "queued")
            refused
        }
        assertEquals(1, refused, "the second of two concurrent exports of 3 rows must be refused (budget 3), but nothing reserves the first one's rows")
    }
}

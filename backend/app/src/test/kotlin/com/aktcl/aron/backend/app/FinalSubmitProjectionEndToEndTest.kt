package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.analytics.AggregationWorker
import com.aktcl.aron.backend.analytics.Aggregator
import com.aktcl.aron.backend.app.TsoWorld.Companion.json
import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlReachResolver
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.Nudger
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.sync.AutoFinalSubmitJob
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lead ruling R-W-03 (tso-manual X2; B2 asks 8 and 11, D-B2-18 ratified; R-W-01, D-B3-11): one Final Submit formula, proved end
 * to end through the production code. A zone-day is closed by AutoFinalSubmitJob's real code (as the worker database login,
 * `SET ROLE worker_rw`), then the aggregation worker (the same login) runs with NO whole-day rebuild, so only the dirty marks the
 * close itself left carry it; then `POST /v1/dashboards/national/final-submit` (queryNationalFinalSubmit) and
 * `GET /v1/dashboards/summary` both count the zone, and so do login-submit `zones[]` and sync-health `by_zone` (the same
 * `zoneSubmittedSql`). An ADMIN reopen through DayService's real code (`POST /v1/day/reopen`), the worker, and all four stop
 * counting it; a second job pass re-closes it as cycle 1 and all four count it again.
 *
 * World: the mirrored division FSE (zone Z-FSE, route FSE-SR-D, TSO `fsetso1001` on T-FSE); one planned route-day on
 * 2027-01-02, nothing else planned in the job's window. The clock is 10:00 Dhaka on 2027-01-03, inside the reopen window.
 * Each read moves the clock 6 s, past the summary's 5 s cache, so a read never sees an answer cached before the change.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinalSubmitProjectionEndToEndTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var workerDb: Database
    private lateinit var worker: AggregationWorker
    private val day = LocalDate.parse("2027-01-02")
    private var zone = 0L
    private var route = 0L

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot, mirrors = listOf("FSE"), start = Instant.parse("2027-01-03T04:00:00Z"))
        zone = world.one("SELECT id FROM app.zone WHERE code = 'Z-FSE'")
        route = world.one("SELECT id FROM app.route WHERE code = 'FSE-SR-D'")
        world.exec(
            "INSERT INTO app.route_day (route_id, business_date, planned, assigned_user_id) SELECT r.id, DATE '$day', true, " +
                "(SELECT a.user_id FROM app.route_assignment a WHERE a.route_id = r.id AND a.kind = 'primary' LIMIT 1) FROM app.route r WHERE r.id = $route",
        )
        // The worker's own login (V0014 worker_rw), for the job and the aggregation worker, as Main.kt runs them.
        workerDb = Database(HikariDataSource(HikariConfig().apply { jdbcUrl = world.fresh.url; maximumPoolSize = 2; connectionInitSql = "SET ROLE worker_rw" }))
        worker = AggregationWorker(workerDb, world.clock)
        // The day's aggregates before anything is closed: the one full rebuild of the test.
        worker.runUntilIdle(); workerDb.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }
        worker.requestRebuild(day); worker.runUntilIdle()
    }

    @AfterAll
    fun tearDown() { workerDb.close(); world.close() }

    private fun job() = AutoFinalSubmitJob(
        workerDb, DbServerConfig(workerDb, RegistryDefaults(), world.clock), SqlReachResolver(workerDb, GeoRepository(workerDb, world.clock), world.clock),
        Nudger { _, _ -> }, world.clock,
    )

    /** One caller's view: the national card (service zones, submitted, bp), the summary (zones, submitted), and the two ops flags of the zone. */
    private data class View(val card: Triple<Int, Int, Int?>, val summary: Pair<Int?, Int?>, val loginSubmitZone: Boolean?, val healthZone: Boolean?)

    private suspend fun HttpClient.view(user: String): View = with(world) {
        world.now.set(world.now.get().plusSeconds(6))
        fun ok(body: String, status: HttpStatusCode, what: String): JsonObject { assertEquals(HttpStatusCode.OK, status, "$what: $body"); return json(body) }
        val c = postWeb(user, "/v1/dashboards/national/final-submit", """{"date":"$day"}""").let { ok(it.bodyAsText(), it.status, "card") }
        val s = getWeb(user, "/v1/dashboards/summary?from=$day&to=$day").let { ok(it.bodyAsText(), it.status, "summary") }["kpis"]!!.jsonObject
        val ls = getWeb(user, "/v1/dashboards/login-submit?business_date=$day").let { ok(it.bodyAsText(), it.status, "login-submit") }
        val hs = getWeb(user, "/v1/dashboards/sync-health?business_date=$day").let { ok(it.bodyAsText(), it.status, "sync-health") }
        fun flag(o: JsonObject, list: String) = o[list]!!.jsonArray.map { it.jsonObject }.singleOrNull { it["zone_id"]!!.jsonPrimitive.content == "$zone" }
            ?.let { it["final_submitted"]!!.jsonPrimitive.content == "true" }
        fun int(o: JsonObject, k: String) = o[k]!!.jsonPrimitive.content.toIntOrNull()
        View(Triple(int(c, "service_zones")!!, int(c, "final_submitted")!!, int(c, "submit_pct_bp")), int(s, "zones_with_target_routes") to int(s, "zones_final_submitted"), flag(ls, "zones"), flag(hs, "by_zone"))
    }

    private fun counted(yes: Boolean) = View(Triple(1, if (yes) 1 else 0, if (yes) 10_000 else 0), 1 to (if (yes) 1 else 0), yes, yes)

    /** The zone-day's route-day was dirtied by the change itself (the job's or the reopen's own transaction), with its reason. */
    private fun dirtiedBy(reason: String) = assertEquals(
        reason, world.scalar("SELECT reason FROM app.dirty_key WHERE kind = 'route_day_agg' AND subject_id = $route AND business_date = DATE '$day'"),
        "the change left its route-day dirty in its own transaction (B2 ask 8)",
    )

    private fun closes() = world.rows("SELECT client_uuid::text AS u, via, reopened_at FROM app.final_submit WHERE zone_id = $zone AND business_date = DATE '$day' ORDER BY id")

    @Test
    fun aJobCloseAReopenAndAJobReCloseMoveTheCardTheSummaryAndTheOpsFlagsThroughTheWorker() = world.withApi {
        with(world) {
            for (u in listOf("fsetso1001", "superadmin1001")) assertEquals(counted(false), view(u), "$u: nothing closed")

            // 1. The midnight job's real close (DayService.closeLocked, via job), then the worker alone.
            assertEquals(listOf(zone to day), job().runOnce().map { it.zoneId to it.businessDate }, "the job closes the open service zone-day")
            assertEquals(listOf(AutoFinalSubmitJob.jobUuid(zone, day, 0) to "job"), closes().map { it["u"] to it["via"] })
            dirtiedBy("final_submit")
            for (u in listOf("fsetso1001", "superadmin1001")) assertEquals(counted(false), view(u), "$u: before the worker ran the projection is unchanged")
            worker.runUntilIdle()
            for (u in listOf("fsetso1001", "superadmin1001")) assertEquals(counted(true), view(u), "$u: the job's close, after the worker")

            // 2. An ADMIN reopen through DayService.reopen over HTTP, then the worker alone.
            val r = postWeb("admin1001", "/v1/day/reopen", """{"client_uuid":"${UUID.randomUUID()}","zone_id":$zone,"business_date":"$day","reason":"X2 end-to-end: late memos to accept"}""")
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            dirtiedBy("reopen")
            worker.runUntilIdle()
            for (u in listOf("fsetso1001", "superadmin1001")) assertEquals(counted(false), view(u), "$u: the reopened close no longer counts")

            // 3. The next job pass re-closes the reopened zone-day as cycle 1 (R-W-01, D-B3-11), then the worker alone.
            assertEquals(listOf(zone to day), job().runOnce().map { it.zoneId to it.businessDate }, "the second pass re-closes it")
            val all = closes()
            assertEquals(2, all.size, all.toString()); assertTrue(all[0]["reopened_at"] != null && all[1]["reopened_at"] == null)
            assertEquals(AutoFinalSubmitJob.jobUuid(zone, day, 1), all[1]["u"], "cycle 1's own uuid")
            dirtiedBy("final_submit")
            worker.runUntilIdle()
            for (u in listOf("fsetso1001", "superadmin1001")) assertEquals(counted(true), view(u), "$u: the re-close counts again")
            assertTrue(job().runOnce().isEmpty(), "a third pass closes nothing")
        }
    }
}

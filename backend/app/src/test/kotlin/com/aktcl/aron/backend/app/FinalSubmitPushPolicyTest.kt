package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlReachResolver
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.Nudger
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.sync.AutoFinalSubmitJob
import com.aktcl.aron.backend.sync.PUSH_DAY_FINAL_SUBMITTED
import com.aktcl.aron.backend.sync.PUSH_DAY_STATE_CHANGED
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * tso-manual X4a fix round (checker high; owner decision 2026-11-04, D-TSO-MAN-49): when a phone sells after the TSO's
 * Final Submit the server accepts the sale and flags it, so the push after a zone-day close may tell the SR and AMO phones
 * "Day closed ... New sales for that day are closed" (reason `day_final_submitted`; the phones show that notice and pull the
 * day state that locks them) only while the late-sale policy holds or refuses a late capture: quarantine or reject. Under
 * the shipped default (no value row), an explicit accept_and_flag and an unreadable value (ingest's own fallback) the close
 * sends `day_state_changed`: the phones pull the day state with no notice. Both closes are checked, through the production
 * code: the TSO's own Final Submit (`POST /v1/day/final-submit`, DayService, pushes through backend:notify to the
 * registered SR and AMO phones) and the midnight job (AutoFinalSubmitJob as the worker login, its nudges recorded).
 *
 * World: the mirrored division FPP (zone Z-FPP, daily route FPP-SR-D of `fppsr1001`, `fpptso1001` on T-FPP, `fppamo1001`
 * whose scope is the zone). The clock is 10:00 Dhaka on 2027-01-03; each case closes its own past dates.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinalSubmitPushPolicyTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var workerDb: Database
    private var zone = 0L
    private var srUser = 0L
    private var amoUser = 0L
    private val nudges = CopyOnWriteArrayList<Pair<Long, String>>()

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot, mirrors = listOf("FPP"), start = Instant.parse("2027-01-03T04:00:00Z"))
        world.cfg("cfg.day.auto_final_submit_enabled", "true") // ships off (D-262); the job's case needs it on
        zone = world.one("SELECT id FROM app.zone WHERE code = 'Z-FPP'")
        srUser = world.one("SELECT id FROM app.app_user WHERE username = 'fppsr1001'")
        amoUser = world.one("SELECT id FROM app.app_user WHERE username = 'fppamo1001'")
        workerDb = Database(HikariDataSource(HikariConfig().apply { jdbcUrl = world.fresh.url; maximumPoolSize = 2; connectionInitSql = "SET ROLE worker_rw" }))
    }

    @AfterAll
    fun tearDown() { workerDb.close(); world.close() }

    /** A fresh job per case: its DbServerConfig reads the policy the case set (TsoWorld.cfg moves the clock past its cache). */
    private fun job() = AutoFinalSubmitJob(
        workerDb, DbServerConfig(workerDb, RegistryDefaults(), world.clock), SqlReachResolver(workerDb, GeoRepository(workerDb, world.clock), world.clock),
        Nudger { u, reason -> nudges += u to reason }, world.clock,
    )

    private fun plannedDay(date: LocalDate) = world.exec(
        "INSERT INTO app.route_day (route_id, business_date, planned, assigned_user_id) SELECT r.id, DATE '$date', true, " +
            "(SELECT a.user_id FROM app.route_assignment a WHERE a.route_id = r.id AND a.kind = 'primary' LIMIT 1) FROM app.route r WHERE r.code = 'FPP-SR-D'",
    )

    @Test
    fun theCloseSaysSalesAreClosedOnlyUnderQuarantineOrReject() = world.withApi {
        with(world) {
            val srFcm = registerPush(phone("fppsr1001", "sr"))
            val amoFcm = registerPush(phone("fppamo1001", "amo"))
            val tokens = setOf(srFcm, amoFcm)
            // (policy row as the portal stores it, or none) to the reason the zone's SR and AMO phones must get
            val cases = listOf(
                null to PUSH_DAY_STATE_CHANGED, // the shipped default: no value row, the registry's accept_and_flag (V0134)
                "\"quarantine\"" to PUSH_DAY_FINAL_SUBMITTED,
                "\"reject\"" to PUSH_DAY_FINAL_SUBMITTED,
                "\"accept_and_flag\"" to PUSH_DAY_STATE_CHANGED,
                "\"QUARANTINE\"" to PUSH_DAY_STATE_CHANGED, // unreadable: ingest's fallback is accept_and_flag, and so is the push's
            )
            // what each close sent, per case: (the TSO's close: reasons per phone, the job's close: reasons per user); compared
            // once at the end, so a failure names every path and policy that sent the wrong reason
            val expected = LinkedHashMap<String, Pair<Map<String, List<String>>, Map<Long, List<String>>>>()
            val actual = LinkedHashMap<String, Pair<Map<String, List<String>>, Map<Long, List<String>>>>()
            cases.forEachIndexed { i, (policy, reason) ->
                if (policy != null) cfg("cfg.day.late_sync_after_final_policy", policy)
                val what = "policy ${policy ?: "(none)"}"

                // 1. The TSO's own Final Submit of one past day: one data-only nudge to each field phone of the zone.
                val tsoDay = LocalDate.parse("2026-12-10").plusDays(2L * i)
                pushes.clear()
                val r = postWeb("fpptso1001", "/v1/day/final-submit", """{"client_uuid":"${UUID.randomUUID()}","zone_id":$zone,"business_date":"$tsoDay"}""")
                assertEquals(HttpStatusCode.OK, r.status, "$what: ${r.bodyAsText()}")
                fun mine() = pushes.filter { it.first in tokens }
                val until = System.nanoTime() + 30_000_000_000L
                while (mine().map { it.first }.toSet() != tokens && System.nanoTime() < until) Thread.sleep(20)
                Thread.sleep(200) // nothing more follows (one nudge per close and phone)
                assertTrue(mine().all { it.second.keys == setOf("kind", "reason") && it.second["kind"] == "sync_nudge" }, "$what: data-only nudges: ${mine()}")
                val tsoClose = tokens.associateWith { t -> mine().filter { it.first == t }.map { it.second.getValue("reason") } }

                // 2. The midnight job's close of another past day: the same reason to the zone's SR and AMO.
                val jobDay = tsoDay.plusDays(1)
                plannedDay(jobDay)
                nudges.clear()
                val closed = job().closeDate(jobDay).filter { it.zoneId == zone }
                assertEquals(listOf(jobDay), closed.map { it.businessDate }, "$what: the job closes the zone-day")
                assertTrue(closed.single().fieldUserIds.containsAll(listOf(srUser, amoUser)), "$what: ${closed.single().fieldUserIds}")
                val jobClose = listOf(srUser, amoUser).associateWith { u -> nudges.filter { it.first == u }.map { it.second } }

                expected[what] = tokens.associateWith { listOf(reason) } to listOf(srUser, amoUser).associateWith { listOf(reason) }
                actual[what] = tsoClose to jobClose
            }
            assertEquals(expected, actual, "per policy: (the TSO's close per phone, the job's close per user); one nudge each, of the policy's reason")
        }
    }
}

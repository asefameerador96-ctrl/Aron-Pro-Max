package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlReachResolver
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.Nudger
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.sync.AutoFinalSubmitJob
import com.aktcl.aron.backend.app.TsoWorld.Companion.json
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.HttpStatusCode
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * tso-manual B3, D-TSO-MAN-30 (build contract s5 B3 tests "job: midnight, restart catch-up, idempotent, skips submitted
 * zones, inbox rows"; s8 row 26): the midnight AutoFinalSubmitJob, run as the WORKER database login (`SET ROLE
 * worker_rw`, so the grants of V0133/V0135 and R-F-07's missing audit grant are part of the proof).
 *
 * World: mirrored divisions AFS (TSO `afstso1001` on T-AFS, SR `afssr1001` on AFS-SR-*) and AFT, plus zones made here.
 * Z-AFS has a planned normal SR route-day on 2026-12-29, 12-31, 2027-01-01 and 01-02. The clock starts at 23:59:30 Dhaka
 * on 2027-01-02. Z-AFS is in house H-AFS with Z-AFS-H2, where AMO `afsamo9` (scope: the house) has its own AMO route.
 * 1. Midnight and catch-up: a pass just before midnight (a worker that starts then) closes 12-31 and 01-01 (inside the
 *    reopen window of 3 days; on 01-01 also Z-AFS-RE, reopened by an ADMIN) but not 01-02 (still today) nor 12-29
 *    (outside); the next pass at 00:00:05 closes 01-02.
 * 2. The close: via job, submitted_by NULL, the derived uuid (zone, date, cycle), route-days closed, the
 *    `zone_day.final_submitted` event, no audit row; one inbox row `final_submit_auto` per zone-day close for the TSO of
 *    the territory only (dedupe key with the cycle and the recipient); pushes `notification` to that TSO and
 *    `day_final_submitted` to the zone's SR and to the AMO whose house covers the zone (its own route is in another zone
 *    of the house; D-TSO-MAN-12's rule), never to an AMO of another territory, after the commit.
 * 3. Skips: a zone a TSO already submitted, zones that are not service zones (only an AMO route, a dummy BR route, an
 *    unplanned route-day, an approved day exception), and a zone of a wing whose switch
 *    `cfg.day.auto_final_submit_enabled` is off (closed once the switch is on again). A reopened zone-day has no live
 *    submit (D-TSO-MAN-30): it is closed again, as cycle 1 (submit_seq 1, its own uuid and inbox row).
 * 4. Idempotent: a third pass and a second job instance change nothing, write no inbox row and push nothing.
 * 5. After the job the TSO's own Final Submit of that zone-day is 409 ERR_DAY_ALREADY_FINAL_SUBMITTED and the preview
 *    shows the close with no submitter.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AutoFinalSubmitJobTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var workerDb: Database
    private lateinit var job: AutoFinalSubmitJob
    private val nudges = CopyOnWriteArrayList<Pair<Long, String>>()
    private val beforeMidnight = Instant.parse("2027-01-02T17:59:30Z")
    private val afterMidnight = Instant.parse("2027-01-02T18:00:05Z")
    private val d29 = LocalDate.parse("2026-12-29")
    private val d31 = LocalDate.parse("2026-12-31")
    private val d01 = LocalDate.parse("2027-01-01")
    private val d02 = LocalDate.parse("2027-01-02")
    private var zAfs = 0L
    private var zAft = 0L
    private var zReopened = 0L
    private var zOff = 0L
    private var wingOff = 0L
    private val notService = mutableMapOf<String, Long>()
    private var tso = 0L
    private var otherTso = 0L
    private var homeTso = 0L
    private var sr = 0L
    private var houseAmo = 0L
    private var otherAmo = 0L

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot, mirrors = listOf("AFS", "AFT"), start = beforeMidnight)
        zAfs = world.one("SELECT id FROM app.zone WHERE code = 'Z-AFS'")
        zAft = world.one("SELECT id FROM app.zone WHERE code = 'Z-AFT'")
        tso = world.one("SELECT id FROM app.app_user WHERE username = 'afstso1001'")
        otherTso = world.one("SELECT id FROM app.app_user WHERE username = 'afttso1001'")
        homeTso = world.one("SELECT id FROM app.app_user WHERE username = 'tso1001'")
        sr = world.one("SELECT id FROM app.app_user WHERE username = 'afssr1001'")
        fun planned(routeCode: String, date: LocalDate, planned: Boolean = true) = world.exec(
            "INSERT INTO app.route_day (route_id, business_date, planned, assigned_user_id) SELECT r.id, DATE '$date', $planned, " +
                "(SELECT a.user_id FROM app.route_assignment a WHERE a.route_id = r.id AND a.kind = 'primary' LIMIT 1) FROM app.route r WHERE r.code = '$routeCode'",
        )
        for (d in listOf(d29, d31, d01, d02)) planned("AFS-SR-D", d)
        // Z-AFT: its TSO closed the day by hand.
        planned("AFT-SR-D", d02)
        world.exec("INSERT INTO app.final_submit (client_uuid, zone_id, business_date, submitted_by, submitted_at, via) VALUES (gen_random_uuid(), $zAft, DATE '$d02', $otherTso, TIMESTAMPTZ '2027-01-02T12:00:00Z', 'web')")
        // Zones of T-AFS made here, each with one route of its own.
        fun zone(code: String, routeKind: String = "sr", routeClass: String = "normal", territory: String = "T-AFS"): Long {
            world.exec("INSERT INTO app.zone (code, name, territory_id) SELECT '$code', 'Zone $code', id FROM app.territory WHERE code = '$territory'")
            world.exec(
                "INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no, route_class) " +
                    "SELECT '$code-R', 'Route $code', 'Daily', id, '$routeKind', ${if (routeKind == "sr") "'daily'" else "NULL"}, 127, 1, '$routeClass' FROM app.zone WHERE code = '$code'",
            )
            return world.one("SELECT id FROM app.zone WHERE code = '$code'")
        }
        // A reopened zone-day (D-TSO-MAN-30: no live submit, so the job closes it again; D-B3-11 revised).
        zReopened = zone("Z-AFS-RE")
        planned("Z-AFS-RE-R", d01)
        world.exec(
            "INSERT INTO app.final_submit (client_uuid, zone_id, business_date, submitted_by, submitted_at, via, reopened_at, reopened_by, reopen_reason) " +
                "SELECT gen_random_uuid(), $zReopened, DATE '$d01', $tso, TIMESTAMPTZ '2027-01-01T13:00:00Z', 'app', TIMESTAMPTZ '2027-01-02T05:00:00Z', u.id, 'late memos to accept' FROM app.app_user u WHERE u.username = 'admin1001'",
        )
        // Not service zones on 01-02: an AMO route only, a dummy BR route only, an unplanned route-day, an excused route.
        notService["amo"] = zone("Z-AFS-AM", routeKind = "amo").also { planned("Z-AFS-AM-R", d02) }
        notService["dummy_br"] = zone("Z-AFS-BR", routeClass = "dummy_br").also { planned("Z-AFS-BR-R", d02) }
        notService["unplanned"] = zone("Z-AFS-UP").also { planned("Z-AFS-UP-R", d02, planned = false) }
        notService["excused"] = zone("Z-AFS-EX").also {
            planned("Z-AFS-EX-R", d02)
            world.exec(
                "INSERT INTO app.day_exception (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, reason_code, route_ids, from_date, to_date, status) " +
                    "SELECT gen_random_uuid(), gen_random_uuid(), DATE '$d02', $tso, TIMESTAMPTZ '2027-01-02T03:00:00Z', 1, 'market_closed', ARRAY[r.id], DATE '$d02', DATE '$d02', 'approved' FROM app.route r WHERE r.code = 'Z-AFS-EX-R'",
            )
        }
        // A wing whose switch is off.
        world.exec("INSERT INTO app.wing (code, name) VALUES ('W-OFF', 'Off Wing')")
        world.exec("INSERT INTO app.division (code, name, wing_id) SELECT 'D-OFF', 'Off Division', id FROM app.wing WHERE code = 'W-OFF'")
        world.exec("INSERT INTO app.territory (code, name, division_id) SELECT 'T-OFF', 'Off Territory', id FROM app.division WHERE code = 'D-OFF'")
        wingOff = world.one("SELECT id FROM app.wing WHERE code = 'W-OFF'")
        zOff = zone("Z-OFF", territory = "T-OFF").also { planned("Z-OFF-R", d02) }
        world.exec(
            "INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) " +
                "SELECT 'cfg.day.auto_final_submit_enabled', 'wing', $wingOff, 'false'::jsonb, TIMESTAMPTZ '${ApiWorld.CFG_FROM}', max(config_version), 'test: off in W-OFF' FROM app.cfg_version",
        )
        // tso-manual B3 fix round (D-TSO-MAN-31, D-TSO-MAN-12): the mirror's house H-AFS holds Z-AFS, and Z-AFS-H2 joins it;
        // AMO afsamo9's scope is the house and its own AMO route is in Z-AFS-H2, so it has no route-day in Z-AFS; AMO
        // aftamo9 covers T-AFT only.
        check(world.one("SELECT count(*) FROM app.zone z JOIN app.house h ON h.id = z.house_id WHERE z.id = $zAfs AND h.code = 'H-AFS'") == 1L) { "the mirror puts Z-AFS in H-AFS" }
        val zAmo = zone("Z-AFS-H2", routeKind = "amo")
        world.exec("UPDATE app.zone SET house_id = (SELECT id FROM app.house WHERE code = 'H-AFS') WHERE id = $zAmo")
        for (name in listOf("afsamo9", "aftamo9")) world.exec(
            "INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password) " +
                "SELECT '$name', 'AMO $name', role, designation, upper('$name'), locale, home_zone_id, false, false FROM app.app_user WHERE username = 'amo1001'",
        )
        houseAmo = world.one("SELECT id FROM app.app_user WHERE username = 'afsamo9'")
        otherAmo = world.one("SELECT id FROM app.app_user WHERE username = 'aftamo9'")
        world.exec("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from) SELECT $houseAmo, 'house', id, DATE '2026-01-01' FROM app.house WHERE code = 'H-AFS'")
        world.exec("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from) SELECT $otherAmo, 'territory', id, DATE '2026-01-01' FROM app.territory WHERE code = 'T-AFT'")
        world.exec("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from) SELECT r.id, $houseAmo, 'primary', DATE '2026-01-01' FROM app.route r WHERE r.code = 'Z-AFS-H2-R'")
        for (d in listOf(d31, d01, d02)) planned("Z-AFS-H2-R", d)
        // The worker's own login (V0014 worker_rw): the job must manage with the worker's grants only.
        workerDb = Database(HikariDataSource(HikariConfig().apply { jdbcUrl = world.fresh.url; maximumPoolSize = 2; connectionInitSql = "SET ROLE worker_rw" }))
        job = newJob()
    }

    private fun newJob() = AutoFinalSubmitJob(
        workerDb, DbServerConfig(workerDb, RegistryDefaults(), world.clock), SqlReachResolver(workerDb, GeoRepository(workerDb, world.clock), world.clock),
        Nudger { u, reason -> nudges += u to reason }, world.clock,
    )

    @AfterAll
    fun tearDown() { workerDb.close(); world.close() }

    private fun closes(zone: Long, date: LocalDate) = world.rows("SELECT client_uuid::text AS u, via, submitted_by, reopened_at FROM app.final_submit WHERE zone_id = $zone AND business_date = DATE '$date' ORDER BY id")
    private fun inbox(user: Long) = world.rows("SELECT kind, ref_type, ref_id, dedupe_key, title_en, title_bn, body_en, body_bn FROM app.user_notification WHERE user_id = $user ORDER BY id")

    @Test
    @Order(1)
    fun theMidnightPassClosesTheDayThatEndedAndTheStartUpPassCatchesUpTheWindow() {
        assertEquals(Duration.ofSeconds(35), AutoFinalSubmitJob.delayToNextRun(beforeMidnight), "the next run is 00:00:05 Dhaka")
        assertEquals(Duration.ofHours(24), AutoFinalSubmitJob.delayToNextRun(afterMidnight))
        assertEquals(listOf(LocalDate.parse("2026-12-30"), d31, d01), job.window(), "the reopen window before today (cfg.day.reopen_window_days 3)")
        // A worker that starts at 23:59:30 on 01-02: the catch-up closes the window, never today.
        val first = job.runOnce()
        assertEquals(setOf(zAfs to d31, zAfs to d01, zReopened to d01), first.map { it.zoneId to it.businessDate }.toSet(), "the catch-up pass (the reopened zone-day included)")
        assertTrue(closes(zAfs, d02).isEmpty(), "01-02 is still today at 23:59:30")
        assertTrue(job.closeDate(d02).isEmpty(), "a day that has not ended is never closed")
        // Midnight.
        world.now.set(afterMidnight)
        val second = job.runOnce()
        assertEquals(listOf(zAfs to d02), second.map { it.zoneId to it.businessDate }, "the midnight pass closes the day that ended, and only it")
        assertTrue(closes(zAfs, d29).isEmpty(), "12-29 is outside the reopen window")
    }

    @Test
    @Order(2)
    fun theCloseIsTheJobsWithItsEventInboxRowsAndPushesAndNoAuditRow() {
        for (d in listOf(d31, d01, d02)) {
            val c = closes(zAfs, d).single()
            assertEquals(AutoFinalSubmitJob.jobUuid(zAfs, d, 0), c["u"])
            assertEquals("job", c["via"]); assertNull(c["submitted_by"], "V0135 final_submit_job_actor: no submitter")
            val ev = world.rows("SELECT payload::text AS p FROM app.domain_event WHERE event_type = 'zone_day.final_submitted' AND aggregate_id = '$zAfs:$d'").single()
            val p = json(ev["p"] as String)
            assertEquals("job", p["via"]!!.jsonPrimitive.content); assertEquals("null", p["submitted_by"].toString())
            assertEquals(AutoFinalSubmitJob.jobUuid(zAfs, d, 0), p["final_submit_uuid"]!!.jsonPrimitive.content)
        }
        val rd = world.rows("SELECT state, final_submitted_at FROM app.route_day rd JOIN app.route r ON r.id = rd.route_id WHERE r.code = 'AFS-SR-D' AND rd.business_date = DATE '$d02'").single()
        assertEquals("final_submitted", rd["state"]); assertNotNull(rd["final_submitted_at"])
        assertEquals(0L, world.one("SELECT count(*) FROM app.audit_log WHERE entity = 'zone_day'"), "R-F-07: the job writes no audit row")
        // One inbox row per zone-day close for the TSO of the territory; none for the TSO of another territory.
        val mine = inbox(tso)
        assertEquals(listOf("$zAfs:$d31", "$zAfs:$d01", "$zReopened:$d01", "$zAfs:$d02"), mine.map { it["ref_id"] })
        for (n in mine) {
            val reclosed = n["ref_id"] == "$zReopened:$d01"
            assertEquals("final_submit_auto", n["kind"]); assertEquals("zone_day", n["ref_type"])
            assertEquals("final_submit_auto:${n["ref_id"]}:${if (reclosed) 1 else 0}:$tso", n["dedupe_key"], "R-F-04: the key names the zone-day, the cycle and the recipient")
            assertEquals("Final Submit done automatically", n["title_en"]); assertTrue((n["title_bn"] as String).isNotBlank())
            val date = (n["ref_id"] as String).substringAfter(':')
            assertEquals("${if (reclosed) "Zone Z-AFS-RE" else "Afspur"}, $date: no Final Submit by midnight, so the system submitted the zone.", n["body_en"], "the zone's name and the date")
            assertTrue((n["body_bn"] as String).contains("২০২"), "Bengali digits in the Bangla body: ${n["body_bn"]}")
        }
        assertTrue(inbox(otherTso).isEmpty() && inbox(homeTso).isEmpty(), "only TSOs whose reach covers the zone")
        assertEquals(4, nudges.count { it == tso to "notification" }, "one inbox push per new row: $nudges")
        assertEquals(3, nudges.count { it == sr to "day_final_submitted" }, "the zone's SR phone locks each closed day: $nudges")
        assertEquals(3, nudges.count { it == houseAmo to "day_final_submitted" }, "the AMO whose house covers Z-AFS locks too, without a route-day there: $nudges")
        assertEquals(0, nudges.count { it.first == otherAmo }, "an AMO of another territory is not the zone's: $nudges")
    }

    @Test
    @Order(3)
    fun submittedReopenedNonServiceAndSwitchedOffZonesAreLeftAlone() {
        assertEquals(listOf("web"), closes(zAft, d02).map { it["via"] }, "a zone its TSO submitted keeps that one close")
        // D-TSO-MAN-30: a reopened zone-day has no live submit, so the job closed it again as cycle 1 (its own uuid, never
        // the first close's), with submit_seq 1 in the event.
        val re = closes(zReopened, d01)
        assertEquals(2, re.size, re.toString())
        assertNotNull(re[0]["reopened_at"]); assertEquals("app", re[0]["via"])
        assertNull(re[1]["reopened_at"]); assertEquals("job", re[1]["via"]); assertNull(re[1]["submitted_by"])
        assertEquals(AutoFinalSubmitJob.jobUuid(zReopened, d01, 1), re[1]["u"])
        val ev = world.rows("SELECT payload::text AS p FROM app.domain_event WHERE event_type = 'zone_day.final_submitted' AND aggregate_id = '$zReopened:$d01'").single()
        assertEquals("1", json(ev["p"] as String)["submit_seq"]!!.jsonPrimitive.content)
        for ((why, z) in notService) assertTrue(closes(z, d02).isEmpty(), "not a service zone ($why)")
        assertTrue(closes(zOff, d02).isEmpty(), "the wing's switch is off")
        // The switch on again at the wing: the next pass closes it.
        world.exec("UPDATE app.cfg_value SET effective_to = TIMESTAMPTZ '${ApiWorld.CFG_SWITCH}' WHERE key = 'cfg.day.auto_final_submit_enabled' AND scope_type = 'wing' AND scope_id = $wingOff AND effective_to IS NULL")
        world.exec(
            "INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) " +
                "SELECT 'cfg.day.auto_final_submit_enabled', 'wing', $wingOff, 'true'::jsonb, TIMESTAMPTZ '${ApiWorld.CFG_SWITCH}', max(config_version), 'test: on again' FROM app.cfg_version",
        )
        assertEquals(listOf(zOff to d02), job.runOnce().map { it.zoneId to it.businessDate })
        assertEquals("job", closes(zOff, d02).single()["via"])
    }

    @Test
    @Order(4)
    fun aRepeatedPassOrASecondWorkerChangesNothing() {
        val finals = world.one("SELECT count(*) FROM app.final_submit")
        val notes = world.one("SELECT count(*) FROM app.user_notification")
        val events = world.one("SELECT count(*) FROM app.domain_event WHERE event_type = 'zone_day.final_submitted'")
        val pushes = nudges.size
        assertTrue(job.runOnce().isEmpty())
        assertTrue(newJob().runOnce().isEmpty(), "a second replica")
        assertEquals(finals, world.one("SELECT count(*) FROM app.final_submit"))
        assertEquals(notes, world.one("SELECT count(*) FROM app.user_notification"))
        assertEquals(events, world.one("SELECT count(*) FROM app.domain_event WHERE event_type = 'zone_day.final_submitted'"))
        assertEquals(pushes, nudges.size, "no push on a repeat")
    }

    @Test
    @Order(5)
    fun afterTheJobTheTsosOwnSubmitIsRefusedAndThePreviewShowsTheClose() = world.withApi {
        with(world) {
            val r = postWeb("afstso1001", "/v1/day/final-submit", """{"client_uuid":"${UUID.randomUUID()}","zone_id":$zAfs,"business_date":"$d02"}""")
            assertEquals(HttpStatusCode.Conflict, r.status, r.bodyAsText())
            assertTrue(r.bodyAsText().contains("ERR_DAY_ALREADY_FINAL_SUBMITTED"))
            val p = getWeb("afstso1001", "/v1/day/final-submit/preview?zone_id=$zAfs&business_date=$d02")
            assertEquals(HttpStatusCode.OK, p.status, p.bodyAsText())
            val b = json(p.bodyAsText())
            assertEquals("true", b["already_submitted"]!!.jsonPrimitive.content)
            assertEquals("null", b["submitted_by"].toString(), "a job close has no submitter")
        }
    }
}

package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.contract.Role
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Round 1.20.0 (D-SV2-15, D-SV2-16), the two pieces that live in the master-data module: the worker rule that withdraws a
 * CHECKOUT_WITHOUT_SUBMIT signal when an effective Sales Submit of the user-date is stored (run as `worker_rw`: the API role may not
 * update `risk_signal`), and the admin's username rule for memo-producing roles (3 to 32 characters).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SrCheckoutRoundTest {
    private lateinit var env: AdminEnv
    @BeforeAll fun up() { env = AdminEnv() }
    @AfterAll fun down() = env.close()

    private val today = LocalDate.parse("2027-01-03")
    private val uid get() = env.db.ids.getValue("sr1001")

    private fun signal(code: String, date: String, status: String = "open") = env.exec(
        "INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, user_id, score, evidence, config_version, status) " +
            "VALUES ('$code', 2, DATE '$date', 'user', '$uid', $uid, 20, '{\"day_submit_found\": false}'::jsonb, 1, '$status')",
    )

    private fun submit(date: String, voided: Boolean = false, scope: String = "route_day"): String {
        val u = UUID.randomUUID().toString()
        env.exec(
            "INSERT INTO app.route_day_event (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, kind, scope, submit_cycle, device_counts, device_money, voided_at) " +
                "VALUES ('$u', '$u', DATE '$date', $uid, TIMESTAMPTZ '$date 10:00+00', 1, 'day_submit', '$scope', 1, '{}'::jsonb, '{}'::jsonb, ${if (voided) "now()" else "NULL"})",
        )
        return u
    }

    private fun status(code: String, date: String) = env.sql("SELECT status FROM app.risk_signal WHERE code = '$code' AND user_id = $uid AND business_date = DATE '$date'")

    private fun evidence(code: String, date: String) = env.sql("SELECT evidence::text FROM app.risk_signal WHERE code = '$code' AND user_id = $uid AND business_date = DATE '$date'")!!

    @Test
    fun anEffectiveSalesSubmitWithdrawsTheOpenSignalAndNothingElseMoves() {
        val c = "CHECKOUT_WITHOUT_SUBMIT"
        val withdrawn = "2026-12-30"; val noSubmit = "2026-12-29"; val voidedSubmit = "2026-12-28"; val reviewed = "2026-12-27"; val tooOld = "2026-10-01"; val other = "2026-12-31"
        signal(c, withdrawn); signal(c, noSubmit); signal(c, voidedSubmit); signal(c, reviewed, "confirmed"); signal(c, "2026-12-26", "dismissed"); signal(c, "2026-12-25", "reviewed"); submit("2026-12-26"); submit("2026-12-25"); signal(c, tooOld); signal("GEO_MOCK", withdrawn)
        signal(c, other)
        val s1 = submit(withdrawn); submit(voidedSubmit, voided = true); submit(reviewed); submit(tooOld)
        // A supervisor-day submit of an AMO-style scope also counts, as it does at the ingest.
        submit(other, scope = "supervisor_day")
        val ev = RiskSignalEvaluator(env.db.fresh.db, DbServerConfig(env.db.fresh.db, RegistryDefaults()))

        // As the worker role: the ingest's role may not update risk_signal at all (the premise of doing this in a worker rule).
        env.db.fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("SET ROLE api_rw")
            try { assertFailsWith<Exception> { h.execute("UPDATE app.risk_signal SET status = 'dismissed' WHERE code = 'GEO_MOCK'") } } finally { h.execute("RESET ROLE") }
        }
        val moved = env.db.fresh.db.jdbi.inTransaction<Int, Exception> { h ->
            h.execute("SET LOCAL ROLE worker_rw")
            ev.withdrawCheckoutWithoutSubmit(h, today)
        }
        assertEquals(2, moved, "the two open signals whose user-date has an effective submit")

        assertTrue(env.count("SELECT count(*) FROM app.dirty_key WHERE kind = 'route_day_agg' AND reason = 'risk_signal_withdrawn' AND business_date = DATE '$withdrawn'") > 0, "the user's route-day aggregates are rebuilt")
        assertEquals("dismissed", status(c, withdrawn))
        val e = evidence(c, withdrawn)
        assertTrue(e.contains("\"withdrawn_by\": \"day_submit\"") && e.contains(s1) && e.contains("\"withdrawn_at\": \"20") && e.contains("\"day_submit_found\": false"), "the original evidence stays, the withdrawn fields are added: $e")
        assertEquals("dismissed", status(c, other), "a supervisor-day submit counts too")
        assertEquals("open", status(c, noSubmit), "no submit: still open")
        assertEquals("open", status(c, voidedSubmit), "a data-voided submit does not count")
        assertEquals("confirmed", status(c, reviewed), "a reviewer's decision always stands")
        assertEquals("1", env.sql("SELECT count(*) FROM app.risk_signal WHERE code = '$c' AND business_date = DATE '2026-12-26' AND status = 'dismissed' AND NOT jsonb_exists(evidence, 'withdrawn_by')"), "a reviewer's dismissal is not rewritten")
        assertEquals("reviewed", status(c, "2026-12-25"))
        assertEquals("open", status(c, tooOld), "older than the registry retention horizon: not looked at")
        assertEquals("open", status("GEO_MOCK", withdrawn), "no other code moves")

        // Idempotent: a second run finds nothing open to move.
        assertEquals(0, env.db.fresh.db.jdbi.inTransaction<Int, Exception> { h -> h.execute("SET LOCAL ROLE worker_rw"); ev.withdrawCheckoutWithoutSubmit(h, today) })

        // A submit that arrives later for a day whose signal is still open withdraws it on the next run.
        submit(noSubmit)
        assertEquals(1, ev.withdrawCheckoutWithoutSubmit(today))
        assertEquals("dismissed", status(c, noSubmit))

        // The submit is voided AFTER the withdrawal: nothing re-raises or re-opens the signal (the ingest raises only when a check-out is stored).
        env.exec("ALTER TABLE app.route_day_event DISABLE TRIGGER route_day_event_immutable")
        env.exec("UPDATE app.route_day_event SET voided_at = now() WHERE client_uuid = '$s1'")
        env.exec("ALTER TABLE app.route_day_event ENABLE TRIGGER route_day_event_immutable")
        assertEquals(0, ev.withdrawCheckoutWithoutSubmit(today))
        assertEquals("dismissed", status(c, withdrawn))
    }

    @Test
    fun theAdminAcceptsAThreeCharacterMemoUsernameAndRefusesTwoOrAnInvalidOne() = env.app {
        val admin = env.token("admin1001", Role.ADMIN)
        val z = env.sql("SELECT id FROM app.zone WHERE code = 'Z-MIR'")!!.toLong()
        suspend fun mk(u: String, role: String) = sendA1(HttpMethod.Post, "/admin/users", admin, """{"username":"$u","full_name":"Short $u","role":"$role","locale":"en","home_zone_id":$z}""")
        val ok = mk("abc", "SR")
        assertEquals(HttpStatusCode.Created, ok.status, ok.bodyAsText())
        assertEquals(HttpStatusCode.Created, mk("amo", "AMO").status)
        for (bad in listOf("ab", "1ab", "a-b", "a".repeat(33))) {
            val r = mk(bad, "SR")
            assertEquals(HttpStatusCode.BadRequest, r.status, "$bad -> ${r.bodyAsText()}")
        }
    }

    private fun checkout(date: String, closedBy: String = "rep") = env.exec(
        "INSERT INTO app.attendance_event (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, kind, fix_status, closed_by) " +
            "VALUES (gen_random_uuid(), gen_random_uuid(), DATE '$date', $uid, TIMESTAMPTZ '$date 11:00+00', 1, 'check_out', 'ok', '$closedBy')",
    )

    private fun capture(date: String, type: String = "visit") = env.exec(
        "INSERT INTO app.ingest_registry (client_uuid, record_type, payload_sha256, status, business_date, user_id, first_batch_uuid) " +
            "VALUES (gen_random_uuid(), '$type', decode(repeat('ab', 32), 'hex'), 'accepted', DATE '$date', $uid, gen_random_uuid())",
    )

    /** Lead review of 2f5922ee, low 4: the rule is complete in both directions. A real check-out whose day got captures AFTER it was stored and has no submit raises the signal later. */
    @Test
    fun aCaptureRegisteredAfterTheCheckOutRaisesTheSignalLaterAndOnlyForARealCheckOut() {
        val c = "CHECKOUT_WITHOUT_SUBMIT"
        val late = "2026-12-20"; val noCaptures = "2026-12-19"; val phoneClosed = "2026-12-18"; val submitted = "2026-12-17"; val tooOld = "2026-10-02"
        checkout(late); capture(late, "memo")
        checkout(noCaptures)
        checkout(phoneClosed, "phone"); capture(phoneClosed)
        checkout(submitted); capture(submitted); submit(submitted)
        checkout(tooOld); capture(tooOld)
        // A day whose captures are only state records or telemetry is not a capture day.
        val state = "2026-12-16"; checkout(state); capture(state, "day_open"); capture(state, "geo_breadcrumb")
        val ev = RiskSignalEvaluator(env.db.fresh.db, DbServerConfig(env.db.fresh.db, RegistryDefaults()))
        val raised = env.db.fresh.db.jdbi.inTransaction<Int, Exception> { h -> h.execute("SET LOCAL ROLE worker_rw"); ev.raiseCheckoutWithoutSubmit(h, today) }
        assertEquals(1, raised, "only the real check-out of a capture day without a submit")
        assertEquals("open", status(c, late))
        for (d in listOf(noCaptures, phoneClosed, submitted, tooOld, state)) assertEquals(null, status(c, d), "no signal for $d")
        val e = evidence(c, late)
        assertTrue(e.contains("\"raised_by\": \"worker\"") && e.contains("\"day_submit_found\": false"), e)
        assertEquals("20.00", env.sql("SELECT score FROM app.risk_signal WHERE code = '$c' AND user_id = $uid AND business_date = DATE '$late'"))
        assertTrue(env.count("SELECT count(*) FROM app.dirty_key WHERE kind = 'route_day_agg' AND reason = 'risk_signal_raised' AND business_date = DATE '$late'") > 0)
        // Idempotent, and an existing signal of any status (here a withdrawn one) is left alone.
        assertEquals(0, ev.raiseCheckoutWithoutSubmit(today))
        submit(late)
        assertEquals(1, ev.withdrawCheckoutWithoutSubmit(today))
        assertEquals("dismissed", status(c, late))
        assertEquals(0, ev.raiseCheckoutWithoutSubmit(today), "a withdrawn day is not raised again")
        assertEquals("dismissed", status(c, late))
    }

    @Test
    fun theWorkerRuleAndTheIngestAgreeOnWhatARouteCaptureIs() {
        // The ingest's list lives in backend:sync (IngestService.DAY_LOCKED_TYPES); backend:app's CheckoutOutranksTest asserts the two sets are equal.
        assertEquals(13, ROUTE_CAPTURE_TYPES.size)
    }
}


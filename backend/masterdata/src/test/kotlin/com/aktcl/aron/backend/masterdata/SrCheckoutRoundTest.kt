package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.RegistryDefaults
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
 * Train 117 (contract 1.19.0) (D-SV2-15, D-SV2-16), the two pieces that live in the master-data module: the worker rule that withdraws a
 * CHECKOUT_WITHOUT_SUBMIT signal when an effective Sales Submit of the user-date is stored (run as `worker_rw`: the API role may not
 * update `risk_signal`). (The admin's 3-character username rule is the master-data train's, with its own tests in AdminUsersRoutesTest.)
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
}

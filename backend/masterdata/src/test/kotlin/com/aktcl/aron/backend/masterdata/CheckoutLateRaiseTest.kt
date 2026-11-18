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
import kotlin.test.assertTrue

/** The late raise of CHECKOUT_WITHOUT_SUBMIT (the mirror of the withdrawal), in its own database: its days must not meet the signals of [SrCheckoutRoundTest]. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckoutLateRaiseTest {
    private lateinit var env: AdminEnv
    @BeforeAll fun up() { env = AdminEnv() }
    @AfterAll fun down() = env.close()

    private val today = LocalDate.parse("2027-01-03")
    private val uid get() = env.db.ids.getValue("sr1001")

    private fun submit(date: String, voided: Boolean = false): String {
        val u = UUID.randomUUID().toString()
        env.exec(
            "INSERT INTO app.route_day_event (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, kind, scope, submit_cycle, device_counts, device_money, voided_at) " +
                "VALUES ('$u', '$u', DATE '$date', $uid, TIMESTAMPTZ '$date 10:00+00', 1, 'day_submit', 'route_day', 1, '{}'::jsonb, '{}'::jsonb, ${if (voided) "now()" else "NULL"})",
        )
        return u
    }

    private fun status(code: String, date: String) = env.sql("SELECT status FROM app.risk_signal WHERE code = '$code' AND user_id = $uid AND business_date = DATE '$date'")

    private fun evidence(code: String, date: String) = env.sql("SELECT evidence::text FROM app.risk_signal WHERE code = '$code' AND user_id = $uid AND business_date = DATE '$date'")!!

    private fun checkout(date: String, closedBy: String = "rep", receivedAt: String? = null, routeSql: String = "NULL") = env.exec(
        "INSERT INTO app.attendance_event (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, kind, fix_status, closed_by, received_at) " +
            "VALUES (gen_random_uuid(), gen_random_uuid(), DATE '$date', $uid, $routeSql, TIMESTAMPTZ '$date 11:00+00', 1, 'check_out', 'ok', '$closedBy', ${receivedAt?.let { "TIMESTAMPTZ '$it'" } ?: "TIMESTAMPTZ '$date 11:00+00'"})",
    )

    private fun capture(date: String, type: String = "visit") = env.exec(
        "INSERT INTO app.ingest_registry (client_uuid, record_type, payload_sha256, status, business_date, user_id, first_batch_uuid) " +
            "VALUES (gen_random_uuid(), '$type', decode(repeat('ab', 32), 'hex'), 'accepted', DATE '$date', $uid, gen_random_uuid())",
    )

    private val now = java.time.Instant.parse("2027-01-03T04:00:00Z")

    /**
     * Lead reviews of 2f5922ee and ee82e904: the rule is complete in both directions. A real check-out whose day got captures AFTER it was stored and has no submit raises the signal later,
     * after a grace period, never for a day that had any submit (even a data-voided one), carrying the check-out's route, looking back only the sync window plus one day.
     */
    @Test
    fun aCaptureRegisteredAfterTheCheckOutRaisesTheSignalLaterUnderTheRulesOfTheLateRaise() {
        val c = "CHECKOUT_WITHOUT_SUBMIT"
        val late = "2026-12-31"; val noCaptures = "2026-12-30"; val phoneClosed = "2026-12-29"; val submitted = "2026-12-28"; val voidedSubmit = "2026-12-27"; val tooOld = "2026-12-20"; val state = "2026-12-26"
        val young = "2027-01-02"
        val route = env.sql("SELECT id FROM app.route ORDER BY id LIMIT 1")!!
        checkout(late, routeSql = route); capture(late, "memo")
        checkout(noCaptures)
        checkout(phoneClosed, "phone"); capture(phoneClosed)
        checkout(submitted); capture(submitted); submit(submitted)
        checkout(voidedSubmit); capture(voidedSubmit); submit(voidedSubmit, voided = true)
        checkout(tooOld); capture(tooOld)
        checkout(state); capture(state, "day_open"); capture(state, "geo_breadcrumb")
        // received 10 minutes before `now`: inside the 30-minute settle window.
        checkout(young, receivedAt = "2027-01-03 03:50+00"); capture(young)
        val ev = RiskSignalEvaluator(env.db.fresh.db, DbServerConfig(env.db.fresh.db, RegistryDefaults()))
        val raised = env.db.fresh.db.jdbi.inTransaction<Int, Exception> { h -> h.execute("SET LOCAL ROLE worker_rw"); ev.raiseCheckoutWithoutSubmit(h, today, now) }
        assertEquals(1, raised, "only the real check-out of a capture day without any submit, past the grace period and inside the window")
        assertEquals("open", status(c, late))
        for (d in listOf(noCaptures, phoneClosed, submitted, voidedSubmit, tooOld, state, young)) assertEquals(null, status(c, d), "no signal for $d")
        val e = evidence(c, late)
        assertTrue(e.contains("\"raised_by\": \"worker\"") && e.contains("\"day_submit_found\": false"), e)
        assertEquals(route, env.sql("SELECT route_id FROM app.risk_signal WHERE code = '$c' AND user_id = $uid AND business_date = DATE '$late'"), "the check-out's route, like the ingest raise")
        assertEquals("20.00", env.sql("SELECT score FROM app.risk_signal WHERE code = '$c' AND user_id = $uid AND business_date = DATE '$late'"))
        assertTrue(env.count("SELECT count(*) FROM app.dirty_key WHERE kind = 'route_day_agg' AND reason = 'risk_signal_raised' AND business_date = DATE '$late'") > 0)
        // The grace period ends: the young check-out is judged on a later run.
        assertEquals(1, ev.raiseCheckoutWithoutSubmit(today, now.plusSeconds(3600)))
        assertEquals("open", status(c, young))
        // Idempotent, and an existing signal of any status (here a withdrawn one) is left alone.
        assertEquals(0, ev.raiseCheckoutWithoutSubmit(today, now.plusSeconds(3600)))
        submit(late)
        assertEquals(1, ev.withdrawCheckoutWithoutSubmit(today))
        assertEquals("dismissed", status(c, late))
        assertEquals(0, ev.raiseCheckoutWithoutSubmit(today, now.plusSeconds(3600)), "a withdrawn day is not raised again")
        assertEquals("dismissed", status(c, late))
    }

}

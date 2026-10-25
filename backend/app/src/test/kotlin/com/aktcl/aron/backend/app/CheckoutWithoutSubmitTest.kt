package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.sync.IngestService
import com.aktcl.aron.contract.RiskSignalCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D-NIGHT-CHECKOUT-01 server half (contract 1.9 queue row 5): a stored check-out of an SR without an effective
 * `day_submit` for its business date raises CHECKOUT_WITHOUT_SUBMIT once per user-day; the check-out is stored
 * unchanged either way. Always on since contract 1.9.0 (catalogue) and db V0114/V0115 (constraint, weight default 20);
 * the switch that waited for both is gone (D-BC-CHECKOUT-ON-01), so these tests run on the migrated database as is.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CheckoutWithoutSubmitTest : NightSyncHarness() {
    private val phone get() = seededPhone

    private fun signals(date: String) = count("SELECT count(*) FROM app.risk_signal WHERE code = 'CHECKOUT_WITHOUT_SUBMIT' AND subject_type = 'user' AND subject_id = '$srId' AND user_id = $srId AND business_date = DATE '$date'")
    private fun stored(r: kotlinx.serialization.json.JsonObject) = count("SELECT count(*) FROM app.attendance_event WHERE client_uuid = '${cu(r)}'")

    @Test
    @Order(1)
    fun onWithTheMigratedConstraintTheCatalogueAndTheDefaultWeight() {
        assertTrue(RiskSignalCode.entries.any { it.wire == IngestService.CHECKOUT_WITHOUT_SUBMIT }, "contract 1.9.0 catalogue")
        fresh.db.jdbi.useHandle<Exception> { h ->
            val def = h.createQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'app.risk_signal'::regclass AND conname = 'risk_signal_code_check'").mapTo(String::class.java).one()
            assertTrue(def.contains("'CHECKOUT_WITHOUT_SUBMIT'"), "db V0114/V0115: $def")
        }
        assertEquals(20L, count("SELECT (default_value -> 'CHECKOUT_WITHOUT_SUBMIT')::bigint FROM app.cfg_key WHERE key = 'cfg.geo.integrity_weight'"))
    }

    @Test
    @Order(2)
    fun aCheckOutWithoutSalesSubmitRaisesOneSignalPerUserDayAndIsStoredUnchanged() = testApplication {
        application { aronApi(wiring) }
        val t = client.token("sr1001", phone)
        val out = attendance("check_out")
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(out)))
        assertEquals(1, stored(out), "the check-out is stored unchanged")
        assertEquals(1, signals(day))
        val ev = fresh.db.jdbi.withHandle<String, Exception> { h ->
            h.createQuery("SELECT evidence::text FROM app.risk_signal WHERE code = 'CHECKOUT_WITHOUT_SUBMIT' AND business_date = DATE '$day'").mapTo(String::class.java).one()
        }
        assertTrue(ev.contains(cu(out)) && ev.contains("\"day_submit_found\": false"), ev)
        assertEquals(20.0, count("SELECT score::bigint FROM app.risk_signal WHERE code = 'CHECKOUT_WITHOUT_SUBMIT' AND business_date = DATE '$day'").toDouble())
        // The same check-out again (a duplicate) is never judged again: with the signal removed, a resend raises none.
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("DELETE FROM app.risk_signal WHERE code = 'CHECKOUT_WITHOUT_SUBMIT' AND business_date = DATE '$day'") }
        assertEquals(listOf("duplicate:-"), client.send(t, phone, listOf(out)))
        assertEquals(0, signals(day), "a duplicate is not evaluated")
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, user_id, score, evidence, config_version) VALUES ('CHECKOUT_WITHOUT_SUBMIT', 2, DATE '$day', 'user', '$srId', $srId, 20, '{}', 1)")
        }
        // A second check-out of the day (refused) leaves one signal.
        assertEquals(listOf("rejected:attendance_duplicate"), client.send(t, phone, listOf(attendance("check_out"))))
        assertEquals(1, signals(day))
        // A Sales Submit stored afterwards does not raise anything new (the open signal is the reviewer's).
        assertTrue(client.send(t, phone, listOf(daySubmit())).single().startsWith("accepted"))
        assertEquals(1, signals(day))
    }

    @Test
    @Order(3)
    fun aSalesSubmitInTheSameBatchOrEarlierCountsInEitherOrderAndAVoidedOneDoesNot() = testApplication {
        application { aronApi(wiring) }
        val t = client.token("sr1001", phone)
        // Same batch, check-out first: judged after the whole batch, so the submit counts.
        val d1 = "2027-01-02"
        assertTrue(client.send(t, phone, listOf(attendance("check_out", d1), daySubmit(d1))).all { it.startsWith("accepted") })
        assertEquals(0, signals(d1))
        // An earlier batch's submit counts too; a data-voided submit does not.
        val d2 = "2026-12-30"
        val submit = daySubmit(d2)
        assertTrue(client.send(t, phone, listOf(submit)).single().startsWith("accepted"))
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(attendance("check_out", d2))))
        assertEquals(0, signals(d2))
        val d3 = "2026-12-29"
        val voided = daySubmit(d3)
        assertTrue(client.send(t, phone, listOf(voided)).single().startsWith("accepted"))
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.route_day_event SET voided_at = now() WHERE client_uuid = '${cu(voided)}'") }
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(attendance("check_out", d3))))
        assertEquals(1, signals(d3))
        // A check-in never raises it.
        val d4 = "2026-12-28"
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(attendance("check_in", d4))))
        assertEquals(0, count("SELECT count(*) FROM app.risk_signal WHERE code = 'CHECKOUT_WITHOUT_SUBMIT' AND business_date = DATE '$d4'"))
    }
}

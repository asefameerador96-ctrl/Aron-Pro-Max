package com.aktcl.aron.backend.app

import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 1.20.0 (D-SV2-15; ruling 6 of D-LEAD-PREREQ-03; `docs/requests/android-gates.md` section 4): the attendance check-out carries who closed it.
 * (a) a phone close three days late is stored and acknowledged but is "no check-out" (no too-early flag, no signal); (b) a check-out of a day with no
 * route captures raises no CHECKOUT_WITHOUT_SUBMIT; (c) a check-out with captures and no submit raises it, a later accepted day_submit is stored, and the worker rule
 * (RiskSignalEvaluator.withdrawCheckoutWithoutSubmit, tested in the masterdata module under worker_rw) withdraws it; old phones (member absent) behave as before.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CheckoutClosedByTest : NightSyncHarness() {
    private val phone get() = seededPhone

    private fun signals(date: String) = count("SELECT count(*) FROM app.risk_signal WHERE code = 'CHECKOUT_WITHOUT_SUBMIT' AND user_id = $srId AND business_date = DATE '$date'")
    private fun closedBy(r: JsonObject) = fresh.db.jdbi.withHandle<String?, Exception> { h -> h.createQuery("SELECT closed_by FROM app.attendance_event WHERE client_uuid = CAST('${cu(r)}' AS uuid)").mapTo(String::class.java).findOne().orElse(null) }
    private fun tooEarlyFlag(r: JsonObject) = count("SELECT count(*) FROM app.ingest_registry WHERE client_uuid = CAST('${cu(r)}' AS uuid) AND 'checkout_too_early' = ANY(flags)")

    /** A check-out with its capture time and member set by the caller; [closedBy] null leaves the member out (an older phone). */
    private fun checkOut(date: String, capturedAt: String, closedBy: String?, noFix: Boolean = false): JsonObject {
        val base = attendance("check_out", date)
        val payload = base["payload"]!!.jsonObject.let { p ->
            val fix = if (noFix) buildJsonObject {
                put("purpose", "attendance_out"); put("fix_status", "provider_unavailable"); put("lat", JsonNull); put("lng", JsonNull); put("accuracy_m", JsonNull)
                put("provider", "unknown"); put("is_mock", false); put("reused", false)
                put("device", p["fix"]!!.jsonObject["device"]!!)
            } else p["fix"]!!
            JsonObject(p + ("fix" to fix) + (closedBy?.let { mapOf("closed_by" to JsonPrimitive(it)) } ?: emptyMap()))
        }
        return JsonObject(base + ("captured_at" to JsonPrimitive(capturedAt)) + ("payload" to payload))
    }

    @Test
    @Order(1)
    fun aPhoneCloseThreeDaysLateIsStoredAckedAndNeverATooEarlyOrASubmitSignal() = testApplication {
        application { aronApi(wiring) }
        val t = client.token("sr1001", phone)
        val d = "2026-12-30" // Dhaka date of the forgotten day; the phone closes it at its next start, three days later
        seedCapture(d)
        // The phone's close: provider_unavailable, no coordinates. Captured at 08:00 Dhaka of the 2nd (hence also long before any 17:00 earliest time on its own date).
        val phoneClose = checkOut(d, "2027-01-02T02:00:00Z", "phone", noFix = true)
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(phoneClose)), "stored and acknowledged, not refused")
        assertEquals("phone", closedBy(phoneClose))
        assertEquals(0, signals(d), "no check-out, so no check-out without a submit, even though the day has captures and no submit")
        assertEquals(0, tooEarlyFlag(phoneClose))
        // A resend is a duplicate (idempotent by client_uuid).
        assertEquals(listOf("duplicate:-"), client.send(t, phone, listOf(phoneClose)))
        assertEquals(1, count("SELECT count(*) FROM app.attendance_event WHERE client_uuid = CAST('${cu(phoneClose)}' AS uuid)"))

        // Control: the same early hour as a REP check-out is flagged too early (the rule is on, only the phone close is exempt).
        val d2 = "2026-12-31"
        val early = checkOut(d2, "2026-12-31T02:00:00Z", "rep")
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(early)))
        assertEquals("rep", closedBy(early))
        assertEquals(1, tooEarlyFlag(early))
        // And the same early phone close of another day is not flagged.
        val d3 = "2027-01-01"
        val earlyPhone = checkOut(d3, "2027-01-01T02:00:00Z", "phone", noFix = true)
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(earlyPhone)))
        assertEquals(0, tooEarlyFlag(earlyPhone))
    }

    @Test
    @Order(2)
    fun aMissingMemberMeansRepAndAnOlderPhoneBehavesAsBefore() = testApplication {
        application { aronApi(wiring) }
        val t = client.token("sr1001", phone)
        val d = "2026-12-28"
        seedCapture(d)
        val old = checkOut(d, "2026-12-28T12:00:00Z", null) // 18:00 Dhaka, member absent
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(old)))
        assertEquals("rep", closedBy(old))
        assertEquals(1, signals(d), "an old phone's check-out of a day with captures and no submit is judged as before")
    }

    @Test
    @Order(3)
    fun aCheckOutOfADayWithNoCapturesRaisesNothing() = testApplication {
        application { aronApi(wiring) }
        val t = client.token("sr1001", phone)
        val empty = "2026-12-29"
        seedCapture("2026-12-28") // another day's capture is not this day's
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(attendance("check_out", empty))))
        assertEquals(0, signals(empty), "no route captures that day, nothing to submit")
        // Attendance, day_open and day_submit rows are not captures.
        val e2 = "2027-01-02"
        seedCapture(e2, "day_open"); seedCapture(e2, "attendance_event"); seedCapture(e2, "day_submit")
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(attendance("check_out", e2))))
        assertEquals(0, signals(e2))
    }

    @Test
    @Order(6)
    fun everyFigureTypeIsARouteCaptureAndNothingElseIs() {
        val inCaptures = listOf("visit", "visit_close", "visit_skip", "sale_abort", "memo", "memo_line", "memo_discount", "qc_line", "memo_void", "due_collection", "stock_movement", "redemption", "redemption_line")
        for ((i, type) in inCaptures.withIndex()) {
            val d = "2026-11-${(i + 1).toString().padStart(2, '0')}"
            assertEquals(false, has(d))
            seedCapture(d, type)
            assertEquals(true, has(d), "$type is a route capture")
        }
        val none = "2026-11-20"
        for (type in listOf("attendance_event", "day_open", "day_submit", "geo_breadcrumb", "device_status", "activity_log", "feedback")) seedCapture(none, type)
        assertEquals(false, has(none), "state records and telemetry are not captures")
        // A rejected or parked capture still shows the rep captured that day.
        val held = "2026-11-21"
        seedCapture(held)
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.ingest_registry SET status = 'quarantined', outcome_code = 'payload_conflict' WHERE business_date = DATE '$held'") }
        assertEquals(true, has(held))
    }

    private fun has(date: String) = fresh.db.jdbi.withHandle<Boolean, Exception> { h -> com.aktcl.aron.backend.sync.IngestService.routeCapturesExist(h, srId, java.time.LocalDate.parse(date)) }

    @Test
    @Order(4)
    fun aSubmitAcceptedAfterTheSignalIsStoredAndTheSignalStaysOpenForTheWorkerRule() = testApplication {
        application { aronApi(wiring) }
        val t = client.token("sr1001", phone)
        val d = day
        seedCapture(d)
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(attendance("check_out", d))))
        assertEquals(1, signals(d))
        assertEquals("open", fresh.db.jdbi.withHandle<String, Exception> { h -> h.createQuery("SELECT status FROM app.risk_signal WHERE code = 'CHECKOUT_WITHOUT_SUBMIT' AND business_date = DATE '$d'").mapTo(String::class.java).one() })
        // The rep's own Sales Submit after the check-out is accepted; the API role does not touch the signal (the worker rule does: masterdata SrCheckoutRoundTest).
        assertTrue(client.send(t, phone, listOf(daySubmit(d))).single().startsWith("accepted"))
        assertEquals(1, signals(d))
        assertEquals(1, count("SELECT count(*) FROM app.route_day_event WHERE kind = 'day_submit' AND user_id = $srId AND business_date = DATE '$d' AND voided_at IS NULL"))
    }

    @Test
    @Order(5)
    fun aBadClosedByValueOrOneOnACheckInIsRefusedFinal() = testApplication {
        application { aronApi(wiring) }
        val t = client.token("sr1001", phone)
        val d = "2026-12-28"
        val badValue = checkOut(d, "2026-12-28T12:00:00Z", "server")
        assertEquals(listOf("rejected:schema_invalid"), client.send(t, phone, listOf(badValue)))
        val checkIn = attendance("check_in", d).let { r -> JsonObject(r + ("payload" to JsonObject(r["payload"]!!.jsonObject + ("closed_by" to JsonPrimitive("phone"))))) }
        assertEquals(listOf("rejected:schema_invalid"), client.send(t, phone, listOf(checkIn)), "only a check-out can be closed by the phone")
        assertEquals(0, count("SELECT count(*) FROM app.attendance_event WHERE client_uuid IN (CAST('${cu(badValue)}' AS uuid), CAST('${cu(checkIn)}' AS uuid))"))
    }
}

package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.masterdata.ROUTE_CAPTURE_TYPES
import com.aktcl.aron.backend.masterdata.RiskSignalEvaluator
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.sync.IngestService
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lead review of 2f5922ee (lows 1, 2, 4, 6, 7): a real check-out outranks a phone close of the same day whatever the arrival order (the once-rule is two partial unique indexes, V0205:
 * one rep row per user, day and kind, one phone close per user and day); `closed_by = rep` on a check-in is accepted (the contract says so); a phone close's fix is never a geo_fix; and
 * the whole chain through the real ingest: check-out, then the day_submit, then the worker rule.
 */
class CheckoutOutranksTest : NightSyncHarness() {
    private val phone get() = seededPhone
    private val today = LocalDate.parse("2027-01-03")

    private fun withMembers(r: JsonObject, closedBy: String?, atUtc: String? = null, noFix: Boolean = false): JsonObject {
        val p = r["payload"]!!.jsonObject.let { p0 ->
            val fix = if (noFix) buildJsonObject {
                put("purpose", "attendance_out"); put("fix_status", "provider_unavailable"); put("lat", JsonNull); put("lng", JsonNull); put("accuracy_m", JsonNull)
                put("provider", "unknown"); put("is_mock", false); put("reused", false); put("device", p0["fix"]!!.jsonObject["device"]!!)
            } else p0["fix"]!!
            JsonObject(p0 + ("fix" to fix) + (closedBy?.let { mapOf("closed_by" to JsonPrimitive(it)) } ?: emptyMap()))
        }
        return JsonObject(r + ("payload" to p) + (atUtc?.let { mapOf("captured_at" to JsonPrimitive(it)) } ?: emptyMap()))
    }

    private fun rows(date: String, kind: String) = fresh.db.jdbi.withHandle<List<String>, Exception> { h ->
        h.createQuery("SELECT closed_by FROM app.attendance_event WHERE user_id = $srId AND business_date = DATE '$date' AND kind = '$kind' AND voided_at IS NULL ORDER BY closed_by").mapTo(String::class.java).list()
    }

    private fun signal(date: String) = fresh.db.jdbi.withHandle<String?, Exception> { h ->
        h.createQuery("SELECT status || ' ' || evidence::text FROM app.risk_signal WHERE code = 'CHECKOUT_WITHOUT_SUBMIT' AND user_id = $srId AND business_date = DATE '$date'").mapTo(String::class.java).findOne().orElse(null)
    }

    private val evaluator by lazy { RiskSignalEvaluator(fresh.db, DbServerConfig(fresh.db, RegistryDefaults(), clock)) }

    @Test
    fun aRealCheckOutOutranksAPhoneCloseInEitherOrderAndTwoOfAKindAreStillRefused() = testApplication {
        application { aronApi(wiring) }
        val t = client.token("sr1001", phone)
        // The phone closed the day first (a reinstall or a shared phone); the rep's own check-out arrives later: stored as the day's check-out, never attendance_duplicate.
        val d1 = "2026-12-30"
        val phoneClose = withMembers(attendance("check_out", d1), "phone", "2027-01-02T02:00:00Z", noFix = true)
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(phoneClose)))
        val rep = withMembers(attendance("check_out", d1), "rep")
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(rep)))
        assertEquals(listOf("phone", "rep"), rows(d1, "check_out"), "both stored; every reader looks at the rep row only")
        // Two of a kind are still refused: a second rep check-out, a second phone close, a second check-in.
        assertEquals(listOf("rejected:attendance_duplicate"), client.send(t, phone, listOf(withMembers(attendance("check_out", d1), "rep"))))
        assertEquals(listOf("rejected:attendance_duplicate"), client.send(t, phone, listOf(withMembers(attendance("check_out", d1), "phone", "2027-01-02T03:00:00Z", noFix = true))))
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(attendance("check_in", d1))))
        assertEquals(listOf("rejected:attendance_duplicate"), client.send(t, phone, listOf(attendance("check_in", d1))))
        // The other order: the rep's check-out first, a phone close for the same day later (nothing to close really): stored, and it changes nothing.
        val d2 = "2026-12-31"
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(withMembers(attendance("check_out", d2), "rep"))))
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(withMembers(attendance("check_out", d2), "phone", "2027-01-02T02:00:00Z", noFix = true))))
        assertEquals(listOf("phone", "rep"), rows(d2, "check_out"))
    }

    @Test
    fun closedByRepOnACheckInIsAcceptedAndPhoneOnACheckInIsRefused() = testApplication {
        application { aronApi(wiring) }
        val t = client.token("sr1001", phone)
        val d = "2027-01-01"
        assertEquals(listOf("rejected:schema_invalid"), client.send(t, phone, listOf(withMembers(attendance("check_in", d), "phone"))))
        val ok = withMembers(attendance("check_in", d), "rep")
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(ok)))
        assertEquals(listOf("rep"), rows(d, "check_in"))
    }

    @Test
    fun aPhoneCloseWithAUsableFixIsStoredButItsFixIsNeverAGeoFix() = testApplication {
        application { aronApi(wiring) }
        val t = client.token("sr1001", phone)
        val d = "2026-12-29"
        val withFix = withMembers(attendance("check_out", d), "phone", "2027-01-02T02:00:00Z") // lat 23.80, lng 90.36, status ok: a usable fix
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(withFix)))
        assertEquals(listOf("phone"), rows(d, "check_out"))
        assertEquals(0, count("SELECT count(*) FROM app.geo_fix WHERE source_client_uuid = CAST('${cu(withFix)}' AS uuid)"), "no geo_fix, so no newest location and no geo risk rule")
        // Control: a rep check-out's fix is a geo_fix.
        val d2 = "2026-12-28"
        val rep = withMembers(attendance("check_out", d2), "rep")
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(rep)))
        assertEquals(1, count("SELECT count(*) FROM app.geo_fix WHERE source_client_uuid = CAST('${cu(rep)}' AS uuid)"))
    }

    /** One chain through the real ingest and the worker rules: check-out, then the day_submit, then withdrawal; and the mirror, a capture after the check-out. */
    @Test
    fun checkOutThenDaySubmitThenTheWorkerRuleThroughTheRealIngest() = testApplication {
        application { aronApi(wiring) }
        val t = client.token("sr1001", phone)
        val d = day // 2027-01-03
        seedCapture(d, "memo")
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(attendance("check_out", d))))
        assertTrue(signal(d)!!.startsWith("open ") && signal(d)!!.contains("\"day_submit_found\": false"), signal(d))
        val submit = daySubmit(d)
        assertTrue(client.send(t, phone, listOf(submit)).single().startsWith("accepted"))
        assertTrue(signal(d)!!.startsWith("open "), "the API role never touches the signal")
        assertEquals(1, evaluator.withdrawCheckoutWithoutSubmit(today))
        val s = signal(d)!!
        assertTrue(s.startsWith("dismissed ") && s.contains(cu(submit)) && s.contains("\"withdrawn_by\": \"day_submit\"") && s.contains("\"day_submit_found\": false"), s)
        assertEquals(0, evaluator.withdrawCheckoutWithoutSubmit(today))

        // The mirror: a rep check-out on a day with no captures raises nothing at the ingest; a capture registered later makes the worker raise it.
        val d2 = "2027-01-02"
        assertEquals(listOf("accepted:-"), client.send(t, phone, listOf(attendance("check_out", d2))))
        assertEquals(null, signal(d2))
        seedCapture(d2)
        assertEquals(1, evaluator.raiseCheckoutWithoutSubmit(today))
        assertTrue(signal(d2)!!.startsWith("open ") && signal(d2)!!.contains("\"raised_by\": \"worker\""), signal(d2))
        assertTrue(client.send(t, phone, listOf(daySubmit(d2))).single().startsWith("accepted"))
        assertEquals(1, evaluator.withdrawCheckoutWithoutSubmit(today))
        assertTrue(signal(d2)!!.startsWith("dismissed "))
    }

    @Test
    fun theWorkerRulesCaptureTypesAreTheIngestDayLockTypes() {
        assertEquals(IngestService.DAY_LOCKED_TYPES, ROUTE_CAPTURE_TYPES)
    }
}

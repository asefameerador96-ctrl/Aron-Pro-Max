package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.sync.SalesSubmitDigestService
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D-NIGHT-DAYCLOSE-01 server half (contract 1.9 queue row 4, shapes in docs/requests/contract-v1.9-shapes-backend-core.md):
 * the Sales Submit digest counts one login's rows per device and for the login, and lists the rows the server did not
 * store with their code, finality and memo number, for this login only. Built behind the current DTOs: the service is
 * called directly until the 1.9 batch puts it on GET /v1/sync/totals.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SalesSubmitDigestTest : NightSyncHarness() {
    private val phoneA get() = seededPhone
    private val phoneB = "00000000-0000-4000-8000-0000000000b2"

    private fun deviceId(uuid: String): Long = count("SELECT id FROM app.device WHERE device_uuid = '$uuid'")

    @Test
    @Order(1)
    fun theDigestSplitsThisPhoneFromTheLoginAndListsWhatTheServerDidNotStore() = testApplication {
        application { aronApi(wiring) }
        val digest = SalesSubmitDigestService(fresh.db)
        val date = LocalDate.parse(day)
        val a = client.token("sr1001", phoneA)
        // Phone A: one stored sale, one memo under another day's number (memo_no_invalid, refused for good with its lines).
        assertEquals(List(5) { "accepted:-" }, client.send(a, phoneA, sale()))
        val wrongNo = "sr1001-270104-${memoSeq.incrementAndGet()}"
        val refused = sale(memoNo = wrongNo)
        val refusedAcks = client.send(a, phoneA, refused)
        assertTrue(refusedAcks[1].startsWith("rejected:memo_no_invalid"), refusedAcks.toString())
        // A pack line whose pack_factor is not the SKU's pack size: parked (a re-send after a bundle refresh can store it).
        val parked = sale(packFactor = 7)
        val parkedAcks = client.send(a, phoneA, parked)
        assertTrue(parkedAcks.any { it.startsWith("rejected:qty_invalid") }, parkedAcks.toString())

        // Phone B (an earlier install, another device of the same login): one more stored sale.
        addPhone(phoneB, 1)
        val b = client.token("sr1001", phoneB)
        assertEquals(List(5) { "accepted:-" }, client.send(b, phoneB, sale()))

        val devA = deviceId(phoneA)
        val d = digest.digest(srId, devA, phoneA, date)
        assertEquals(day, d.business_date); assertEquals(phoneA, d.device_uuid)
        val memo = d.by_type.getValue("memo")
        assertEquals(2, memo.login.acknowledged, "both phones' stored memos")
        assertEquals(3, memo.device!!.received, "phone A sent three memos")
        assertEquals(1, memo.device!!.acknowledged, "phone A's one stored memo")
        val lines = d.by_type.getValue("memo_line")
        assertEquals(4, lines.login.acknowledged); assertEquals(6, lines.device!!.received); assertEquals(2, lines.device!!.acknowledged)
        // The login count equals the Server column (ServerTotals.by_type.accepted).
        val totals = json(client.get("/v1/sync/totals?business_date=$day") { bearerAuth(a); header("X-Device-Id", phoneA); header("X-App-Version", "1.0.9+9") }.bodyAsText())["totals"]!!.jsonObject["by_type"]!!.jsonObject
        assertEquals(totals.keys, d.by_type.keys)
        d.by_type.forEach { (t, c) -> assertEquals(totals.getValue(t).jsonObject["accepted"]!!.jsonPrimitive.int, c.login.acknowledged, t) }

        val byUuid = d.rejected.associateBy { it.client_uuid }
        val rm = byUuid.getValue(cu(refused[1]))
        assertEquals("memo", rm.type); assertEquals("rejected", rm.status); assertEquals("memo_no_invalid", rm.code)
        assertTrue(rm.final); assertTrue(rm.from_this_device); assertEquals(wrongNo, rm.memo_no); assertEquals(outletId, rm.outlet_id)
        // Its line carries the refused memo's number (from the memo's refused copy).
        val rl = byUuid.getValue(cu(refused[2]))
        assertEquals("memo_line", rl.type); assertEquals(wrongNo, rl.memo_no); assertTrue(rl.final)
        val pm = byUuid.getValue(cu(parked[1]))
        assertEquals("parked", pm.status); assertEquals(false, pm.final, "a parked row is not final: it blocks Sales Submit until re-synced")
        // Past its parked_until (cfg.sync.parked_ttl_days) the row is final: it never blocks the day for good.
        val later = SalesSubmitDigestService(fresh.db, com.aktcl.aron.backend.platform.AronClock { clock.now().plus(java.time.Duration.ofDays(60)) })
        assertEquals(true, later.digest(srId, devA, phoneA, date).rejected.single { it.client_uuid == cu(parked[1]) }.final)
        // A device id and uuid that do not belong together (or to this user): no device half.
        assertNull(digest.digest(srId, devA, "00000000-0000-4000-8000-0000000000ff", date).device_uuid)
        assertTrue(d.rejected.none { it.client_uuid in sale().map(::cu) })
        assertTrue(d.rejected.all { it.business_date == day })
        assertEquals(false, d.rejected_truncated)
        // Newest first.
        val seen = d.rejected.map { it.last_received_at }
        assertEquals(seen.sortedDescending(), seen)

        // Phone B's view: the login's rejected rows are listed, but none is from this device; its own counts are its own.
        val dB = digest.digest(srId, deviceId(phoneB), phoneB, date)
        assertEquals(1, dB.by_type.getValue("memo").device!!.received)
        assertEquals(2, dB.by_type.getValue("memo").login.acknowledged)
        assertEquals(d.rejected.map { it.client_uuid }.toSet(), dB.rejected.map { it.client_uuid }.toSet())
        assertTrue(dB.rejected.none { it.from_this_device })

        // A caller without a phone (web): no device half.
        val web = digest.digest(srId, null, null, date)
        assertNull(web.device_uuid)
        assertTrue(web.by_type.values.all { it.device == null })
        assertTrue(web.rejected.none { it.from_this_device })

        // Another user and another date see none of it.
        val other = count("SELECT id FROM app.app_user WHERE username <> 'sr1001' ORDER BY id LIMIT 1")
        val o = digest.digest(other, devA, phoneA, date)
        assertTrue(o.by_type.isEmpty() && o.rejected.isEmpty())
        val earlier = digest.digest(srId, devA, phoneA, date.minusDays(1))
        assertTrue(earlier.by_type.isEmpty() && earlier.rejected.isEmpty())
    }

    @Test
    @Order(2)
    fun aRowStoredOnALaterResendLeavesTheRejectedList() = testApplication {
        application { aronApi(wiring) }
        val digest = SalesSubmitDigestService(fresh.db)
        val a = client.token("sr1001", phoneA)
        // A line whose memo has not arrived yet is parked (parent_missing); the memo then arrives and the resend stores it.
        val family = sale()
        val firstLine = family[2]
        val acks = client.send(a, phoneA, listOf(firstLine))
        assertTrue(acks.single().startsWith("rejected:") || acks.single().startsWith("quarantined:"), acks.toString())
        assertTrue(digest.digest(srId, deviceId(phoneA), phoneA, LocalDate.parse(day)).rejected.any { it.client_uuid == cu(firstLine) })
        assertTrue(client.send(a, phoneA, family).all { it.startsWith("accepted") || it.startsWith("duplicate") })
        val after = digest.digest(srId, deviceId(phoneA), phoneA, LocalDate.parse(day))
        assertTrue(after.rejected.none { it.client_uuid == cu(firstLine) }, "stored since: no longer listed")
    }
}

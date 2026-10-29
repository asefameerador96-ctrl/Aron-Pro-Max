package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.app.TsoWorld.Companion.ack
import com.aktcl.aron.backend.app.TsoWorld.Companion.code
import com.aktcl.aron.backend.app.TsoWorld.Companion.json
import com.aktcl.aron.backend.app.TsoWorld.Companion.status
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * tso-manual B3, D-TSO-MAN-33 (LV-16, 17, 19, 22; s8 row 21 "LeaveHandlerTest"): a leave application is judged by the
 * same rules on both paths, the `leave_application` sync record (LeaveApplicationHandler) and `POST /v1/leave`, through
 * the production wiring on the seed (tso1001 of Dhaka North with a TSO phone, sr1001, dmo1001). Today is 2027-01-03.
 * 1. A valid application is stored on both paths; the last day is from + days - 1 in calendar days (a Friday counts);
 *    the window bounds (31 days back, 365 ahead) are inside.
 * 2. The same refusals on both paths, each with the ack code the phone turns into "Not accepted: <reason>": an unknown
 *    leave type (400 unknown_code / rejected schema_invalid, final), a first day outside the window (400 out_of_window /
 *    quarantined business_date_out_of_window), an applicant who is not a TSO (403 / quarantined scope_out_of_reach).
 * 3. Overlap is allowed by default (cfg.leave.block_overlap false); with it on, 409 overlap / quarantined
 *    content_duplicate; a replay is answered before any rule on both paths.
 * 4. A decision writes the applicant's inbox row `leave_decided` and the `leave.decided` event in its transaction and
 *    pushes `notification` to the TSO's phone after the commit, once; the list names the applicant and territory.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LeaveHandlerTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var tso: TsoPhone
    private lateinit var sr: TsoPhone
    private val today = LocalDate.parse("2027-01-03")

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot)
        tso = world.phone("tso1001", "tso")
        sr = world.phone("sr1001", "sr")
    }

    @AfterAll
    fun tearDown() = world.close()

    private fun uuid() = UUID.randomUUID().toString()
    private fun payload(from: LocalDate, days: Int, type: String = "casual", reason: String = "Family event") =
        """{"leave_type_code":"$type","from_date":"$from","days":$days,"reason":"$reason"}"""
    private fun post(id: String, from: LocalDate, days: Int, type: String = "casual") =
        """{"leave_uuid":"$id","leave_type_code":"$type","from_date":"$from","days":$days,"reason":"Family event"}"""

    private suspend fun io.ktor.client.HttpClient.viaSync(p: TsoPhone, from: LocalDate, days: Int, type: String = "casual", cu: String = uuid()): JsonObject =
        with(world) { ack(sync(p, listOf(record("leave_application", payload(from, days, type), cu = cu))), cu) }

    private suspend fun io.ktor.client.HttpClient.viaPost(p: TsoPhone, from: LocalDate, days: Int, type: String = "casual", id: String = uuid()): HttpResponse =
        with(world) { postAs(p, "/v1/leave", post(id, from, days, type)) }

    private suspend fun HttpResponse.reason(): String? = json(bodyAsText())["errors"]?.jsonArray?.firstOrNull()?.jsonObject?.get("code")?.jsonPrimitive?.content

    private fun stored(cu: String) = world.one("SELECT count(*) FROM app.leave_application WHERE client_uuid = '$cu'")

    @Test
    @Order(1)
    fun bothPathsStoreAValidApplicationAndCountCalendarDays() = world.withApi {
        // 2027-01-07 is a Thursday: two days end on Friday 01-08 (Fridays and holidays count, D-TSO-MAN-33).
        val cu = uuid()
        assertEquals("accepted", viaSync(tso, LocalDate.parse("2027-01-07"), 2, cu = cu).status())
        assertEquals("2027-01-08", world.scalar("SELECT to_date::text FROM app.leave_application WHERE client_uuid = '$cu'"))
        val r = viaPost(tso, LocalDate.parse("2027-01-14"), 3)
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        assertEquals("2027-01-16", json(r.bodyAsText())["to_date"]!!.jsonPrimitive.content)
        // The window bounds are inside on both paths.
        assertEquals("accepted", viaSync(tso, today.minusDays(31), 1).status(), "31 days back")
        assertEquals(HttpStatusCode.Created, viaPost(tso, today.plusDays(365), 1).status, "365 days ahead")
        // A resend of the stored record is a duplicate; a POST replay answers the stored row.
        assertEquals("duplicate", viaSync(tso, LocalDate.parse("2027-01-07"), 2, cu = cu).status())
        val id = uuid()
        val first = viaPost(tso, LocalDate.parse("2027-02-01"), 1, id = id)
        val again = viaPost(tso, LocalDate.parse("2027-02-01"), 1, id = id)
        assertEquals(HttpStatusCode.Created, first.status); assertEquals(HttpStatusCode.OK, again.status); assertEquals(first.bodyAsText(), again.bodyAsText())
    }

    @Test
    @Order(2)
    fun bothPathsRefuseForTheSameReasons() = world.withApi {
        data class Case(val name: String, val from: LocalDate, val type: String, val reason: String, val ack: String, val code: String)
        val cases = listOf(
            Case("unknown leave type", today.plusDays(3), "vacation", "unknown_code", "rejected", "schema_invalid"),
            Case("32 days back", today.minusDays(32), "casual", "out_of_window", "quarantined", "business_date_out_of_window"),
            Case("366 days ahead", today.plusDays(366), "casual", "out_of_window", "quarantined", "business_date_out_of_window"),
        )
        for (c in cases) {
            val cu = uuid()
            val a = viaSync(tso, c.from, 1, c.type, cu)
            assertEquals(c.ack to c.code, a.status() to a.code(), "${c.name}: sync ack $a")
            if (c.ack == "rejected") assertEquals("false", a["retryable"]!!.jsonPrimitive.content, "${c.name}: final")
            assertEquals("sync.outcome.${c.code}", a["message_key"]!!.jsonPrimitive.content, "${c.name}: the reason the phone shows")
            assertEquals(0L, stored(cu), "${c.name}: nothing stored from sync")
            val id = uuid()
            val r = viaPost(tso, c.from, 1, c.type, id)
            assertEquals(HttpStatusCode.BadRequest, r.status, "${c.name}: ${r.bodyAsText()}")
            assertEquals(c.reason, r.reason(), c.name)
            assertEquals(0L, stored(id), "${c.name}: nothing stored from the POST")
        }
        // Not a TSO: the SR's phone and the SR's POST.
        val cu = uuid()
        val a = viaSync(sr, today.plusDays(2), 1, cu = cu)
        assertEquals("quarantined" to "scope_out_of_reach", a.status() to a.code())
        assertEquals(0L, stored(cu))
        assertEquals(HttpStatusCode.Forbidden, viaPost(sr, today.plusDays(2), 1).status)
    }

    @Test
    @Order(3)
    fun overlapIsAllowedByDefaultAndRefusedOnBothPathsWhenBlocked() = world.withApi {
        val from = LocalDate.parse("2027-03-01")
        assertEquals("accepted", viaSync(tso, from, 3).status())
        assertEquals(HttpStatusCode.Created, viaPost(tso, from.plusDays(1), 2).status, "cfg.leave.block_overlap is false by default")
        // A record the phone will resend byte for byte (a resend is the same envelope, not a new capture).
        val kept = world.record("leave_application", payload(LocalDate.parse("2027-04-01"), 1))
        val keptUuid = kept["client_uuid"]!!.jsonPrimitive.content
        assertEquals("accepted", with(world) { ack(sync(tso, listOf(kept)), keptUuid) }.status())
        world.cfg("cfg.leave.block_overlap", "true")
        world.forgetTokens()
        val cu = uuid()
        val a = viaSync(tso, from.plusDays(2), 1, cu = cu)
        assertEquals("quarantined" to "content_duplicate", a.status() to a.code(), "an overlap held for review: $a")
        val r = viaPost(tso, from.plusDays(2), 1)
        assertEquals(HttpStatusCode.Conflict, r.status, r.bodyAsText()); assertEquals("overlap", r.reason())
        // A replay is answered before the rules: the stored application stays a duplicate.
        assertEquals("duplicate", with(world) { ack(sync(tso, listOf(kept)), keptUuid) }.status())
        assertEquals(HttpStatusCode.Created, viaPost(tso, LocalDate.parse("2027-05-01"), 1).status, "no overlap, no refusal")
        world.cfg("cfg.leave.block_overlap", "false")
        world.forgetTokens()
    }

    @Test
    @Order(4)
    fun aDecisionWritesTheInboxRowAndTheEventAndPushesTheTsoOnce() = world.withApi {
        with(world) {
            val fcm = registerPush(tso)
            val cu = uuid()
            assertEquals("accepted", viaSync(tso, LocalDate.parse("2027-06-10"), 2, cu = cu).status())
            pushes.clear()
            val path = "/v1/leave/$cu/decision"
            val d1 = postWeb("dmo1001", path, """{"decision":"approve","note":"Enjoy"}""")
            assertEquals(HttpStatusCode.OK, d1.status, d1.bodyAsText())
            val dj = json(d1.bodyAsText())
            assertEquals("approved", dj["status"]!!.jsonPrimitive.content)
            assertEquals("tso1001", dj["applicant_username"]!!.jsonPrimitive.content)
            assertEquals("Test TSO (Dhaka North)", dj["applicant_name"]!!.jsonPrimitive.content)
            assertEquals("Dhaka North", dj["territory_name"]!!.jsonPrimitive.content)
            val n = rows("SELECT kind, ref_type, ref_id, dedupe_key, title_en, title_bn, body_en, body_bn FROM app.user_notification WHERE user_id = ${tso.userId}").single()
            assertEquals(listOf("leave_decided", "leave_application", cu, "leave_decided:$cu", "Leave approved"), listOf(n["kind"], n["ref_type"], n["ref_id"], n["dedupe_key"], n["title_en"]))
            assertEquals("Your leave of 2 days (2027-06-10 to 2027-06-11) was approved.", n["body_en"])
            assertTrue((n["body_bn"] as String).contains("২০২৭-০৬-১০") && (n["title_bn"] as String).isNotBlank(), n.toString())
            val ev = json(scalar("SELECT payload::text FROM app.domain_event WHERE event_type = 'leave.decided' AND aggregate_id = '$cu'")!!)
            assertEquals(cu, ev["leave_uuid"]!!.jsonPrimitive.content); assertEquals("approved", ev["status"]!!.jsonPrimitive.content)
            assertEquals(tso.userId.toString(), ev["user_id"]!!.jsonPrimitive.content)
            assertEquals(one("SELECT id FROM app.app_user WHERE username = 'dmo1001'").toString(), ev["decided_by"]!!.jsonPrimitive.content)
            assertEquals(listOf("2027-06-10", "2027-06-11", "2", "casual"), listOf("from_date", "to_date", "days", "leave_type_code").map { ev[it]!!.jsonPrimitive.content })
            fun mine() = pushes.filter { it.first == fcm && it.second["reason"] == "notification" }
            val until = System.nanoTime() + 30_000_000_000L
            while (mine().isEmpty() && System.nanoTime() < until) Thread.sleep(20)
            assertEquals(1, mine().size, "one push after the commit: $pushes")
            assertEquals(mapOf("kind" to "sync_nudge", "reason" to "notification"), mine().single().second, "no business data in the push")
            // A replay of the decision changes nothing: no second row, event or push.
            assertEquals(HttpStatusCode.OK, postWeb("dmo1001", path, """{"decision":"approve"}""").status)
            Thread.sleep(300)
            assertEquals(1L, one("SELECT count(*) FROM app.user_notification WHERE user_id = ${tso.userId}"))
            assertEquals(1L, one("SELECT count(*) FROM app.domain_event WHERE event_type = 'leave.decided' AND aggregate_id = '$cu'"))
            assertEquals(1, mine().size)
            // A rejection reads "not approved".
            val other = uuid()
            assertEquals(HttpStatusCode.Created, viaPost(tso, LocalDate.parse("2027-07-01"), 1, id = other).status)
            assertEquals(HttpStatusCode.OK, postWeb("dmo1001", "/v1/leave/$other/decision", """{"decision":"reject"}""").status)
            assertEquals("Leave not approved", scalar("SELECT title_en FROM app.user_notification WHERE ref_id = '$other'"))
            assertEquals("Your leave of 1 day (2027-07-01) was not approved.", scalar("SELECT body_en FROM app.user_notification WHERE ref_id = '$other'"))
            // The deciding page's list names the applicant (LV-19).
            val list = json(getWeb("dmo1001", "/v1/leave?status=approved").bodyAsText())["items"]!!.jsonArray.map { it.jsonObject }
            val row = list.single { it["leave_uuid"]!!.jsonPrimitive.content == cu }
            assertEquals("tso1001", row["applicant_username"]!!.jsonPrimitive.content); assertEquals("Dhaka North", row["territory_name"]!!.jsonPrimitive.content)
        }
    }
}

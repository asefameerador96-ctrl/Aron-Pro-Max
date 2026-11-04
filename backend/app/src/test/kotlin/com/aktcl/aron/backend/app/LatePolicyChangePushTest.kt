package com.aktcl.aron.backend.app

import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * tso-manual X4a fix round 4 (checker medium; owner decision 2026-11-04, D-TSO-MAN-HO-02): nothing may tell a rep that sales
 * are closed while the server would accept the sale. An admin set quarantine, the TSO closed the zone-day and the SR and AMO
 * phones locked; when the admin returns the policy to accept_and_flag the server accepts and flags at once, so the phones
 * must hear of it at once too, not at their next config delta. Through the production wiring (ConfigService over HTTP, its
 * post-commit listener, LatePolicyPush, backend:notify to registered phones):
 * - a committed change of `cfg.day.late_sync_after_final_policy` to accept_and_flag sends the SR and AMO phones of the closed
 *   zone-day one data-only `config_pull` (CONFIG pull only: the phone pulls the policy after a jittered `pull_after_s` and withdraws its "Day closed"
 *   notice when the policy no longer locks), and a change back to quarantine sends the same one `config_pull` (D-X4a-28 case c, ask 11;
 *   D-TSO-MAN-62: ONE mechanism, reason config_pull);
 * - nothing is sent while the C3 change waits for its approver (only a committed version counts);
 * - a phone of a zone whose day is not closed gets nothing (no national fan-out), and a change of another key sends nothing.
 *
 * World: the mirrored division LPC (zone Z-LPC, `lpcsr1001` on its daily route, `lpcamo1001` whose scope is the zone,
 * `lpctso1001`, a second SUPERADMIN `lpcsuperadmin1001`), and the seed's `sr1001` in another zone. The clock is 10:00 Dhaka on
 * 2027-01-03 (outside the C3 freeze windows); the zone-day closed is today's.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LatePolicyChangePushTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private var zone = 0L

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot, mirrors = listOf("LPC"), start = Instant.parse("2027-01-03T04:00:00Z"))
        zone = world.one("SELECT id FROM app.zone WHERE code = 'Z-LPC'")
    }

    @AfterAll
    fun tearDown() = world.close()

    private val key = "cfg.day.late_sync_after_final_policy"

    /** A C3 change of the policy requested by `superadmin1001` (pending until approved); returns its id. */
    private suspend fun HttpClient.request(json: String): Long = with(world) {
        now.updateAndGet { it.plusSeconds(60) } // each version starts after the last one
        val r = postWeb("superadmin1001", "/v1/admin/config/changes", """{"reason":"late policy for the pilot zones","changes":[{"key":"$key","scope_type":"global","scope_id":0,"value":$json}]}""")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("pending_approval", body["status"]!!.jsonPrimitive.content, "the late policy is class C3")
        body["change_id"]!!.jsonPrimitive.long
    }

    private suspend fun HttpClient.approve(id: Long) = with(world) {
        val r = postWeb("lpcsuperadmin1001", "/v1/admin/config/changes/$id/decision", """{"decision":"approve"}""")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("applied", Json.parseToJsonElement(r.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun aCommittedPolicyChangeNudgesTheFieldPhonesOfTheClosedZoneDaysOnly() = world.withApi {
        with(world) {
            val sr = registerPush(phone("lpcsr1001", "sr"))
            val amo = registerPush(phone("lpcamo1001", "amo"))
            val elsewhere = registerPush(phone("sr1001", "sr")) // another zone, whose day is not closed
            val tokens = listOf(sr, amo, elsewhere)
            fun sent(): Map<String, List<String>> = tokens.associateWith { t -> pushes.filter { it.first == t }.map { it.second["kind"] + ":" + it.second["reason"] } }
            fun waitFor(expected: Map<String, List<String>>) {
                val until = System.nanoTime() + 30_000_000_000L
                while (sent() != expected && System.nanoTime() < until) Thread.sleep(20)
                Thread.sleep(300) // nothing more follows
            }
            val none = tokens.associateWith { emptyList<String>() }

            // An admin's quarantine, set before the close; the TSO closes today's zone-day; its push locks the zone's phones.
            cfg(key, "\"quarantine\"")
            pushes.clear()
            val close = postWeb("lpctso1001", "/v1/day/final-submit", """{"client_uuid":"${UUID.randomUUID()}","zone_id":$zone,"business_date":"${today()}"}""")
            assertEquals(HttpStatusCode.OK, close.status, close.bodyAsText())
            val lock = mapOf(sr to listOf("sync_nudge:day_final_submitted"), amo to listOf("sync_nudge:day_final_submitted"), elsewhere to emptyList())
            waitFor(lock)
            assertEquals(lock, sent(), "the close under quarantine")

            // 1. Back to accept_and_flag: nothing while the change waits for its approver; the commit unlocks the closed zone's phones.
            pushes.clear()
            val back = request("\"accept_and_flag\"")
            Thread.sleep(500)
            assertEquals(none, sent(), "a pending change sends nothing")
            approve(back)
            val relaxed = mapOf(sr to listOf("config_pull:late_sync_policy_relaxed"), amo to listOf("config_pull:late_sync_policy_relaxed"), elsewhere to emptyList())
            val pull = mapOf(sr to listOf("config_pull:late_sync_policy_changed"), amo to listOf("config_pull:late_sync_policy_changed"), elsewhere to emptyList())
            waitFor(relaxed)
            assertEquals(relaxed, sent(), "the policy back to accept_and_flag after a locked close: ONE config_pull per closed-zone phone, reason relaxed (the phone withdraws its notice)")
            assertEquals(listOf(setOf("kind", "reason", "notification_id", "pull_after_s", "urgent")), pushes.map { it.second.keys }.distinct(), "data only (CONFIG pull, no business data): ${pushes.map { it.second }}")
            assertTrue(pushes.all { it.second["pull_after_s"]!!.toInt() in 0..20 && it.second["urgent"] == "true" }, "the jitter is within the default window of 20 s: ${pushes.map { it.second }}")

            // 2. Quarantine again, after the close (D-X4a-28 case c): the same ONE config_pull; the phone pulls the policy and locks.
            pushes.clear()
            approve(request("\"quarantine\""))
            waitFor(pull)
            assertEquals(pull, sent(), "quarantine set after the close")

            // 2b. The window is config (cfg.ops.config_pull_spread_s, read at send time): 0 -> every phone pulls at once.
            cfg("cfg.ops.config_pull_spread_s", "0")
            pushes.clear()
            approve(request("\"accept_and_flag\""))
            waitFor(relaxed)
            assertEquals(relaxed, sent())
            assertTrue(pushes.all { it.second["pull_after_s"] == "0" }, "a window of 0 s: ${pushes.map { it.second }}")
            cfg("cfg.ops.config_pull_spread_s", "20")

            // 3. Another key, committed at once (C1): nothing.
            pushes.clear()
            now.updateAndGet { it.plusSeconds(60) }
            val other = postWeb("admin1001", "/v1/admin/config/changes", """{"reason":"one feedback image for the pilot","changes":[{"key":"cfg.feedback.max_images","scope_type":"global","scope_id":0,"value":0}]}""")
            assertEquals(HttpStatusCode.Created, other.status, other.bodyAsText())
            assertEquals("applied", Json.parseToJsonElement(other.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
            Thread.sleep(500)
            assertEquals(none, sent(), "a change of another key")
        }
    }
}

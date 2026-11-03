package com.aktcl.aron.backend.app

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property test of the ticket sync path (tso-manual B1; CLAUDE.md "the sync engine has property/fuzz tests for duplicate
 * and out-of-order uploads"). Per schedule a TSO phone holds a few tickets with comments and close reviews; random
 * batches resend any subset in any order (events before their ticket, resends of acknowledged rows) while the support
 * desk replies (and replays its reply) and resolves some tickets at random points; then the phone drains its outbox and
 * resends everything once more. Every schedule must hold:
 * - no double: one ticket row, one state row, one `created` event and one `support_ticket.created` per ticket; the
 *   ticket number never changes after its first store; every device event uuid is stored at most once, and the device's
 *   accepted set equals the server's rows (device count == server count);
 * - stable answers: once accepted a record is a duplicate forever; a final refusal is the same refusal on every resend;
 *   nothing stays parked after the drain;
 * - the rules, whatever the order: no comment stored after the ticket's resolution, a close review only after it, at
 *   most one, and the final status follows (closed exactly when a close review was stored; else resolved when the desk
 *   resolved; else in_progress after a desk reply; else new);
 * - the outbox events: one `support_ticket.commented` per stored comment (device and desk), one status change per move.
 * Size and seed: ARON_TICKET_FUZZ_RUNS (default 12) and ARON_TICKET_FUZZ_SEED (default 20261029), printed on failure.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupportTicketSyncFuzzTest {
    private lateinit var w: TicketHarness
    private val runs = System.getenv("ARON_TICKET_FUZZ_RUNS")?.toIntOrNull() ?: 12
    private val seed = System.getenv("ARON_TICKET_FUZZ_SEED")?.toLongOrNull() ?: 20261029L

    @BeforeAll
    fun setUp() { w = TicketHarness() }

    @AfterAll
    fun tearDown() = w.close()

    @Test
    fun duplicateAndOutOfOrderUploadsNeverDoubleAndConverge() = testApplication {
        application { aronApi(w.wiring) }
        with(w) {
            val c = client
            val tso = login(c, "tso1001", tsoPhone)
            val admin = login(c, "admin1001")
            for (run in 1..runs) {
                val rnd = Random(seed + run)
                val ctx = "seed=$seed run=$run"
                val tickets = List(rnd.nextInt(1, 4)) { uuid() }
                val outbox = mutableListOf<JsonObject>()
                tickets.forEach { t ->
                    outbox += ticket(t, issue = listOf("printer_connection", "sync_stuck", "others").random(rnd))
                    repeat(rnd.nextInt(0, 4)) { outbox += comment(t, "comment ${rnd.nextInt(1000)}") }
                    if (rnd.nextBoolean()) outbox += closeReview(t)
                }
                fun uuidOf(r: JsonObject) = r["client_uuid"]!!.jsonPrimitive.content
                fun typeOf(r: JsonObject) = r["type"]!!.jsonPrimitive.content
                fun ticketOf(r: JsonObject) = if (typeOf(r) == "support_ticket") uuidOf(r) else r["payload"]!!.jsonObject["ticket_uuid"]!!.jsonPrimitive.content
                fun kindOf(r: JsonObject) = if (typeOf(r) == "support_ticket") "ticket" else r["payload"]!!.jsonObject["kind"]!!.jsonPrimitive.content
                val history = HashMap<String, MutableList<String>>()
                val resolveAt = tickets.associateWith { if (rnd.nextInt(3) > 0) rnd.nextInt(0, 8) else -1 }
                val replyAt = tickets.associateWith { if (rnd.nextBoolean()) rnd.nextInt(0, 8) else -1 }
                val resolved = HashSet<String>(); val replied = HashSet<String>()
                suspend fun batch(recs: List<JsonObject>) {
                    if (recs.isEmpty()) return
                    c.send(tso, recs).forEachIndexed { i, a -> history.getOrPut(uuidOf(recs[i])) { mutableListOf() } += a }
                }
                suspend fun deskStep(step: Int) {
                    for (t in tickets) {
                        val stored = count("SELECT count(*) FROM app.support_ticket_state WHERE ticket_uuid = '$t'") == 1L
                        if (!stored) continue
                        if (replyAt.getValue(t) <= step && replyAt.getValue(t) >= 0 && t !in replied && status(t) in setOf("new", "in_progress")) {
                            val ev = uuid()
                            val r = c.reply(admin, t, "desk reply $run", ev)
                            assertEquals(HttpStatusCode.Created, r.status, "$ctx ${r.bodyAsText()}")
                            assertEquals(HttpStatusCode.OK, c.reply(admin, t, "desk reply $run", ev).status, "$ctx reply replay")
                            replied += t
                        }
                        if (resolveAt.getValue(t) in 0..step && t !in resolved && status(t) in setOf("new", "in_progress")) {
                            val r = c.desk(admin, t, "status" to "resolved", "resolution_code" to "resolved", "resolution_notes" to "fuzz $run")
                            assertEquals(HttpStatusCode.OK, r.status, "$ctx ${r.bodyAsText()}")
                            resolved += t
                        }
                    }
                }
                // Random batches: any subset, any order, resends included.
                val steps = rnd.nextInt(3, 9)
                for (step in 0 until steps) {
                    batch(outbox.shuffled(rnd).take(rnd.nextInt(1, outbox.size + 1)))
                    deskStep(step)
                }
                // Drain: tickets first, then whatever is still parked or never sent, until nothing is parked.
                batch(outbox.filter { typeOf(it) == "support_ticket" })
                repeat(3) { batch(outbox.filter { r -> history[uuidOf(r)]?.last().let { it == null || it == "rejected parent_missing" } }) }
                deskStep(steps)
                // Everything once more, in a random order: the answers are stable.
                val before = outbox.associate { uuidOf(it) to history.getValue(uuidOf(it)).last() }
                val numbers = tickets.associateWith { scalar("SELECT ticket_no FROM app.support_ticket WHERE client_uuid = '$it'") }
                batch(outbox.shuffled(rnd))
                for (r in outbox) {
                    val u = uuidOf(r); val h = history.getValue(u)
                    assertTrue(h.last() != "rejected parent_missing", "$ctx ${kindOf(r)} $u still parked: $h")
                    val acceptedAt = h.indexOfFirst { it == "accepted" }
                    if (acceptedAt >= 0) {
                        assertTrue(h.drop(acceptedAt + 1).all { it == "duplicate" }, "$ctx ${kindOf(r)} $u answered $h after its store")
                        assertTrue(h.take(acceptedAt).all { it == "rejected parent_missing" }, "$ctx ${kindOf(r)} $u answered $h before its store")
                    } else {
                        val finalAt = h.indexOfFirst { it != "rejected parent_missing" }
                        assertTrue(finalAt >= 0 && h.drop(finalAt).all { it == h[finalAt] } && h[finalAt] == "rejected ticket_state_invalid", "$ctx ${kindOf(r)} $u answered $h")
                        assertEquals(before.getValue(u), h.last())
                    }
                }
                val stored = outbox.filter { r -> history.getValue(uuidOf(r)).contains("accepted") }.map(::uuidOf).toSet()
                assertTrue(tickets.all { it in stored }, "$ctx every ticket is stored")
                // No double, numbers fixed.
                for (t in tickets) {
                    assertEquals("1|1|1|1", scalar(
                        "SELECT (SELECT count(*) FROM app.support_ticket WHERE client_uuid = '$t') || '|' || (SELECT count(*) FROM app.support_ticket_state WHERE ticket_uuid = '$t') || '|' || " +
                            "(SELECT count(*) FROM app.support_ticket_event WHERE ticket_uuid = '$t' AND kind = 'created') || '|' || " +
                            "(SELECT count(*) FROM app.domain_event WHERE event_type = 'support_ticket.created' AND aggregate_id = '$t')",
                    ), "$ctx ticket $t")
                    assertEquals(numbers.getValue(t), scalar("SELECT ticket_no FROM app.support_ticket WHERE client_uuid = '$t'"), "$ctx number of $t")
                    assertTrue(Regex("^INC[0-9]{6,}$").matches(numbers.getValue(t)!!))
                }
                val serverDevice = tickets.flatMap { t -> rows("SELECT client_uuid::text FROM app.support_ticket_event WHERE ticket_uuid = '$t' AND via = 'device' AND kind <> 'created'") }
                assertEquals(serverDevice.size, serverDevice.toSet().size, "$ctx a device event stored twice")
                assertEquals(stored - tickets.toSet(), serverDevice.toSet(), "$ctx device count == server count")
                // The rules hold in the order the server applied them.
                for (t in tickets) {
                    data class Ev(val id: Long, val kind: String, val via: String)
                    val thread = rows("SELECT id || '|' || kind || '|' || via FROM app.support_ticket_event WHERE ticket_uuid = '$t' ORDER BY id")
                        .map { it.split('|').let { (a, b, cc) -> Ev(a.toLong(), b, cc) } }
                    val resolution = thread.firstOrNull { it.kind == "resolution" }
                    val closes = thread.filter { it.kind == "close_review" }
                    val deviceComments = thread.filter { it.kind == "comment" && it.via == "device" }
                    if (resolution != null) assertTrue(deviceComments.all { it.id < resolution.id }, "$ctx a comment stored after the resolution of $t: $thread")
                    assertTrue(closes.size <= 1, "$ctx two close reviews on $t")
                    closes.singleOrNull()?.let { cl -> assertTrue(resolution != null && cl.id > resolution.id, "$ctx a close review before the resolution of $t") }
                    val want = when {
                        closes.isNotEmpty() -> "closed"
                        t in resolved -> "resolved"
                        t in replied -> "in_progress"
                        else -> "new"
                    }
                    assertEquals(want, status(t), "$ctx status of $t: $thread")
                    val comments = thread.count { it.kind == "comment" }
                    assertEquals(comments.toLong(), count("SELECT count(*) FROM app.domain_event WHERE event_type = 'support_ticket.commented' AND aggregate_id = '$t'"), "$ctx commented events of $t")
                    val moves = thread.count { it.kind == "status_change" || it.kind == "resolution" || it.kind == "close_review" }
                    assertEquals(moves.toLong(), count("SELECT count(*) FROM app.domain_event WHERE event_type = 'support_ticket.status_changed' AND aggregate_id = '$t'"), "$ctx status events of $t")
                    val version = 1 + thread.count { (it.kind == "status_change" && it.via == "web") || it.kind == "resolution" || it.kind == "close_review" }
                    assertEquals(version, version(t), "$ctx version of $t")
                }
            }
        }
    }
}

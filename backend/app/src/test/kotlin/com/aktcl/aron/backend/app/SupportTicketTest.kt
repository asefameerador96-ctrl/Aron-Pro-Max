package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.app.TicketHarness.Companion.json
import com.aktcl.aron.rules.TicketMath
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Support tickets end to end through the production wiring (tso-manual B1; build contract s5 B1 tests, D-TSO-MAN-17 to
 * 26, lead rulings R-F-03, R-F-05, R-F-14; F1 ask 10): tickets and the reporter's comments and close reviews arrive by
 * `POST /v1/sync/batch` from a TSO phone; the support desk works them over REST.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupportTicketTest {
    private lateinit var w: TicketHarness

    @BeforeAll
    fun setUp() { w = TicketHarness() }

    @AfterAll
    fun tearDown() = w.close()

    private fun run(body: suspend TicketHarness.(ApplicationTestBuilder) -> Unit) = testApplication {
        application { aronApi(w.wiring) }
        w.body(this)
    }

    private val ok = HttpStatusCode.OK
    private fun code(text: String): String = json(text)["code"]?.jsonPrimitive?.content ?: "-"
    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    // ---- ingest ----------------------------------------------------------------------------------------------------

    @Test
    fun aTicketFromThePhoneGetsItsNumberStateCreatedEventAndDomainEvent() = run { app ->
        val c = app.client
        val tso = login(c, "tso1001", tsoPhone)
        val t1 = uuid(); val t2 = uuid(); val t3 = uuid()
        assertEquals(
            listOf("accepted", "accepted", "accepted"),
            c.send(tso, listOf(ticket(t1, affected = "SR1001"), ticket(t2, issue = "others", title = "Others", source = "quick"), ticket(t3))),
        )
        val nos = listOf(t1, t2, t3).map { scalar("SELECT ticket_no FROM app.support_ticket WHERE client_uuid = '$it'")!! }
        // D-TSO-MAN-18: INC + at least 6 digits from 100001, assigned at the first insert, unique and in order.
        assertTrue(nos.all { Regex("^INC[0-9]{6,}$").matches(it) && it.removePrefix("INC").toLong() >= 100001 }, "$nos")
        assertEquals(nos.size, nos.toSet().size)
        assertEquals(nos.sortedBy { it.removePrefix("INC").toLong() }, nos)
        // The state row: requester and role from the token, priority from the issue type's attrs (p1), status new, version 1;
        // the affected user resolved ignoring case (D-TSO-MAN-24); one created event; one support_ticket.created.
        assertEquals("${ids["tso1001"]}|TSO|p1|new|1|${ids["sr1001"]}",
            scalar("SELECT requester_user_id || '|' || requester_role || '|' || priority || '|' || status || '|' || version || '|' || affected_user_id FROM app.support_ticket_state WHERE ticket_uuid = '$t1'"))
        assertNull(scalar("SELECT affected_user_id FROM app.support_ticket_state WHERE ticket_uuid = '$t2'"))
        assertEquals("created|device|new|${TicketHarness.stableV4("aron.support.ticket-created:$t1")}",
            scalar("SELECT kind || '|' || via || '|' || to_status || '|' || client_uuid FROM app.support_ticket_event WHERE ticket_uuid = '$t1'"))
        assertEquals(1L, count("SELECT count(*) FROM app.domain_event WHERE event_type = 'support_ticket.created' AND aggregate_id = '$t1'"))
        val payload = json(scalar("SELECT payload::text FROM app.domain_event WHERE event_type = 'support_ticket.created' AND aggregate_id = '$t1'")!!)
        assertEquals(setOf("ticket_uuid", "ticket_no", "issue_type_code", "priority", "requester_user_id", "requester_role", "business_date", "source", "has_affected_user"), payload.keys)
        assertEquals("true", payload.s("has_affected_user"))

        // The reporter reads it back: the contract shape, the thread with the created entry by the reporter's name.
        val d = json(c.call(tso, HttpMethod.Get, "/v1/support/tickets/$t1").bodyAsText())
        val tk = d["ticket"]!!.jsonObject
        assertEquals(nos[0], tk.s("ticket_no")); assertEquals("Printer Connection issue", tk.s("issue_type_label_en")); assertEquals("Tickets", tk.s("category_label_en"))
        assertEquals("SR1001", tk.s("affected_username")); assertEquals("p1", tk.s("priority")); assertEquals("new", tk.s("status")); assertEquals(1L, tk["version"]!!.jsonPrimitive.long)
        assertEquals("tso1001", tk["requester"]!!.jsonObject.s("username")); assertEquals("TSO", tk["requester"]!!.jsonObject.s("role"))
        assertFalse("assignee_user_id" in tk || "assignee_name" in tk, "desk-only members are absent for the reporter")
        val ev = d["events"]!!.jsonArray.single().jsonObject
        assertEquals("created", ev.s("kind")); assertEquals(false, ev["actor_is_support"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("Test TSO (Dhaka North)", ev.s("actor_display")); assertEquals("new", ev.s("to_status"))
    }

    @Test
    fun replayIsDuplicateAndAnotherPayloadUnderTheSameUuidIsQuarantined() = run { app ->
        val c = app.client
        val tso = login(c, "tso1001", tsoPhone)
        val t = uuid()
        val first = ticket(t, title = "Sync stuck since morning", issue = "sync_stuck")
        assertEquals(listOf("accepted"), c.send(tso, listOf(first)))
        val no = scalar("SELECT ticket_no FROM app.support_ticket WHERE client_uuid = '$t'")
        assertEquals(listOf("duplicate"), c.send(tso, listOf(first)), "a resend is a duplicate")
        assertEquals(listOf("duplicate"), c.send(tso, listOf(first)))
        val other = ticket(t, title = "Something else", issue = "sync_stuck")
        assertEquals(listOf("quarantined payload_conflict"), c.send(tso, listOf(other)), "the same uuid with another payload")
        // Nothing doubled, nothing changed: one ticket (same number), one state row, one created event, one domain event.
        assertEquals(no, scalar("SELECT ticket_no FROM app.support_ticket WHERE client_uuid = '$t'"))
        assertEquals("Sync stuck since morning", scalar("SELECT title FROM app.support_ticket WHERE client_uuid = '$t'"))
        assertEquals("1|1|1|1", scalar(
            "SELECT (SELECT count(*) FROM app.support_ticket WHERE client_uuid = '$t') || '|' || (SELECT count(*) FROM app.support_ticket_state WHERE ticket_uuid = '$t') || '|' || " +
                "(SELECT count(*) FROM app.support_ticket_event WHERE ticket_uuid = '$t') || '|' || (SELECT count(*) FROM app.domain_event WHERE aggregate_id = '$t')",
        ))
        // The same for an event: a resent comment is one row and one support_ticket.commented.
        val cm = comment(t, "Restarted twice")
        assertEquals(listOf("accepted"), c.send(tso, listOf(cm)))
        assertEquals(listOf("duplicate"), c.send(tso, listOf(cm)))
        assertEquals(2L, count("SELECT count(*) FROM app.support_ticket_event WHERE ticket_uuid = '$t'"))
        assertEquals(1L, count("SELECT count(*) FROM app.domain_event WHERE event_type = 'support_ticket.commented' AND aggregate_id = '$t'"))
    }

    @Test
    fun anUnknownIssueTypeIsRejectedAndTheEventsOfARefusedTicketWithIt() = run { app ->
        val c = app.client
        val tso = login(c, "tso1001", tsoPhone)
        val t = uuid()
        assertEquals(listOf("rejected issue_type_unknown"), c.send(tso, listOf(ticket(t, issue = "no_such_issue"))))
        assertEquals(0L, count("SELECT count(*) FROM app.support_ticket WHERE client_uuid = '$t'"))
        assertEquals(listOf("rejected ticket_state_invalid"), c.send(tso, listOf(comment(t))), "its events can never be stored")
        // A retired type is unknown from its valid_to on.
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.code_list_item SET valid_to = DATE '2027-01-01' WHERE list_key = 'ticket_issue_type' AND code = 'campaign'") }
        try {
            assertEquals(listOf("rejected issue_type_unknown"), c.send(tso, listOf(ticket(issue = "campaign"))))
        } finally {
            fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.code_list_item SET valid_to = NULL WHERE list_key = 'ticket_issue_type' AND code = 'campaign'") }
        }
    }

    /** R-F-05 and D-TSO-MAN-17: an SR ticket is quarantined; the accept re-checks the role and refuses while it is still not allowed. */
    @Test
    fun aRoleOutsideTheRequesterRolesIsQuarantinedAndTheAcceptReChecksTheRole() = run { app ->
        val c = app.client
        val sr = login(c, "sr1001", srPhone)
        val admin = login(c, "admin1001")
        val t = uuid(); val cm = uuid()
        val srTicket = ticket(t, issue = "user_login")
        assertEquals(listOf("quarantined scope_out_of_reach"), c.send(sr, listOf(srTicket)))
        assertEquals(listOf("quarantined scope_out_of_reach"), c.send(sr, listOf(comment(t, uuid = cm))))
        fun item(u: String) = count("SELECT id FROM app.sync_quarantine WHERE client_uuid = '$u' AND status = 'open'")
        suspend fun accept(u: String) = c.call(admin, HttpMethod.Post, "/v1/admin/quarantine/${item(u)}/resolve", """{"action":"accept","reason":"checked with the field team"}""")
        val refused = accept(t)
        assertEquals(HttpStatusCode.Conflict, refused.status, refused.bodyAsText())
        assertEquals("ERR_REQUEST_STATE", code(refused.bodyAsText()))
        assertEquals("open", scalar("SELECT status FROM app.sync_quarantine WHERE client_uuid = '$t'"), "the item stays open")
        assertEquals(0L, count("SELECT count(*) FROM app.support_ticket WHERE client_uuid = '$t'"))
        assertEquals("quarantined", scalar("SELECT status FROM app.ingest_registry WHERE client_uuid = '$t'"))
        assertEquals(listOf("quarantined scope_out_of_reach"), c.send(sr, listOf(srTicket)), "the phone's resend still gets the hold")
        setCfg("cfg.support.ticket_requester_roles", """["TSO","SR"]""")
        try {
            val accepted = accept(t)
            assertEquals(ok, accepted.status, accepted.bodyAsText())
            assertEquals("SR|new", scalar("SELECT requester_role || '|' || status FROM app.support_ticket_state WHERE ticket_uuid = '$t'"))
            assertEquals(1L, count("SELECT count(*) FROM app.domain_event WHERE event_type = 'support_ticket.created' AND aggregate_id = '$t'"))
            assertEquals(listOf("duplicate"), c.send(sr, listOf(srTicket)), "the phone's copy reconciles")
            assertEquals(ok, accept(cm).status, "the comment is accepted on its own item")
            assertEquals("comment", scalar("SELECT kind FROM app.support_ticket_event WHERE client_uuid = '$cm'"))
            // A new SR ticket is now accepted directly.
            assertEquals(listOf("accepted"), c.send(sr, listOf(ticket(issue = "login_otp"))))
        } finally {
            setCfg("cfg.support.ticket_requester_roles", """["TSO"]""")
        }
        assertEquals(listOf("quarantined scope_out_of_reach"), c.send(sr, listOf(ticket())), "the default holds again")
    }

    @Test
    fun anEventBeforeItsTicketIsParkedThenAccepted() = run { app ->
        val c = app.client
        val tso = login(c, "tso1001", tsoPhone)
        val t = uuid()
        val cm = comment(t, "Sent before the ticket")
        assertEquals(listOf("rejected parent_missing"), c.send(tso, listOf(cm)), "parked: retryable")
        assertEquals("parked", scalar("SELECT status FROM app.ingest_registry WHERE client_uuid = '${cm["client_uuid"]!!.jsonPrimitive.content}'"))
        assertEquals(listOf("accepted"), c.send(tso, listOf(ticket(t))))
        assertEquals(listOf("accepted"), c.send(tso, listOf(cm)), "accepted once its ticket is stored")
        assertEquals(listOf("created", "comment"), rows("SELECT kind FROM app.support_ticket_event WHERE ticket_uuid = '$t' ORDER BY created_at, id"))
        // In one batch the families run in the order sent: the event before its ticket is parked, after it accepted.
        val t2 = uuid(); val early = comment(t2)
        assertEquals(listOf("rejected parent_missing", "accepted", "accepted"), c.send(tso, listOf(early, ticket(t2), comment(t2))))
        assertEquals(listOf("accepted"), c.send(tso, listOf(early)))
        assertEquals(3L, count("SELECT count(*) FROM app.support_ticket_event WHERE ticket_uuid = '$t2'"))
    }

    // ---- scope -----------------------------------------------------------------------------------------------------

    @Test
    fun reporterSeesOwnTicketsOnlyAndTheDeskSeesAll() = run { app ->
        val c = app.client
        val tso = login(c, "tso1001", tsoPhone); val tsoWeb = login(c, "tso1001")
        val tso2 = login(c, "tsoq9001", tso2Phone)
        val admin = login(c, "admin1001"); val support = login(c, "support1001")
        val mine = uuid(); val theirs = uuid()
        assertEquals(listOf("accepted"), c.send(tso, listOf(ticket(mine, title = "Camera shows black", issue = "camera_black_screen"))))
        assertEquals(listOf("accepted"), c.send(tso2, listOf(ticket(theirs, title = "Battery drains", issue = "others"))))
        suspend fun list(cl: TicketHarness.Caller, q: String = ""): List<String> {
            val r = c.call(cl, HttpMethod.Get, "/v1/support/tickets$q")
            assertEquals(ok, r.status, r.bodyAsText())
            return json(r.bodyAsText())["items"]!!.jsonArray.map { it.jsonObject.s("ticket_uuid")!! }
        }
        assertTrue(mine in list(tso) && theirs !in list(tso))
        assertTrue(mine in list(tsoWeb) && theirs !in list(tsoWeb), "the TSO's web login is the same reporter")
        assertTrue(theirs in list(tso2) && mine !in list(tso2))
        assertEquals(HttpStatusCode.NotFound, c.call(tso2, HttpMethod.Get, "/v1/support/tickets/$mine").status, "another reporter's ticket does not exist for it")
        assertEquals(HttpStatusCode.NotFound, c.call(support, HttpMethod.Get, "/v1/support/tickets/$mine").status, "SUPPORT without the desk permission")
        assertEquals(emptyList(), list(support))
        // Desk-only filters are refused to a reporter; the desk sees and filters everything.
        assertEquals(HttpStatusCode.Forbidden, c.call(tso, HttpMethod.Get, "/v1/support/tickets?requester_user_id=${ids["tsoq9001"]}").status)
        assertEquals(HttpStatusCode.Forbidden, c.call(tso, HttpMethod.Get, "/v1/support/tickets?assignee_user_id=${ids["admin1001"]}").status)
        assertTrue(mine in list(admin) && theirs in list(admin))
        assertEquals(listOf(theirs), list(admin, "?requester_user_id=${ids["tsoq9001"]}").filter { it == mine || it == theirs })
        val detail = json(c.call(admin, HttpMethod.Get, "/v1/support/tickets/$mine").bodyAsText())["ticket"]!!.jsonObject
        assertTrue("assignee_user_id" in detail && "assignee_name" in detail, "the desk gets the desk-only members")
        // q: a ticket number prefix (any case), or text in the title or the issue label; other filters.
        val no = scalar("SELECT ticket_no FROM app.support_ticket WHERE client_uuid = '$mine'")!!
        assertEquals(listOf(mine), list(tso, "?q=$no"))
        assertEquals(listOf(mine), list(tso, "?q=${no.lowercase().dropLast(1)}").filter { it == mine })
        assertTrue(mine in list(tso, "?q=black") && mine in list(tso, "?q=camera%20BLACK%20screen"))
        assertTrue(mine !in list(tso, "?q=battery") && theirs in list(admin, "?q=BATTERY"))
        assertEquals(emptyList(), list(tso, "?q=%25%25"), "a % in q is a literal, not a wildcard")
        assertTrue(mine in list(tso, "?status=new&priority=p1&issue_type_code=camera_black_screen&from=2027-01-03&to=2027-01-03"))
        assertTrue(mine !in list(tso, "?status=resolved"))
        for (bad in listOf("?q=x", "?status=open", "?priority=p9", "?limit=0", "?limit=201", "?cursor=***", "?issue_type_code=Bad", "?from=2027-01-05&to=2027-01-03")) {
            assertEquals(HttpStatusCode.BadRequest, c.call(tso, HttpMethod.Get, "/v1/support/tickets$bad").status, bad)
        }
        // Paging: newest first (created_at, then id), one per page, the cursor walks the whole list once.
        val all = list(admin, "?limit=200")
        val walked = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = json(c.call(admin, HttpMethod.Get, "/v1/support/tickets?limit=1" + (cursor?.let { "&cursor=$it" } ?: "")).bodyAsText())
            walked += page["items"]!!.jsonArray.map { it.jsonObject.s("ticket_uuid")!! }
            cursor = page.s("next_cursor")
        } while (cursor != null)
        assertEquals(all, walked)
        // Writes are the desk's; a reporter or SUPPORT without the permission gets 403 before the body is read.
        assertEquals(HttpStatusCode.Forbidden, c.desk(tso, mine, "status" to "in_progress").status)
        assertEquals(HttpStatusCode.Forbidden, c.desk(support, mine, "status" to "in_progress").status)
        assertEquals(HttpStatusCode.Forbidden, c.call(tsoWeb, HttpMethod.Patch, "/v1/support/tickets/$mine", "{}").status)
        assertEquals(HttpStatusCode.Forbidden, c.reply(tso, mine, "hello").status)
        assertEquals(HttpStatusCode.NotFound, c.desk(admin, uuid(), "status" to "in_progress", expected = 1).status)
        // Me.support_desk drives the web menu.
        suspend fun desk(cl: TicketHarness.Caller) = json(c.call(cl, HttpMethod.Get, "/v1/me").bodyAsText())["support_desk"]?.jsonPrimitive?.content
        assertEquals("true", desk(admin)); assertEquals("true", desk(login(c, "superadmin1001")))
        assertEquals("false", desk(support)); assertEquals("false", desk(tsoWeb))
    }

    /** TK-14: a ticket photo is the reporter's and the desk's (D-TSO-MAN-22, 23), not a supervisor's in reach. */
    @Test
    fun ticketPhotosAreReadByTheReporterAndTheDeskOnly() = run { app ->
        val c = app.client
        val tso = login(c, "tso1001", tsoPhone)
        val t = uuid()
        assertEquals(listOf("accepted"), c.send(tso, listOf(ticket(t))))
        val photo = uuid()
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute(
                "INSERT INTO app.media (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, purpose, ref_type, ref_client_uuid, sha256, bytes, width, height, blob_path, taken_at, status, stored_at, blob_bytes, blob_sha256) " +
                    "VALUES ('$photo', '$photo', DATE '$day', ${ids["tso1001"]}, TIMESTAMPTZ '${day}T03:00:00Z', 1, 'support', 'support_ticket', '$t', decode(repeat('ab', 32), 'hex'), 1000, 640, 480, " +
                    "'photos/$day/$tsoPhone/$photo.jpg', TIMESTAMPTZ '${day}T03:00:00Z', 'stored', now(), 1000, decode(repeat('ab', 32), 'hex'))",
            )
        }
        val d = json(c.call(tso, HttpMethod.Get, "/v1/support/tickets/$t").bodyAsText())
        assertEquals(listOf(photo), d["attachments"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1L, d["ticket"]!!.jsonObject["attachment_count"]!!.jsonPrimitive.long)
        val read = "/v1/media/$photo/read-url"
        assertEquals(ok, c.call(tso, HttpMethod.Get, read).status, "the reporter")
        assertEquals(ok, c.call(login(c, "admin1001"), HttpMethod.Get, read).status, "the desk")
        assertEquals(HttpStatusCode.Forbidden, c.call(login(c, "dmo1001"), HttpMethod.Get, read).status, "a supervisor whose reach covers the TSO")
        assertEquals(HttpStatusCode.Forbidden, c.call(login(c, "tsoq9001", tso2Phone), HttpMethod.Get, read).status)
        assertEquals(HttpStatusCode.Forbidden, c.call(login(c, "support1001"), HttpMethod.Get, read).status)
    }

    // ---- desk transitions ----------------------------------------------------------------------------------------

    /** D-TSO-MAN-20 for every (from, to) pair of the desk, and the reporter's two kinds in every state. */
    @Test
    fun theFullTransitionMatrix() = run { app ->
        val c = app.client
        val tso = login(c, "tso1001", tsoPhone)
        val admin = login(c, "admin1001")
        val states = listOf("new", "in_progress", "resolved", "closed", "cancelled")
        val targets = listOf("in_progress", "resolved", "closed", "cancelled")
        val allowed = setOf("new>in_progress", "new>resolved", "in_progress>resolved", "new>cancelled", "in_progress>cancelled", "resolved>closed")
        fun change(to: String): Array<Pair<String, Any?>> = when (to) {
            "resolved" -> arrayOf("status" to to, "resolution_code" to "resolved", "resolution_notes" to "Replaced the printer cable.")
            "cancelled" -> arrayOf("status" to to, "cancel_reason" to "Raised twice by mistake")
            else -> arrayOf("status" to to)
        }
        suspend fun bring(t: String, state: String) {
            val path = when (state) { "new" -> emptyList(); "in_progress" -> listOf("in_progress"); "resolved" -> listOf("resolved"); "closed" -> listOf("resolved", "closed"); else -> listOf("cancelled") }
            for (s in path) { val r = c.desk(admin, t, *change(s)); assertEquals(ok, r.status, "$state via $s: ${r.bodyAsText()}") }
            assertEquals(state, status(t))
        }
        val pairs = states.flatMap { f -> targets.map { f to it } }
        val tickets = pairs.associateWith { uuid() }
        assertEquals(pairs.map { "accepted" }, c.send(tso, tickets.values.map { ticket(it) }))
        for ((pair, t) in tickets) {
            val (from, to) = pair
            bring(t, from)
            val v = version(t)
            val r = c.desk(admin, t, *change(to))
            if ("$from>$to" in allowed) {
                assertEquals(ok, r.status, "$from>$to ${r.bodyAsText()}")
                val body = json(r.bodyAsText())
                assertEquals(to, body.s("status")); assertEquals((v + 1).toLong(), body["version"]!!.jsonPrimitive.long)
                when (to) {
                    "in_progress" -> assertTrue(body.s("first_response_at") != null)
                    "resolved" -> { assertTrue(body.s("resolved_at") != null && body.s("first_response_at") != null); assertEquals("Resolved", body.s("resolution_label_en")) }
                    "closed" -> assertTrue(body.s("closed_at") != null && body.s("resolved_at") != null)
                    "cancelled" -> { assertTrue(body.s("cancelled_at") != null); assertEquals("Raised twice by mistake", body.s("cancel_reason")) }
                }
                val last = rows("SELECT kind || '|' || coalesce(from_status, '-') || '|' || coalesce(to_status, '-') FROM app.support_ticket_event WHERE ticket_uuid = '$t' ORDER BY created_at DESC, id DESC LIMIT 1").single()
                assertEquals("${if (to == "resolved") "resolution" else "status_change"}|$from|$to", last, "$from>$to")
            } else {
                assertEquals(HttpStatusCode.Conflict, r.status, "$from>$to must be refused: ${r.bodyAsText()}")
                assertEquals("ERR_TICKET_TRANSITION_INVALID", code(r.bodyAsText()), "$from>$to")
                assertEquals(from, status(t)); assertEquals(v, version(t), "a refused change writes nothing")
            }
        }
        // The reporter's side: a comment only while new or in_progress, a close review only while resolved.
        val kinds = states.flatMap { s -> listOf(s to "comment", s to "close_review") }.associateWith { uuid() }
        c.send(tso, kinds.values.map { ticket(it) })
        for ((pair, t) in kinds) {
            val (state, kind) = pair
            bring(t, state)
            val ack = c.send(tso, listOf(if (kind == "comment") comment(t) else closeReview(t))).single()
            val want = if ((kind == "comment" && state in setOf("new", "in_progress")) || (kind == "close_review" && state == "resolved")) "accepted" else "rejected ticket_state_invalid"
            assertEquals(want, ack, "$kind on a $state ticket")
        }
    }

    @Test
    fun aResolveWritesTheSupportMessageAndNeedsItsCodeAndNotes() = run { app ->
        val c = app.client
        val tso = login(c, "tso1001", tsoPhone)
        val admin = login(c, "admin1001")
        val t = uuid(); val long = uuid()
        c.send(tso, listOf(ticket(t), ticket(long)))
        val no = scalar("SELECT ticket_no FROM app.support_ticket WHERE client_uuid = '$t'")!!
        // 400 before anything: a resolve without a code or notes, an unknown code, a cancel without a reason, a stray member.
        for (members in listOf(
            arrayOf<Pair<String, Any?>>("status" to "resolved", "resolution_notes" to "x"),
            arrayOf("status" to "resolved", "resolution_code" to "resolved"),
            arrayOf("status" to "resolved", "resolution_code" to "resolved", "resolution_notes" to "   "),
            arrayOf("status" to "resolved", "resolution_code" to "no_such_code", "resolution_notes" to "x"),
            arrayOf("status" to "cancelled"),
            arrayOf("status" to "cancelled", "cancel_reason" to "no"),
            arrayOf("status" to "in_progress", "cancel_reason" to "Raised twice"),
            arrayOf("priority" to "p2", "resolution_notes" to "x"),
            arrayOf("status" to "open"),
            arrayOf("priority" to "p7"),
            arrayOf("assignee_user_id" to 0),
            arrayOf("assignee_user_id" to ids["tso1001"]),
            arrayOf("surprise" to 1),
        )) {
            val r = c.desk(admin, t, *members)
            assertEquals(HttpStatusCode.BadRequest, r.status, "${members.toList()}: ${r.bodyAsText()}")
        }
        assertEquals(HttpStatusCode.BadRequest, c.call(admin, HttpMethod.Patch, "/v1/support/tickets/$t", """{"op_uuid":"${uuid()}","expected_version":1}""").status, "no change named")
        assertEquals(HttpStatusCode.BadRequest, c.call(admin, HttpMethod.Patch, "/v1/support/tickets/$t", """{"op_uuid":"not-a-uuid","expected_version":1,"priority":"p2"}""").status)
        assertEquals(HttpStatusCode.BadRequest, c.call(admin, HttpMethod.Patch, "/v1/support/tickets/$t", """{"op_uuid":"${uuid()}","expected_version":"1","priority":"p2"}""").status)
        assertEquals(1, version(t), "nothing was written")
        // The D-TSO-MAN-20 message (screenshot 14), exactly.
        val notes = "Replaced the printer cable.\nPaired it again from Settings."
        val op = uuid()
        assertEquals(ok, c.desk(admin, t, "status" to "resolved", "resolution_code" to "resolved", "resolution_notes" to notes, op = op).status)
        assertEquals("[TicketID: $no] - Resolved:\nResolution Code: Resolved\nResolution Notes:\n\n$notes",
            scalar("SELECT body FROM app.support_ticket_event WHERE client_uuid = '$op'"))
        assertEquals("resolution|new|resolved|resolved|web", scalar("SELECT kind || '|' || from_status || '|' || to_status || '|' || resolution_code || '|' || via FROM app.support_ticket_event WHERE client_uuid = '$op'"))
        val ev = json(c.call(tso, HttpMethod.Get, "/v1/support/tickets/$t").bodyAsText())["events"]!!.jsonArray.last().jsonObject
        assertEquals("Support", ev.s("actor_display")); assertEquals("true", ev.s("actor_is_support")); assertEquals("resolved", ev.s("resolution_code"))
        // 2000 characters of notes still fit the event (R-F-02: the message is at most 2204 of 2400).
        val max = "n".repeat(2000)
        val r = c.desk(admin, long, "status" to "resolved", "resolution_code" to "resolved", "resolution_notes" to max)
        assertEquals(ok, r.status, r.bodyAsText())
        assertEquals(max, json(r.bodyAsText()).s("resolution_notes"))
        assertEquals(HttpStatusCode.BadRequest, c.desk(admin, long, "status" to "closed", "resolution_notes" to max + "n").status)
    }

    /** R-F-03 and F1 ask 10: the desk PATCH replay rules. */
    @Test
    fun theDeskOpReplaysByItsUuidAndFingerprint() = run { app ->
        val c = app.client
        val tso = login(c, "tso1001", tsoPhone)
        val admin = login(c, "admin1001"); val superadmin = login(c, "superadmin1001")
        val t = uuid(); val other = uuid()
        c.send(tso, listOf(ticket(t), ticket(other)))
        val adminId = ids["admin1001"]!!
        val op = uuid()
        // A three-change op: one event per change, in order; the first is op_uuid with the fingerprint, the others named.
        val body = """{"op_uuid":"$op","expected_version":1,"status":"in_progress","priority":"p2","assignee_user_id":$adminId}"""
        val first = c.call(admin, HttpMethod.Patch, "/v1/support/tickets/$t", body)
        assertEquals(ok, first.status, first.bodyAsText())
        assertEquals(2, version(t))
        val canonical = """{"assignee_user_id":$adminId,"expected_version":1,"op_uuid":"$op","priority":"p2","status":"in_progress"}"""
        assertEquals(
            listOf("$op|status_change|${TicketHarness.sha256Hex(canonical)}", "${TicketHarness.stableV4("aron.support.ticket-op:$op:priority_change")}|priority_change|-",
                "${TicketHarness.stableV4("aron.support.ticket-op:$op:assign")}|assign|-"),
            rows("SELECT client_uuid || '|' || kind || '|' || coalesce(encode(op_sha256, 'hex'), '-') FROM app.support_ticket_event WHERE ticket_uuid = '$t' AND via = 'web' ORDER BY id"),
        )
        fun writes() = scalar("SELECT (SELECT count(*) FROM app.support_ticket_event WHERE ticket_uuid = '$t') || '|' || (SELECT count(*) FROM app.audit_log WHERE entity = 'support_ticket' AND entity_id = '$t') || '|' || " +
            "(SELECT count(*) FROM app.user_notification WHERE ref_id = '$t') || '|' || (SELECT count(*) FROM app.domain_event WHERE aggregate_id = '$t') || '|' || (SELECT version FROM app.support_ticket_state WHERE ticket_uuid = '$t')")
        val after = writes()
        // A replay after a lost answer, members reordered and spaced, after the version moved on: 200, the ticket now, nothing written.
        val replay = c.call(admin, HttpMethod.Patch, "/v1/support/tickets/$t", """{ "priority" : "p2", "assignee_user_id": $adminId, "status":"in_progress","expected_version":1,"op_uuid":"$op" }""")
        assertEquals(ok, replay.status, replay.bodyAsText())
        assertEquals(2L, json(replay.bodyAsText())["version"]!!.jsonPrimitive.long)
        assertEquals(after, writes(), "a replay writes no event, audit row, inbox row or domain event")
        // The same op_uuid with another body, on another ticket, or by another desk user with another body: 409 ERR_CONFLICT.
        for ((who, tk, b) in listOf(
            Triple(admin, t, """{"op_uuid":"$op","expected_version":1,"status":"in_progress","priority":"p3","assignee_user_id":$adminId}"""),
            Triple(admin, other, body),
            Triple(superadmin, t, """{"op_uuid":"$op","expected_version":2,"priority":"p4"}"""),
        )) {
            val r = c.call(who, HttpMethod.Patch, "/v1/support/tickets/$tk", b)
            assertEquals(HttpStatusCode.Conflict, r.status, r.bodyAsText()); assertEquals("ERR_CONFLICT", code(r.bodyAsText()))
        }
        assertEquals(after, writes()); assertEquals(1, version(other))
        // An op_uuid that a reply (or any other event) already uses is 409 too.
        val replyUuid = uuid()
        assertEquals(HttpStatusCode.Created, c.reply(admin, t, "Looking into it", replyUuid).status)
        val reused = c.desk(admin, t, "priority" to "p3", op = replyUuid)
        assertEquals(HttpStatusCode.Conflict, reused.status); assertEquals("ERR_CONFLICT", code(reused.bodyAsText()))
        // An op without a change answers the ticket and records nothing, so its uuid stays free for a real change.
        val op2 = uuid()
        val same = writes()
        val noChange = c.desk(admin, t, "priority" to "p2", "assignee_user_id" to adminId, op = op2)
        assertEquals(ok, noChange.status, noChange.bodyAsText())
        assertEquals(same, writes())
        assertEquals(0L, count("SELECT count(*) FROM app.support_ticket_event WHERE client_uuid = '$op2'"))
        assertEquals(ok, c.desk(admin, t, "priority" to "p3", op = op2).status)
        assertEquals("p3", scalar("SELECT priority FROM app.support_ticket_state WHERE ticket_uuid = '$t'"))
        // A stale expected_version is 409 ERR_CONFLICT (reload and apply again); unassign with null.
        val stale = c.desk(admin, t, "priority" to "p4", expected = 1)
        assertEquals(HttpStatusCode.Conflict, stale.status); assertEquals("ERR_CONFLICT", code(stale.bodyAsText()))
        val unassign = c.desk(admin, t, "assignee_user_id" to null)
        assertEquals(ok, unassign.status, unassign.bodyAsText())
        assertTrue(json(unassign.bodyAsText())["assignee_user_id"] is JsonNull)
        // Audit: one desk_update row per applied op (three), none for the replay, the refusals or the no-change op.
        assertEquals(3L, count("SELECT count(*) FROM app.audit_log WHERE entity = 'support_ticket' AND entity_id = '$t' AND action = 'desk_update'"))
    }

    // ---- comments and close ----------------------------------------------------------------------------------------

    /** D-TSO-MAN-21 (CR-8): no comment after the ticket left new and in_progress, on either path; replies replay. */
    @Test
    fun commentsCloseAfterResolveOnBothPaths() = run { app ->
        val c = app.client
        val tso = login(c, "tso1001", tsoPhone)
        val admin = login(c, "admin1001")
        val t = uuid()
        c.send(tso, listOf(ticket(t)))
        assertEquals(listOf("accepted"), c.send(tso, listOf(comment(t, "It shows a red light"))))
        // The first desk reply on a New ticket moves it to in_progress (its own status_change first) and stamps first_response_at.
        val reply = uuid()
        val r1 = c.reply(admin, t, "Please restart the printer", reply)
        assertEquals(HttpStatusCode.Created, r1.status, r1.bodyAsText())
        val ev = json(r1.bodyAsText())
        assertEquals(reply, ev.s("event_uuid")); assertEquals("comment", ev.s("kind")); assertEquals("Support", ev.s("actor_display")); assertEquals("Please restart the printer", ev.s("body"))
        assertEquals("in_progress|2", scalar("SELECT status || '|' || version FROM app.support_ticket_state WHERE ticket_uuid = '$t' AND first_response_at IS NOT NULL"))
        assertEquals(listOf("created", "comment", "status_change", "comment"), rows("SELECT kind FROM app.support_ticket_event WHERE ticket_uuid = '$t' ORDER BY created_at, id"))
        assertEquals(TicketHarness.stableV4("aron.support.ticket-reply:$reply:status_change"), scalar("SELECT client_uuid FROM app.support_ticket_event WHERE ticket_uuid = '$t' AND kind = 'status_change'"))
        assertEquals(ok, c.reply(admin, t, "Please restart the printer", reply).status, "a replay")
        val reused = c.reply(admin, t, "Something else", reply)
        assertEquals(HttpStatusCode.Conflict, reused.status); assertEquals("ERR_CONFLICT", code(reused.bodyAsText()))
        assertEquals(HttpStatusCode.Conflict, c.reply(admin, uuid().also { c.send(tso, listOf(ticket(it))) }, "Please restart the printer", reply).status, "the uuid on another ticket")
        // A second reply on an in_progress ticket changes no status.
        assertEquals(HttpStatusCode.Created, c.reply(admin, t, "Any better?").status)
        assertEquals(2, version(t))
        for (bad in listOf("""{"event_uuid":"${uuid()}","body":""}""", """{"event_uuid":"${uuid()}","body":"${"x".repeat(2001)}"}""", """{"event_uuid":"x","body":"hi"}""", """{"body":"hi"}""")) {
            assertEquals(HttpStatusCode.BadRequest, c.call(admin, HttpMethod.Post, "/v1/support/tickets/$t/events", bad).status, bad)
        }
        assertEquals(HttpStatusCode.NotFound, c.reply(admin, uuid(), "hello").status)
        // Resolved: the phone's comment is refused for good, the desk's reply 409; the earlier reply still replays.
        assertEquals(ok, c.desk(admin, t, "status" to "resolved", "resolution_code" to "resolved", "resolution_notes" to "Cable replaced").status)
        assertEquals(listOf("rejected ticket_state_invalid"), c.send(tso, listOf(comment(t, "Thanks"))))
        val closed = c.reply(admin, t, "One more thing")
        assertEquals(HttpStatusCode.Conflict, closed.status); assertEquals("ERR_TICKET_COMMENTS_CLOSED", code(closed.bodyAsText()))
        assertEquals(ok, c.reply(admin, t, "Please restart the printer", reply).status, "replay first, then the state rule")
        // Cancelled and closed tickets take no comments either.
        val x = uuid()
        c.send(tso, listOf(ticket(x)))
        assertEquals(ok, c.desk(admin, x, "status" to "cancelled", "cancel_reason" to "Duplicate of another ticket").status)
        assertEquals(listOf("rejected ticket_state_invalid"), c.send(tso, listOf(comment(x))))
        assertEquals("ERR_TICKET_COMMENTS_CLOSED", code(c.reply(admin, x, "hello").bodyAsText()))
        // A blank comment from the phone is a malformed record.
        val y = uuid()
        c.send(tso, listOf(ticket(y)))
        assertEquals(listOf("rejected schema_invalid"), c.send(tso, listOf(comment(y, "   "))))
    }

    /** "Submit Review & Close": only the reporter, only from resolved, once; the desk may close a resolved ticket too. */
    @Test
    fun closeRules() = run { app ->
        val c = app.client
        val tso = login(c, "tso1001", tsoPhone); val tso2 = login(c, "tsoq9001", tso2Phone)
        val admin = login(c, "admin1001")
        val t = uuid()
        c.send(tso, listOf(ticket(t)))
        assertEquals(listOf("rejected ticket_state_invalid"), c.send(tso, listOf(closeReview(t))), "close from new")
        assertEquals(ok, c.desk(admin, t, "status" to "resolved", "resolution_code" to "resolved", "resolution_notes" to "Done").status)
        assertEquals(listOf("rejected ticket_state_invalid"), c.send(tso2, listOf(closeReview(t))), "close by another reporter")
        val v = version(t)
        val close = closeReview(t)
        assertEquals(listOf("accepted"), c.send(tso, listOf(close)))
        val closeUuid = close["client_uuid"]!!.jsonPrimitive.content
        assertEquals("closed|${ids["tso1001"]}|${v + 1}", scalar("SELECT status || '|' || closed_by || '|' || version FROM app.support_ticket_state WHERE ticket_uuid = '$t' AND closed_at IS NOT NULL AND resolved_at IS NOT NULL"))
        val ev = json(c.call(tso, HttpMethod.Get, "/v1/support/tickets/$t").bodyAsText())["events"]!!.jsonArray.map { it.jsonObject }.single { it.s("event_uuid") == closeUuid }
        assertEquals("close_review|resolved|closed|false", "${ev.s("kind")}|${ev.s("from_status")}|${ev.s("to_status")}|${ev.s("actor_is_support")}")
        val dom = json(scalar("SELECT payload::text FROM app.domain_event WHERE event_type = 'support_ticket.status_changed' AND source_client_uuid = '$closeUuid'")!!)
        assertEquals("resolved|closed|false", "${dom.s("from_status")}|${dom.s("to_status")}|${dom.s("actor_is_support")}")
        assertEquals(listOf("duplicate"), c.send(tso, listOf(close)))
        assertEquals(listOf("rejected ticket_state_invalid"), c.send(tso, listOf(closeReview(t))), "a second close review")
        assertEquals("ERR_TICKET_TRANSITION_INVALID", code(c.desk(admin, t, "status" to "closed").bodyAsText()), "closed stays closed")
        // The desk may close a resolved ticket.
        val d = uuid()
        c.send(tso, listOf(ticket(d)))
        assertEquals(ok, c.desk(admin, d, "status" to "resolved", "resolution_code" to "resolved", "resolution_notes" to "Done").status)
        assertEquals(ok, c.desk(admin, d, "status" to "closed").status)
        assertEquals("closed|${ids["admin1001"]}", scalar("SELECT status || '|' || closed_by FROM app.support_ticket_state WHERE ticket_uuid = '$d'"))
    }

    // ---- summary, inbox, push ------------------------------------------------------------------------------------

    /** D-TSO-MAN-25 and R-F-14: counts by status; the displays compute the whole percent once from them (TicketMath). */
    @Test
    fun summaryCountsTheCallersTicketsAndTheDeskCountsAll() = run { app ->
        val c = app.client
        val tso2 = login(c, "tsoq9001", tso2Phone)
        val admin = login(c, "admin1001")
        suspend fun summary(cl: TicketHarness.Caller): JsonObject {
            val r = c.call(cl, HttpMethod.Get, "/v1/support/tickets/summary")
            assertEquals(ok, r.status, r.bodyAsText())
            return json(r.bodyAsText())
        }
        val before = summary(tso2)
        val base = listOf("total", "new", "in_progress", "resolved", "closed", "cancelled", "open").associateWith { before[it]!!.jsonPrimitive.long }
        val tk = List(5) { uuid() }
        c.send(tso2, tk.map { ticket(it) })
        assertEquals(ok, c.desk(admin, tk[0], "status" to "in_progress").status)
        assertEquals(ok, c.desk(admin, tk[1], "status" to "resolved", "resolution_code" to "resolved", "resolution_notes" to "Done").status)
        assertEquals(ok, c.desk(admin, tk[2], "status" to "cancelled", "cancel_reason" to "Duplicate ticket").status)
        val s = summary(tso2)
        fun n(k: String) = s[k]!!.jsonPrimitive.long - base.getValue(k)
        assertEquals(listOf(5L, 2L, 1L, 1L, 0L, 1L, 3L), listOf("total", "new", "in_progress", "resolved", "closed", "cancelled", "open").map(::n))
        assertEquals(setOf("as_of", "total", "new", "in_progress", "resolved", "closed", "cancelled", "open"), s.keys, "counts only (R-F-14)")
        assertEquals("2027-01-03T04:00:00.000Z", s.s("as_of"))
        // The desk counts every ticket; the reporter's counts are inside it.
        val all = summary(admin)
        assertTrue(all["total"]!!.jsonPrimitive.long >= s["total"]!!.jsonPrimitive.long + 0)
        assertEquals(all["total"]!!.jsonPrimitive.long, count("SELECT count(*) FROM app.support_ticket WHERE voided_at IS NULL"))
        // The display's whole percent from the counts (s4.4): 18 of 723 is 2 %, total 0 prints 0 %.
        val counts = TicketMath.counts(List(723) { if (it < 18) "resolved" else "closed" })
        assertEquals(2L, counts.resolvedPct)
        assertEquals(0L, TicketMath.counts(emptyList()).openPct)
        val own = TicketMath.counts(rows("SELECT s.status FROM app.support_ticket_state s WHERE s.requester_user_id = ${ids["tsoq9001"]}"))
        assertEquals(s["total"]!!.jsonPrimitive.long to s["open"]!!.jsonPrimitive.long, own.total to own.open)
    }

    /** D-TSO-MAN-26: each desk op and reply puts one bell row for the reporter and nudges after the commit (when switched on). */
    @Test
    fun aDeskUpdateReachesTheReportersBellOnceAndPushesWhenSwitchedOn() = run { app ->
        val c = app.client
        val tso = login(c, "tso1001", tsoPhone)
        val admin = login(c, "admin1001")
        val fcm = "fcm-token-" + "t".repeat(40)
        assertEquals(HttpStatusCode.NoContent, c.call(tso, HttpMethod.Put, "/v1/devices/me/push-token", """{"provider":"fcm","token":"$fcm","app_flavour":"tso"}""").status)
        val t = uuid()
        c.send(tso, listOf(ticket(t)))
        val no = scalar("SELECT ticket_no FROM app.support_ticket WHERE client_uuid = '$t'")!!
        sent.clear()
        val op = uuid()
        val body = """{"op_uuid":"$op","expected_version":1,"status":"resolved","resolution_code":"resolved","resolution_notes":"Done"}"""
        assertEquals(ok, c.call(admin, HttpMethod.Patch, "/v1/support/tickets/$t", body).status)
        assertEquals("ticket_updated|support_ticket|ticket:$t:$op|Ticket $no is resolved|টিকিট $no সমাধান হয়েছে",
            scalar("SELECT kind || '|' || ref_type || '|' || dedupe_key || '|' || title_en || '|' || title_bn FROM app.user_notification WHERE user_id = ${ids["tso1001"]} AND ref_id = '$t'"))
        awaitSent(1)
        assertEquals(listOf(fcm to mapOf("kind" to "sync_nudge", "reason" to "notification")), sent.toList(), "one push, no ticket data")
        // The bell reads it.
        val inbox = json(c.call(tso, HttpMethod.Get, "/v1/me/notifications").bodyAsText())
        assertTrue(inbox["items"]!!.jsonArray.any { it.jsonObject.s("ref_id") == t && it.jsonObject.s("kind") == "ticket_updated" })
        // A replay adds no row and no push.
        assertEquals(ok, c.call(admin, HttpMethod.Patch, "/v1/support/tickets/$t", body).status)
        Thread.sleep(300)
        assertEquals(1, sent.size); assertEquals(1L, count("SELECT count(*) FROM app.user_notification WHERE ref_id = '$t'"))
        // Switched off: the row is still written, no push goes.
        setCfg("cfg.notify.ticket_push_enabled", "false")
        try {
            val t2 = uuid()
            c.send(tso, listOf(ticket(t2)))
            assertEquals(HttpStatusCode.Created, c.reply(admin, t2, "We are on it").status)
            Thread.sleep(500)
            assertEquals(1, sent.size, "no push while cfg.notify.ticket_push_enabled is false")
            assertEquals("Support replied on ticket ${scalar("SELECT ticket_no FROM app.support_ticket WHERE client_uuid = '$t2'")}",
                scalar("SELECT title_en FROM app.user_notification WHERE ref_id = '$t2'"))
        } finally {
            setCfg("cfg.notify.ticket_push_enabled", "true")
        }
    }
}

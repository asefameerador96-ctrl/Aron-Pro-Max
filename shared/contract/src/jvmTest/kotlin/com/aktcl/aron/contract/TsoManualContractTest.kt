package com.aktcl.aron.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract 1.10.0 (TSO manual build, docs/requests/tso-manual-build-2026-10.md s3): the operations, schemas, enums and
 * changed members other lanes build against. A later edit that drops or narrows one of them fails here, next to the
 * drift tests.
 */
@Suppress("UNCHECKED_CAST")
class TsoManualContractTest {
    private fun map(o: Any?): Map<String, Any?> = o as Map<String, Any?>
    private fun props(schema: String): Map<String, Map<String, Any?>> = OpenApi.schema(schema)["properties"] as Map<String, Map<String, Any?>>
    private fun required(schema: String): List<String> = (OpenApi.schema(schema)["required"] as List<String>?).orEmpty()
    private fun prop(schema: String, name: String): Map<String, Any?> = props(schema)[name] ?: error("$schema.$name missing")
    private fun op(method: String, path: String): Map<String, Any?> = map(OpenApi.paths[path]?.get(method) ?: error("$method $path missing"))
    private fun statuses(method: String, path: String): Set<String> = map(op(method, path)["responses"]).keys

    /** The member's schema with `null` removed from `type` / `oneOf` (the shape a non-null value has). */
    private fun nonNull(s: Map<String, Any?>): Map<String, Any?> {
        (s["oneOf"] as List<Map<String, Any?>>?)?.filter { it["type"] != "null" }?.singleOrNull()?.let { return it }
        val t = s["type"]
        return if (t is List<*>) s + ("type" to t.single { it != "null" }) else s
    }
    private fun nullable(s: Map<String, Any?>): Boolean =
        (s["type"] as? List<*>)?.contains("null") == true || (s["oneOf"] as List<Map<String, Any?>>?)?.any { it["type"] == "null" } == true
    private fun ref(s: Map<String, Any?>): String? = (nonNull(s)["\$ref"] as String?)?.substringAfterLast('/')
    private fun enumOf(s: Map<String, Any?>): List<String> = (nonNull(s)["enum"] as List<Any?>).filterNotNull().map { it.toString() }
    private fun param(method: String, path: String, name: String): Map<String, Any?> {
        val params = (op(method, path)["parameters"] as List<Map<String, Any?>>?).orEmpty()
        return params.firstOrNull { it["name"] == name } ?: error("$method $path has no parameter $name")
    }

    @Test
    fun versionIs110() = assertEquals("1.10.0", map(OpenApi.doc["info"])["version"])

    @Test
    fun theTenNewOperationsExistWithTheirIdsAndStatuses() {
        val expected = listOf(
            Triple("get", "/v1/support/tickets", "listSupportTickets") to setOf("200", "400", "401", "403"),
            Triple("get", "/v1/support/tickets/summary", "getSupportTicketSummary") to setOf("200", "401"),
            Triple("get", "/v1/support/tickets/{ticket_uuid}", "getSupportTicket") to setOf("200", "401", "404"),
            Triple("patch", "/v1/support/tickets/{ticket_uuid}", "updateSupportTicket") to setOf("200", "400", "403", "404", "409"),
            Triple("post", "/v1/support/tickets/{ticket_uuid}/events", "addSupportTicketEvent") to setOf("200", "201", "403", "404", "409"),
            Triple("get", "/v1/me/notifications", "listMyNotifications") to setOf("200", "401"),
            Triple("post", "/v1/me/notifications/read", "markMyNotificationsRead") to setOf("200", "400", "401"),
            Triple("post", "/v1/dashboards/national/brand-cpr", "queryNationalBrandCpr") to statuses("post", "/v1/dashboards/national/cpr"),
            Triple("post", "/v1/dashboards/national/segment-share", "queryNationalSegmentShare") to statuses("post", "/v1/dashboards/national/sales"),
            Triple("post", "/v1/dashboards/national/login-submit/users", "queryNationalLoginSubmitUsers") to statuses("post", "/v1/dashboards/national/login-submit"),
        )
        for ((key, codes) in expected) {
            val (method, path, id) = key
            assertEquals(id, op(method, path)["operationId"], "$method $path")
            assertTrue(statuses(method, path).containsAll(codes), "$id statuses ${statuses(method, path)} miss ${codes - statuses(method, path)}")
        }
    }

    @Test
    fun operationsReturnTheSchemasOfSection31() {
        fun body(method: String, path: String, status: String): String? =
            ((map(map(op(method, path)["responses"])[status])["content"] as Map<String, Any?>?)?.get("application/json") as Map<String, Any?>?)
                ?.let { (map(it["schema"])["\$ref"] as String).substringAfterLast('/') }
        fun request(method: String, path: String): String? =
            (map(op(method, path)["requestBody"])["content"] as Map<String, Any?>).let { (map(map(it["application/json"])["schema"])["\$ref"] as String).substringAfterLast('/') }
        assertEquals("SupportTicketPage", body("get", "/v1/support/tickets", "200"))
        assertEquals("SupportTicketSummary", body("get", "/v1/support/tickets/summary", "200"))
        assertEquals("SupportTicketDetail", body("get", "/v1/support/tickets/{ticket_uuid}", "200"))
        assertEquals("SupportTicket", body("patch", "/v1/support/tickets/{ticket_uuid}", "200"))
        assertEquals("SupportTicketUpdate", request("patch", "/v1/support/tickets/{ticket_uuid}"))
        assertEquals("SupportTicketEvent", body("post", "/v1/support/tickets/{ticket_uuid}/events", "200"))
        assertEquals("SupportTicketEvent", body("post", "/v1/support/tickets/{ticket_uuid}/events", "201"))
        assertEquals("SupportTicketEventWrite", request("post", "/v1/support/tickets/{ticket_uuid}/events"))
        assertEquals("NotificationInbox", body("get", "/v1/me/notifications", "200"))
        assertEquals("NotificationReadRequest", request("post", "/v1/me/notifications/read"))
        assertEquals("NotificationReadResult", body("post", "/v1/me/notifications/read", "200"))
        for ((path, schema) in listOf("brand-cpr" to "NationalBrandCpr", "segment-share" to "NationalSegmentShare", "login-submit/users" to "NationalLoginSubmitUsers")) {
            assertEquals(schema, body("post", "/v1/dashboards/national/$path", "200"))
            assertEquals("NationalDashboardFilter", request("post", "/v1/dashboards/national/$path"))
        }
        // one ingest path for tickets: no online create (D-TSO-MAN-18)
        assertFalse(OpenApi.paths.getValue("/v1/support/tickets").containsKey("post"))
    }

    @Test
    fun queryParametersOfSection31() {
        val q = map(param("get", "/v1/support/tickets", "q")["schema"])
        assertEquals(2, q["minLength"]); assertEquals(40, q["maxLength"])
        val limit = map(param("get", "/v1/support/tickets", "limit")["schema"])
        assertEquals(listOf(1, 200, 50), listOf(limit["minimum"], limit["maximum"], limit["default"]))
        for (p in listOf("status", "issue_type_code", "priority", "requester_user_id", "assignee_user_id")) param("get", "/v1/support/tickets", p)
        val refs = (op("get", "/v1/support/tickets")["parameters"] as List<Map<String, Any?>>).mapNotNull { it["\$ref"] as String? }
        assertEquals(listOf("FromDate", "ToDate", "Cursor"), refs.map { it.substringAfterLast('/') })
        val inboxLimit = map(param("get", "/v1/me/notifications", "limit")["schema"])
        assertEquals(listOf(1, 100, 50), listOf(inboxLimit["minimum"], inboxLimit["maximum"], inboxLimit["default"]))
        assertEquals("Id", (map(param("get", "/v1/me/notifications", "before_id")["schema"])["\$ref"] as String).substringAfterLast('/'))
        val tab = param("post", "/v1/dashboards/national/login-submit/users", "tab")
        assertEquals(true, tab["required"])
        assertEquals(listOf("not_logged_in", "not_uploaded"), map(tab["schema"])["enum"])
        val children = param("get", "/v1/dashboards/targets", "children")
        assertEquals(false, children["required"])
        assertEquals(listOf("zone"), map(children["schema"])["enum"])
    }

    @Test
    fun everyNewSchemaIsClosed() {
        val objects = listOf(
            "SupportTicketPayload", "SupportTicketEventPayload", "SupportTicket", "SupportTicketPage", "SupportTicketDetail", "SupportTicketEvent",
            "SupportTicketSummary", "SupportTicketUpdate", "SupportTicketEventWrite", "NotificationItem", "NotificationInbox",
            "NotificationReadRequest", "NotificationReadResult", "NationalBrandCpr", "NationalSegmentShare", "LoginSubmitUserRow",
            "NationalLoginSubmitUsers", "TargetAchievementNode", "TargetAchievementRow",
        )
        for (s in objects) assertEquals(false, OpenApi.schema(s)["additionalProperties"], "$s must be additionalProperties: false")
        for (s in listOf("SupportTicketRecord", "SupportTicketEventRecord")) assertEquals(false, OpenApi.schema(s)["unevaluatedProperties"], s)
        // inline objects of the new schemas are closed too
        assertEquals(false, prop("SupportTicket", "requester")["additionalProperties"])
        assertEquals(false, map(prop("NationalBrandCpr", "brands")["items"])["additionalProperties"])
        assertEquals(false, map(prop("NationalSegmentShare", "segments")["items"])["additionalProperties"])
        assertEquals(false, map(prop("NationalSegmentShare", "families")["items"])["additionalProperties"])
    }

    @Test
    fun ticketNumberIsInc6DigitsOrMore() {
        val rx = Regex(OpenApi.schema("TicketNumber")["pattern"] as String)
        for (ok in listOf("INC100001", "INC999999", "INC1000000", "INC010700")) assertTrue(rx.matches(ok), ok)
        for (bad in listOf("INC12345", "inc100001", "INC10000a", "IN100001", "INC 100001", "")) assertFalse(rx.matches(bad), bad)
        assertEquals("TicketNumber", ref(prop("SupportTicket", "ticket_no")))
        assertTrue(nullable(prop("SupportTicket", "ticket_no")))
        // the number comes from a bigint sequence: at most 19 digits, so at most 22 characters
        val longest = "INC" + Long.MAX_VALUE
        assertTrue(rx.matches(longest))
        assertEquals(longest.length, OpenApi.schema("TicketNumber")["maxLength"])
    }

    /** All whitespace runs (line breaks of a folded description included) as one space. */
    private fun flat(s: Any?): String = s.toString().replace(Regex("\\s+"), " ")

    /**
     * D-TSO-MAN-20: resolution notes are 1..2000 and the resolve writes them wrapped in the support message. The longest
     * message (longest ticket number, longest resolution label, longest notes) must fit `SupportTicketEvent.body`, or a
     * valid PATCH would end in a contract violation (and in the event body CHECK of V0130).
     */
    @Test
    fun theLongestResolutionMessageFitsTheEventBody() {
        val desc = op("patch", "/v1/support/tickets/{ticket_uuid}")["description"].toString()
        val template = Regex("\"(\\[TicketID: <ticket_no>\\][^\"]*<notes>)\"").find(desc)?.groupValues?.get(1) ?: error("resolution message template missing")
        assertEquals("[TicketID: <ticket_no>] - Resolved:\\nResolution Code: <label>\\nResolution Notes:\\n\\n<notes>", template, "D-TSO-MAN-20 text (screenshot 14)")
        val ticketNo = "INC" + "9".repeat(OpenApi.schema("TicketNumber")["maxLength"] as Int - 3)
        val labelMax = nonNull(prop("SupportTicket", "resolution_label_en"))["maxLength"] as Int
        assertEquals(120, labelMax, "code_list.label_en <= 120 (V0005)")
        val notesMax = prop("SupportTicketUpdate", "resolution_notes")["maxLength"] as Int
        assertEquals(listOf(1, 2000), listOf(prop("SupportTicketUpdate", "resolution_notes")["minLength"], notesMax), "D-TSO-MAN-20 notes 1..2000")
        val longest = template.replace("\\n", "\n").replace("<ticket_no>", ticketNo).replace("<label>", "x".repeat(labelMax)).replace("<notes>", "n".repeat(notesMax))
        assertEquals(62 + 22 + 120 + 2000, longest.length, "62 fixed characters around the number, the label and the notes")
        val bodyMax = nonNull(prop("SupportTicketEvent", "body"))["maxLength"] as Int
        assertTrue(longest.length <= bodyMax, "resolution message ${longest.length} > SupportTicketEvent.body maxLength $bodyMax")
        assertEquals(2400, bodyMax, "the V0130 event body CHECK must match (F2 ask, docs/status/tso-manual-F1.md)")
        // what a person types stays at 2000: the reporter's comment and the desk reply
        assertEquals(2000, prop("SupportTicketEventPayload", "body")["maxLength"])
        assertEquals(2000, prop("SupportTicketEventWrite", "body")["maxLength"])
        assertEquals(2000, nonNull(prop("SupportTicket", "resolution_notes"))["maxLength"])
    }

    /** The name-based, version-4-shaped UUID of the TSO app's StableIds.v4 (SHA-256, version nibble 4, variant 10). */
    private fun stableV4(name: String): String {
        val b = java.security.MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8)).copyOf(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = b.joinToString("") { "%02x".format(it) }
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    /**
     * The desk PATCH is idempotent by `op_uuid` with a defined replay key: the replay check runs before the version and
     * transition checks, a reused `op_uuid` with another body or on another ticket is 409, and the event rows of one op
     * have deterministic UUIDs (the first = `op_uuid`, the others name-based), so a retried multi-field op can neither
     * double-write events nor answer a version conflict for a change it already made.
     */
    @Test
    fun deskPatchIdempotencyHasADefinedReplayKey() {
        val desc = flat(op("patch", "/v1/support/tickets/{ticket_uuid}")["description"])
        val order = listOf("the desk permission (403)", "the request body (400)", "the ticket (404)", "the `op_uuid` replay check", "`expected_version` (409)", "the transition rules (409)")
        val at = order.map { desc.indexOf(it).also { i -> assertTrue(i >= 0, "check order names: $it") } }
        assertEquals(at.sorted(), at, "checks run in the order $order")
        for (rule in listOf(
            "The replay check comes before `expected_version` and the transition rules",
            "answers 200 with the ticket as it is now and writes nothing again",
            "The same `op_uuid` with another body, or one already used on another ticket or by any other ticket event, is 409 `ERR_CONFLICT`",
            "The first of them has `event_uuid` = `op_uuid`",
            "`aron.support.ticket-op:<op_uuid>:<kind>`",
            "an op with no change at all answers 200 with the ticket and writes nothing",
        )) assertTrue(desc.contains(rule), "updateSupportTicket must say: $rule")
        // the event order names real event kinds
        val kinds = SupportTicketEventKind.entries.map { it.wire }
        for (k in listOf("status_change", "resolution", "priority_change", "assign")) { assertTrue(k in kinds, k); assertTrue(desc.contains("`$k`"), k) }
        // the derived event uuids are valid `Uuid` values, stable, and distinct per kind and per op
        val uuid = Regex(OpenApi.schema("Uuid")["pattern"] as String)
        val opUuid = "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10"
        val derived = listOf("priority_change", "assign").map { stableV4("aron.support.ticket-op:$opUuid:$it") }
        for (d in derived) assertTrue(uuid.matches(d), d)
        assertEquals(derived, listOf("priority_change", "assign").map { stableV4("aron.support.ticket-op:$opUuid:$it") })
        assertEquals(3, (derived + opUuid).toSet().size)
        assertTrue(stableV4("aron.support.ticket-op:7a1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10:assign") != derived[1])
        // the request side points at the rules
        assertTrue(flat(OpenApi.schema("SupportTicketUpdate")["description"]).contains("the 409 for a reused `op_uuid` are in updateSupportTicket"))
    }

    @Test
    fun recordPayloadsOfSection32() {
        assertEquals(listOf("issue_type_code", "title", "description", "source"), required("SupportTicketPayload"))
        val code = Regex(prop("SupportTicketPayload", "issue_type_code")["pattern"] as String)
        assertTrue(code.matches("login_otp") && code.matches("app_crash_order_submit") && !code.matches("Login") && !code.matches("x"))
        assertEquals(listOf(1, 120), listOf(prop("SupportTicketPayload", "title")["minLength"], prop("SupportTicketPayload", "title")["maxLength"]))
        assertEquals(null, prop("SupportTicketPayload", "description")["minLength"])
        assertEquals(2000, prop("SupportTicketPayload", "description")["maxLength"])
        val affected = prop("SupportTicketPayload", "affected_username")
        assertTrue(nullable(affected))
        val user = Regex(affected["pattern"] as String)
        assertTrue(user.matches("sr334001") && user.matches("AMO-6334") && !user.matches("ab") && !user.matches("a".repeat(41)))
        assertEquals(listOf("quick", "manual"), enumOf(prop("SupportTicketPayload", "source")))
        assertEquals(listOf("ticket_uuid", "kind"), required("SupportTicketEventPayload"))
        assertEquals("Uuid", ref(prop("SupportTicketEventPayload", "ticket_uuid")))
        assertEquals(listOf("comment", "close_review"), enumOf(prop("SupportTicketEventPayload", "kind")))
        assertEquals(2000, prop("SupportTicketEventPayload", "body")["maxLength"])
        // the records: envelope + type const + payload, in the SyncRecord oneOf and discriminator
        for ((type, rec, payload) in listOf(Triple("support_ticket", "SupportTicketRecord", "SupportTicketPayload"), Triple("support_ticket_event", "SupportTicketEventRecord", "SupportTicketEventPayload"))) {
            val parts = OpenApi.schema(rec)["allOf"] as List<Map<String, Any?>>
            assertEquals("RecordEnvelope", (parts[0]["\$ref"] as String).substringAfterLast('/'))
            val p = map(parts[1]["properties"])
            assertEquals(type, map(p["type"])["const"])
            assertEquals(payload, (map(p["payload"])["\$ref"] as String).substringAfterLast('/'))
            val sync = OpenApi.schema("SyncRecord")
            assertTrue((sync["oneOf"] as List<Map<String, Any?>>).any { (it["\$ref"] as String).endsWith("/$rec") }, rec)
            assertEquals("#/components/schemas/$rec", map(map(sync["discriminator"])["mapping"])[type])
        }
        assertTrue(RecordType.entries.map { it.wire }.containsAll(listOf("support_ticket", "support_ticket_event")))
        // a ticket photo is media_meta with ref_type support_ticket(_event): MediaMetaPayload.ref_type is the RecordType enum
        assertEquals("RecordType", ref(prop("MediaMetaPayload", "ref_type")))
    }

    @Test
    fun ticketReadSchemasOfSection32() {
        val ticket = required("SupportTicket").toSet()
        assertTrue(ticket.containsAll(listOf("ticket_uuid", "ticket_no", "issue_type_code", "issue_type_label_en", "issue_type_label_bn", "category_label_en",
            "title", "description", "source", "priority", "status", "requester", "affected_username", "business_date", "captured_at", "created_at",
            "updated_at", "first_response_at", "resolved_at", "closed_at", "cancelled_at", "resolution_code", "resolution_label_en", "resolution_notes",
            "cancel_reason", "version", "attachment_count")))
        // desk-only members are optional (absent for the reporter)
        assertFalse("assignee_user_id" in ticket || "assignee_name" in ticket)
        assertEquals(listOf("user_id", "username", "full_name", "role"), prop("SupportTicket", "requester")["required"])
        for (n in listOf("issue_type_label_bn", "affected_username", "first_response_at", "resolved_at", "closed_at", "cancelled_at", "resolution_code",
            "resolution_label_en", "resolution_notes", "cancel_reason", "assignee_user_id", "assignee_name")) assertTrue(nullable(prop("SupportTicket", n)), n)
        assertEquals(1, prop("SupportTicket", "version")["minimum"])
        assertEquals(0, prop("SupportTicket", "attachment_count")["minimum"])
        assertEquals(200, prop("SupportTicketPage", "items")["maxItems"])
        assertTrue(nullable(prop("SupportTicketPage", "next_cursor")))
        assertEquals(500, prop("SupportTicketDetail", "events")["maxItems"])
        assertEquals(3, prop("SupportTicketDetail", "attachments")["maxItems"])
        assertEquals(3, prop("SupportTicketEvent", "attachments")["maxItems"])
        assertEquals(listOf("event_uuid", "kind", "body", "actor_is_support", "actor_display", "from_status", "to_status", "resolution_code", "created_at", "attachments"),
            required("SupportTicketEvent"))
        assertEquals("SupportTicketEventKind", ref(prop("SupportTicketEvent", "kind")))
        assertEquals("SupportTicketStatus", ref(prop("SupportTicketEvent", "from_status")))
        // R-F-14: counts only; the displays compute the whole percent once from them
        val summary = listOf("as_of", "total", "new", "in_progress", "resolved", "closed", "cancelled", "open")
        assertEquals(summary, required("SupportTicketSummary"))
        assertEquals(summary, props("SupportTicketSummary").keys.toList(), "no percentage members (R-F-14)")
        for (n in summary.drop(1)) assertEquals(0, prop("SupportTicketSummary", n)["minimum"], n)
        val text = flat(OpenApi.schema("SupportTicketSummary")["description"])
        assertTrue(text.contains("compute each whole percent once from these counts") && text.contains("total 0 prints \"0%\""), text)
        val opText = flat(op("get", "/v1/support/tickets/summary")["description"])
        assertTrue(opText.contains("counts only (lead ruling R-F-14)") && !opText.contains("_pct_bp"), opText)
    }

    @Test
    fun deskWritesOfSection32() {
        assertEquals(listOf("op_uuid", "expected_version"), required("SupportTicketUpdate"))
        assertEquals(3, OpenApi.schema("SupportTicketUpdate")["minProperties"], "op_uuid, expected_version and at least one change")
        assertEquals(listOf("in_progress", "resolved", "closed", "cancelled"), enumOf(prop("SupportTicketUpdate", "status")))
        assertEquals("SupportTicketPriority", ref(prop("SupportTicketUpdate", "priority")))
        assertTrue(nullable(prop("SupportTicketUpdate", "assignee_user_id")))
        assertEquals(listOf(1, 2000), listOf(prop("SupportTicketUpdate", "resolution_notes")["minLength"], prop("SupportTicketUpdate", "resolution_notes")["maxLength"]))
        assertEquals(listOf(3, 500), listOf(prop("SupportTicketUpdate", "cancel_reason")["minLength"], prop("SupportTicketUpdate", "cancel_reason")["maxLength"]))
        assertEquals(listOf("event_uuid", "body"), required("SupportTicketEventWrite"))
        assertEquals(listOf(1, 2000), listOf(prop("SupportTicketEventWrite", "body")["minLength"], prop("SupportTicketEventWrite", "body")["maxLength"]))
    }

    @Test
    fun inboxSchemasOfSection32() {
        assertEquals(listOf("support_ticket", "leave_application", "zone_day"), enumOf(prop("NotificationItem", "ref_type")))
        assertEquals(64, prop("NotificationItem", "ref_id")["maxLength"])
        assertEquals(120, prop("NotificationItem", "title_en")["maxLength"]); assertEquals(300, prop("NotificationItem", "body_en")["maxLength"])
        for (n in listOf("title_bn", "body_bn", "read_at")) assertTrue(nullable(prop("NotificationItem", n)), n)
        assertEquals("NotificationKind", ref(prop("NotificationItem", "kind")))
        assertEquals(listOf("as_of", "unread_count", "items", "next_before_id"), required("NotificationInbox"))
        assertEquals(100, prop("NotificationInbox", "items")["maxItems"])
        assertTrue(nullable(prop("NotificationInbox", "next_before_id")))
        assertEquals(listOf("up_to_id"), required("NotificationReadRequest"))
        assertEquals(listOf("unread_count"), required("NotificationReadResult"))
    }

    @Test
    fun dashboardSchemasOfSection32() {
        assertEquals(listOf("successful_calls", "target_outlets"), enumOf(prop("NationalBrandCpr", "denominator")))
        val brands = prop("NationalBrandCpr", "brands")
        assertEquals(100, brands["maxItems"])
        assertEquals(listOf("brand_code", "brand_name", "outlets_bought", "cpr_bp", "cpr_of_successful_bp", "cpr_of_target_bp"), map(brands["items"])["required"])
        val segments = prop("NationalSegmentShare", "segments")
        assertEquals(50, segments["maxItems"])
        assertEquals(listOf("segment_code", "segment_name", "category_code", "base_unit", "value_mtk", "value_share_bp", "qty_base", "volume_share_bp"),
            map(segments["items"])["required"])
        assertEquals(listOf("base_unit", "total_qty_base"), map(prop("NationalSegmentShare", "families")["items"])["required"])
        assertEquals("MtkNonNegative", ref(prop("NationalSegmentShare", "total_value_mtk")))
        assertEquals(listOf("route_id", "route_code", "route_name", "zone_id", "dep_name", "user_id", "username", "display_name", "role"), required("LoginSubmitUserRow"))
        for (n in listOf("user_id", "username", "display_name")) assertTrue(nullable(prop("LoginSubmitUserRow", n)), "vacant route: $n null")
        assertEquals(listOf("SR", "AMO"), enumOf(prop("LoginSubmitUserRow", "role")))
        assertEquals(2000, prop("NationalLoginSubmitUsers", "items")["maxItems"])
        assertEquals(listOf("not_logged_in", "not_uploaded"), enumOf(prop("NationalLoginSubmitUsers", "tab")))
        // a national web reach has more rows than the cap: the cut is flagged, never silent (as the other capped lists)
        assertTrue("truncated" in required("NationalLoginSubmitUsers"))
        assertEquals("boolean", prop("NationalLoginSubmitUsers", "truncated")["type"])
        val usersOp = flat(op("post", "/v1/dashboards/national/login-submit/users")["description"])
        assertTrue(usersOp.contains("a longer tab answers the first 2000 in this order with `truncated` true"), usersOp)
        assertTrue(flat(prop("NationalLoginSubmitUsers", "route_count")["description"]).contains("also when `items` is cut"))
    }

    @Test
    fun targetAchievementOfSections32And33() {
        assertTrue(required("TargetAchievement").containsAll(listOf("day", "elapsed_days", "period_days", "last_updated_at")))
        assertFalse("children" in required("TargetAchievement"), "children only with children=zone")
        assertEquals(listOf(0, 31), listOf(prop("TargetAchievement", "elapsed_days")["minimum"], prop("TargetAchievement", "elapsed_days")["maximum"]))
        // R-F-15: a working-days month with no working day has n = 0
        assertEquals(listOf(0, 31), listOf(prop("TargetAchievement", "period_days")["minimum"], prop("TargetAchievement", "period_days")["maximum"]))
        assertEquals(200, prop("TargetAchievement", "children")["maxItems"])
        assertEquals("TargetAchievementNode", ref(map(prop("TargetAchievement", "children")["items"])))
        assertEquals("TargetAchievementRow", ref(map(prop("TargetAchievement", "rows")["items"])))
        assertEquals("TargetAchievementRow", ref(map(prop("TargetAchievementNode", "rows")["items"])))
        assertEquals("NodeRef", ref(prop("TargetAchievementNode", "node")))
        val row = required("TargetAchievementRow")
        for (n in listOf("target_qty_base", "achieved_qty_base", "tilldate_target_qty_base", "remaining_qty_base", "remaining_tilldate_qty_base")) {
            assertTrue(n in row, n)
            val p = prop("TargetAchievementRow", n)
            assertEquals(listOf("integer", "int64", 0), listOf(nonNull(p)["type"], p["format"], p["minimum"]), n)
        }
        for (n in listOf("achievement_pct_bp", "tilldate_achievement_pct_bp")) { assertTrue(n in row); assertTrue(nullable(prop("TargetAchievementRow", n)), n) }
        // R-F-15: required, but null when n = 0; the month figures stay non-null
        for (n in listOf("tilldate_target_qty_base", "remaining_tilldate_qty_base", "tilldate_achievement_pct_bp")) {
            assertTrue(nullable(prop("TargetAchievementRow", n)), "$n is null when period_days is 0")
            assertTrue(flat(prop("TargetAchievementRow", n)["description"]).contains("null when `period_days` is 0 (R-F-15)"), n)
        }
        for (n in listOf("target_qty_base", "achieved_qty_base", "remaining_qty_base")) assertFalse(nullable(prop("TargetAchievementRow", n)), n)
        assertTrue(flat(OpenApi.schema("TargetAchievement")["description"]).contains("n = 0 (a working-days basis in a month with no working day) makes the till-date members of every row null"))
        assertTrue("category_code" in row)
        // the AMO app's members keep their meaning
        for (n in listOf("target_qty", "achieved_qty", "tilldate_target_qty", "remaining_qty")) assertEquals("Decimal3", ref(prop("TargetAchievementRow", n)), n)
        assertNotNull(props("TargetAchievementRow")["achievement_pct"])
    }

    /**
     * D-TSO-MAN-05: a card or category till-date is the sum of its item rows, never its total rounded once. The example
     * in the TargetAchievement description is recomputed here with Targets.tillDate of s4.1, (2*T*e + n) div (2*n), over
     * the eight Cigarette items of screenshots 39-42; at 21/26 the item sum (33114) and a rounded total (33115) differ.
     */
    @Test
    fun tillDateCardIsTheSumOfItemRows() {
        val items = listOf(1000L, 1000L, 10000L, 10000L, 5000L, 6000L, 2999L, 5000L)
        fun tillDate(t: Long, e: Long, n: Long) = (2 * t * e + n) / (2 * n)
        fun card(e: Long, n: Long) = items.sumOf { tillDate(it, e, n) }
        assertEquals(40999L, items.sum())
        assertEquals(33064L, card(25, 31)); assertEquals(33064L, tillDate(40999, 25, 31))
        assertEquals(32799L, card(20, 25))
        assertEquals(33114L, card(21, 26)); assertEquals(33115L, tillDate(40999, 21, 26), "the rounded total the rule forbids")
        val desc = flat(OpenApi.schema("TargetAchievement")["description"])
        assertTrue(desc.contains("a category or card figure is the sum of its item rows, never the card total rounded once"), desc)
        assertTrue(desc.contains("25/31 gives ${card(25, 31)}; 21/26 gives ${card(21, 26)}, where a rounded total would give ${tillDate(40999, 21, 26)}"), desc)
        assertTrue(flat(OpenApi.schema("TargetAchievementRow")["description"]).contains("a category row the sum of its items (never its own T rounded once"))
    }

    @Test
    fun changedSchemasOfSection33() {
        assertTrue(CodeListKey.entries.map { it.wire }.containsAll(listOf("ticket_issue_type", "ticket_resolution_code")))
        assertEquals(listOf("integer", "int64"), listOf(nonNull(prop("NationalSalesCard", "monthly_target_qty"))["type"], prop("NationalSalesCard", "monthly_target_qty")["format"]))
        assertTrue(nullable(prop("NationalSalesCard", "monthly_target_qty")))
        assertTrue((prop("NationalSalesCard", "achievement_pct_bp")["description"] as String).contains("round_half_up(10000 * total_sales * working_days / monthly_target_qty)"))
        assertTrue(nullable(prop("NationalLoginSubmit", "submit_of_target_pct_bp")))
        assertTrue((prop("DashboardSummary", "by_category")["description"] as String).contains("Sold quantity of sale lines only"))
        assertEquals(0, prop("FinalSubmitPreview", "submit_seq")["minimum"])
        assertEquals("Id", ref(map(prop("FinalSubmitPreview", "warn_route_ids")["items"])))
        val route = map(map(prop("FinalSubmitPreview", "routes")["items"])["properties"])
        assertEquals(listOf("uploaded", "web_entry", "not_uploaded", "not_due"), enumOf(map(route["upload_state"])))
        assertEquals(listOf("normal", "manual", "dummy_br", "dummy_institutional"), enumOf(map(route["route_class"])))
        assertEquals(20, map(route["visit_kind"])["maxLength"])
        assertEquals("Role", ref(map(route["ff_role"])))
        assertEquals("Uuid", ref(prop("DayReopenRequest", "client_uuid")))
        assertFalse("client_uuid" in required("DayReopenRequest"), "optional")
        for (n in listOf("applicant_username", "applicant_name", "territory_name")) assertTrue(nullable(prop("LeaveApplication", n)), n)
        assertEquals("BusinessDate", ref(prop("Feedback", "business_date"))); assertEquals("Timestamp", ref(prop("Feedback", "captured_at")))
        assertTrue(nullable(prop("Feedback", "username")) && nullable(prop("Feedback", "zone_id")))
        assertEquals(listOf("integer", 0), listOf(prop("TeamLocation", "mock_fixes_today")["type"], prop("TeamLocation", "mock_fixes_today")["minimum"]))
        assertEquals("boolean", prop("Me", "support_desk")["type"])
        assertTrue((OpenApi.schema("FinalSubmitRequest")["properties"] as Map<String, Map<String, Any?>>)["client_uuid"]!!["description"].toString().contains("aron.tso.final-submit:"))
    }

    /**
     * Members added to responses the server already sends stay optional until the server lanes ship them (the conformance
     * suite validates every served answer; 1.10.x makes them required).
     */
    @Test
    fun membersAddedToServedResponsesAreOptional() {
        val added = mapOf(
            "NationalSalesCard" to listOf("monthly_target_qty"), "NationalLoginSubmit" to listOf("submit_of_target_pct_bp"),
            "FinalSubmitPreview" to listOf("submit_seq", "warn_route_ids"), "LeaveApplication" to listOf("applicant_username", "applicant_name", "territory_name"),
            "Feedback" to listOf("business_date", "captured_at", "username", "zone_id"), "TeamLocation" to listOf("mock_fixes_today"), "Me" to listOf("support_desk"),
        )
        for ((schema, members) in added) for (m in members) {
            assertNotNull(props(schema)[m], "$schema.$m")
            assertFalse(m in required(schema), "$schema.$m must stay optional in 1.10.0")
        }
        val routeRequired = map(prop("FinalSubmitPreview", "routes")["items"])["required"] as List<String>
        assertTrue(routeRequired.none { it in setOf("route_class", "visit_kind", "ff_role", "upload_state") })
        // no schema default: openapi-typescript would make the member required for the web (defaultNonNullable)
        assertFalse(prop("Me", "support_desk").containsKey("default")); assertFalse(prop("TeamLocation", "mock_fixes_today").containsKey("default"))
    }

    @Test
    fun newCodesAndTheirStatuses() {
        val outcome = RecordOutcomeCode.entries.associateBy { it.wire }
        assertEquals(AckStatus.QUARANTINED to null, outcome.getValue("after_final_submit").let { it.status to it.retryable })
        for (c in listOf("day_final_submitted", "ticket_state_invalid", "issue_type_unknown")) {
            assertEquals(AckStatus.REJECTED to false, outcome.getValue(c).let { it.status to it.retryable }, c)
        }
        val problems = ProblemCode.entries.associate { it.wire to it.httpStatus }
        for (c in listOf("ERR_TICKET_TRANSITION_INVALID", "ERR_TICKET_COMMENTS_CLOSED", "ERR_DAY_FINAL_SUBMIT_REOPENED")) assertEquals(409, problems[c], c)
        assertEquals(listOf("new", "in_progress", "resolved", "closed", "cancelled"), SupportTicketStatus.entries.map { it.wire })
        assertEquals(listOf("p1", "p2", "p3", "p4"), SupportTicketPriority.entries.map { it.wire })
        assertEquals(listOf("created", "comment", "close_review", "status_change", "resolution", "priority_change", "assign"), SupportTicketEventKind.entries.map { it.wire })
        assertEquals(listOf("ticket_updated", "leave_decided", "final_submit_auto"), NotificationKind.entries.map { it.wire })
    }

    // ---- lead rulings after the foundation wave (build contract s9, 2026-10-29) ----

    /** R-F-05: a requester role outside the allowed list is quarantined, and an accept runs the role check again. */
    @Test
    fun ticketFromARoleOutsideTheRequesterRolesIsQuarantinedAndReCheckedOnAccept() {
        val ticket = flat(OpenApi.schema("SupportTicketPayload")["description"])
        for (rule in listOf(
            "a requester role outside the configured requester roles (default TSO, D-TSO-MAN-17) is quarantined `scope_out_of_reach`",
            "the server runs the role check again and refuses the accept while the role is still not allowed",
            "no new outcome code, lead ruling R-F-05",
        )) assertTrue(ticket.contains(rule), "SupportTicketPayload must say: $rule")
        assertTrue(flat(OpenApi.schema("SupportTicketEventPayload")["description"]).contains("with the same role check again on an accept as for the ticket (R-F-05)"))
        assertEquals(AckStatus.QUARANTINED to null, RecordOutcomeCode.entries.single { it.wire == "scope_out_of_reach" }.let { it.status to it.retryable })
    }

    /** R-F-11: admin module `support` in the enum `Me.modules` and `UserAccess.modules` key on, and in the mirror. */
    @Test
    fun adminModuleSupportIsInTheContract() {
        assertEquals("support", OpenApi.enumOf("AdminModule").last())
        assertEquals(AdminModule.SUPPORT, AdminModule.entries.last())
        val meModules = (prop("Me", "modules")["oneOf"] as List<Map<String, Any?>>).single { it["type"] == "object" }
        assertEquals("AdminModule", (map(meModules["propertyNames"])["\$ref"] as String).substringAfterLast('/'))
        for (s in listOf("UserAccess", "UserAccessWrite")) assertEquals("AdminModule", (map(prop(s, "modules")["propertyNames"])["\$ref"] as String).substringAfterLast('/'), s)
        val text = flat(OpenApi.schema("AdminModule")["description"])
        assertTrue(text.contains("lead ruling R-F-11") && text.contains("`/v1/support/tickets`") && text.contains("stays `support.ticket.manage`"), text)
    }

    /** R-F-12: report key `tso-visit-log` (B3's report, W1's catalogue entry). */
    @Test
    fun reportKeyTsoVisitLogIsInTheContract() {
        assertTrue("tso-visit-log" in OpenApi.enumOf("ReportKey"))
        assertEquals(ReportKey.TSO_VISIT_LOG, ReportKey.entries.single { it.wire == "tso-visit-log" })
    }

    /** R-F-13, R-F-17: W at the scope's lowest common node; W = 0 makes the daily target and the ring null. */
    @Test
    fun noWorkingDayMakesTheDailyTargetAndTheRingNull() {
        for (n in listOf("daily_target", "remaining", "achievement_pct_bp")) assertTrue(nullable(prop("NationalSalesCard", n)), "NationalSalesCard.$n")
        assertTrue(flat(prop("NationalSalesCard", "daily_target")["description"]).contains("null when W = 0"))
        assertTrue(flat(prop("NationalSalesCard", "remaining")["description"]).contains("null whenever daily_target is null (no target, or W = 0)"))
        assertTrue(flat(prop("NationalSalesCard", "achievement_pct_bp")["description"]).contains("when `NationalSales.working_days` is 0"))
        // the route card of the Map Details page carries a daily target too
        for (n in listOf("daily_target", "achievement_pct_bp")) assertTrue(nullable(prop("TargetVsAchievement", n)), "TargetVsAchievement.$n")
        assertTrue(flat(prop("TargetVsAchievement", "daily_target")["description"]).contains("null when the month has no working day (W = 0"))
        assertTrue(flat(prop("TargetVsAchievement", "achievement_pct_bp")["description"]).contains("null whenever daily_target is null"))
        val w = prop("NationalSales", "working_days")
        assertEquals(listOf(0, 31), listOf(w["minimum"], w["maximum"]))
        val text = flat(w["description"])
        for (rule in listOf(
            "at the lowest common node of the effective scope", "one zone gives the zone, one territory the territory, mixed or national the global calendar",
            "the node's weekend days (`cfg.calendar.weekend_days` resolved at the node)", "the holidays on the node's path (global, wing, division, territory, zone)",
            "+ the make-up days on that path", "WorkingDays of shared/rules", "R-F-17",
        )) assertTrue(text.contains(rule), "NationalSales.working_days must say: $rule")
        assertTrue(flat(prop("NationalSalesCard", "daily_target")["description"]).contains("D-TSO-MAN-08"))
    }

    /**
     * R-F-16: a basis-point member that can exceed 10000 without bound is int64, so the phone decodes it as a Long. Every
     * `*_bp` member the contract describes as uncapped is int64, and the new 1.10.0 members without a `maximum` too.
     */
    @Test
    fun uncappedBasisPointMembersAreInt64() {
        val found = mutableListOf<String>()
        fun walk(owner: String, s: Map<String, Any?>) {
            (s["properties"] as Map<String, Map<String, Any?>>?)?.forEach { (name, p) ->
                if (name.endsWith("_bp")) {
                    val text = flat(p["description"] ?: "")
                    if (text.contains("uncapped", ignoreCase = true) || text.contains("exceed 10000")) {
                        found += "$owner.$name"
                        assertEquals("int64", p["format"], "$owner.$name is uncapped")
                    }
                }
                (p["items"] as Map<String, Any?>?)?.let { walk("$owner.$name[]", it) }
                walk("$owner.$name", p)
            }
        }
        (OpenApi.doc["components"] as Map<String, Any?>).let { map(it["schemas"]) }.forEach { (n, s) -> walk(n, map(s)) }
        assertTrue(found.containsAll(listOf("NationalSalesCard.achievement_pct_bp", "TargetVsAchievement.achievement_pct_bp",
            "TargetAchievementRow.achievement_pct_bp", "TargetAchievementRow.tilldate_achievement_pct_bp")), found.toString())
        // the new 1.10.0 *_bp members: capped at 10000 or int64
        val newMembers = listOf("cpr_bp", "cpr_of_successful_bp", "cpr_of_target_bp").map { map(map(prop("NationalBrandCpr", "brands")["items"])["properties"])[it] as Map<String, Any?> } +
            listOf("value_share_bp", "volume_share_bp").map { map(map(prop("NationalSegmentShare", "segments")["items"])["properties"])[it] as Map<String, Any?> } +
            listOf(prop("NationalLoginSubmit", "submit_of_target_pct_bp"), prop("TargetAchievementRow", "achievement_pct_bp"), prop("TargetAchievementRow", "tilldate_achievement_pct_bp"))
        for (p in newMembers) assertTrue(p["maximum"] == 10000 || p["format"] == "int64", p.toString())
        // what the phone decodes (WireDtos.kt, generated): Long, not Int
        fun fieldType(c: Class<*>, f: String) = c.getDeclaredField(f).type
        for (f in listOf("achievementPctBp", "tilldateAchievementPctBp", "tilldateTargetQtyBase", "remainingTilldateQtyBase")) {
            assertEquals(Long::class.javaObjectType, fieldType(TargetAchievementRow::class.java, f), "TargetAchievementRow.$f")
        }
        for (f in listOf("cprBp", "cprOfSuccessfulBp", "cprOfTargetBp")) assertEquals(Long::class.javaObjectType, fieldType(NationalBrandCprBrands::class.java, f), f)
    }

    /** R-F-18: `children=zone` only at a territory or zone node; above it 400 ERR_VALIDATION. */
    @Test
    fun childrenZoneOnlyAtATerritoryOrBelow() {
        val text = flat(op("get", "/v1/dashboards/targets")["description"])
        for (rule in listOf(
            "`children=zone` is allowed only when the node is a territory or below (lead ruling R-F-18)",
            "a territory node answers its zones, a zone node answers itself as its one child",
            "at a national, wing or division node it is 400 `ERR_VALIDATION` (`query.children` invalid_value)",
            "The check runs on the resolved node, the default top reach node included",
        )) assertTrue(text.contains(rule), "getTargetAchievement must say: $rule")
        assertTrue("400" in statuses("get", "/v1/dashboards/targets"))
        assertTrue(flat(param("get", "/v1/dashboards/targets", "children")["description"]).contains("at a national, wing or division node 400 `ERR_VALIDATION` (R-F-18)"))
        // the levels the dashboards accept: territory and zone are the only ones at or below a territory
        assertTrue(listOf("territory", "zone").all { it in OpenApi.enumOf("ReachLevel") })
    }
}

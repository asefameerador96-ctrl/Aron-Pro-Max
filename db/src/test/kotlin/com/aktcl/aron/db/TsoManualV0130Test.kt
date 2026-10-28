package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V0130..V0140, the data model of the TSO manual build (docs/requests/tso-manual-build-2026-10.md s2, lane F2): support
 * tickets (number, write-once capture, forward-only state, append-only thread, origin), the 31 Annex A issue types and
 * the other code lists, the notification inbox, the build's settings, permission and events, the midnight job's Final
 * Submit, the day-lock sync codes, the visit plan route, the Visit Query rubric and the six TSO dw views.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TsoManualV0130Test {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
        db.connect().use { SeedLoader.load(it) }
    }

    @AfterAll
    fun tearDown() = db.close()

    private fun Connection.tx(block: Connection.() -> Unit) {
        autoCommit = false
        try { block() } finally { rollback(); autoCommit = true }
    }

    private fun Connection.refused(sql: String): String {
        exec("SAVEPOINT r")
        val e = assertFailsWith<SQLException>(sql) { exec(sql) }
        exec("ROLLBACK TO SAVEPOINT r")
        return e.sqlState
    }

    // The seed's clock (D-TEST-DATE-PIN-01).
    private val day = "app.dhaka_date(now())"
    private fun user(name: String) = "(SELECT id FROM app.app_user WHERE username = '$name')"
    private val zone = "(SELECT id FROM app.zone WHERE code = 'Z-MIR')"
    private fun route(code: String) = "(SELECT id FROM app.route WHERE code = '$code')"
    private val ticketNo = Regex("^INC[0-9]{6,}$")

    private fun ticket(uuid: String, by: String, extraCols: String = "", extraVals: String = "") =
        "INSERT INTO app.support_ticket (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, issue_type_code, title, description, source$extraCols) " +
            "VALUES ('$uuid', '$uuid', $day, ${user(by)}, now(), 0, 'sync_stuck', 'Sync stuck', 'Upload does not finish', 'quick'$extraVals)"

    private fun state(uuid: String, by: String) =
        "INSERT INTO app.support_ticket_state (ticket_uuid, requester_user_id, requester_role, priority) VALUES ('$uuid', ${user(by)}, 'TSO', 'p1')"

    private fun event(uuid: String, ticket: String, by: String, kind: String = "comment", via: String = "device") =
        "INSERT INTO app.support_ticket_event (client_uuid, ticket_uuid, family_uuid, business_date, user_id, captured_at, config_version, via, kind, body) " +
            "VALUES ('$uuid', '$ticket', '$uuid', $day, ${user(by)}, now(), 0, '$via', '$kind', 'Still stuck')"

    private fun uuid(n: Int) = "0f000000-0000-4000-8000-%012d".format(n)

    /** A production (real) user made inside the test transaction; the seed's accounts are test (V0116). */
    private fun Connection.realUser(name: String, role: String) =
        exec("INSERT INTO app.app_user (username, full_name, role) VALUES ('$name', 'Real $role', '$role')")

    @Test
    fun ticketNumbersAreIncPlusAtLeastSixDigitsFromTheirOwnSequence() {
        // A fresh database: the sequence is moved below and its values are never rolled back.
        val fresh = TestPostgres.createDatabase().migrated()
        try {
            fresh.connect().use { c ->
                assertEquals("100001|1|false", c.scalar("SELECT start_value || '|' || min_value || '|' || cycle FROM pg_sequences WHERE schemaname = 'app' AND sequencename = 'support_ticket_no_seq'"))
                c.exec("INSERT INTO app.app_user (username, full_name, role) VALUES ('tso0001', 'TSO One', 'TSO')")
                fun next(n: Int): String {
                    c.exec(ticket(uuid(n), "tso0001"))
                    return c.scalar("SELECT ticket_no FROM app.support_ticket WHERE client_uuid = '${uuid(n)}'")!!
                }
                assertEquals("INC100001", next(1), "the first number clears the vendor's INC0107xx")
                assertEquals("INC100002", next(2))
                c.exec("SELECT setval('app.support_ticket_no_seq', 41)")
                assertEquals("INC000042", next(3), "padded to 6 digits")
                c.exec("SELECT setval('app.support_ticket_no_seq', 999998)")
                assertEquals("INC999999", next(4))
                assertEquals("INC1000000", next(5), "7 digits after INC999999, never cut to 6")
                assertEquals("23505", c.refusedAutocommit(ticket(uuid(6), "tso0001", ", ticket_no", ", 'INC100001'")), "numbers are unique")
                assertEquals("23514", c.refusedAutocommit(ticket(uuid(7), "tso0001", ", ticket_no", ", 'INC12345'")))
                assertEquals("23514", c.refusedAutocommit(ticket(uuid(8), "tso0001", ", ticket_no", ", 'ABC123456'")))
                assertEquals("23505", c.refusedAutocommit(ticket(uuid(1), "tso0001")), "the client UUID is the idempotency key")
            }
        } finally {
            fresh.close()
        }
    }

    private fun Connection.refusedAutocommit(sql: String): String = assertFailsWith<SQLException>(sql) { exec(sql) }.sqlState

    @Test
    fun fiftyConcurrentInsertsGetFiftyDistinctNumbers() {
        val n = 50
        val uuids = (1..n).map { UUID.randomUUID().toString() }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(n)
        try {
            val futures = uuids.map { u ->
                pool.submit<Unit> {
                    db.connect().use { c ->
                        start.await()
                        c.exec(ticket(u, "tso1001"))
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }
        db.connect().use { c ->
            val numbers = c.column("SELECT ticket_no FROM app.support_ticket WHERE client_uuid = ANY (?::uuid[])", uuids.toTypedArray().let { c.createArrayOf("uuid", it) })
                .filterNotNull()
            assertEquals(n, numbers.size)
            assertEquals(n, numbers.toSet().size, "every concurrent insert got its own number")
            assertTrue(numbers.all(ticketNo::matches), numbers.toString())
        }
    }

    @Test
    fun theTicketAndItsThreadAreWriteOnce() = db.connect().use { c ->
        c.tx {
            val t = uuid(101)
            exec(ticket(t, "tso1001"))
            exec(state(t, "tso1001"))
            exec(event(uuid(102), t, "tso1001"))
            assertEquals("42501", refused("UPDATE app.support_ticket SET title = 'Other' WHERE client_uuid = '$t'"))
            assertEquals("42501", refused("UPDATE app.support_ticket SET ticket_no = 'INC999998' WHERE client_uuid = '$t'"))
            assertEquals("42501", refused("DELETE FROM app.support_ticket WHERE client_uuid = '$t'"))
            assertEquals("42501", refused("UPDATE app.support_ticket_event SET body = 'Edited' WHERE client_uuid = '${uuid(102)}'"))
            assertEquals("42501", refused("DELETE FROM app.support_ticket_event WHERE client_uuid = '${uuid(102)}'"))
            // The admin data void tombstones once.
            exec("UPDATE app.support_ticket_event SET voided_at = now() WHERE client_uuid = '${uuid(102)}'")
            assertEquals("42501", refused("UPDATE app.support_ticket_event SET voided_at = now() + interval '1 hour' WHERE client_uuid = '${uuid(102)}'"))
            exec("UPDATE app.support_ticket SET voided_at = now() WHERE client_uuid = '$t'")
            assertEquals("42501", refused("UPDATE app.support_ticket SET voided_at = NULL WHERE client_uuid = '$t'"))
            // Shapes of the record.
            assertEquals("23514", refused(ticket(uuid(103), "tso1001").replace("'quick'", "'phone'")))
            assertEquals("23514", refused(ticket(uuid(104), "tso1001", ", affected_username", ", 'a b'")))
            assertEquals("23514", refused(ticket(uuid(105), "tso1001").replace("'sync_stuck'", "'Sync Stuck'")))
            assertEquals("23503", refused(event(uuid(106), uuid(199), "tso1001")), "an event needs its ticket")
            assertEquals("23514", refused(event(uuid(107), t, "tso1001", kind = "reopen")))
            assertEquals("23514", refused(event(uuid(108), t, "tso1001", via = "api")))
            exec(ticket(uuid(109), "tso1001", ", affected_username", ", 'sr1001'"))
            // A quick ticket without text stores an empty description.
            exec(
                "INSERT INTO app.support_ticket (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, issue_type_code, title, source) " +
                    "VALUES ('${uuid(110)}', '${uuid(110)}', $day, ${user("tso1001")}, now(), 0, 'printer_connection', 'Printer Connection issue', 'quick')",
            )
            assertEquals("0", scalar("SELECT length(description) FROM app.support_ticket WHERE client_uuid = '${uuid(110)}'"))
            assertEquals("23514", refused(ticket(uuid(111), "tso1001").replace("'Upload does not finish'", "repeat('x', 2001)")))
        }
    }

    @Test
    fun theStateMovesOnlyForwardAndKeepsItsFacts() = db.connect().use { c ->
        c.tx {
            val t = uuid(201)
            exec(ticket(t, "tso1001"))
            exec(state(t, "tso1001"))
            assertEquals("new|p1|1", scalar("SELECT status || '|' || priority || '|' || version FROM app.support_ticket_state WHERE ticket_uuid = '$t'"))
            assertEquals("23505", refused(state(t, "tso1001")), "one state row per ticket")
            assertEquals("23503", refused(state(uuid(299), "tso1001")), "a state needs its ticket")
            val s = "UPDATE app.support_ticket_state SET"
            val w = "WHERE ticket_uuid = '$t'"
            assertEquals("42501", refused("$s status = 'closed', closed_at = now() $w"), "new cannot close")
            assertEquals("23514", refused("$s status = 'resolved', resolved_at = now() $w"), "a resolution needs its code and notes")
            assertEquals("23514", refused("$s status = 'cancelled' $w"), "a cancel needs its time")
            assertEquals("23514", refused("$s status = 'cancelled', cancelled_at = now(), cancel_reason = 'no' $w"), "a cancel reason has 3..500 characters")
            assertEquals("23514", refused("$s priority = 'p5' $w"))
            exec("$s status = 'in_progress', first_response_at = now(), version = version + 1 $w")
            assertEquals("42501", refused("$s first_response_at = now() + interval '1 minute' $w"), "first response is set once")
            exec("$s status = 'resolved', resolved_at = now(), resolved_by = ${user("admin1001")}, resolution_code = 'resolved', resolution_notes = 'Cleared the outbox.', version = version + 1 $w")
            assertEquals("42501", refused("$s resolution_notes = 'Something else.' $w"), "the resolution is written once")
            assertEquals("42501", refused("$s status = 'in_progress' $w"), "no reopen")
            assertEquals("42501", refused("$s status = 'cancelled', cancelled_at = now(), cancel_reason = 'Too late' $w"))
            exec("$s priority = 'p2', assignee_user_id = ${user("support1001")}, version = version + 1 $w")
            exec("$s status = 'closed', closed_at = now(), closed_by = ${user("tso1001")}, version = version + 1 $w")
            assertEquals("closed|p2|5", scalar("SELECT status || '|' || priority || '|' || version FROM app.support_ticket_state $w"))
            assertEquals("42501", refused("$s status = 'resolved' $w"), "closed is final")
            assertEquals("42501", refused("$s requester_user_id = ${user("sr1001")} $w"))
            assertEquals("42501", refused("DELETE FROM app.support_ticket_state $w"))
            // Cancel from new and from in_progress; a cancelled ticket stays cancelled.
            for ((n, first) in listOf(202 to null, 203 to "in_progress")) {
                val u = uuid(n)
                exec(ticket(u, "tso1001"))
                exec(state(u, "tso1001"))
                if (first != null) exec("$s status = '$first' WHERE ticket_uuid = '$u'")
                exec("$s status = 'cancelled', cancelled_at = now(), cancelled_by = ${user("admin1001")}, cancel_reason = 'Duplicate of INC100001' WHERE ticket_uuid = '$u'")
                assertEquals("42501", refused("$s status = 'in_progress' WHERE ticket_uuid = '$u'"))
                assertEquals("42501", refused("$s status = 'closed', closed_at = now() WHERE ticket_uuid = '$u'"))
            }
            // new -> resolved directly (a desk answer without a start).
            exec(ticket(uuid(204), "tso1001"))
            exec(state(uuid(204), "tso1001"))
            exec("$s status = 'resolved', resolved_at = now(), resolution_code = 'resolved', resolution_notes = 'Done.' WHERE ticket_uuid = '${uuid(204)}'")
        }
    }

    @Test
    fun theStateAndTheThreadTakeTheTicketsOrigin() = db.connect().use { c ->
        assertEquals(
            listOf("support_ticket::::1", "support_ticket_event:support_ticket:ticket_uuid:client_uuid:2", "support_ticket_state:support_ticket:ticket_uuid:client_uuid:2"),
            c.column("SELECT concat_ws(':', table_name, coalesce(parent, ''), coalesce(child_col, ''), coalesce(parent_col, ''), pass) FROM app.data_origin_capture WHERE table_name LIKE 'support\\_ticket%'")
                .filterNotNull().sortedBy { it.substringBefore(':') },
        )
        c.tx {
            realUser("realtso", "TSO")
            realUser("realdesk", "SUPPORT")
            fun origin(table: String, col: String, u: String) = scalar("SELECT data_origin::text FROM app.$table WHERE $col = '$u'")
            // A test account's ticket is test, and so is the desk's real reply on it.
            exec(ticket(uuid(301), "tso1001"))
            exec(state(uuid(301), "tso1001"))
            exec(event(uuid(302), uuid(301), "realdesk", via = "web"))
            assertEquals("test", origin("support_ticket", "client_uuid", uuid(301)))
            assertEquals("test", origin("support_ticket_state", "ticket_uuid", uuid(301)))
            assertEquals("test", origin("support_ticket_event", "client_uuid", uuid(302)))
            // A real TSO's ticket is production; a reply by a test account makes that event test.
            exec(ticket(uuid(303), "realtso"))
            exec(state(uuid(303), "realtso"))
            exec(event(uuid(304), uuid(303), "realdesk", via = "web"))
            exec(event(uuid(305), uuid(303), "admin1001", via = "web"))
            assertEquals("production", origin("support_ticket", "client_uuid", uuid(303)))
            assertEquals("production", origin("support_ticket_state", "ticket_uuid", uuid(303)))
            assertEquals("production", origin("support_ticket_event", "client_uuid", uuid(304)))
            assertEquals("test", origin("support_ticket_event", "client_uuid", uuid(305)))
            // A payload naming its own origin is overwritten.
            exec(ticket(uuid(306), "realtso", ", data_origin", ", 'seed'"))
            assertEquals("production", origin("support_ticket", "client_uuid", uuid(306)))
            // The owner's restamp follows a re-tag to the ticket, its state and its thread.
            exec("UPDATE app.app_user SET data_origin = 'pilot' WHERE username = 'realtso'")
            exec("SELECT app.restamp_data_origin(NULL)")
            assertEquals("pilot|pilot|pilot", scalar("SELECT concat_ws('|', (SELECT data_origin FROM app.support_ticket WHERE client_uuid = '${uuid(303)}'), (SELECT data_origin FROM app.support_ticket_state WHERE ticket_uuid = '${uuid(303)}'), (SELECT data_origin FROM app.support_ticket_event WHERE client_uuid = '${uuid(304)}'))"))
            exec("SET LOCAL ROLE api_rw")
            assertEquals("42501", refused("UPDATE app.support_ticket_state SET data_origin = 'production' WHERE ticket_uuid = '${uuid(303)}'"))
            exec("RESET ROLE")
        }
    }

    @Test
    fun theIssueTypesAreThe31AnnexATypesVerbatim() = db.connect().use { c ->
        val annexA = listOf(
            "app_crash_order_submit" to "App crashes on order submit", "app_version_update" to "Application Version Update",
            "astha_gift_disbursement" to "Astha Gift Choice & Disbursement", "astha_target" to "Astha Target", "auto_logout" to "Auto Logout",
            "camera_black_screen" to "Camera Black Screen", "campaign" to "Campaign", "change_request" to "Change Request",
            "check_in_out" to "Check in Check out issue", "cluster_mapping" to "Cluster Mapping", "discount" to "Discount",
            "ff_geo_location_map" to "FF Geo Location Map", "final_submission" to "Final Submission", "gps_inaccuracy" to "GPS location inaccuracy",
            "login_otp" to "Login OTP issue", "memo_printing" to "Memo Printing issue", "new_application_needed" to "New Application Needed (Tso, Amo, SR)",
            "others" to "Others", "outlet_related" to "Outlet Related Issue", "outlet_request" to "Outlet request (Close, New, & Info Update)",
            "outlet_request_approval" to "Outlet Request Approval", "password_reset" to "Password Reset", "printer_connection" to "Printer Connection issue",
            "reporting" to "Reporting", "sales_adjustment" to "Sales Adjustment", "sales_order_failing" to "Sales order creation failing",
            "sales_submit" to "Sales Submit (Bikroy Joma)", "sync_stuck" to "Sync stuck / not updating", "target_panel" to "Target Panel",
            "user_login" to "User Login issue", "web_entry" to "Web Entry",
        )
        assertEquals(31, annexA.size)
        assertEquals(
            annexA.mapIndexed { i, (code, label) -> "${i + 1}|$code|$label" },
            c.column("SELECT sort || '|' || code || '|' || label_en FROM app.code_list_item WHERE list_key = 'ticket_issue_type' AND valid_to IS NULL ORDER BY sort"),
        )
        // D-TSO-MAN-19 attrs on every item; Bangla labels wait for AKTCL.
        assertEquals(
            "0",
            c.scalar(
                "SELECT count(*) FROM app.code_list_item WHERE list_key = 'ticket_issue_type' AND NOT (attrs ->> 'priority' = 'p1' AND attrs ->> 'category' = 'tickets' " +
                    "AND attrs ->> 'urgency' = 'high' AND (attrs ->> 'icon')::int = (sort - 1) % 8 AND jsonb_typeof(attrs -> 'icon') = 'number' " +
                    "AND length(attrs ->> 'hint_en') > 0 AND label_bn IS NULL AND valid_from <= $day)",
            ),
        )
        assertEquals("Nothing else fits.", c.scalar("SELECT attrs ->> 'hint_en' FROM app.code_list_item WHERE list_key = 'ticket_issue_type' AND code = 'others'"))
        assertEquals("0,1,2,3,4,5,6,7", c.scalar("SELECT string_agg(DISTINCT attrs ->> 'icon', ',') FROM app.code_list_item WHERE list_key = 'ticket_issue_type'"))
        assertEquals(listOf("resolved|Resolved"), c.column("SELECT code || '|' || label_en FROM app.code_list_item WHERE list_key = 'ticket_resolution_code'"))
        // D-TSO-MAN-36: Suggestion and Feedback (supersedes D-197's suggestion only).
        assertEquals(listOf("1|suggestion|Suggestion", "2|feedback|Feedback"), c.column("SELECT sort || '|' || code || '|' || label_en FROM app.code_list_item WHERE list_key = 'feedback_category' ORDER BY sort"))
        assertEquals("2", c.scalar("SELECT count(*) FROM app.code_list WHERE list_key IN ('ticket_issue_type', 'ticket_resolution_code')"))
        c.tx { assertEquals("23514", refused("INSERT INTO app.code_list (list_key) VALUES ('ticket_priority')")) }
    }

    @Test
    fun aJobFinalSubmitHasNoUserAndEveryOtherSubmitNamesOne() = db.connect().use { c ->
        assertEquals("YES", c.scalar("SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'app' AND table_name = 'final_submit' AND column_name = 'submitted_by'"))
        c.tx {
            fun submit(n: Int, by: String?, via: String, dayOffset: Int) =
                "INSERT INTO app.final_submit (client_uuid, zone_id, business_date, submitted_by, via) VALUES ('${uuid(n)}', $zone, $day - $dayOffset, ${by?.let(::user) ?: "NULL"}, '$via')"
            exec(submit(401, null, "job", 1))
            exec(submit(402, "tso1001", "app", 2))
            exec(submit(403, "tso1001", "web", 3))
            assertEquals("23514", refused(submit(404, null, "app", 4)), "an app submit names its TSO")
            assertEquals("23514", refused(submit(405, null, "web", 4)))
            assertEquals("23514", refused(submit(406, "tso1001", "job", 4)), "the job has no user")
            assertEquals("23514", refused(submit(407, "tso1001", "api", 4)))
            assertEquals("23505", refused(submit(408, null, "job", 1)), "one live submit per zone-day (final_submit_once)")
            assertEquals("job||1", scalar("SELECT first_via || '|' || coalesce(first_submitted_by::text, '') || '|' || submits FROM dw.v_final_submit_log WHERE zone_id = $zone AND business_date = $day - 1"))
        }
    }

    @Test
    fun theSyncTablesTakeTheDayLockAndTicketCodes() = db.connect().use { c ->
        c.tx {
            fun rejected(n: Int, code: String) =
                "INSERT INTO app.sync_rejected (client_uuid, record_type, code, retryable, payload_sha256, payload, user_id, business_date) " +
                    "VALUES ('${uuid(n)}', 'memo', '$code', false, decode(repeat('ab', 32), 'hex'), '{}', ${user("sr1001")}, $day)"
            fun quarantined(n: Int, code: String) =
                "INSERT INTO app.sync_quarantine (client_uuid, record_type, code, payload_sha256, payload, user_id, business_date) " +
                    "VALUES ('${uuid(n)}', 'memo', '$code', decode(repeat('cd', 32), 'hex'), '{}', ${user("sr1001")}, $day)"
            listOf("day_final_submitted", "ticket_state_invalid", "issue_type_unknown", "price_invalid", "retry_exhausted").forEachIndexed { i, code -> exec(rejected(501 + i, code)) }
            listOf("after_final_submit", "payload_conflict", "scope_out_of_reach", "programme_inactive").forEachIndexed { i, code -> exec(quarantined(511 + i, code)) }
            // The V0008 split stays: a quarantine code never lands in sync_rejected, nor the reverse.
            assertEquals("23514", refused(rejected(521, "after_final_submit")))
            assertEquals("23514", refused(quarantined(522, "day_final_submitted")))
            assertEquals("23514", refused(rejected(523, "ticket_closed")))
        }
    }

    @Test
    fun everyConstraintAddedNotValidIsValidated() = db.connect().use { c ->
        assertEquals(
            listOf(
                "app.code_list|code_list_list_key_check|t", "app.final_submit|final_submit_job_actor|t", "app.final_submit|final_submit_via_check|t",
                "app.sync_quarantine|sync_quarantine_code_check|t", "app.sync_rejected|sync_rejected_code_check|t", "app.visit_plan|visit_plan_route_id_fkey|t",
            ),
            c.column(
                "SELECT conrelid::regclass || '|' || conname || '|' || CASE WHEN convalidated THEN 't' ELSE 'f' END FROM pg_constraint " +
                    "WHERE conname IN ('code_list_list_key_check', 'final_submit_via_check', 'final_submit_job_actor', 'sync_rejected_code_check', " +
                    "'sync_quarantine_code_check', 'visit_plan_route_id_fkey')",
            ).filterNotNull().sorted(),
        )
    }

    @Test
    fun notificationsAreIdempotentAndReadOnce() = db.connect().use { c ->
        c.tx {
            fun note(key: String, kind: String = "ticket_updated", ref: String = "support_ticket", title: String = "Ticket INC100001 updated") =
                "INSERT INTO app.user_notification (user_id, kind, ref_type, ref_id, title_en, body_en, dedupe_key) " +
                    "VALUES (${user("tso1001")}, '$kind', '$ref', '${uuid(601)}', '$title', 'Support replied.', '$key')"
            exec(note("ticket:${uuid(601)}:a"))
            exec(note("ticket:${uuid(601)}:a") + " ON CONFLICT (dedupe_key) DO NOTHING")
            assertEquals("1", scalar("SELECT count(*) FROM app.user_notification WHERE dedupe_key = 'ticket:${uuid(601)}:a'"), "a second producer run adds nothing")
            assertEquals("23505", refused(note("ticket:${uuid(601)}:a")))
            assertEquals("23514", refused(note("k2", kind = "announcement")))
            assertEquals("23514", refused(note("k3", ref = "memo")))
            assertEquals("23514", refused(note("k4", title = "x".repeat(121))))
            exec(note("leave:${uuid(602)}", kind = "leave_decided", ref = "leave_application"))
            exec(note("zone:1:2026-10-25", kind = "final_submit_auto", ref = "zone_day"))
            assertEquals("t|3", scalar("SELECT bool_and(business_date = $day)::text::char || '|' || count(*) FILTER (WHERE read_at IS NULL) FROM app.user_notification WHERE user_id = ${user("tso1001")}"))
            exec("UPDATE app.user_notification SET read_at = now() WHERE user_id = ${user("tso1001")} AND read_at IS NULL")
            assertEquals("42501", refused("UPDATE app.user_notification SET read_at = now() + interval '1 minute' WHERE dedupe_key = 'k2' OR dedupe_key LIKE 'ticket:%'"), "read once")
            assertEquals("42501", refused("UPDATE app.user_notification SET body_en = 'Changed.' WHERE dedupe_key LIKE 'ticket:%'"))
            exec("DELETE FROM app.user_notification WHERE dedupe_key LIKE 'zone:%'")          // the prune job
            assertEquals("2", scalar("SELECT count(*) FROM app.user_notification WHERE user_id = ${user("tso1001")}"))
        }
        assertEquals(
            "api_rw:SELECT,INSERT,UPDATE|worker_rw:SELECT,INSERT,DELETE",
            c.scalar(
                "SELECT string_agg(r || ':' || (SELECT string_agg(p, ',' ORDER BY o) FROM unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE']) WITH ORDINALITY x(p, o) " +
                    "WHERE has_table_privilege(r, 'app.user_notification', p)), '|' ORDER BY r) FROM unnest(ARRAY['api_rw','worker_rw']) r",
            ),
        )
        // The midnight job may insert its Final Submit; the audit trail stays closed to the worker (DbRolesTest).
        assertEquals("t|f", c.scalar("SELECT has_table_privilege('worker_rw', 'app.final_submit', 'INSERT')::text::char || '|' || has_table_privilege('worker_rw', 'app.audit_log', 'INSERT')::text::char"))
    }

    @Test
    fun theBuildsSettingsPermissionAndEventsAreRegistered() = db.connect().use { c ->
        assertEquals(
            listOf(
                "cfg.day.auto_final_submit_enabled|bool|true|3|global,wing|cfg.edit.field",
                "cfg.feedback.max_images|int|1|1|global|cfg.edit.ops",
                "cfg.kpi.bar_bands|list|[40, 80]|1|global|cfg.edit.field",
                "cfg.kpi.brand_cpr_denominator.tso|enum|\"successful_calls\"|1|global|cfg.edit.field",
                "cfg.kpi.tilldate_basis.amo_team|enum|\"calendar_days\"|1|global|cfg.edit.field",
                "cfg.kpi.tilldate_basis.tso|enum|\"calendar_days\"|1|global|cfg.edit.field",
                "cfg.leave.allow_ahead_days|int|365|1|global|cfg.edit.field",
                "cfg.leave.allow_past_days|int|31|1|global|cfg.edit.field",
                "cfg.leave.block_overlap|bool|false|1|global|cfg.edit.field",
                "cfg.notify.inbox_retention_days|int|90|1|global|cfg.edit.ops",
                "cfg.notify.ticket_push_enabled|bool|true|1|global|cfg.edit.ops",
                "cfg.support.ticket_max_photos|int|3|1|global|cfg.edit.ops",
                "cfg.support.ticket_requester_roles|list|[\"TSO\"]|2|global|cfg.edit.security",
                "cfg.tso.plan_max_days_ahead|int|60|1|global|cfg.edit.field",
                "cfg.tso.team_location_max_age_min|int|120|1|global|cfg.edit.field",
            ),
            c.column(
                "SELECT concat_ws('|', key, value_type, default_value::text, risk_class, array_to_string(scope_levels, ','), editor_permission) FROM app.cfg_key " +
                    "WHERE key IN ('cfg.kpi.tilldate_basis.tso', 'cfg.kpi.tilldate_basis.amo_team', 'cfg.kpi.brand_cpr_denominator.tso', 'cfg.kpi.bar_bands', " +
                    "'cfg.support.ticket_requester_roles', 'cfg.support.ticket_max_photos', 'cfg.notify.ticket_push_enabled', 'cfg.notify.inbox_retention_days', " +
                    "'cfg.day.auto_final_submit_enabled', 'cfg.tso.team_location_max_age_min', 'cfg.tso.plan_max_days_ahead', 'cfg.leave.allow_past_days', " +
                    "'cfg.leave.allow_ahead_days', 'cfg.leave.block_overlap', 'cfg.feedback.max_images')",
            ).filterNotNull().sorted(),
        )
        assertEquals(
            listOf(
                "cfg.kpi.brand_cpr_denominator.tso=successful_calls,target_outlets",
                "cfg.kpi.tilldate_basis.amo_team=calendar_days,working_days",
                "cfg.kpi.tilldate_basis.tso=calendar_days,working_days",
            ),
            c.column("SELECT key || '=' || (SELECT string_agg(e, ',' ORDER BY n) FROM jsonb_array_elements_text(bounds -> 'enum') WITH ORDINALITY x(e, n)) FROM app.cfg_key WHERE value_type = 'enum' AND key LIKE 'cfg.kpi.%'")
                .filterNotNull().sorted(),
        )
        // Each new enum default is one of its values; each new int default lies within its bounds; each list fits max_items.
        val mine = "key IN (SELECT key FROM app.cfg_key WHERE key LIKE 'cfg.kpi.%' OR key LIKE 'cfg.support.ticket_%' OR key LIKE 'cfg.notify.ticket_%' " +
            "OR key = 'cfg.notify.inbox_retention_days' OR key = 'cfg.day.auto_final_submit_enabled' OR key IN ('cfg.tso.team_location_max_age_min', 'cfg.tso.plan_max_days_ahead') " +
            "OR key LIKE 'cfg.leave.%' OR key = 'cfg.feedback.max_images')"
        assertEquals("15", c.scalar("SELECT count(*) FROM app.cfg_key WHERE $mine"))
        assertEquals("0", c.scalar("SELECT count(*) FROM app.cfg_key WHERE $mine AND value_type = 'enum' AND NOT ((bounds -> 'enum') @> jsonb_build_array(default_value))"))
        assertEquals("0", c.scalar("SELECT count(*) FROM app.cfg_key WHERE $mine AND value_type = 'int' AND ((default_value #>> '{}')::numeric < (bounds ->> 'min')::numeric OR (default_value #>> '{}')::numeric > (bounds ->> 'max')::numeric)"))
        assertEquals("0", c.scalar("SELECT count(*) FROM app.cfg_key WHERE $mine AND value_type = 'list' AND jsonb_array_length(default_value) > (bounds ->> 'max_items')::int"))
        assertEquals("0", c.scalar("SELECT count(*) FROM app.cfg_key WHERE $mine AND (length(description_en) = 0 OR restrictive_dir <> 'none' OR effect <> 'B' OR requires_ack)"))
        assertEquals("\"quarantine\"", c.scalar("SELECT default_value::text FROM app.cfg_key WHERE key = 'cfg.day.late_sync_after_final_policy'"))
        assertEquals("[\"ADMIN\", \"SUPERADMIN\"]", c.scalar("SELECT default_value::text FROM app.cfg_key WHERE key = 'cfg.day.reopen_roles'"))
        assertEquals(listOf("ADMIN", "SUPERADMIN"), c.column("SELECT role FROM app.role_grant_map WHERE permission = 'support.ticket.manage'").filterNotNull().sorted())
        assertEquals(
            listOf(
                "leave.decided|1|leave_application|backend:masterdata|planned|V0134|f",
                "support_ticket.commented|1|support_ticket|backend:masterdata|planned|V0134|f",
                "support_ticket.created|1|support_ticket|backend:masterdata|planned|V0134|f",
                "support_ticket.status_changed|1|support_ticket|backend:masterdata|planned|V0134|f",
                "zone_day.final_submitted|1|zone_day|backend:sync|planned|V0134|f",
                "zone_day.reopened|1|zone_day|backend:sync|planned|V0134|f",
            ),
            c.column(
                "SELECT concat_ws('|', event_type, payload_version, aggregate_type, producer, status, introduced_in, CASE WHEN enforce_required THEN 't' ELSE 'f' END) " +
                    "FROM app.domain_event_type WHERE introduced_in = 'V0134'",
            ).filterNotNull().sorted(),
        )
        // Thin payloads: no free text keys (title, description, body, notes, reason, name).
        assertEquals("0", c.scalar("SELECT count(*) FROM app.domain_event_type t, jsonb_object_keys(t.payload_schema -> 'properties') k WHERE t.introduced_in = 'V0134' AND k ~ '(title|description|body|note|reason|name)'"))
        c.tx {
            exec("GRANT api_rw TO CURRENT_USER WITH INHERIT FALSE, SET TRUE")
            exec("SET ROLE api_rw")
            exec(
                "INSERT INTO app.domain_event (event_type, payload_version, aggregate_type, aggregate_id, business_date, payload) VALUES " +
                    "('zone_day.final_submitted', 1, 'zone_day', '7:2026-10-25', DATE '2026-10-25', " +
                    "'{\"zone_id\":7,\"business_date\":\"2026-10-25\",\"final_submit_uuid\":\"${uuid(701)}\",\"via\":\"job\",\"submitted_by\":null}')",
            )
            exec("RESET ROLE")
        }
    }

    @Test
    fun theVisitPlanKeepsItsRouteAndTheVisitQueryHasItsQuestions() = db.connect().use { c ->
        assertEquals(
            "bigint|YES|app.route",
            c.scalar(
                "SELECT data_type || '|' || is_nullable || '|' || (SELECT confrelid::regclass::text FROM pg_constraint WHERE conname = 'visit_plan_route_id_fkey') " +
                    "FROM information_schema.columns WHERE table_schema = 'app' AND table_name = 'visit_plan' AND column_name = 'route_id'",
            ),
        )
        c.tx {
            fun plan(n: Int, routeExpr: String) =
                "INSERT INTO app.visit_plan (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, plan_date, route_id) " +
                    "VALUES ('${uuid(n)}', '${uuid(n)}', $day, ${user("tso1001")}, now(), 0, $day + 1, $routeExpr)"
            exec(plan(801, route("MIR-SR-D")))
            assertEquals("23503", refused(plan(802, "-1")))
            assertEquals("42501", refused("UPDATE app.visit_plan SET route_id = NULL WHERE client_uuid = '${uuid(801)}'"), "the route is written once, with the plan")
        }
        assertEquals(
            "retailer_questionnaire|1|active|2|sr_visits_regularly,sr_prints_memo|text,text",
            c.scalar(
                "SELECT concat_ws('|', r.kind, r.version, r.status, jsonb_array_length(v.criteria), " +
                    "(SELECT string_agg(e ->> 'key', ',' ORDER BY (e ->> 'criterion_id')::int) FROM jsonb_array_elements(v.criteria) e), " +
                    "(SELECT string_agg(e ->> 'answer_type', ',' ORDER BY (e ->> 'criterion_id')::int) FROM jsonb_array_elements(v.criteria) e)) " +
                    "FROM app.rubric r JOIN app.rubric_version v ON v.rubric_id = r.id AND v.version = r.version WHERE r.kind = 'retailer_questionnaire'",
            ),
        )
        assertEquals(
            listOf("1|SR আপনার দোকান নিয়মিত ভিজিট করে?|Does the SR visit your shop regularly?", "2|SR নিয়মিত মেমো প্রিন্ট করে দেয়?|Does the SR give you a printed memo regularly?"),
            c.column(
                "SELECT concat_ws('|', e ->> 'criterion_id', e ->> 'label_bn', e ->> 'label_en') FROM app.rubric r JOIN app.rubric_version v ON v.rubric_id = r.id, " +
                    "jsonb_array_elements(v.criteria) e WHERE r.kind = 'retailer_questionnaire' ORDER BY 1",
            ),
        )
    }

    @Test
    fun theTsoViewsShowTheRowsWithoutFreeText() = db.connect().use { c ->
        val views = listOf("v_support_ticket", "v_leave_application", "v_feedback", "v_visit_plan_outlet", "v_call_assessment", "v_task")
        assertEquals("1.3", c.scalar("SELECT dw.view_contract_version()"))
        for (v in views) {
            val cols = c.column("SELECT column_name FROM information_schema.columns WHERE table_schema = 'dw' AND table_name = '$v' ORDER BY ordinal_position").filterNotNull()
            assertEquals(listOf("data_origin", "is_effective"), cols.takeLast(2), v)
            assertEquals(emptyList(), cols.filter { Regex("(title|description|reason|note|body|username|name)").containsMatchIn(it) }, v)
            assertEquals("t|t|t", c.scalar("SELECT concat_ws('|', has_table_privilege('web_ro', 'dw.$v', 'SELECT')::text::char, has_table_privilege('bi_reader', 'dw.$v', 'SELECT')::text::char, has_table_privilege('api_rw', 'dw.$v', 'SELECT')::text::char)"), v)
            assertTrue(c.scalar("SELECT obj_description('dw.$v'::regclass)")!!.endsWith("pii: none"), v)
        }
        assertTrue(c.scalar("SELECT obj_description('dw.v_calendar'::regclass)")!!.contains("scoped holidays and a configured weekend are applied by the API (WorkingDays"))
        c.tx {
            val env = "family_uuid, business_date, user_id, captured_at, config_version"
            fun envVals(n: Int, by: String = "tso1001") = "'${uuid(n)}', $day, ${user(by)}, now(), 0"
            // Ticket with an affected user, resolved.
            exec(ticket(uuid(901), "tso1001", ", affected_username", ", 'sr1001'"))
            exec(state(uuid(901), "tso1001"))
            exec("UPDATE app.support_ticket_state SET status = 'resolved', resolved_at = now(), resolution_code = 'resolved', resolution_notes = 'Done.' WHERE ticket_uuid = '${uuid(901)}'")
            assertEquals(
                "sync_stuck|quick|p1|resolved|resolved|t|TSO|test|t",
                scalar("SELECT concat_ws('|', issue_type_code, source, priority, status, resolution_code, has_affected_user, requester_role, data_origin, is_effective) FROM dw.v_support_ticket WHERE ticket_uuid = '${uuid(901)}'"),
            )
            assertTrue(ticketNo.matches(scalar("SELECT ticket_no FROM dw.v_support_ticket WHERE ticket_uuid = '${uuid(901)}'")!!))
            // Leave.
            exec("INSERT INTO app.leave_application (client_uuid, $env, leave_type_code, from_date, days, reason) VALUES ('${uuid(902)}', ${envVals(902)}, 'casual', $day + 3, 2, 'Family event')")
            assertEquals(
                "casual|2|pending|t|t",
                scalar("SELECT concat_ws('|', leave_type_code, days, status, to_date = from_date + 1, is_effective) FROM dw.v_leave_application WHERE leave_uuid = '${uuid(902)}'"),
            )
            // Feedback: no inbox row reads new; a photo counts.
            exec("INSERT INTO app.feedback (client_uuid, $env, category_code, title, description, photo_uuid) VALUES ('${uuid(903)}', ${envVals(903)}, 'feedback', 'Idea', 'Bigger buttons', '${uuid(904)}')")
            assertEquals("feedback|new|t", scalar("SELECT concat_ws('|', category_code, status, has_photo) FROM dw.v_feedback WHERE feedback_uuid = '${uuid(903)}'"))
            exec("INSERT INTO app.feedback_status (feedback_client_uuid, status) VALUES ('${uuid(903)}', 'in_progress')")
            assertEquals("in_progress", scalar("SELECT status FROM dw.v_feedback WHERE feedback_uuid = '${uuid(903)}'"))
            // Visit plans: one with its Set Plan route, one stored before V0137 (route from its outlets); a Visit Query completes an outlet.
            val outlets = column("SELECT id::text FROM app.outlet WHERE route_id = ${route("MIR-SR-D")} ORDER BY id LIMIT 2").filterNotNull()
            exec("INSERT INTO app.visit_plan (client_uuid, $env, plan_date, route_id) VALUES ('${uuid(905)}', ${envVals(905)}, $day + 1, ${route("MIR-SR-D")})")
            exec("INSERT INTO app.visit_plan (client_uuid, $env, plan_date) VALUES ('${uuid(906)}', ${envVals(906)}, $day + 2)")
            exec("INSERT INTO app.visit_plan_outlet (client_uuid, $env, plan_client_uuid, outlet_id) VALUES ('${uuid(907)}', ${envVals(907)}, '${uuid(905)}', ${outlets[0]}), ('${uuid(908)}', ${envVals(908)}, '${uuid(905)}', ${outlets[1]}), ('${uuid(909)}', ${envVals(909)}, '${uuid(906)}', ${outlets[0]})")
            val rubric = scalar("SELECT id FROM app.rubric WHERE kind = 'retailer_questionnaire'")
            exec("INSERT INTO app.call_assessment (client_uuid, $env, route_id, kind, visit_plan_outlet_client_uuid, rubric_id, rubric_version, total_score, max_score, delegate_task) VALUES ('${uuid(910)}', ${envVals(910)}, ${route("MIR-SR-D")}, 'retailer_questionnaire', '${uuid(907)}', $rubric, 1, 0, 0, false)")
            assertEquals(
                listOf("${outlets[0]}|${uuid(910)}|t", "${outlets[1]}||t"),
                column("SELECT concat_ws('|', outlet_id, coalesce(assessment_uuid::text, ''), CASE WHEN route_id = ${route("MIR-SR-D")} THEN 't' ELSE 'f' END) FROM dw.v_visit_plan_outlet WHERE plan_uuid = '${uuid(905)}' ORDER BY outlet_id"),
            )
            assertEquals("t|t", scalar("SELECT concat_ws('|', (completed_at IS NOT NULL)::text::char, (route_id = (SELECT route_id FROM app.outlet WHERE id = ${outlets[0]}))::text::char) FROM dw.v_visit_plan_outlet WHERE plan_uuid = '${uuid(905)}' AND outlet_id = ${outlets[0]}"))
            assertEquals(scalar("SELECT route_id::text FROM app.outlet WHERE id = ${outlets[0]}"), scalar("SELECT route_id::text FROM dw.v_visit_plan_outlet WHERE plan_uuid = '${uuid(906)}'"), "a plan before V0137 takes its outlets' smallest route")
            assertEquals(
                "retailer_questionnaire|${outlets[0]}|1|f|t",
                scalar("SELECT concat_ws('|', kind, outlet_id, rubric_version, delegate_task, is_effective) FROM dw.v_call_assessment WHERE assessment_uuid = '${uuid(910)}'"),
            )
            exec("UPDATE app.visit_plan SET voided_at = now() WHERE client_uuid = '${uuid(906)}'")
            assertEquals("f", scalar("SELECT is_effective::text::char FROM dw.v_visit_plan_outlet WHERE plan_uuid = '${uuid(906)}'"), "a voided plan voids its outlets")
            // Task assigned by the TSO to the SR on a route.
            exec("INSERT INTO app.task (client_uuid, $env, task_type_code, assignee_user_id, outlet_id, title, route_id) VALUES ('${uuid(911)}', ${envVals(911)}, 'general', ${user("sr1001")}, ${outlets[0]}, 'Check the display', ${route("MIR-SR-D")})")
            assertEquals(
                "general|ongoing|t|t|t",
                scalar("SELECT concat_ws('|', task_type_code, status, (creator_user_id = ${user("tso1001")})::text::char, (route_id = ${route("MIR-SR-D")})::text::char, is_effective) FROM dw.v_task WHERE task_uuid = '${uuid(911)}'"),
            )
        }
    }
}

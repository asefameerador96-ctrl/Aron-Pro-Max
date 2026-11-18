package com.aktcl.aron.db

import org.flywaydb.core.Flyway
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * V0203 to V0206 (round 1.20.0, D-SV2-15 and D-SV2-16): `attendance_event.closed_by` and the memo username widening to three characters.
 * The first test migrates a database that holds data to V0191, adds a check-in, a check-out, a 4-character SR and a sale abort, then applies the round and
 * checks that every old row survives with the default and that all five constraints end up validated. The second checks what the new column and the widened CHECKs accept and refuse.
 */
class CheckoutClosedByV0203Test {
    private val day = "DATE '2026-10-05'" // date-pin-ok: a fixed day inside the migrated partitions; the test reads no clock

    private fun attendance(kind: String, user: String, closedBy: String? = null) =
        "INSERT INTO app.attendance_event (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, kind, fix_status${if (closedBy != null) ", closed_by" else ""}) " +
            "SELECT gen_random_uuid(), gen_random_uuid(), $day, id, TIMESTAMPTZ '2026-10-05 08:00+00', 0, '$kind', 'provider_unavailable'${if (closedBy != null) ", '$closedBy'" else ""} " + // date-pin-ok: fixed instant of the fixture
            "FROM app.app_user WHERE username = '$user'"

    @Test
    fun theRoundAppliesOnADatabaseWithDataAndEveryOldRowKeepsTheDefault() {
        val old = TestPostgres.createDatabase()
        try {
            Flyway.configure().configuration(old.flyway().configuration).target("191").load().migrate()
            old.connect().use { c ->
                SeedLoader.load(c)
                c.exec("UPDATE app.app_user SET username = 'srab1' WHERE username = 'sr1001'")
                c.exec(attendance("check_in", "srab1"))
                c.exec(attendance("check_out", "srab1"))
                assertEquals("0", c.scalar("SELECT count(*) FROM information_schema.columns WHERE table_name = 'attendance_event' AND column_name = 'closed_by'"))
                // Before the round a 3-character SR login is refused.
                assertFailsWith<SQLException> { c.exec("UPDATE app.app_user SET username = 'abc' WHERE username = 'srab1'") }
            }
            old.flyway().migrate()
            old.connect().use { c ->
                assertEquals("2|2", c.scalar("SELECT count(*) || '|' || count(*) FILTER (WHERE closed_by = 'rep') FROM app.attendance_event"), "old rows read as the rep's")
                assertEquals("5|5", c.scalar(
                    "SELECT count(*) || '|' || count(*) FILTER (WHERE convalidated) FROM pg_constraint WHERE conname IN ('attendance_event_closed_by_check', 'app_user_memo_username') " +
                        "OR (conname LIKE '%memo_no_check' AND conrelid IN ('app.memo'::regclass, 'app.memo_void'::regclass, 'app.sale_abort'::regclass))",
                ), "the five widened or new CHECKs are validated")
                // The four-character SR of before still stands; a three-character one now can exist.
                c.exec("UPDATE app.app_user SET username = 'abc' WHERE username = 'srab1'")
            }
        } finally {
            old.close()
        }
    }

    /** The widened CHECKs are NOT VALID after V0204 (also on the PARTITIONED memo parent) and validated by V0206; the lock scope of each migration is its own tables only. */
    @Test
    fun theWideningIsNotValidAfterV0204OnThePartitionedMemoTableAndValidAfterV0206() {
        val db = TestPostgres.createDatabase()
        try {
            Flyway.configure().configuration(db.flyway().configuration).target("204").load().migrate()
            db.connect().use { c ->
                val states = "SELECT string_agg(conrelid::regclass::text || ':' || convalidated::text, ',' ORDER BY conrelid::regclass::text) FROM pg_constraint WHERE conname IN " +
                    "('attendance_event_closed_by_check', 'app_user_memo_username', 'memo_memo_no_check', 'memo_void_memo_no_check', 'sale_abort_memo_no_check') AND conrelid IN " +
                    "('app.attendance_event'::regclass, 'app.app_user'::regclass, 'app.memo'::regclass, 'app.memo_void'::regclass, 'app.sale_abort'::regclass)"
                assertEquals("app.app_user:false,app.attendance_event:false,app.memo:false,app.memo_void:false,app.sale_abort:false", c.scalar(states))
                assertEquals("true", c.scalar("SELECT (count(*) > 0)::text FROM pg_inherits WHERE inhparent = 'app.memo'::regclass"), "memo is partitioned")
                assertEquals("true", c.scalar("SELECT (pg_get_constraintdef(oid) LIKE '%{2,31}%')::text FROM pg_constraint WHERE conrelid = 'app.memo'::regclass AND conname = 'memo_memo_no_check'"))
                // A NOT VALID check already judges new rows.
                assertFailsWith<SQLException> { c.exec("INSERT INTO app.app_user (username, full_name, role) VALUES ('ab', 'QA', 'SR')") }
                c.exec("INSERT INTO app.app_user (username, full_name, role) VALUES ('abc', 'QA', 'SR')")
            }
            db.flyway().migrate()
            db.connect().use { c ->
                // V0205 (the index swap) is idempotent: run its text again on the migrated database, nothing changes and nothing fails (a hand re-run, a pre-created index).
                val swap = java.io.File(System.getProperty("aron.migrations"), "V0205__attendance_once_rep_and_phone.sql").readText()
                c.exec(swap); c.exec(swap)
                assertEquals("attendance_event_once_phone,attendance_event_once_rep", c.scalar("SELECT string_agg(indexname, ',' ORDER BY indexname) FROM pg_indexes WHERE tablename = 'attendance_event' AND indexname LIKE 'attendance_event_once%'"))
                assertEquals("5", c.scalar("SELECT count(*) FROM pg_constraint WHERE convalidated AND conname IN ('attendance_event_closed_by_check', 'app_user_memo_username', 'memo_memo_no_check', 'memo_void_memo_no_check', 'sale_abort_memo_no_check') AND conrelid IN ('app.attendance_event'::regclass, 'app.app_user'::regclass, 'app.memo'::regclass, 'app.memo_void'::regclass, 'app.sale_abort'::regclass)"))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun theColumnAndTheWidenedChecksAcceptAndRefuseWhatTheyShould() {
        val db = TestPostgres.createDatabase().migrated()
        try {
            db.connect().use { c ->
                SeedLoader.load(c)
                // closed_by: rep or phone, and phone only on a check-out.
                c.exec(attendance("check_in", "sr1001"))
                assertEquals("rep", c.scalar("SELECT closed_by FROM app.attendance_event WHERE kind = 'check_in'"), "the default")
                c.exec(attendance("check_out", "sr1001", "phone"))
                assertEquals("phone", c.scalar("SELECT closed_by FROM app.attendance_event WHERE kind = 'check_out'"))
                // Uniqueness (V0205): one rep row per user, day and kind; one phone close per user and day; a phone close and a rep check-out of the same day coexist.
                c.exec(attendance("check_out", "sr1001", "rep"))
                assertEquals("phone,rep", c.scalar("SELECT string_agg(closed_by, ',' ORDER BY closed_by) FROM app.attendance_event WHERE kind = 'check_out'"))
                assertFailsWith<SQLException>("a second rep check-out") { c.exec(attendance("check_out", "sr1001", "rep")) }
                assertFailsWith<SQLException>("a second phone close") { c.exec(attendance("check_out", "sr1001", "phone")) }
                assertFailsWith<SQLException>("a second check-in") { c.exec(attendance("check_in", "sr1001")) }
                assertEquals("attendance_event_once_phone,attendance_event_once_rep", c.scalar("SELECT string_agg(indexname, ',' ORDER BY indexname) FROM pg_indexes WHERE tablename = 'attendance_event' AND indexname LIKE 'attendance_event_once%'"), "the old index is gone")
                assertFailsWith<SQLException>("a check-in is always the rep's") { c.exec(attendance("check_in", "sr1001", "phone")) }
                assertFailsWith<SQLException>("only rep or phone") { c.exec(attendance("check_out", "sr1001", "server")) }
                // memo usernames: 3 to 32 characters for SR and AMO.
                for (ok in listOf("abc", "a1b", "a" + "b".repeat(31))) c.exec("INSERT INTO app.app_user (username, full_name, role) VALUES ('$ok', 'QA', 'SR')")
                for (bad in listOf("ab", "1ab", "a" + "b".repeat(32))) assertFailsWith<SQLException>(bad) { c.exec("INSERT INTO app.app_user (username, full_name, role) VALUES ('$bad', 'QA', 'AMO')") }
                // The three memo_no CHECKs say {2,31} (and partitions inherit the memo one).
                for (t in listOf("memo", "memo_void", "sale_abort")) {
                    val def = c.scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'app.$t'::regclass AND conname = '${t}_memo_no_check'")!!
                    assertEquals(true, def.contains("{2,31}"), "$t: $def")
                }
                assertEquals("1", c.scalar("SELECT count(*) FROM pg_constraint WHERE conrelid = 'app.memo_y2026m10'::regclass AND conname = 'memo_memo_no_check' AND pg_get_constraintdef(oid) LIKE '%{2,31}%'"))
            }
        } finally {
            db.close()
        }
    }
}

package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V0152 / V0153 (discount portal schema, D-DISC-API-01): the offer status CHECK with draft, the version request UUID, the
 * immutable versions and the append-only status trail, the grants of the API role, and the two dw views (view contract 1.6 at V0152; the test checks 1.7 since V0162).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiscountPortalV0152Test {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
        db.connect().use { SeedLoader.load(it) }
    }

    @AfterAll
    fun tearDown() = db.close()

    private fun Connection.refused(sql: String): String {
        exec("SAVEPOINT r")
        val e = assertFailsWith<SQLException>(sql) { exec(sql) }
        exec("ROLLBACK TO SAVEPOINT r")
        return e.sqlState
    }

    private fun Connection.offer(code: String, status: String = "draft"): String {
        val id = scalar("INSERT INTO app.offer (code, group_code, status) VALUES ('$code', 'CAMP-1', '$status') RETURNING id")!!
        return id
    }

    private fun Connection.version(offerId: String, no: Int, uuid: String?, type: String = "amount_per_unit"): String =
        scalar(
            "INSERT INTO app.offer_version (offer_id, version_no, offer_type, level, title_en, valid_from, valid_to, discount_mtk_per_unit, " +
                "stacking, change_reason, request_uuid) VALUES ($offerId, $no, '$type', 'line', 'T$no', CURRENT_DATE, CURRENT_DATE + 30, 500, " +
                "'exclusive', 'created for the test', ${uuid?.let { "'$it'" } ?: "NULL"}) RETURNING id",
        )!!

    @Test
    fun theStatusAcceptsDraftActiveInactiveAndNothingElseAndTheCheckIsValidated() = db.connect().use { c ->
        c.autoCommit = false
        try {
            assertEquals("draft", c.scalar("SELECT column_default FROM information_schema.columns WHERE table_schema='app' AND table_name='offer' AND column_name='status'")!!.substringBefore("::").trim('\''))
            c.offer("D1"); c.offer("A1", "active"); c.offer("I1", "inactive")
            assertEquals("23514", c.refused("INSERT INTO app.offer (code, group_code, status) VALUES ('X1', 'G', 'ended')"))
            assertEquals("1", c.scalar("SELECT count(*) FROM pg_constraint WHERE conname = 'offer_status_check' AND convalidated"))
        } finally { c.rollback(); c.autoCommit = true }
    }

    @Test
    fun aVersionIsImmutableAndItsRequestUuidIsUnique() = db.connect().use { c ->
        c.autoCommit = false
        try {
            val o = c.offer("V1")
            val v1 = c.version(o, 1, "c0ffee00-0000-4000-8000-0000000d0001")
            assertEquals("23505", c.refused("INSERT INTO app.offer_version (offer_id, version_no, offer_type, level, title_en, valid_from, valid_to, discount_mtk_per_unit, stacking, request_uuid) " +
                "VALUES ($o, 2, 'amount_per_unit', 'line', 'dup', CURRENT_DATE, CURRENT_DATE + 30, 500, 'exclusive', 'c0ffee00-0000-4000-8000-0000000d0001')"))
            c.version(o, 2, null); c.version(o, 3, null) // null request UUIDs never collide
            assertEquals("42501", c.refused("UPDATE app.offer_version SET title_en = 'changed' WHERE id = $v1"))
            assertEquals("42501", c.refused("DELETE FROM app.offer_version WHERE id = $v1"))
        } finally { c.rollback(); c.autoCommit = true }
    }

    @Test
    fun theStatusTrailIsAppendOnlyWithUniqueRequestUuidAndAMandatoryReason() = db.connect().use { c ->
        c.autoCommit = false
        try {
            val o = c.offer("S1")
            val actor = c.scalar("SELECT id FROM app.app_user WHERE username = 'sr1001'")
            val ins = { uuid: String, from: String, to: String, reason: String ->
                "INSERT INTO app.offer_status_change (offer_id, from_status, to_status, request_uuid, reason, actor) VALUES ($o, '$from', '$to', '$uuid', '$reason', $actor)"
            }
            c.exec(ins("c0ffee00-0000-4000-8000-0000000d0101", "draft", "active", "approved by the owner"))
            assertEquals("23505", c.refused(ins("c0ffee00-0000-4000-8000-0000000d0101", "active", "inactive", "same uuid again!!")))
            assertEquals("23514", c.refused(ins("c0ffee00-0000-4000-8000-0000000d0102", "draft", "active", "short")), "reason under 10 characters")
            assertEquals("23514", c.refused(ins("c0ffee00-0000-4000-8000-0000000d0103", "active", "active", "no move at all")), "from = to")
            assertEquals("23514", c.refused(ins("c0ffee00-0000-4000-8000-0000000d0104", "draft", "ended", "an unknown status")))
            assertEquals("42501", c.refused("UPDATE app.offer_status_change SET reason = 'rewritten for the test' WHERE offer_id = $o"))
            assertEquals("42501", c.refused("DELETE FROM app.offer_status_change WHERE offer_id = $o"))
        } finally { c.rollback(); c.autoCommit = true }
    }

    @Test
    fun theGrantMapLetsTheApiRoleInsertVersionsAndTrailRowsAndUpdateOnlyTheOfferRow() = db.connect().use { c ->
        // The grants themselves (not just what one statement happens to do): INSERT yes, UPDATE/DELETE/TRUNCATE no on the two append-only tables.
        fun priv(role: String, table: String, p: String) = c.scalar("SELECT has_table_privilege('$role', '$table', '$p')::text")
        for (t in listOf("app.offer_version", "app.offer_status_change")) {
            assertEquals("true", priv("api_rw", t, "INSERT"), "$t insert")
            assertEquals("true", priv("api_rw", t, "SELECT"), "$t select")
            for (p in listOf("UPDATE", "DELETE", "TRUNCATE")) assertEquals("false", priv("api_rw", t, p), "$t $p")
        }
        assertEquals("true", priv("api_rw", "app.offer", "UPDATE"), "the offer row itself still takes status and current_version_id")
        assertEquals("false", priv("api_rw", "app.offer", "DELETE"))
        assertEquals("false", priv("worker_rw", "app.offer_status_change", "INSERT"), "only the API writes the trail")
        c.autoCommit = false
        try {
            val o = c.offer("G1")
            val actor = c.scalar("SELECT id FROM app.app_user WHERE username = 'sr1001'")
            c.exec("SET LOCAL ROLE api_rw")
            c.exec("UPDATE app.offer SET group_code = 'CAMP-2' WHERE id = $o")
            c.version(o, 1, null)
            c.exec("INSERT INTO app.offer_status_change (offer_id, from_status, to_status, request_uuid, reason, actor) VALUES ($o, 'draft', 'active', 'c0ffee00-0000-4000-8000-0000000d0201', 'approved by the owner', $actor)")
            assertEquals("42501", c.refused("UPDATE app.offer_version SET title_en = 'x' WHERE offer_id = $o"))
            assertEquals("42501", c.refused("UPDATE app.offer_status_change SET reason = 'x' WHERE offer_id = $o"))
            assertEquals("42501", c.refused("DELETE FROM app.offer WHERE id = $o"))
            c.exec("RESET ROLE")
        } finally { c.rollback(); c.autoCommit = true }
    }

    @Test
    fun truncateIsRefusedOnTheAppendOnlyTablesEvenForTheOwner() = db.connect().use { c ->
        c.autoCommit = false
        try {
            // The statement triggers are what refuse it (the owner holds the privilege), and the PUBLIC grant is gone as well.
            assertEquals("2", c.scalar("SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal AND tgname IN ('offer_status_change_no_truncate', 'offer_version_no_truncate') AND (tgtype & 32) = 32"))
            val e1 = assertFailsWith<SQLException> { c.exec("TRUNCATE app.offer_status_change") }
            assertEquals("42501", e1.sqlState); assertTrue("append-only" in e1.message.orEmpty() && "offer_status_change" in e1.message.orEmpty(), e1.message)
            c.rollback()
            // Without CASCADE the foreign keys onto app.offer_version refuse first (0A000), which would hide the guard; CASCADE gets past them,
            // so the refusal below is the offer_version_no_truncate trigger's own message.
            val e2 = assertFailsWith<SQLException> { c.exec("TRUNCATE app.offer_version CASCADE") }
            assertEquals("42501", e2.sqlState); assertTrue("app.offer_version is append-only" in e2.message.orEmpty(), e2.message)
        } finally { c.rollback(); c.autoCommit = true }
    }

    @Test
    fun theDatabaseRefusesAnyStatusMoveButDraftActiveInactiveAndALateVersionLink() = db.connect().use { c ->
        c.autoCommit = false
        try {
            val o = c.offer("LC1"); val v1 = c.version(o, 1, null); val v2 = c.version(o, 2, null)
            c.exec("UPDATE app.offer SET current_version_id = $v1 WHERE id = $o")
            c.exec("UPDATE app.offer SET current_version_id = $v2 WHERE id = $o")          // a draft may take a newer version
            assertEquals("23514", c.refused("UPDATE app.offer SET status = 'inactive' WHERE id = $o"), "draft -> inactive")
            c.exec("UPDATE app.offer SET status = 'active' WHERE id = $o")
            assertEquals("23514", c.refused("UPDATE app.offer SET current_version_id = $v1 WHERE id = $o"), "an active offer's rule is final")
            assertEquals("23514", c.refused("UPDATE app.offer SET status = 'draft' WHERE id = $o"), "active -> draft")
            c.exec("UPDATE app.offer SET status = 'inactive' WHERE id = $o")
            assertEquals("23514", c.refused("UPDATE app.offer SET status = 'active' WHERE id = $o"), "inactive -> active")
            c.exec("UPDATE app.offer SET group_code = 'OTHER' WHERE id = $o")               // other columns stay editable
        } finally { c.rollback(); c.autoCommit = true }
    }

    @Test
    fun anOfferInsertedWithoutAStatusIsADraft() = db.connect().use { c ->
        c.autoCommit = false
        try {
            c.exec("INSERT INTO app.offer (code, group_code) VALUES ('NOSTATUS', 'CAMP-1')")
            assertEquals("draft", c.scalar("SELECT status FROM app.offer WHERE code = 'NOSTATUS'"))
        } finally { c.rollback(); c.autoCommit = true }
    }

    @Test
    fun theViewsShowTheCurrentVersionWithProductAndScopeRowsAndTheContractIs16() = db.connect().use { c ->
        c.autoCommit = false
        try {
            assertEquals("1.7", c.scalar("SELECT dw.view_contract_version()"))
            val o = c.offer("W1", "active")
            val v1 = c.version(o, 1, null)
            val v2 = c.version(o, 2, null)
            c.exec("UPDATE app.offer SET current_version_id = $v2 WHERE id = $o")
            val sku = c.scalar("SELECT min(id) FROM app.sku")
            c.exec("INSERT INTO app.offer_version_product VALUES ($v2, 'sku', $sku)")
            c.exec("INSERT INTO app.offer_version_scope VALUES ($v2, 'zone', (SELECT min(id) FROM app.zone))")
            assertEquals("2|2|500|true|1", c.scalar("SELECT current_version_no || '|' || version_count || '|' || discount_mtk_per_unit || '|' || is_effective || '|' || (SELECT count(*) FROM dw.v_offer WHERE offer_code = 'W1') FROM dw.v_offer WHERE offer_code = 'W1'"))
            assertEquals("1|1", c.scalar("SELECT jsonb_array_length(products) || '|' || jsonb_array_length(scope) FROM dw.v_offer_version WHERE offer_version_id = $v2"))
            assertEquals("sku|$sku", c.scalar("SELECT (products->0->>'product_level') || '|' || (products->0->>'product_id') FROM dw.v_offer_version WHERE offer_version_id = $v2"), "the contract's member names")
            assertEquals("zone", c.scalar("SELECT scope->0->>'node_type' FROM dw.v_offer_version WHERE offer_version_id = $v2"))
            assertEquals(c.scalar("SELECT min(id) FROM app.zone"), c.scalar("SELECT scope->0->>'node_id' FROM dw.v_offer_version WHERE offer_version_id = $v2"), "node_id carries the zone's id")
            assertEquals("0", c.scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'dw' AND table_name = 'v_offer_version' AND column_name = 'change_reason'"), "no admin free text in a dw reuse view")
            assertEquals("0|0|false", c.scalar("SELECT jsonb_array_length(products) || '|' || jsonb_array_length(scope) || '|' || is_effective FROM dw.v_offer_version WHERE offer_version_id = $v1"), "an old version is not effective")
            c.exec("UPDATE app.offer SET status = 'inactive' WHERE id = $o")
            assertEquals("f", c.scalar("SELECT is_effective FROM dw.v_offer WHERE offer_code = 'W1'"))
            assertTrue(c.column("SELECT column_name FROM information_schema.columns WHERE table_schema='dw' AND table_name='v_offer_version' ORDER BY ordinal_position").last() == "is_effective")
            // The grants: the web and BI roles read the views, nothing in app.
            c.exec("SET LOCAL ROLE bi_reader"); c.scalar("SELECT count(*) FROM dw.v_offer"); c.exec("RESET ROLE")
            c.exec("SET LOCAL ROLE web_ro"); c.scalar("SELECT count(*) FROM dw.v_offer_version"); c.exec("RESET ROLE")
        } finally { c.rollback(); c.autoCommit = true }
    }

    @Test
    fun theDiscountsModuleIsGrantedFromTargetsAndTheFingerprintColumnExists() = db.connect().use { c ->
        c.autoCommit = false
        try {
            // V0154: the module CHECK is validated and accepts `discounts`; a grant on `targets` carried over at migration time (the seed ADMINs got it from 07_admin_module_grants).
            assertEquals("true", c.scalar("SELECT convalidated::text FROM pg_constraint WHERE conname = 'user_module_grant_module_check'"), "validated"); assertTrue(c.scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'user_module_grant_module_check'")!!.contains("discounts"))
            assertEquals(c.scalar("SELECT count(*) FROM app.user_module_grant WHERE module = 'targets'"), c.scalar("SELECT count(*) FROM app.user_module_grant WHERE module = 'discounts'"))
            val admin = c.scalar("SELECT id FROM app.app_user WHERE username = 'admin1001'")
            c.exec("DELETE FROM app.user_module_grant WHERE user_id = $admin AND module = 'discounts'")
            c.exec("INSERT INTO app.user_module_grant (user_id, module, access) VALUES ($admin, 'discounts', 'r')")
            assertEquals("23514", c.refused("INSERT INTO app.user_module_grant (user_id, module, access) VALUES ($admin, 'discount', 'r')"))
            assertEquals("text", c.scalar("SELECT data_type FROM information_schema.columns WHERE table_schema='app' AND table_name='offer_version' AND column_name='request_fingerprint'"))
        } finally { c.rollback(); c.autoCommit = true }
    }
}

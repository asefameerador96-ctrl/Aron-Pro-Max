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
 * V0158 to V0162 (loyalty portal schema, D-LOY-API-01): the programme status CHECK with draft, the lifecycle guard, the immutable
 * versions and their children, the append-only trails, the grants of the API role, the `loyalty` grant module carried over from
 * `targets`, and the three dw views at contract 1.7. The field stays deferred: nothing here touches the ledger or the memo.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoyaltyPortalV0158Test {
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

    private fun Connection.programme(code: String, status: String? = null, kind: String = "astha"): String =
        scalar(
            "INSERT INTO app.programme (kind, code, name_en, active_from, active_to${if (status != null) ", status" else ""}) " +
                "VALUES ('$kind', '$code', 'P $code', CURRENT_DATE, (CURRENT_DATE + 29)${if (status != null) ", '$status'" else ""}) RETURNING id",
        )!!

    private fun Connection.version(programmeId: String, no: Int, uuid: String): String =
        scalar(
            "INSERT INTO app.programme_version (programme_id, version_no, name_en, active_from, active_to, change_reason, request_uuid, request_fingerprint) " +
                "VALUES ($programmeId, $no, 'V$no', CURRENT_DATE, (CURRENT_DATE + 29), 'created for the test', '$uuid', 'fp$no') RETURNING id",
        )!!

    @Test
    fun theStatusAcceptsDraftActiveInactiveAndNothingElseAndDefaultsToDraft() = db.connect().use { c ->
        c.autoCommit = false
        try {
            c.programme("D1", "draft"); c.programme("A1", "active"); c.programme("I1", "inactive")
            assertEquals("23514", c.refused("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('astha', 'X1', 'x', CURRENT_DATE, (CURRENT_DATE + 29), 'ended')"))
            assertEquals("draft", c.scalar("SELECT status FROM app.programme WHERE id = ${c.programme("NOSTATUS")}"))
            assertEquals("2", c.scalar("SELECT count(*) FROM pg_constraint WHERE conname IN ('programme_status_check', 'programme_current_version_fk') AND convalidated"))
        } finally { c.rollback(); c.autoCommit = true }
    }

    @Test
    fun aVersionIsImmutableItsRequestUuidIsUniqueAndItsRulesAreChecked() = db.connect().use { c ->
        c.autoCommit = false
        try {
            val p = c.programme("V1")
            val v1 = c.version(p, 1, "c0ffee00-0000-4000-8000-0000000a0001")
            assertEquals("23505", c.refused("INSERT INTO app.programme_version (programme_id, version_no, name_en, active_from, active_to, change_reason, request_uuid, request_fingerprint) " +
                "VALUES ($p, 2, 'dup', CURRENT_DATE, (CURRENT_DATE + 29), 'created for the test', 'c0ffee00-0000-4000-8000-0000000a0001', 'x')"))
            assertEquals("23505", c.refused("INSERT INTO app.programme_version (programme_id, version_no, name_en, active_from, active_to, change_reason, request_uuid, request_fingerprint) " +
                "VALUES ($p, 1, 'dup', CURRENT_DATE, (CURRENT_DATE + 29), 'created for the test', 'c0ffee00-0000-4000-8000-0000000a0002', 'x')"), "version_no unique per programme")
            assertEquals("23514", c.refused("INSERT INTO app.programme_version (programme_id, version_no, name_en, active_from, active_to, change_reason, request_uuid, request_fingerprint) " +
                "VALUES ($p, 2, 'x', (CURRENT_DATE + 29), CURRENT_DATE, 'created for the test', 'c0ffee00-0000-4000-8000-0000000a0003', 'x')"), "window reversed")
            assertEquals("23514", c.refused("INSERT INTO app.programme_version (programme_id, version_no, name_en, active_from, active_to, change_reason, request_uuid, request_fingerprint) " +
                "VALUES ($p, 2, 'x', CURRENT_DATE, (CURRENT_DATE + 29), 'short', 'c0ffee00-0000-4000-8000-0000000a0004', 'x')"), "reason under 10 characters")
            assertEquals("23514", c.refused("INSERT INTO app.programme_version (programme_id, version_no, name_en, active_from, active_to, points_expire_on, change_reason, request_uuid, request_fingerprint) " +
                "VALUES ($p, 2, 'x', CURRENT_DATE, (CURRENT_DATE + 29), (CURRENT_DATE + 28), 'created for the test', 'c0ffee00-0000-4000-8000-0000000a0005', 'x')"), "points cannot expire before the window ends")
            assertEquals("42501", c.refused("UPDATE app.programme_version SET name_en = 'changed' WHERE id = $v1"))
            assertEquals("42501", c.refused("DELETE FROM app.programme_version WHERE id = $v1"))
        } finally { c.rollback(); c.autoCommit = true }
    }

    @Test
    fun tiersProductsAndScopeAreImmutableChildrenWithCheckedValues() = db.connect().use { c ->
        c.autoCommit = false
        try {
            val v = c.version(c.programme("T1"), 1, "c0ffee00-0000-4000-8000-0000000a0101")
            c.exec("INSERT INTO app.programme_version_tier VALUES ($v, 'silver', 1, 'Silver', 'রৌপ্য', 0, 'std')")
            c.exec("INSERT INTO app.programme_version_tier VALUES ($v, 'gold', 2, 'Gold', NULL, 1500.5, 'std')")
            assertEquals("23514", c.refused("INSERT INTO app.programme_version_tier VALUES ($v, 'neg', 3, 'Neg', NULL, -1, 'std')"), "never negative (the -37,500% bug)")
            assertEquals("23514", c.refused("INSERT INTO app.programme_version_tier VALUES ($v, 'sticks', 3, 'S', NULL, 1, 'stick')"), "only STD until the owner answers the units question")
            assertEquals("23514", c.refused("INSERT INTO app.programme_version_tier VALUES ($v, 'Bad-Code', 3, 'S', NULL, 1, 'std')"))
            assertEquals("23505", c.refused("INSERT INTO app.programme_version_tier VALUES ($v, 'other', 2, 'Same rank', NULL, 1, 'std')"), "rank unique")
            c.exec("INSERT INTO app.programme_version_product VALUES ($v, 'brand', (SELECT min(id) FROM app.product_node))")
            c.exec("INSERT INTO app.programme_version_scope VALUES ($v, 'channel', 1)")
            assertEquals("23514", c.refused("INSERT INTO app.programme_version_scope VALUES ($v, 'planet', 1)"))
            for (t in listOf("programme_version_tier", "programme_version_product", "programme_version_scope")) {
                assertEquals("42501", c.refused("UPDATE app.$t SET programme_version_id = programme_version_id"), "$t update")
                assertEquals("42501", c.refused("DELETE FROM app.$t"), "$t delete")
            }
            assertEquals("1500.500", c.scalar("SELECT threshold FROM app.programme_version_tier WHERE tier_code = 'gold'"), "numeric(16,3), no float")
        } finally { c.rollback(); c.autoCommit = true }
    }

    @Test
    fun theStatusTrailGiftJournalAndEnrolmentBatchAreAppendOnlyWithUniqueUuids() = db.connect().use { c ->
        c.autoCommit = false
        try {
            val p = c.programme("S1")
            val actor = c.scalar("SELECT id FROM app.app_user WHERE username = 'sr1001'")
            val ins = { uuid: String, from: String, to: String, reason: String ->
                "INSERT INTO app.programme_status_change (programme_id, from_status, to_status, request_uuid, request_fingerprint, reason, actor) VALUES ($p, '$from', '$to', '$uuid', 'fp', '$reason', $actor)"
            }
            c.exec(ins("c0ffee00-0000-4000-8000-0000000a0201", "draft", "active", "approved by the owner"))
            assertEquals("23505", c.refused(ins("c0ffee00-0000-4000-8000-0000000a0201", "active", "inactive", "same uuid again!!")))
            assertEquals("23514", c.refused(ins("c0ffee00-0000-4000-8000-0000000a0202", "draft", "active", "short")))
            assertEquals("23514", c.refused(ins("c0ffee00-0000-4000-8000-0000000a0203", "active", "active", "no move at all")))
            assertEquals("42501", c.refused("UPDATE app.programme_status_change SET reason = 'rewritten for the test' WHERE programme_id = $p"))
            assertEquals("42501", c.refused("DELETE FROM app.programme_status_change WHERE programme_id = $p"))

            val g = c.scalar("INSERT INTO app.gift (programme_id, code, name_en, points_cost) VALUES ($p, 'mug', 'Mug', 100) RETURNING id")
            val gc = { uuid: String -> "INSERT INTO app.gift_change (gift_id, change_kind, request_uuid, request_fingerprint, reason, snapshot, result_row_version, actor) VALUES ($g, 'create', '$uuid', 'fp', 'new gift for the test', '{\"code\":\"mug\"}', 1, $actor)" }
            c.exec(gc("c0ffee00-0000-4000-8000-0000000a0301"))
            assertEquals("23505", c.refused(gc("c0ffee00-0000-4000-8000-0000000a0301")))
            assertEquals("42501", c.refused("UPDATE app.gift_change SET reason = 'rewritten for the test' WHERE gift_id = $g"))

            val b = { uuid: String -> "INSERT INTO app.programme_enrolment_batch (batch_uuid, programme_id, request_fingerprint, row_count, inserted, updated, unchanged, reason, actor) VALUES ('$uuid', $p, 'fp', 3, 2, 1, 0, 'enrol the outlets now', $actor)" }
            c.exec(b("c0ffee00-0000-4000-8000-0000000a0401"))
            assertEquals("23505", c.refused(b("c0ffee00-0000-4000-8000-0000000a0401")))
            assertEquals("42501", c.refused("DELETE FROM app.programme_enrolment_batch"))
        } finally { c.rollback(); c.autoCommit = true }
    }

    @Test
    fun theGrantMapLetsTheApiRoleInsertTheAppendOnlyTablesAndUpdateOnlyTheMutableRows() = db.connect().use { c ->
        fun priv(role: String, table: String, p: String) = c.scalar("SELECT has_table_privilege('$role', '$table', '$p')::text")
        val immutable = listOf("programme_version", "programme_version_tier", "programme_version_product", "programme_version_scope", "programme_status_change", "gift_change", "programme_enrolment_batch")
        for (t in immutable) {
            assertEquals("true", priv("api_rw", "app.$t", "INSERT"), "$t insert")
            assertEquals("true", priv("api_rw", "app.$t", "SELECT"), "$t select")
            for (p in listOf("UPDATE", "DELETE", "TRUNCATE")) assertEquals("false", priv("api_rw", "app.$t", p), "$t $p")
            assertEquals("false", priv("worker_rw", "app.$t", "INSERT"), "only the API writes $t")
        }
        for (t in listOf("programme", "gift")) { assertEquals("true", priv("api_rw", "app.$t", "UPDATE"), "$t is mutable"); assertEquals("false", priv("api_rw", "app.$t", "DELETE")) }
        // TRUNCATE is closed by statement triggers even for the owner.
        assertEquals(immutable.size.toString(), c.scalar("SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal AND tgname LIKE ANY (ARRAY[${immutable.joinToString(",") { "'${it}_no_truncate'" }}]) AND (tgtype & 32) = 32"))
        c.autoCommit = false
        try {
            val e = assertFailsWith<SQLException> { c.exec("TRUNCATE app.programme_status_change") }
            assertEquals("42501", e.sqlState); assertTrue("append-only" in e.message.orEmpty(), e.message)
        } finally { c.rollback(); c.autoCommit = true }
    }

    @Test
    fun theDatabaseRefusesAnyStatusMoveButDraftActiveInactiveAndALateVersionLink() = db.connect().use { c ->
        c.autoCommit = false
        try {
            val p = c.programme("LC1"); val v1 = c.version(p, 1, "c0ffee00-0000-4000-8000-0000000a0501"); val v2 = c.version(p, 2, "c0ffee00-0000-4000-8000-0000000a0502")
            c.exec("UPDATE app.programme SET current_version_id = $v1 WHERE id = $p")
            c.exec("UPDATE app.programme SET current_version_id = $v2 WHERE id = $p")        // a draft may take a newer version
            assertEquals("23514", c.refused("UPDATE app.programme SET status = 'inactive' WHERE id = $p"), "draft -> inactive")
            c.exec("UPDATE app.programme SET status = 'active' WHERE id = $p")
            assertEquals("23514", c.refused("UPDATE app.programme SET current_version_id = $v1 WHERE id = $p"), "an active programme's rule is final")
            assertEquals("23514", c.refused("UPDATE app.programme SET status = 'draft' WHERE id = $p"), "active -> draft")
            c.exec("UPDATE app.programme SET status = 'inactive' WHERE id = $p")
            assertEquals("23514", c.refused("UPDATE app.programme SET status = 'active' WHERE id = $p"), "inactive -> active")
            c.exec("UPDATE app.programme SET external_ref = 'EXT-1' WHERE id = $p")           // other columns stay editable
            // the current version must belong to the same programme (composite foreign key)
            val draft = c.programme("LC3"); val other = c.programme("LC2"); val ov = c.version(other, 1, "c0ffee00-0000-4000-8000-0000000a0504")
            assertEquals("23503", c.refused("UPDATE app.programme SET current_version_id = $ov WHERE id = $draft"), "a version of another programme")
            // a legacy active programme (no version yet) may take its first version
            val legacy = c.programme("LEG1", "active"); val lv = c.version(legacy, 1, "c0ffee00-0000-4000-8000-0000000a0503")
            c.exec("UPDATE app.programme SET current_version_id = $lv WHERE id = $legacy")
        } finally { c.rollback(); c.autoCommit = true }
    }

    @Test
    fun theViewsShowTheCurrentVersionWithTiersProductAndScopeAndTheContractIs17() = db.connect().use { c ->
        c.autoCommit = false
        try {
            assertEquals("1.8", c.scalar("SELECT dw.view_contract_version()"))   // 1.7 at V0162, 1.8 at V0174
            val p = c.programme("W1", "draft")
            val v1 = c.version(p, 1, "c0ffee00-0000-4000-8000-0000000a0601")
            val v2 = c.version(p, 2, "c0ffee00-0000-4000-8000-0000000a0602")
            c.exec("INSERT INTO app.programme_version_tier VALUES ($v2, 'silver', 1, 'Silver', NULL, 100, 'std'), ($v2, 'gold', 2, 'Gold', NULL, 200, 'std')")
            c.exec("INSERT INTO app.programme_version_scope VALUES ($v2, 'zone', (SELECT min(id) FROM app.zone))")
            c.exec("UPDATE app.programme SET current_version_id = $v2 WHERE id = $p")
            c.exec("UPDATE app.programme SET status = 'active' WHERE id = $p")
            assertEquals("2|2|2|true", c.scalar("SELECT current_version_no || '|' || version_count || '|' || jsonb_array_length(tiers) || '|' || is_effective FROM dw.v_programme WHERE programme_code = 'W1'"))
            assertEquals("silver|gold", c.scalar("SELECT (tiers->0->>'tier_code') || '|' || (tiers->1->>'tier_code') FROM dw.v_programme WHERE programme_code = 'W1'"), "tiers ordered by rank, contract member names")
            assertEquals("zone", c.scalar("SELECT scope->0->>'node_type' FROM dw.v_programme_version WHERE programme_version_id = $v2"))
            assertEquals("0|0|0|false", c.scalar("SELECT jsonb_array_length(tiers) || '|' || jsonb_array_length(products) || '|' || jsonb_array_length(scope) || '|' || is_effective FROM dw.v_programme_version WHERE programme_version_id = $v1"), "an old version is not effective")
            assertEquals("0", c.scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'dw' AND table_name IN ('v_programme', 'v_programme_version', 'v_gift') AND column_name IN ('change_reason', 'reason')"), "no admin free text in a dw reuse view")
            c.exec("INSERT INTO app.gift (programme_id, code, name_en, points_cost) VALUES ($p, 'fan', 'Fan', 400)")
            assertEquals("fan|400|true", c.scalar("SELECT gift_code || '|' || points_cost || '|' || is_effective FROM dw.v_gift WHERE programme_code = 'W1'"))
            for (v in listOf("v_programme", "v_programme_version", "v_gift")) {
                assertEquals("is_effective", c.column("SELECT column_name FROM information_schema.columns WHERE table_schema='dw' AND table_name='$v' ORDER BY ordinal_position").last())
                c.exec("SET LOCAL ROLE bi_reader"); c.scalar("SELECT count(*) FROM dw.$v"); c.exec("RESET ROLE")
                c.exec("SET LOCAL ROLE web_ro"); c.scalar("SELECT count(*) FROM dw.$v"); c.exec("RESET ROLE")
            }
        } finally { c.rollback(); c.autoCommit = true }
    }

    @Test
    fun theLoyaltyModuleIsGrantedFromTargetsAndTheCheckIsValidated() = db.connect().use { c ->
        c.autoCommit = false
        try {
            assertEquals("true", c.scalar("SELECT convalidated::text FROM pg_constraint WHERE conname = 'user_module_grant_module_check'"))
            assertTrue(c.scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'user_module_grant_module_check'")!!.contains("'loyalty'"))
            val admin = c.scalar("SELECT id FROM app.app_user WHERE username = 'admin1001'")
            // The carry-over statement of V0160 itself (read from the migration file): every `targets` grant yields a `loyalty` grant with the same access.
            val carry = java.io.File(System.getProperty("aron.migrations"), "V0160__loyalty_portal_module.sql").readText().substringAfter("INSERT INTO app.user_module_grant").substringBefore(";")
            c.exec("DELETE FROM app.user_module_grant WHERE user_id = $admin AND module IN ('targets', 'loyalty')")
            c.exec("INSERT INTO app.user_module_grant (user_id, module, access) VALUES ($admin, 'targets', 'r')")
            c.exec("INSERT INTO app.user_module_grant$carry")
            assertEquals("r", c.scalar("SELECT access FROM app.user_module_grant WHERE user_id = $admin AND module = 'loyalty'"))
            c.exec("INSERT INTO app.user_module_grant$carry")   // idempotent (ON CONFLICT DO NOTHING)
            c.exec("DELETE FROM app.user_module_grant WHERE user_id = $admin AND module = 'loyalty'")
            c.exec("INSERT INTO app.user_module_grant (user_id, module, access) VALUES ($admin, 'loyalty', 'w')")
            assertEquals("23514", c.refused("INSERT INTO app.user_module_grant (user_id, module, access) VALUES ($admin, 'loyalities', 'r')"))
        } finally { c.rollback(); c.autoCommit = true }
    }
}

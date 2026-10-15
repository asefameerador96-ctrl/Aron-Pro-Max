package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * V0074: app.dues_adjustment (docs/requests/backend-admin-dues-adjustment-table.md, F-ADM-036).
 * V0075/V0076: app.sku.image_asset_id (docs/requests/backend-admin-sku-image-asset.md).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DuesAdjustmentSkuImageV0074Test {
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

    private fun user(name: String) = "(SELECT id FROM app.app_user WHERE username = '$name')"
    private val outlet = "(SELECT min(id) FROM app.outlet)"

    private fun propose(uuid: String, kind: String = "correction", amount: Long = -150_000, reason: String = "Memo total keyed twice") =
        "INSERT INTO app.dues_adjustment (client_uuid, outlet_id, kind, amount_mtk, reason, created_by) " +
            "VALUES ('$uuid', $outlet, '$kind', $amount, '$reason', ${user("admin1001")})"

    @Test
    fun aProposalIsDecidedOnceByAnotherUserAndThenNeverChanges() = db.connect().use { c ->
        c.tx {
            val id = "a1d5e0c2-3b4f-4a6e-8c7d-9e0f1a2b3c01"
            exec(propose(id))
            val row = "client_uuid = '$id'"
            assertEquals("pending||", scalar("SELECT concat(status, '|', decided_by, '|', decided_at) FROM app.dues_adjustment WHERE $row"))
            // Only the decision columns may change.
            assertEquals(DENIED, refused("UPDATE app.dues_adjustment SET amount_mtk = -1 WHERE $row"))
            assertEquals(DENIED, refused("UPDATE app.dues_adjustment SET reason = 'A different reason' WHERE $row"))
            assertEquals(DENIED, refused("UPDATE app.dues_adjustment SET decision_note = 'not a decision' WHERE $row"))
            // Maker-checker: the maker cannot decide.
            assertEquals("23514", refused("UPDATE app.dues_adjustment SET status = 'approved', decided_by = created_by, decided_at = now() WHERE $row"))
            // A decision needs both decided_by and decided_at.
            assertEquals("23514", refused("UPDATE app.dues_adjustment SET status = 'approved', decided_by = ${user("superadmin1001")} WHERE $row"))
            assertEquals("23514", refused("UPDATE app.dues_adjustment SET status = 'approved', decided_by = ${user("superadmin1001")}, decided_at = created_at - interval '1 hour' WHERE $row"))
            exec("UPDATE app.dues_adjustment SET status = 'approved', decided_by = ${user("superadmin1001")}, decided_at = now(), decision_note = 'Checked against the memo' WHERE $row")
            assertEquals("approved", scalar("SELECT status FROM app.dues_adjustment WHERE $row"))
            // Decided once: no second decision, no reopening, no edit.
            assertEquals(DENIED, refused("UPDATE app.dues_adjustment SET status = 'rejected' WHERE $row"))
            assertEquals(DENIED, refused("UPDATE app.dues_adjustment SET decision_note = 'changed later' WHERE $row"))
            assertEquals(DENIED, refused("DELETE FROM app.dues_adjustment WHERE $row"))
            assertEquals(DENIED, refused("TRUNCATE app.dues_adjustment"))
        }
    }

    @Test
    fun pendingRowsCannotBeDeletedOrMovedBackToPending() = db.connect().use { c ->
        c.tx {
            val id = "a1d5e0c2-3b4f-4a6e-8c7d-9e0f1a2b3c02"
            exec(propose(id))
            assertEquals(DENIED, refused("DELETE FROM app.dues_adjustment WHERE client_uuid = '$id'"))
            assertEquals(DENIED, refused("UPDATE app.dues_adjustment SET status = 'pending' WHERE client_uuid = '$id'"))
            exec("UPDATE app.dues_adjustment SET status = 'rejected', decided_by = ${user("superadmin1001")}, decided_at = now() WHERE client_uuid = '$id'")
            assertEquals(DENIED, refused("UPDATE app.dues_adjustment SET status = 'approved' WHERE client_uuid = '$id'"))
        }
    }

    @Test
    fun theChecksKeepAmountsKindsAndTextsInRange() = db.connect().use { c ->
        c.tx {
            exec(propose("a1d5e0c2-3b4f-4a6e-8c7d-9e0f1a2b3c03", kind = "write_off", amount = -2_500_000))
            exec(propose("a1d5e0c2-3b4f-4a6e-8c7d-9e0f1a2b3c04", kind = "correction", amount = 75_000))
            assertEquals("23514", refused(propose("a1d5e0c2-3b4f-4a6e-8c7d-9e0f1a2b3c05", kind = "write_off", amount = 10_000)))
            assertEquals("23514", refused(propose("a1d5e0c2-3b4f-4a6e-8c7d-9e0f1a2b3c06", amount = 0)))
            assertEquals("23514", refused(propose("a1d5e0c2-3b4f-4a6e-8c7d-9e0f1a2b3c07", kind = "discount")))
            assertEquals("23514", refused(propose("a1d5e0c2-3b4f-4a6e-8c7d-9e0f1a2b3c08", reason = "too short")))
            assertEquals("23505", refused(propose("a1d5e0c2-3b4f-4a6e-8c7d-9e0f1a2b3c03")))
            // A row cannot be created already decided by its maker, nor pending with a decision.
            assertEquals(DENIED, refused("INSERT INTO app.dues_adjustment (client_uuid, outlet_id, kind, amount_mtk, reason, created_by, status, decided_by, decided_at) VALUES ('a1d5e0c2-3b4f-4a6e-8c7d-9e0f1a2b3c09', $outlet, 'correction', 1000, 'Created already approved', ${user("admin1001")}, 'approved', ${user("admin1001")}, now())"))
            // Nor already decided by a checker: every row starts pending (guard trigger).
            assertEquals(DENIED, refused("INSERT INTO app.dues_adjustment (client_uuid, outlet_id, kind, amount_mtk, reason, created_by, status, decided_by, decided_at) VALUES ('a1d5e0c2-3b4f-4a6e-8c7d-9e0f1a2b3c11', $outlet, 'correction', 1000, 'Created already approved', ${user("admin1001")}, 'approved', ${user("superadmin1001")}, now())"))
            assertEquals("23514", refused("INSERT INTO app.dues_adjustment (client_uuid, outlet_id, kind, amount_mtk, reason, created_by, decided_by, decided_at) VALUES ('a1d5e0c2-3b4f-4a6e-8c7d-9e0f1a2b3c10', $outlet, 'correction', 1000, 'Pending with a decision', ${user("admin1001")}, ${user("superadmin1001")}, now())"))
        }
    }

    @Test
    fun theApiInsertsReadsAndDecidesAndNoRoleDeletes() = db.connect().use { c ->
        fun can(role: String, priv: String) = c.scalar("SELECT has_table_privilege('$role', 'app.dues_adjustment', '$priv')::text")
        assertEquals("true|true|true|false", listOf("SELECT", "INSERT", "UPDATE", "DELETE").joinToString("|") { can("api_rw", it)!! })
        assertEquals("true|false|false|false", listOf("SELECT", "INSERT", "UPDATE", "DELETE").joinToString("|") { can("worker_rw", it)!! })
        val outsiders = listOf("web_ro", "bi_reader", "auth_rw", "support_ro")
        assertEquals(outsiders.joinToString("|") { "false" }, outsiders.joinToString("|") { can(it, "SELECT")!! })
    }

    @Test
    fun aSkuLinksAnAdminAssetOrNone() = db.connect().use { c ->
        c.tx {
            exec(
                "INSERT INTO app.admin_asset (asset_id, purpose, mime, bytes, sha256, blob_path) " +
                    "VALUES ('b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d01', 'sku_image', 'image/jpeg', 120000, decode(repeat('cd', 32), 'hex'), 'admin/sku/b2c3.jpg')",
            )
            val sku = "id = (SELECT min(id) FROM app.sku)"
            val before = scalar("SELECT version FROM app.sku WHERE $sku")!!.toInt()
            exec("UPDATE app.sku SET image_asset_id = 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d01' WHERE $sku")
            assertEquals(before + 1, scalar("SELECT version FROM app.sku WHERE $sku")!!.toInt())
            assertEquals("23503", refused("UPDATE app.sku SET image_asset_id = 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d99' WHERE $sku"))
            exec("UPDATE app.sku SET image_asset_id = NULL WHERE $sku")
        }
        assertEquals("0", c.scalar("SELECT count(*) FROM app.sku WHERE image_asset_id IS NOT NULL"))
        assertEquals("t", c.scalar("SELECT convalidated FROM pg_constraint WHERE conname = 'sku_image_asset_id_fkey'"))
        assertEquals("true", c.scalar("SELECT has_column_privilege('api_rw', 'app.sku', 'image_asset_id', 'UPDATE')::text"))
    }

    private companion object {
        const val DENIED = "42501"
    }
}

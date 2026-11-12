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

/** V0170 / V0171 (loyalty portal routes, D-LOY-API-05): the gift picture column and its validated foreign key, and the scope_version bump of the ADMINs who hold `loyalty`. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoyaltyPortalV0170Test {
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

    @Test
    fun aGiftPictureMustBeAnExistingAdminAssetAndTheForeignKeyIsValidated() = db.connect().use { c ->
        c.autoCommit = false
        try {
            assertEquals("true", c.scalar("SELECT convalidated::text FROM pg_constraint WHERE conname = 'gift_image_asset_fk'"))
            c.exec("INSERT INTO app.programme (kind, code, name_en, active_from, active_to) VALUES ('astha', 'IMG1', 'P', CURRENT_DATE, CURRENT_DATE + 29)")
            val prog = c.scalar("SELECT id FROM app.programme WHERE code = 'IMG1'")
            assertEquals("23503", c.refused("INSERT INTO app.gift (programme_id, code, name_en, image_asset_id) VALUES ($prog, 'pic', 'Pic', gen_random_uuid())"))
            c.exec("INSERT INTO app.admin_asset (asset_id, purpose, mime, bytes, sha256, blob_path) VALUES ('c0ffee00-0000-4000-8000-0000000b0001', 'gift_image', 'image/png', 10, decode(repeat('ab', 32), 'hex'), 'gifts/x.png')")
            c.exec("INSERT INTO app.gift (programme_id, code, name_en, image_asset_id) VALUES ($prog, 'pic', 'Pic', 'c0ffee00-0000-4000-8000-0000000b0001')")
            assertEquals("c0ffee00-0000-4000-8000-0000000b0001", c.scalar("SELECT image_asset_id::text FROM app.gift WHERE code = 'pic'"))
        } finally { c.rollback(); c.autoCommit = true }
    }

    @Test
    fun theBumpStatementRaisesTheScopeVersionOfAdminsHoldingLoyaltyOnly() = db.connect().use { c ->
        c.autoCommit = false
        try {
            val sql = java.io.File(System.getProperty("aron.migrations"), "V0170__loyalty_portal_routes.sql").readText().substringAfter("UPDATE app.app_user u SET").substringBefore(";")
            val admin = c.scalar("SELECT id FROM app.app_user WHERE username = 'admin1001'")
            val sr = c.scalar("SELECT id FROM app.app_user WHERE username = 'sr1001'")
            c.exec("DELETE FROM app.user_module_grant WHERE user_id = $admin AND module = 'loyalty'")
            c.exec("INSERT INTO app.user_module_grant (user_id, module, access) VALUES ($admin, 'loyalty', 'r')")
            val before = c.scalar("SELECT scope_version FROM app.app_user WHERE id = $admin")!!.toLong()
            val srBefore = c.scalar("SELECT scope_version FROM app.app_user WHERE id = $sr")!!.toLong()
            c.exec("UPDATE app.app_user u SET$sql")
            assertEquals(before + 1, c.scalar("SELECT scope_version FROM app.app_user WHERE id = $admin")!!.toLong(), "an ADMIN with a loyalty grant gets a new scope version: the old token is refreshed")
            assertEquals(srBefore, c.scalar("SELECT scope_version FROM app.app_user WHERE id = $sr")!!.toLong(), "a user without the grant is untouched")
            assertTrue(sql.contains("role = 'ADMIN'"))
        } finally { c.rollback(); c.autoCommit = true }
    }
}

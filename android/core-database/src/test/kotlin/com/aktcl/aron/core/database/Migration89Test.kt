package com.aktcl.aron.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Version 8 to 9 adds `cluster_create` (SRM-B-44) and keeps every existing row. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class Migration89Test {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AronDatabase::class.java)

    @Test fun version8To9KeepsRowsAndAddsAnEmptyClusterCreateTable() {
        helper.createDatabase("migration-89", 8).use { db ->
            db.execSQL(
                """INSERT INTO sku (sku_id, code, variant_id, category_code, name, short_name, name_bn, base_unit, base_per_pack,
                   entry_unit_default, report_unit, report_factor, sort, status, version, image_asset_id) VALUES (1, 'MaxR-10S', 1, 'cigarette',
                   'Maxim Regular 10', 'MaxR-10S', null, 'stick', 10, 'pack', null, '1.000', 1, 'active', 3, null)""",
            )
        }
        helper.runMigrationsAndValidate("migration-89", 9, true).use { db ->
            db.query("SELECT COUNT(*) FROM sku").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM cluster_create").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
            db.execSQL("INSERT INTO cluster_create (client_uuid, name, route_id, route_name, created_at, business_date, fix_client_uuid) VALUES ('a', 'Lakepar', 5, 'R', 't', '2026-11-12', 'f')")
            db.query("SELECT COUNT(*) FROM cluster_create").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        }
    }
}

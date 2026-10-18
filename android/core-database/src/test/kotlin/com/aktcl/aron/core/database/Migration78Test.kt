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

/** Version 7 to 8 adds `sku.image_asset_id` (the pack picture, PARITY-SPEC s2.6), null on every existing SKU, and keeps every row. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class Migration78Test {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AronDatabase::class.java)

    @Test fun version7To8KeepsEverySkuAndAddsANullImageAsset() {
        helper.createDatabase("migration-78", 7).use { db ->
            db.execSQL(
                """INSERT INTO sku (sku_id, code, variant_id, category_code, name, short_name, name_bn, base_unit, base_per_pack,
                   entry_unit_default, report_unit, report_factor, sort, status, version) VALUES (1, 'MaxR-10S', 1, 'cigarette',
                   'Maxim Regular 10', 'MaxR-10S', null, 'stick', 10, 'pack', null, '1.000', 1, 'active', 3)""",
            )
        }
        helper.runMigrationsAndValidate("migration-78", 8, true).use { db ->
            db.query("SELECT code, version, image_asset_id FROM sku").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("MaxR-10S", c.getString(0))
                assertEquals(3, c.getInt(1))
                assertTrue(c.isNull(2))
            }
            db.execSQL("UPDATE sku SET image_asset_id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' WHERE sku_id = 1")
            db.query("SELECT COUNT(*) FROM sku WHERE image_asset_id IS NOT NULL").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        }
    }
}

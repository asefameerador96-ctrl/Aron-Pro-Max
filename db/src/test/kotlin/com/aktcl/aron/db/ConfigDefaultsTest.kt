package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lead ruling R5 (docs/24 s14a) and docs/27: the menu and home-tile defaults, with no deferred programme in them. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigDefaultsTest {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
    }

    @AfterAll
    fun tearDown() = db.close()

    private val deferred = listOf("target", "astha", "loyalty", "photo_capture", "superstar", "campaign", "gift", "discount", "offer")

    @Test
    fun srHomeTilesKeepTheRequiredTilesInTheAppOrderWithoutDeferredOnes() = db.connect().use { c ->
        val tiles = c.column("SELECT jsonb_array_elements_text(default_value->'SR') FROM app.cfg_key WHERE key = 'cfg.app.home_tiles'")
            .filterNotNull()
        assertEquals(
            listOf("attendance", "stock", "sale", "memo", "summary", "sales_submit", "outlet", "tutorial", "task_delegation", "sales_journey", "kpi"),
            tiles,
        )
        assertTrue(tiles.containsAll(listOf("attendance", "sale", "memo", "sales_submit")), "s9.5 bound: must keep these")
        assertTrue(tiles.none { t -> deferred.any { t.contains(it) } }, "no deferred tile: $tiles")
    }

    @Test
    fun theMenuMatrixNamesOnlyContractRolesAndNoDeferredPage() = db.connect().use { c ->
        val roles = c.column("SELECT jsonb_object_keys(default_value) FROM app.cfg_key WHERE key = 'cfg.web.menu_by_role' ORDER BY 1")
        assertEquals(listOf("ADMIN", "ANALYST", "DMO", "SUPERADMIN", "SUPPORT", "TOP", "TSO", "WM"), roles)
        assertEquals("0", c.scalar("SELECT count(*) FROM jsonb_object_keys((SELECT default_value FROM app.cfg_key WHERE key = 'cfg.web.menu_by_role')) r WHERE r NOT IN (SELECT role FROM app.role_def)"))
        val pages = c.column(
            "SELECT e->>'menu' || '/' || (e->>'page') FROM app.cfg_key k, jsonb_each(k.default_value) r, jsonb_array_elements(r.value) e WHERE k.key = 'cfg.web.menu_by_role'",
        ).filterNotNull()
        assertTrue(pages.none { p -> deferred.any { p.contains(it) } }, pages.filter { p -> deferred.any { p.contains(it) } }.toString())
        // Every entry has a menu, a page and at least one action; the TSO keeps the Outlet Approval Panel and the Device OTP view.
        assertEquals("0", c.scalar("SELECT count(*) FROM app.cfg_key k, jsonb_each(k.default_value) r, jsonb_array_elements(r.value) e WHERE k.key = 'cfg.web.menu_by_role' AND (e->>'menu' IS NULL OR e->>'page' IS NULL OR jsonb_array_length(e->'actions') = 0)"))
        val tso = c.column("SELECT e->>'page' FROM app.cfg_key, jsonb_array_elements(default_value->'TSO') e WHERE key = 'cfg.web.menu_by_role'")
        assertTrue(tso.containsAll(listOf("outlet_approval_panel", "device_otp", "final_submit", "web_entry")), tso.toString())
    }
}
